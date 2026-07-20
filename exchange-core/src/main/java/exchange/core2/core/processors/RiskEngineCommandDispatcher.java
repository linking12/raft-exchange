/*
 * Copyright 2019 Maksim Zheravin
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on
 * an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations under the License.
 */
package exchange.core2.core.processors;

import java.util.List;

import org.eclipse.collections.impl.list.mutable.FastList;
import org.eclipse.collections.impl.map.mutable.primitive.IntObjectHashMap;

import exchange.core2.core.common.BalanceAdjustmentType;
import exchange.core2.core.common.CoreCurrencySpecification;
import exchange.core2.core.common.CoreSymbolSpecification;
import exchange.core2.core.common.MarginMode;
import exchange.core2.core.common.PositionMode;
import exchange.core2.core.common.SymbolPositionRecord;
import exchange.core2.core.common.SymbolLoanSpecification;
import exchange.core2.core.common.SymbolType;
import exchange.core2.core.common.UserProfile;
import exchange.core2.core.common.api.binary.BatchAddAccountsCommand;
import exchange.core2.core.common.api.binary.BatchAddCurrenciesCommand;
import exchange.core2.core.common.api.binary.BatchAddLoanCommand;
import exchange.core2.core.common.api.binary.BatchAddSymbolsCommand;
import exchange.core2.core.common.api.binary.BinaryDataCommand;
import exchange.core2.core.common.cmd.CommandResultCode;
import exchange.core2.core.common.cmd.OrderCommand;
import exchange.core2.core.processors.loan.LoanGlobalConfig;
import exchange.core2.core.processors.loan.rate.FloatingRateModel;
import exchange.core2.core.utils.CoreArithmeticUtils;
import lombok.extern.slf4j.Slf4j;

/**
 * per-shard 非交易命令分发器：{@link RiskEngine#preProcessCommand} 把不涉及下单撮合 / 结算的命令委托到这里，
 * 让 RiskEngine 主体聚焦下单（R1）与结算（R2）。
 *
 * <p><b>处理范围</b>（{@link #dispatch} 与方法定义均按此三类顺序排列）：
 * <ul>
 *   <li><b>账户维度</b>：建用户（ADD_USER）、余额充提（BALANCE_ADJUSTMENT）、追加保证金（MARGIN_ADJUSTMENT）、
 *       杠杆调整（LEVERAGE_ADJUSTMENT）、持仓模式（POSITION_MODE_ADJUSTMENT）、挂起/恢复（SUSPEND_USER / RESUME_USER）；</li>
 *   <li><b>行情</b>：标记价更新（MARKPRICE_ADJUSTMENT）；</li>
 *   <li><b>运营</b>：保险基金充提（IF_DEPOSIT / IF_WITHDRAW）、手续费重置（RESET_FEE）、借贷利率重定价（REPRICE_LOAN_RATES）、
 *       BINARY_DATA 批量加 currency/symbol/account 与 loan 配置更新 / 报表查询。</li>
 * </ul>
 *
 * <p><b>状态归属</b>：本类<b>无自有状态</b>，只持 {@link RiskEngine} 引用；一切资金 / 序列化状态（accounts、
 * fees/adjustments/suspends 守恒桶、IF 池、loan 配置）都在 RiskEngine，经其 getter 与包内可见的
 * {@link RiskEngine#calculateLocked}/{@link RiskEngine#calculateFreeFuturesMargin}/{@link RiskEngine#loanCollateralLocked}
 * 访问——每次调用都取当前引用，故 snapshot 恢复后不会 stale。
 *
 * <p><b>分片</b>：账户维度命令过 {@code uidForThisHandler} 分片门；标记价 / 运营命令各 shard 都处理，只 shard 0 对外置 resultCode。
 */
@Slf4j
public final class RiskEngineCommandDispatcher {

    private static final long DUST_SAFETY_LIMIT = 1000L;

    private final RiskEngine engine;

    public RiskEngineCommandDispatcher(RiskEngine engine) {
        this.engine = engine;
    }

    /**
     * 非交易命令分发入口：{@link RiskEngine#preProcessCommand} 对 {@code isNonTrading()} 命令整块委托到这里。
     * 分片门（uidForThisHandler）、margin-trading 门、IF 定向 shard 的写码规则都在此，主 switch 不再感知这些命令。
     * case 顺序与类内方法定义顺序一致：账户维度 → 行情 → 运营。
     */
    public void dispatch(final OrderCommand cmd) {
        switch (cmd.command) {
            // ===== 账户维度：过 uidForThisHandler 分片门 =====
            case ADD_USER:
                if (engine.uidForThisHandler(cmd.uid)) {
                    addUser(cmd);
                }
                return;
            case BALANCE_ADJUSTMENT:
                if (engine.uidForThisHandler(cmd.uid)) {
                    adjustBalance(cmd);
                }
                return;
            case INTERNAL_TRANSFER:
                // collectInput 内部自 gate（from-shard 扣款、to-shard 由 R2 入账），此处不加 uidForThisHandler
                engine.getInternalTransferProcessor().collectInput(cmd);
                return;
            case MARGIN_ADJUSTMENT:
                if (engine.uidForThisHandler(cmd.uid)) {
                    adjustMargin(cmd);
                }
                return;
            case LEVERAGE_ADJUSTMENT:
                if (engine.uidForThisHandler(cmd.uid)) {
                    if (!engine.isCfgMarginTradingEnabled()) {
                        cmd.resultCode = CommandResultCode.RISK_MARGIN_TRADING_DISABLED;
                        return;
                    }
                    cmd.resultCode = adjustLeverage(cmd);
                }
                return;
            case POSITION_MODE_ADJUSTMENT:
                if (engine.uidForThisHandler(cmd.uid)) {
                    adjustPositionMode(cmd);
                }
                return;
            case SUSPEND_USER:
                if (engine.uidForThisHandler(cmd.uid)) {
                    suspendUser(cmd);
                }
                return;
            case RESUME_USER:
                if (engine.uidForThisHandler(cmd.uid)) {
                    resumeUser(cmd);
                }
                return;

            // ===== 行情：各 shard 都处理，无 uid 分片门 =====
            case MARKPRICE_ADJUSTMENT:
                adjustMarkPrice(cmd);
                return;

            // ===== 运营：各 shard 都跑 handle，只 target shard / shard 0 对外置 resultCode =====
            case IF_DEPOSIT: {
                // 定向单 shard：所有 shard 都跑 handle（内部按 shardId 短路），只 target shard 写 resultCode
                final CommandResultCode rc = processIFDeposit(cmd);
                if ((int)cmd.uid == engine.getShardId()) {
                    cmd.resultCode = rc;
                }
                return;
            }
            case IF_WITHDRAW: {
                final CommandResultCode rc = processIFWithdraw(cmd);
                if ((int)cmd.uid == engine.getShardId()) {
                    cmd.resultCode = rc;
                }
                return;
            }
            case RESET_FEE:
                // 各 shard collectInput 汇总入参，R2 由 resetFeeProcessor 派发
                engine.getResetFeeProcessor().collectInput(cmd);
                if (engine.getShardId() == 0) {
                    cmd.resultCode = CommandResultCode.VALID_FOR_MATCHING_ENGINE;
                }
                return;
            case REPRICE_LOAN_RATES:
                engine.getLoanRatePricingProcessor().collectInput(cmd);
                if (engine.getShardId() == 0) {
                    cmd.resultCode = CommandResultCode.VALID_FOR_MATCHING_ENGINE;
                }
                return;
            case BINARY_DATA_COMMAND:
            case BINARY_DATA_QUERY:
                // acceptBinaryFrame 组帧完整后回调 handleBinaryMessage / handleReportQuery；resultCode 由 MatchingEngineRouter 兜底写
                engine.getBinaryCommandsProcessor().acceptBinaryFrame(cmd);
                if (engine.getShardId() == 0) {
                    cmd.resultCode = CommandResultCode.VALID_FOR_MATCHING_ENGINE;
                }
                return;

            default:
                // isNonTrading() 与本 switch 必须同步；漏配即静默 no-op，此处兜底防御
                return;
        }
    }

    // ====================================================================================
    // 账户维度命令
    // ====================================================================================

    /** ADD_USER：创建空用户档案；已存在则返回 USER_MGMT_USER_ALREADY_EXISTS。 */
    private void addUser(final OrderCommand cmd) {
        cmd.resultCode = engine.getUserProfileService().addEmptyUserProfile(cmd.uid) ? CommandResultCode.SUCCESS
            : CommandResultCode.USER_MGMT_USER_ALREADY_EXISTS;
    }

    /** BALANCE_ADJUSTMENT：充值 / 提现。提现（price&lt;0）先过 NSF（扣现货冻结 + 借贷抵押，可用期货浮盈），再 applyBalanceAdjustment + 发事件。 */
    private void adjustBalance(final OrderCommand cmd) {
        final int currency = cmd.symbol;
        final long amountDiff = cmd.price;
        final UserProfile userProfile = engine.getUserProfileService().getUserProfile(cmd.uid);
        if (userProfile == null) {
            cmd.resultCode = CommandResultCode.AUTH_INVALID_USER;
            return;
        }
        // 提现 NSF：可提余额（现货冻结 / 借贷抵押必扣，可用期货浮盈）≥ 提现额。与内部转账共用同一口径。
        if (amountDiff < 0) {
            final long withdrawalAmount = -amountDiff;
            if (engine.withdrawableBalance(userProfile, currency) - withdrawalAmount < 0) {
                cmd.resultCode = CommandResultCode.RISK_NSF;
                return;
            }
        }
        cmd.resultCode = applyBalanceAdjustment(cmd.uid, cmd.symbol, cmd.price, cmd.orderId,
            BalanceAdjustmentType.of(cmd.orderType.getCode()), cmd.timestamp);
        if (cmd.resultCode == CommandResultCode.SUCCESS) {
            final long locked = engine.calculateLocked(userProfile, currency);
            final long balance = userProfile.accounts.get(currency);
            if (amountDiff > 0) {
                engine.getEventsHelper().sendDepositEvent(cmd, currency, balance - locked, locked);
            } else {
                engine.getEventsHelper().sendWithdrawEvent(cmd, currency, balance - locked, locked);
            }
        }
    }

    /**
     * MARGIN_ADJUSTMENT：给持仓追加保证金。CROSS 直接充入账户（所有同 currency 全仓仓位共享 accounts）；
     * ISOLATED 从 accounts 转入 position.extraMargin（NSF 通过 + 幂等后）。
     */
    private void adjustMargin(final OrderCommand cmd) {
        if (!engine.isCfgMarginTradingEnabled()) {
            cmd.resultCode = CommandResultCode.RISK_MARGIN_TRADING_DISABLED;
            return;
        }
        if (cmd.price <= 0) {
            cmd.resultCode = CommandResultCode.RISK_INVALID_AMOUNT;
            return;
        }
        final UserProfile userProfile = engine.getUserProfileService().getUserProfile(cmd.uid);
        if (userProfile == null) {
            cmd.resultCode = CommandResultCode.AUTH_INVALID_USER;
            return;
        }
        if (cmd.marginMode == MarginMode.CROSS) {
            // CROSS 无 extraMargin：走 applyBalanceAdjustment 充入 accounts（accounts += price，adjustments bucket 对冲），
            // 幂等由 cmd.orderId 走 processedTransactionIds。
            cmd.resultCode = applyBalanceAdjustment(cmd.uid, cmd.symbol, cmd.price, cmd.orderId,
                BalanceAdjustmentType.ADJUSTMENT, cmd.timestamp);
            if (cmd.resultCode == CommandResultCode.SUCCESS) {
                final long locked = engine.calculateLocked(userProfile, cmd.symbol);
                final long free = userProfile.accounts.get(cmd.symbol) - locked;
                // 同 currency 所有 CROSS 仓位共享同一 free/locked 快照，各自收事件
                userProfile.positions
                    .select(pos -> pos.marginMode == MarginMode.CROSS && pos.currency == cmd.symbol)
                    .forEach(pos -> engine.getEventsHelper().sendMarginAdjustmentEvent(cmd, pos, free, locked));
            }
            return;
        }
        // ISOLATED：从 accounts 转入 position.extraMargin
        final SymbolPositionRecord pos =
            userProfile.positions.get(userProfile.createPositionsKey(cmd.symbol, cmd.action, cmd.command));
        if (pos == null) {
            cmd.resultCode = CommandResultCode.RISK_MARGIN_POSITION_NOT_EXISTS;
            return;
        }
        if (pos.marginMode != cmd.marginMode) {
            cmd.resultCode = CommandResultCode.RISK_MARGIN_MODE_MISMATCH;
            return;
        }
        // NSF：可提余额 ≥ 追加保证金（现货冻结 / 借贷抵押必扣，不能拨进 isolate margin 否则贷款变裸债）
        if (engine.withdrawableBalance(userProfile, pos.currency) - cmd.price < 0) {
            cmd.resultCode = CommandResultCode.RISK_NSF;
            return;
        }
        // ISOLATED 无 adjustments bucket 对冲，按 cmd.orderId 自行幂等；NSF 通过后再 tryClaim
        if (!userProfile.processedTransactionIds.tryClaim(cmd.orderId, cmd.timestamp)) {
            cmd.resultCode = CommandResultCode.USER_MGMT_ACCOUNT_BALANCE_ADJUSTMENT_ALREADY_APPLIED_SAME;
            return;
        }
        // accounts −= price（currencyScale），extraMargin += price（sizePriceScale）。必须 currencyToSizePriceScale：
        // extraMargin 要与 openInitMarginSum 同单位(baseScaleK×quoteScaleK)，否则爆仓价/破产价严重偏低。
        final long quoteBalance = userProfile.accounts.addToValue(pos.currency, -cmd.price);
        final CoreCurrencySpecification currencySpec =
            engine.getCurrencySpecificationProvider().getCurrencySpecification(pos.currency);
        final CoreSymbolSpecification spec = engine.getSymbolSpecificationProvider().getSymbolSpecification(pos.symbol);
        pos.extraMargin += CoreArithmeticUtils.currencyToSizePriceScale(cmd.price, spec, currencySpec);
        cmd.resultCode = CommandResultCode.SUCCESS;
        final long quoteLocked = engine.calculateLocked(userProfile, pos.currency);
        engine.getEventsHelper().sendMarginAdjustmentEvent(cmd, pos, quoteBalance - quoteLocked, quoteLocked);
    }

    /**
     * LEVERAGE_ADJUSTMENT（调用方已确认 margin trading 开启）：
     * <ol>
     *   <li>收集 user 在该 symbol 的所有 position（无仓位直接 SUCCESS）；</li>
     *   <li>算每个 position 新旧 required margin 并校验单仓 leverage 合法；新需求 &gt; 旧需求时做 NSF check；</li>
     *   <li>全部仓位 updateLeverage。</li>
     * </ol>
     */
    private CommandResultCode adjustLeverage(final OrderCommand cmd) {
        final UserProfile userProfile = engine.getUserProfileService().getUserProfile(cmd.uid);
        if (userProfile == null) {
            cmd.resultCode = CommandResultCode.AUTH_INVALID_USER;
            log.warn("User profile {} not found", cmd.uid);
            return CommandResultCode.AUTH_INVALID_USER;
        }

        final CoreSymbolSpecification spec = engine.getSymbolSpecificationProvider().getSymbolSpecification(cmd.symbol);
        if (spec == null) {
            log.warn("Symbol {} not found", cmd.symbol);
            return CommandResultCode.INVALID_SYMBOL;
        }

        List<SymbolPositionRecord> positions = FastList.newList();
        userProfile.processPositionRecord(cmd.symbol, positions::add);
        if (positions.isEmpty()) {
            return CommandResultCode.SUCCESS;
        }
        LastPriceCacheRecord priceRecord = engine.getLastPriceCache().get(cmd.symbol);
        // 0=默认 1x：与落地侧 updateLeverage(cmd.leverage) 的归一保持一致，避免 calculateInitMargin 除零；负数交给 isValidLeverage 拒
        final int effectiveLeverage = cmd.leverage == 0 ? 1 : cmd.leverage;
        long oldRequired = 0L;
        long newRequired = 0L;
        for (SymbolPositionRecord position : positions) {
            long notional = position.estimateNotionalForOrder(null, 0, priceRecord.markPrice);
            if (!spec.isValidLeverage(notional, effectiveLeverage)) {
                return CommandResultCode.RISK_INVALID_LEVERAGE;
            }
            oldRequired += position.calculateRequiredMarginForFutures(spec);
            newRequired += position.calculateRequiredMarginForFutures(spec, effectiveLeverage);
        }
        if (newRequired > oldRequired) {
            long balance = userProfile.accounts.get(spec.quoteCurrency);
            long locked = engine.calculateLocked(userProfile, spec.quoteCurrency);
            CoreCurrencySpecification currencySpec =
                engine.getCurrencySpecificationProvider().getCurrencySpecification(spec.quoteCurrency);
            long diff = CoreArithmeticUtils.sizePriceToCurrencyScale(newRequired - oldRequired, spec, currencySpec);
            if (diff > (balance - locked)) {
                return CommandResultCode.RISK_NSF;
            }
        }
        for (SymbolPositionRecord position : positions) {
            position.updateLeverage(effectiveLeverage);
        }
        return CommandResultCode.SUCCESS;
    }

    /** POSITION_MODE_ADJUSTMENT：切换 ONEWAY/HEDGE。已是目标模式直接 SUCCESS；有持仓则拒（RISK_MARGIN_POSITION_EXISTS）。 */
    private void adjustPositionMode(final OrderCommand cmd) {
        final UserProfile userProfile = engine.getUserProfileService().getUserProfile(cmd.uid);
        if (userProfile == null) {
            cmd.resultCode = CommandResultCode.AUTH_INVALID_USER;
            return;
        }
        final PositionMode positionMode = PositionMode.of(cmd.action.getCode());
        if (userProfile.positionMode == positionMode) {
            cmd.resultCode = CommandResultCode.SUCCESS;
            return;
        }
        if (!userProfile.positions.isEmpty()) {
            cmd.resultCode = CommandResultCode.RISK_MARGIN_POSITION_EXISTS;
            return;
        }
        userProfile.positionMode = positionMode;
        cmd.resultCode = CommandResultCode.SUCCESS;
    }

    /**
     * SUSPEND_USER：挂起前把精度漂移残留 dust 充缴到 fees、清零账户（dust 本就是应付未付的 fee 尾差）。
     * 守恒：dust 从 accounts + exchangeLocked 扣除并入 fees，全局 sum delta = 0。仅当满足<b>全部</b>前提才补缴：
     * <ol>
     *   <li>无持仓；</li>
     *   <li>每个 currency accounts == exchangeLocked（free 已提完）；</li>
     *   <li>exchangeLocked &lt; DUST_SAFETY_LIMIT（防御兜底）；</li>
     *   <li>accounts 非 0 的 currency 在 exchangeLocked 上有等额。</li>
     * </ol>
     */
    private void suspendUser(final OrderCommand cmd) {
        final UserProfile up = engine.getUserProfileService().getUserProfile(cmd.uid);
        if (up != null && !up.positions.anySatisfy(pos -> !pos.isEmpty())) {
            final boolean[] eligible = {true};
            up.exchangeLocked.forEachKeyValue((currency, locked) -> {
                if (!eligible[0]) {
                    return;
                }
                if (locked >= DUST_SAFETY_LIMIT || up.accounts.get(currency) != locked) {
                    eligible[0] = false;
                }
            });
            if (eligible[0]) {
                up.accounts.forEachKeyValue((currency, acc) -> {
                    if (eligible[0] && acc != 0 && up.exchangeLocked.get(currency) != acc) {
                        eligible[0] = false;
                    }
                });
            }
            if (eligible[0]) {
                up.exchangeLocked.forEachKeyValue((currency, dust) -> {
                    if (dust > 0) {
                        up.accounts.addToValue(currency, -dust);
                        engine.getFees().addToValue(currency, dust);
                    }
                });
                up.exchangeLocked.clear();
            }
        }
        cmd.resultCode = engine.getUserProfileService().suspendUserProfile(cmd.uid);
    }

    /** RESUME_USER：解除挂起，恢复为 ACTIVE。 */
    private void resumeUser(final OrderCommand cmd) {
        cmd.resultCode = engine.getUserProfileService().resumeUserProfile(cmd.uid);
    }

    /**
     * 账户增减 + 守恒对冲（账户命令共用原语）：成功调账后按 adjustmentType 反向更新 adjustments / suspends bucket
     * （accounts += amountDiff、对应 bucket -= amountDiff，全局 sum delta = 0）。
     */
    private CommandResultCode applyBalanceAdjustment(long uid, int currency, long amountDiff, long transactionId,
        BalanceAdjustmentType adjustmentType, long nowMs) {
        final CommandResultCode res =
            engine.getUserProfileService().balanceAdjustment(uid, currency, amountDiff, transactionId, nowMs);
        if (res == CommandResultCode.SUCCESS) {
            switch (adjustmentType) {
                case ADJUSTMENT:
                    engine.getAdjustments().addToValue(currency, -amountDiff);
                    break;
                case SUSPEND:
                    engine.getSuspends().addToValue(currency, -amountDiff);
                    break;
            }
        }
        return res;
    }

    /** 批量播种新用户初始余额：accounts += amount 并对 adjustments 做守恒对冲。仅 BatchAddAccounts 播种用。 */
    private void seedNewUserBalance(long uid, int currency, long amount) {
        final UserProfile up = engine.getUserProfileService().getUserProfile(uid);
        if (up != null) {
            up.accounts.addToValue(currency, amount);
            engine.getAdjustments().addToValue(currency, -amount);
        }
    }

    // ====================================================================================
    // 行情
    // ====================================================================================

    /** MARKPRICE_ADJUSTMENT：更新标记价并触发 on-lane 强平检测。市场数据、各 shard 都处理；resultCode 仅 shard 0 置。 */
    private void adjustMarkPrice(final OrderCommand cmd) {
        final CoreSymbolSpecification spec = engine.getSymbolSpecificationProvider().getSymbolSpecification(cmd.symbol);
        if (spec == null) {
            cmd.resultCode = CommandResultCode.INVALID_SYMBOL;
            return;
        }
        final LastPriceCacheRecord priceRecord =
            engine.getLastPriceCache().getIfAbsentPut(cmd.symbol, LastPriceCacheRecord::new);
        priceRecord.markPrice = cmd.price;
        priceRecord.markPriceTs = cmd.timestamp;
        engine.getLiquidationEngine().checkPositions(cmd);
        if (engine.getShardId() == 0) {
            cmd.resultCode = CommandResultCode.SUCCESS;
        }
    }

    // ====================================================================================
    // 运营命令
    // ====================================================================================

    /**
     * IF_DEPOSIT 定向单 shard 充值：cmd.uid 承载目标 shardId，只有匹配的 RiskEngine 入账，其他 shard 静默 SUCCESS no-op。
     * 运营侧通过 {@link exchange.core2.core.common.api.reports.InsuranceFundReportQuery} 查各 shard 明细决定注资目标，
     * 应对分片不均衡（跟期货 IF 池同款 per-shard state 设计）。
     *
     * <p>精度可逆校验：sizePriceToCurrencyScale(currencyToSizePriceScale(currencyAmount)) 必须严格等于原值，
     * 否则 adjustments 对冲会有截断残量、对账漂移。
     */
    private CommandResultCode processIFDeposit(final OrderCommand cmd) {
        // 定向 shard 路由：不是我的 shard 直接静默 SUCCESS no-op
        if ((int)cmd.uid != engine.getShardId()) {
            return CommandResultCode.SUCCESS;
        }
        final CoreSymbolSpecification spec = engine.getSymbolSpecificationProvider().getSymbolSpecification(cmd.symbol);
        if (spec == null) {
            return CommandResultCode.INVALID_SYMBOL;
        }
        final long currencyAmount = cmd.price;
        if (currencyAmount <= 0) {
            return CommandResultCode.RISK_INVALID_AMOUNT;
        }
        final CoreCurrencySpecification currencySpec =
            engine.getCurrencySpecificationProvider().getCurrencySpecification(spec.quoteCurrency);
        if (currencySpec == null) {
            return CommandResultCode.INVALID_SYMBOL;
        }
        // currency 尺度 → 撮合 product (size*price) 尺度
        final long notional = CoreArithmeticUtils.currencyToSizePriceScale(currencyAmount, spec, currencySpec);
        // 精度可逆校验：sizePriceToCurrencyScale(notional) 必须严格等于 currencyAmount，
        // 否则 adjustments 对冲会有截断残量，对账漂移。
        final long roundTripped = CoreArithmeticUtils.sizePriceToCurrencyScale(notional, spec, currencySpec);
        if (roundTripped != currencyAmount) {
            return CommandResultCode.RISK_INVALID_AMOUNT;
        }
        engine.getLiquidationService().depositToInsuranceFund(cmd.symbol, notional);
        // 对冲：本 shard 的 adjustments 反向记账，本 shard 内 sum = 0
        engine.getAdjustments().addToValue(spec.quoteCurrency, -currencyAmount);
        return CommandResultCode.SUCCESS;
    }

    /**
     * IF_WITHDRAW 定向单 shard 抽资：语义跟 {@link #processIFDeposit} 对称，差别只在正负号 + 非负校验。
     * IF available 不足以覆盖时返回 {@link CommandResultCode#RISK_IF_INSUFFICIENT}。
     * 仅从 available 扣，不动 reserved（reserved 是正在保护某笔强平的预冻结部分）。
     */
    private CommandResultCode processIFWithdraw(final OrderCommand cmd) {
        // 定向 shard 路由：不是我的 shard 直接静默 SUCCESS no-op
        if ((int)cmd.uid != engine.getShardId()) {
            return CommandResultCode.SUCCESS;
        }
        final CoreSymbolSpecification spec = engine.getSymbolSpecificationProvider().getSymbolSpecification(cmd.symbol);
        if (spec == null) {
            return CommandResultCode.INVALID_SYMBOL;
        }
        final long currencyAmount = cmd.price;
        if (currencyAmount <= 0) {
            return CommandResultCode.RISK_INVALID_AMOUNT;
        }
        final CoreCurrencySpecification currencySpec =
            engine.getCurrencySpecificationProvider().getCurrencySpecification(spec.quoteCurrency);
        if (currencySpec == null) {
            return CommandResultCode.INVALID_SYMBOL;
        }
        final long notional = CoreArithmeticUtils.currencyToSizePriceScale(currencyAmount, spec, currencySpec);
        final long roundTripped = CoreArithmeticUtils.sizePriceToCurrencyScale(notional, spec, currencySpec);
        if (roundTripped != currencyAmount) {
            return CommandResultCode.RISK_INVALID_AMOUNT;
        }
        // 非负校验在 LiquidationService 里；只扣 available，不动 reserved
        if (!engine.getLiquidationService().withdrawFromInsuranceFund(cmd.symbol, notional)) {
            return CommandResultCode.RISK_IF_INSUFFICIENT;
        }
        // 反向对冲：adjustments 加回
        engine.getAdjustments().addToValue(spec.quoteCurrency, currencyAmount);
        return CommandResultCode.SUCCESS;
    }

    /**
     * BINARY_DATA dispatcher（{@link #dispatch} 里 acceptBinaryFrame 组帧完成后回调，收的是解码后的 {@link BinaryDataCommand}）：
     * 按消息子类型路由到批量加 currency / symbol / accounts / loan 配置；margin symbol 在 spot-only 部署下拒绝。
     */
    void handleBinaryMessage(BinaryDataCommand message) {
        if (message instanceof BatchAddCurrenciesCommand) {
            final IntObjectHashMap<CoreCurrencySpecification> currencies =
                ((BatchAddCurrenciesCommand)message).getCurrencies();
            currencies.forEach(spec -> engine.getCurrencySpecificationProvider().addCurrency(spec));

        } else if (message instanceof BatchAddSymbolsCommand) {
            final IntObjectHashMap<CoreSymbolSpecification> symbols = ((BatchAddSymbolsCommand)message).getSymbols();
            symbols.forEach(spec -> {
                if (spec.type == SymbolType.CURRENCY_EXCHANGE_PAIR || engine.isCfgMarginTradingEnabled()) {
                    engine.getSymbolSpecificationProvider().addSymbol(spec);
                } else {
                    log.warn("Margin symbols are not allowed: {}", spec);
                }
            });

        } else if (message instanceof BatchAddAccountsCommand) {
            ((BatchAddAccountsCommand)message).getUsers().forEachKeyValue((uid, accounts) -> {
                if (engine.getUserProfileService().addEmptyUserProfile(uid)) {
                    accounts.forEachKeyValue((cur, bal) -> seedNewUserBalance(uid, cur, bal));
                } else {
                    log.debug("User already exist: {}", uid);
                }
            });

        } else if (message instanceof BatchAddLoanCommand) {
            // 统一 loan 配置更新：global / symbol / rateCurve 三部分作用域独立，各自校验、各自 apply，一部分非法不影响另一部分。
            final BatchAddLoanCommand cmd = (BatchAddLoanCommand)message;
            // --- 全局部分：partial-update（≤0=不改）+ apply-all-or-nothing，任一违规整条拒绝、不留半更新 ---
            if (cmd.hasGlobal()) {
                final BatchAddLoanCommand.GlobalLoanConfig g = cmd.getGlobal();
                final int newNumeraire = g.getNumeraireCurrency();
                final LoanGlobalConfig config = engine.getLoanService().getGlobalConfig();
                final boolean numeraireOk = newNumeraire <= 0
                    || engine.getCurrencySpecificationProvider().getCurrencySpecification(newNumeraire) != null;
                if (numeraireOk
                    && g.thresholdsValidGivenCurrent(config.crossLiquidationLtvBps, config.crossMarginCallLtvBps)) {
                    if (newNumeraire > 0) {
                        config.numeraireCurrency = newNumeraire;
                    }
                    if (g.getCrossLiquidationLtvBps() > 0) {
                        config.crossLiquidationLtvBps = g.getCrossLiquidationLtvBps();
                    }
                    if (g.getCrossMarginCallLtvBps() > 0) {
                        config.crossMarginCallLtvBps = g.getCrossMarginCallLtvBps();
                    }
                    if (g.getLoanPoolUtilizationCapBps() > 0) {
                        config.loanPoolUtilizationCapBps = g.getLoanPoolUtilizationCapBps();
                    }
                    if (g.getLoanLiquidationFeeBps() > 0) {
                        config.loanLiquidationFeeBps = g.getLoanLiquidationFeeBps();
                    }
                    if (g.getLtvLiquidationBufferBps() > 0) {
                        config.ltvLiquidationBufferBps = g.getLtvLiquidationBufferBps();
                    }
                    if (g.getLtvMarginCallBufferBps() > 0) {
                        config.ltvMarginCallBufferBps = g.getLtvMarginCallBufferBps();
                    }
                    log.info("ADD_LOAN global config applied: {}", g);
                } else {
                    log.warn("ADD_LOAN global config rejected (invalid config): {}", g);
                }
            }
            // --- per-symbol 部分：字段层校验 + spec 存在性/类型校验，valid 才原子改写该 pair 的 loan 配置 ---
            if (cmd.hasSymbol()) {
                final BatchAddLoanCommand.SymbolLoanConfig s = cmd.getSymbol();
                final CoreSymbolSpecification spec =
                    engine.getSymbolSpecificationProvider().getSymbolSpecification(s.getSymbolId());
                final LoanGlobalConfig gc = engine.getLoanService().getGlobalConfig();
                final BatchAddLoanCommand.SymbolLoanConfig.Resolved r =
                    s.resolve(gc.ltvLiquidationBufferBps, gc.ltvMarginCallBufferBps);
                if (spec != null && spec.type == SymbolType.CURRENCY_EXCHANGE_PAIR && r.valid()) {
                    if (r.initialLtvBps == 0) {
                        // 停借只关开关：liquidation/marginCall 由 initialLtv 派生，跟着归零会把存量贷款连带强平，故保持原值
                        final SymbolLoanSpecification cur = spec.loanConfig;
                        spec.updateLoanConfig(0, cur.liquidationLtvBps, cur.marginCallLtvBps, cur.maxAmount,
                            cur.maxTermDays);
                    } else {
                        spec.updateLoanConfig(r.initialLtvBps, r.liquidationLtvBps, r.marginCallLtvBps, r.maxAmount,
                            r.maxTermDays);
                        // collateralWeightBps 是 base 币的账户级折价率，同 base 的多个 pair 共享，后写覆盖前写
                        final CoreCurrencySpecification baseCurrencySpec =
                            engine.getCurrencySpecificationProvider().getCurrencySpecification(spec.baseCurrency);
                        if (baseCurrencySpec != null) {
                            baseCurrencySpec.updateCollateralWeight(r.collateralWeightBps);
                        }
                    }
                    log.info("ADD_LOAN symbol config applied (resolved): {}", r);
                } else {
                    log.warn("ADD_LOAN symbol config rejected: {}", s);
                }
            }

            // 利率曲线部分：存在即整体替换 Floating 曲线参数 + Fixed 点差（valid 才写，否则整块拒绝）
            if (cmd.hasRateCurve()) {
                final BatchAddLoanCommand.RateCurveConfig rc = cmd.getRateCurve();
                if (rc.valid()) {
                    final FloatingRateModel floating = engine.getLoanService().getFloatingRate();
                    floating.setBaseBps(rc.getBaseBps());
                    floating.setKinkUtilBps(rc.getKinkUtilBps());
                    floating.setSlope1Bps(rc.getSlope1Bps());
                    floating.setSlope2Bps(rc.getSlope2Bps());
                    engine.getLoanService().getFixedRate().setLockedRateAdjustBps(rc.getLockedRateAdjustBps());
                    log.info("ADD_LOAN rate curve applied: {}", rc);
                } else {
                    log.warn("ADD_LOAN rate curve rejected: {}", rc);
                }
            }
        }
    }
}
