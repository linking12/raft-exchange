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

import java.util.HashMap;
import java.util.Optional;
import java.util.function.ObjLongConsumer;

import org.eclipse.collections.impl.map.mutable.primitive.IntLongHashMap;
import org.eclipse.collections.impl.map.mutable.primitive.IntObjectHashMap;

import exchange.core2.collections.objpool.ObjectsPool;
import exchange.core2.core.SimpleEventsProcessor;
import exchange.core2.core.common.CoreCurrencySpecification;
import exchange.core2.core.common.CoreSymbolSpecification;
import exchange.core2.core.common.IsolatedLoanRecord;
import exchange.core2.core.common.L2MarketData;
import exchange.core2.core.common.MarginMode;
import exchange.core2.core.common.MatcherEventType;
import exchange.core2.core.common.MatcherTradeEvent;
import exchange.core2.core.common.OrderAction;
import exchange.core2.core.common.OrderType;
import exchange.core2.core.common.PositionDirection;
import exchange.core2.core.common.PositionMode;
import exchange.core2.core.common.SymbolPositionRecord;
import exchange.core2.core.common.SymbolType;
import exchange.core2.core.common.UserProfile;
import exchange.core2.core.common.api.reports.ReportQuery;
import exchange.core2.core.common.api.reports.ReportResult;
import exchange.core2.core.common.cmd.CommandResultCode;
import exchange.core2.core.common.cmd.OrderCommand;
import exchange.core2.core.common.cmd.OrderCommandType;
import exchange.core2.core.common.config.ExchangeConfiguration;
import exchange.core2.core.common.config.LoggingConfiguration;
import exchange.core2.core.common.config.OrdersProcessingConfiguration;
import exchange.core2.core.common.config.ReportsQueriesConfiguration;
import exchange.core2.core.processors.journaling.ISerializationProcessor;
import exchange.core2.core.processors.liquidation.LiquidationEngine;
import exchange.core2.core.processors.liquidation.LiquidationService;
import exchange.core2.core.processors.loan.LoanCommandDispatcher;
import exchange.core2.core.processors.loan.LoanService;
import exchange.core2.core.utils.CoreArithmeticUtils;
import exchange.core2.core.utils.SerializationUtils;
import exchange.core2.core.utils.UnsafeUtils;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import net.openhft.chronicle.bytes.BytesOut;
import net.openhft.chronicle.bytes.WriteBytesMarshallable;

/**
 * Stateful risk engine
 */
@Slf4j
@Getter
public final class RiskEngine implements WriteBytesMarshallable {

    // 这些都是有状态的字段 state
    private SymbolSpecificationProvider symbolSpecificationProvider;
    private CurrencySpecificationProvider currencySpecificationProvider;
    private UserProfileService userProfileService;
    private LiquidationService liquidationService;
    private LoanService loanService;
    private BinaryCommandsProcessor binaryCommandsProcessor;
    private IntObjectHashMap<LastPriceCacheRecord> lastPriceCache;
    private IntLongHashMap fees;
    private IntLongHashMap adjustments;
    private IntLongHashMap suspends;

    // 无状态的配置字段
    private final SharedPool sharedPool;
    private final ObjectsPool objectsPool;
    // sharding by symbolId
    private final int shardId;
    private final int numShards;
    private final long shardMask;
    private final boolean cfgIgnoreRiskProcessing;
    private final boolean cfgMarginTradingEnabled;
    private final ISerializationProcessor serializationProcessor;
    private final boolean logDebug;
    private final ReportsQueriesConfiguration reportsQueriesConfiguration;
    private final ObjLongConsumer<OrderCommand> resultsConsumer;
    private final LiquidationEngine liquidationEngine;
    private final FundEventsHelper eventsHelper;

    private final ADLCommandProcessor adlProcessor;
    private final IFCommandProcessor ifProcessor;
    private final FundingFeeCommandProcessor fundingFeeProcessor;
    private final ResetFeeCommandProcessor resetFeeProcessor;
    private final InternalTransferProcessor internalTransferProcessor;
    private final LoanRatePricingProcessor loanRatePricingProcessor;
    private final LoanCommandDispatcher loanCommandDispatcher;
    private final RiskEngineCommandDispatcher commandDispatcher;

    public RiskEngine(final int shardId, final int numShards, final ISerializationProcessor serializationProcessor,
        final SharedPool sharedPool, final ExchangeConfiguration exchangeConfiguration,
        final ObjLongConsumer<OrderCommand> resultsConsumer) {
        if (Long.bitCount(numShards) != 1) {
            throw new IllegalArgumentException("Invalid number of shards " + numShards + " - must be power of 2");
        }
        this.shardId = shardId;
        this.numShards = numShards;
        this.shardMask = numShards - 1;
        this.serializationProcessor = serializationProcessor;
        this.sharedPool = sharedPool;
        // initialize object pools
        final HashMap<Integer, Integer> objectsPoolConfig = new HashMap<>();
        objectsPoolConfig.put(ObjectsPool.SYMBOL_POSITION_RECORD, 1024 * 256);
        // loan record 频次远低于 position（长期持有 vs 秒级开平仓），池尺寸取 1/8 就够
        objectsPoolConfig.put(ObjectsPool.ISOLATED_LOAN_RECORD, 1024 * 32);
        objectsPoolConfig.put(ObjectsPool.CROSS_LOAN_RECORD, 1024 * 32);
        this.objectsPool = new ObjectsPool(objectsPoolConfig);
        this.logDebug = exchangeConfiguration.getLoggingCfg().getLoggingLevels()
            .contains(LoggingConfiguration.LoggingLevel.LOGGING_RISK_DEBUG);
        final OrdersProcessingConfiguration ordersProcCfg = exchangeConfiguration.getOrdersProcessingCfg();
        this.cfgIgnoreRiskProcessing = ordersProcCfg
            .getRiskProcessingMode() == OrdersProcessingConfiguration.RiskProcessingMode.NO_RISK_PROCESSING;
        this.cfgMarginTradingEnabled = ordersProcCfg
            .getMarginTradingMode() == OrdersProcessingConfiguration.MarginTradingMode.MARGIN_TRADING_ENABLED;
        this.reportsQueriesConfiguration = exchangeConfiguration.getReportsQueriesCfg();
        this.resultsConsumer = resultsConsumer;
        this.liquidationEngine = new LiquidationEngine(sharedPool::getFundEventChain, shardId, exchangeConfiguration);
        this.eventsHelper = new FundEventsHelper(sharedPool::getFundEventChain, shardId);
        // R1/R2 用的实例（shard 绑定）：matcher stage 用的 processor 由 MatchingEngineRouter 另持一份
        this.adlProcessor = new ADLCommandProcessor(this);
        this.ifProcessor = new IFCommandProcessor(this);
        this.fundingFeeProcessor = new FundingFeeCommandProcessor(this);
        this.resetFeeProcessor = new ResetFeeCommandProcessor(this);
        this.internalTransferProcessor = new InternalTransferProcessor(this);
        this.loanRatePricingProcessor = new LoanRatePricingProcessor(this);
        this.loanCommandDispatcher = new LoanCommandDispatcher(this);
        this.commandDispatcher = new RiskEngineCommandDispatcher(this);
        this.initState();
    }

    /**
     * 全新初始化所有可变状态（构造时调用）：new 出各 provider / service / cache，并把这套 provider 转发给
     * liquidationService / liquidationEngine（它们缓存引用，必须在此刷新，否则 stale）。恢复路径见 {@link #recoverStateBySnapshot}。
     */
    private void initState() {
        this.symbolSpecificationProvider = new SymbolSpecificationProvider();
        this.currencySpecificationProvider = new CurrencySpecificationProvider();
        this.userProfileService = new UserProfileService();
        this.liquidationService = new LiquidationService();
        this.loanService = new LoanService();
        this.binaryCommandsProcessor = new BinaryCommandsProcessor(commandDispatcher::handleBinaryMessage,
            this::handleReportQuery, sharedPool, reportsQueriesConfiguration, shardId);
        this.lastPriceCache = new IntObjectHashMap<LastPriceCacheRecord>();
        this.fees = new IntLongHashMap();
        this.adjustments = new IntLongHashMap();
        this.suspends = new IntLongHashMap();
        this.eventsHelper.setSymbolSpecificationProvider(this.symbolSpecificationProvider);
        this.eventsHelper.setCurrencySpecificationProvider(this.currencySpecificationProvider);
        this.eventsHelper.setUserProfileService(this.userProfileService);
        this.eventsHelper.setLastPriceCache(this.lastPriceCache);
        if (this.resultsConsumer instanceof SimpleEventsProcessor) {
            SimpleEventsProcessor simpleEventsProcessor = (SimpleEventsProcessor)this.resultsConsumer;
            simpleEventsProcessor.setNumShards(numShards);
            simpleEventsProcessor.setSymbolSpecificationProvider(this.symbolSpecificationProvider);
            simpleEventsProcessor.saveUserProfileService(shardId, this.userProfileService);
        }
        this.liquidationService.updateProvider(userProfileService, symbolSpecificationProvider,
            currencySpecificationProvider, lastPriceCache);
        this.liquidationEngine.updateProvider(symbolSpecificationProvider, currencySpecificationProvider,
            userProfileService, lastPriceCache, loanService);
    }

    /**
     * 从 raft snapshot 恢复本 shard 全部风控状态（RECOVER_STATE_RISK 命令）：反序列化 State 并原地替换各 provider / cache，
     * 再把新引用转发给 liquidationService / liquidationEngine 刷新（同 {@link #initState}，规避 stale-ref）。
     * 注意换届 / 快照安装后走此路径，故对 leader-local 缓存（如强平索引）的重建也挂在这里。
     */
    private void recoverStateBySnapshot(long snapshotId) {
        final State state = serializationProcessor.loadData(snapshotId,
            ISerializationProcessor.SerializedModuleType.RISK_ENGINE, shardId, bytesIn -> {
                if (shardId != bytesIn.readInt()) {
                    throw new IllegalStateException("wrong shardId");
                }
                if (shardMask != bytesIn.readLong()) {
                    throw new IllegalStateException("wrong shardMask");
                }
                final SymbolSpecificationProvider symbolSpecificationProvider =
                    new SymbolSpecificationProvider(bytesIn);
                final CurrencySpecificationProvider currencySpecificationProvider =
                    new CurrencySpecificationProvider(bytesIn);
                final UserProfileService userProfileService = new UserProfileService(bytesIn);
                final LiquidationService liquidationService = new LiquidationService(bytesIn);
                final LoanService loanService = new LoanService(bytesIn);
                final BinaryCommandsProcessor binaryCommandsProcessor =
                    new BinaryCommandsProcessor(commandDispatcher::handleBinaryMessage, this::handleReportQuery,
                        sharedPool, reportsQueriesConfiguration, bytesIn, shardId);
                final IntObjectHashMap<LastPriceCacheRecord> lastPriceCache =
                    SerializationUtils.readIntHashMap(bytesIn, LastPriceCacheRecord::new);
                final IntLongHashMap fees = SerializationUtils.readIntLongHashMap(bytesIn);
                final IntLongHashMap adjustments = SerializationUtils.readIntLongHashMap(bytesIn);
                final IntLongHashMap suspends = SerializationUtils.readIntLongHashMap(bytesIn);
                return new State(symbolSpecificationProvider, currencySpecificationProvider, userProfileService,
                    liquidationService, loanService, binaryCommandsProcessor, lastPriceCache, fees, adjustments,
                    suspends);
            });
        if (state.lastPriceCache == null || state.fees == null) {
            throw new IllegalStateException("Invalid recovered state: missing critical fields");
        }
        synchronized (this) {
            this.symbolSpecificationProvider = state.symbolSpecificationProvider;
            this.currencySpecificationProvider = state.currencySpecificationProvider;
            this.userProfileService = state.userProfileService;
            this.liquidationService = state.liquidationService;
            this.loanService = state.loanService;
            this.binaryCommandsProcessor = state.binaryCommandsProcessor;
            this.lastPriceCache = state.lastPriceCache;
            this.eventsHelper.setSymbolSpecificationProvider(this.symbolSpecificationProvider);
            this.eventsHelper.setCurrencySpecificationProvider(this.currencySpecificationProvider);
            this.eventsHelper.setUserProfileService(this.userProfileService);
            this.eventsHelper.setLastPriceCache(this.lastPriceCache);
            if (this.resultsConsumer instanceof SimpleEventsProcessor) {
                SimpleEventsProcessor simpleEventsProcessor = (SimpleEventsProcessor)this.resultsConsumer;
                simpleEventsProcessor.setSymbolSpecificationProvider(this.symbolSpecificationProvider);
                simpleEventsProcessor.saveUserProfileService(shardId, this.userProfileService);
            }
            this.liquidationService.updateProvider(userProfileService, symbolSpecificationProvider,
                currencySpecificationProvider, lastPriceCache);
            this.liquidationEngine.updateProvider(symbolSpecificationProvider, currencySpecificationProvider,
                userProfileService, lastPriceCache, loanService);
            this.fees = state.fees;
            this.adjustments = state.adjustments;
            this.suspends = state.suspends;
        }
    }

    /**
     * R1 pre-process 分派器（per-shard）。三级路由：
     * <ol>
     *   <li>{@code isLoan()} → {@link LoanCommandDispatcher#dispatch}（借贷子域）；</li>
     *   <li>{@code isNonTrading()} → {@link RiskEngineCommandDispatcher#dispatch}（账户 / 行情 / 运营命令）；</li>
     *   <li>其余进主 switch：交易（下单）/ 结算强平 / 引擎自身生命周期（persist / recover / reset）。</li>
     * </ol>
     *
     * <p>主 switch 通用语义：
     * <ul>
     *   <li>uidForThisHandler：判定本 shard 是否参与（uid 相关分支）；</li>
     *   <li>shardId == 0：决定是否对外暴露 resultCode（多 shard 协作分支只由 shard 0 置）；</li>
     *   <li>返回值：默认 false 让 disruptor 继续聚批；仅 PERSIST_STATE_MATCHING / RECOVER_STATE_MATCHING 返回 true 强制提前发布序号。</li>
     * </ul>
     *
     * @param seq command sequence
     * @param cmd command
     * @return true if caller should publish sequence even if batch was not processed yet
     */
    public boolean preProcessCommand(final long seq, final OrderCommand cmd) {
        if (cmd.command.isLoan()) {
            loanCommandDispatcher.dispatch(cmd);
            return false;
        }
        if (cmd.command.isNonTrading()) {
            commandDispatcher.dispatch(cmd);
            return false;
        }
        switch (cmd.command) {
            case MOVE_ORDER:
            case CANCEL_ORDER:
            case REDUCE_ORDER:
            case ORDER_BOOK_REQUEST:
                // 不涉及资金校验：直接落到 MatchingEngine 处理（cmd.resultCode 由 ME 写）
                return false;

            case CLOSE_POSITION:
                if (uidForThisHandler(cmd.uid)) {
                    cmd.resultCode = closePositionRiskCheck(cmd);
                }
                return false;

            case PLACE_ORDER:
                if (uidForThisHandler(cmd.uid)) {
                    cmd.resultCode = placeOrderRiskCheck(cmd);
                }
                return false;

            case FORCE_LIQUIDATION:
                if (uidForThisHandler(cmd.uid)) {
                    cmd.resultCode = normalizeCmdPositionSize(cmd);
                }
                return false;
            case SETTLE_FUNDINGFEES: {
                final CoreSymbolSpecification spec = symbolSpecificationProvider.getSymbolSpecification(cmd.symbol);
                if (spec == null || spec.type != SymbolType.FUTURES_CONTRACT_PERPETUAL) {
                    cmd.resultCode = CommandResultCode.INVALID_SYMBOL;
                    return false;
                }
                LastPriceCacheRecord priceRecord = lastPriceCache.get(cmd.symbol);
                if (priceRecord == null) {
                    cmd.resultCode = CommandResultCode.RISK_MARKPRICE_NOT_AVAILABLE;
                    return false;
                }
                fundingFeeProcessor.collectInput(cmd);
                if (shardId == 0) {
                    cmd.resultCode = CommandResultCode.VALID_FOR_MATCHING_ENGINE;
                }
                return false;
            }
            case SETTLE_PNL: {
                final CoreSymbolSpecification spec = symbolSpecificationProvider.getSymbolSpecification(cmd.symbol);
                if (spec == null || spec.type != SymbolType.FUTURES_CONTRACT_DELIVERY) {
                    cmd.resultCode = CommandResultCode.INVALID_SYMBOL;
                    return false;
                }
                settlePnl(cmd);
                if (shardId == 0) {
                    cmd.resultCode = CommandResultCode.SUCCESS;
                }
                return false;
            }
            case LIQUIDATION_SCAN:
                liquidationEngine.checkPositions(cmd);
                if (shardId == 0) {
                    cmd.resultCode = CommandResultCode.SUCCESS;
                }
                return false;
            case RESET:
                reset();
                if (shardId == 0) {
                    cmd.resultCode = CommandResultCode.SUCCESS;
                }
                return false;

            case PERSIST_STATE_MATCHING:
                if (shardId == 0) {
                    cmd.resultCode = CommandResultCode.VALID_FOR_MATCHING_ENGINE;
                }
                return true;// true = publish sequence before finishing processing whole batch

            case PERSIST_STATE_RISK:
                final boolean isSuccess = serializationProcessor.storeData(cmd.orderId, seq, cmd.timestamp,
                    ISerializationProcessor.SerializedModuleType.RISK_ENGINE, shardId, this);
                UnsafeUtils.setResultVolatile(cmd, isSuccess, CommandResultCode.SUCCESS,
                    CommandResultCode.STATE_PERSIST_RISK_ENGINE_FAILED);
                return false;
            case RECOVER_STATE_RISK:
                recoverStateBySnapshot(cmd.orderId);
                UnsafeUtils.setResultVolatile(cmd, true, CommandResultCode.SUCCESS,
                    CommandResultCode.STATE_RECOVER_RISK_ENGINE_FAILED);
                return false;
            case RECOVER_STATE_MATCHING: {
                if (shardId == 0) {
                    cmd.resultCode = CommandResultCode.VALID_FOR_MATCHING_ENGINE;
                }
                return true; // 同 PERSIST_STATE_MATCHING：强制提前发布序号让 MatchingEngine 立刻看到
            }
            case SYSTEM_LIQUIDATION_NOTIFY: {
                if (shardId == 0) {
                    cmd.resultCode = CommandResultCode.SUCCESS;
                }
                return false;
            }
            case IF_TAKEOVER: {
                // R1：collectInput 在各 shard 汇总入参（IF 接管的目标 user 位置 / cover 量），
                // normalizeCmdPositionSize 按 LIQ_USER position 收敛 size。R2 由 ifProcessor.applyEvent 实际派发。
                ifProcessor.collectInput(cmd);
                if (uidForThisHandler(cmd.uid)) {
                    cmd.resultCode = normalizeCmdPositionSize(cmd);
                }
                return false;
            }
            case AUTO_DELEVERAGING: {
                // R1：collectInput 汇总各 shard 候选 counterparty 持仓；R2 由 adlProcessor 派发实际接管。
                adlProcessor.collectInput(cmd);
                if (uidForThisHandler(cmd.uid)) {
                    cmd.resultCode = normalizeCmdPositionSize(cmd);
                }
                return false;
            }
            // loan 命令走首行 isLoan() 二级 dispatch，不进本主 switch
        }
        return false;
    }

    // ====================================================================================
    // R1 主线：下单（PLACE_ORDER → placeOrderRiskCheck → placeOrder → 现货/期货 分支）
    // ====================================================================================

    /**
     * 下单总入口：
     * <ol>
     *   <li>校验 user + symbol spec 存在性（不存在直接返错码）；</li>
     *   <li>cfgIgnoreRiskProcessing 开关短路（测试 / 高频路径跳过 risk）；</li>
     *   <li>转 {@link #placeOrder} 做实质 risk 检查；失败时 warn 日志带 cmd + accounts 上下文，便于线上回溯。</li>
     * </ol>
     */
    private CommandResultCode placeOrderRiskCheck(final OrderCommand cmd) {
        final UserProfile userProfile = userProfileService.getUserProfile(cmd.uid);
        if (userProfile == null) {
            cmd.resultCode = CommandResultCode.AUTH_INVALID_USER;
            log.warn("User profile {} not found", cmd.uid);
            return CommandResultCode.AUTH_INVALID_USER;
        }
        final CoreSymbolSpecification spec = symbolSpecificationProvider.getSymbolSpecification(cmd.symbol);
        if (spec == null) {
            log.warn("Symbol {} not found", cmd.symbol);
            return CommandResultCode.INVALID_SYMBOL;
        }
        if (cfgIgnoreRiskProcessing) {
            return CommandResultCode.VALID_FOR_MATCHING_ENGINE;
        }
        final CommandResultCode resultCode = placeOrder(cmd, userProfile, spec);
        if (resultCode != CommandResultCode.VALID_FOR_MATCHING_ENGINE) {
            log.warn("{} risk result={} uid={}: Can not place {}", cmd.orderId, resultCode, userProfile.uid, cmd);
            log.warn("{} accounts:{}", cmd.orderId, userProfile.accounts);
        }
        return resultCode;
    }

    /**
     * 下单前置分派：现货 → {@link #placeExchangeOrder}；期货 → 校验后 {@link #canPlaceMarginOrder} + pendingHold。
     *
     * <p>期货三阶段：
     * <ol>
     *   <li>marginMode / leverage / markPrice / reduce-only 各项前置校验（按需 new positionRecord）；</li>
     *   <li>{@link #canPlaceMarginOrder} NSF check（失败时回收刚 new 出的空 position，避免污染对象池）；</li>
     *   <li>pendingHold[Budget] 占用 + 发 sendLockPendingEvent。</li>
     * </ol>
     */
    private CommandResultCode placeOrder(final OrderCommand cmd, final UserProfile userProfile,
        final CoreSymbolSpecification spec) {
        if (spec.type == SymbolType.CURRENCY_EXCHANGE_PAIR) {
            return placeExchangeOrder(cmd, userProfile, spec);
        }
        if (!SymbolType.isFuturesContract(spec.type)) {
            return CommandResultCode.UNSUPPORTED_SYMBOL_TYPE;
        }
        if (!cfgMarginTradingEnabled) {
            return CommandResultCode.RISK_MARGIN_TRADING_DISABLED;
        }
        final LastPriceCacheRecord priceRecord = lastPriceCache.get(cmd.symbol);
        if (priceRecord == null || priceRecord.markPrice == 0) {
            return CommandResultCode.RISK_MARKPRICE_NOT_AVAILABLE;
        }
        // 同 symbol 下所有仓位必须 marginMode + leverage 一致。
        if (userProfile.countPositionRecord(spec.symbolId, pos -> pos.marginMode != cmd.marginMode) > 0) {
            return CommandResultCode.RISK_MARGIN_MODE_MISMATCH;
        }
        if (userProfile.countPositionRecord(spec.symbolId, pos -> !pos.isSameLeverage(cmd.leverage)) > 0) {
            return CommandResultCode.RISK_LEVERAGE_MISMATCH;
        }

        final int positionRecordKey = userProfile.createPositionsKey(spec.symbolId, cmd.action, cmd.command);
        SymbolPositionRecord position = userProfile.positions.get(positionRecordKey);
        final boolean newPosition = (position == null);
        if (newPosition) {
            position = objectsPool.get(ObjectsPool.SYMBOL_POSITION_RECORD, SymbolPositionRecord::new);
            position.initialize(userProfile.uid, spec.symbolId, spec.quoteCurrency, cmd.action, cmd.leverage,
                cmd.marginMode);
        }
        // ONEWAY + reduce-only：裁剪 size 到 ≤ 当前反向可平量；同向 / 无仓直接 SUCCESS no-op（不开新敞口）。
        if (userProfile.positionMode == PositionMode.ONEWAY && cmd.isReduceOnly()) {
            cmd.size = maxClosableSize(position, cmd.action, cmd.size);
            if (cmd.size <= 0) {
                if (newPosition) {
                    objectsPool.put(ObjectsPool.SYMBOL_POSITION_RECORD, position);
                }
                return CommandResultCode.SUCCESS;
            }
        }
        final CoreCurrencySpecification currencySpec =
            currencySpecificationProvider.getCurrencySpecification(position.currency);
        final long notional = position.estimateNotionalForOrder(cmd.action, cmd.size, priceRecord.markPrice);
        if (!spec.isValidLeverage(notional, cmd.leverage)) {
            if (newPosition) {
                objectsPool.put(ObjectsPool.SYMBOL_POSITION_RECORD, position);
            }
            return CommandResultCode.RISK_INVALID_LEVERAGE;
        }
        if (!canPlaceMarginOrder(cmd, userProfile, spec, position, currencySpec)) {
            if (newPosition) {
                objectsPool.put(ObjectsPool.SYMBOL_POSITION_RECORD, position);
            }
            return CommandResultCode.RISK_NSF;
        }
        // 校验全过，commit 新 position 到 map（再 pendingHold）。
        if (newPosition) {
            userProfile.positions.put(positionRecordKey, position);
            liquidationEngine.onPositionOpened(userProfile, position);
        }
        // BUDGET 单的 cmd.price 是 product-scale 总预算（= notional），用 pendingHoldBudget；
        // limit 单的 cmd.price 是单价，用原 pendingHold（price × size）。
        if (cmd.orderType == OrderType.FOK_BUDGET || cmd.orderType == OrderType.IOC_BUDGET) {
            position.pendingHoldBudget(cmd.action, cmd.size, cmd.price);
        } else {
            position.pendingHold(cmd.action, cmd.size, cmd.price);
        }
        final long balance = userProfile.accounts.get(position.currency);
        final long locked = calculateLocked(userProfile, position.currency);
        eventsHelper.sendLockPendingEvent(cmd, position, balance - locked, locked);
        return CommandResultCode.VALID_FOR_MATCHING_ENGINE;
    }

    /**
     * 期货下单 NSF 校验：仓位总保证金 + 手续费预扣 + 开仓浮亏 − 同 currency 可抵扣浮盈 ≤ 账户可支配。
     *
     * <p>
     * 字段来源：
     * <ul>
     * <li>{@code cmd.action / size / price / symbol / orderType} — 新单信息</li>
     * <li>{@code position.openVolume / direction} 判反向单； {@link SymbolPositionRecord#calculateRequiredMarginForOrder}
     * 算含新单的总仓位 margin</li>
     * <li>{@code userProfile.positions} — 遍历同账户所有仓位算 cross 抵扣</li>
     * <li>{@code userProfile.positionMode} — ONEWAY 反向单要先扣 openVolume</li>
     * <li>{@code userProfile.accounts / exchangeLocked} — 可支配 = accounts − 现货冻结 − 借贷抵押</li>
     * <li>{@code lastPriceCache[symbol].markPrice} — 其它仓 PnL 估值 + openLoss 参照</li>
     * <li>{@code spec / currencySpec} — scale 换算</li>
     * </ul>
     *
     * <p>
     * 三块可抵扣 / 预留：
     * <ul>
     * <li>{@code crossFreeMargin} = Σ(其它仓 PnL − 已锁 margin) + 本仓 PnL —— cross 浮盈可开新单；各仓按<b>自身 symbol</b> scale 折算</li>
     * <li>{@code pendingFee} — 本单成交按 taker rate 预扣（BUDGET 走专用估算）</li>
     * <li>{@code openLoss} — orderPrice 相对 mark 不利时的成交浮亏预留，防"开仓即爆仓"</li>
     * </ul>
     *
     * <p>
     * 跨 currency 不互通；BUDGET 单成交价由撮合决定，跳过 openLoss 检查。
     */
    private boolean canPlaceMarginOrder(final OrderCommand cmd, final UserProfile userProfile,
        final CoreSymbolSpecification spec, final SymbolPositionRecord position,
        final CoreCurrencySpecification currencySpec) {
        final boolean isBudgetOrder = cmd.orderType == OrderType.FOK_BUDGET || cmd.orderType == OrderType.IOC_BUDGET;

        // ────────────────────────────────────────────────────────────────────
        // ① positionMargin：本仓位含新挂单后的总保证金（openInitMarginSum + 新增敞口的初始保证金）
        // ────────────────────────────────────────────────────────────────────
        // BUDGET 单的 cmd.price 已经是总预算 notional；LIMIT 单 cmd.price 是单价，需 × size 得到 notional。
        final long orderNotional = isBudgetOrder ? cmd.price : Math.multiplyExact(cmd.size, cmd.price);
        // calculateRequiredMarginForOrder 返回 -1 表示新单不扩敞口（纯反向 / 抵消现有 pending），
        // 此时本仓总保证金维持当前值（fall back 到含现有 pending 的 calculateRequiredMarginForFutures）。
        final long newOrderMargin = position.calculateRequiredMarginForOrder(spec, cmd.action, orderNotional);
        final long positionMargin =
            newOrderMargin == -1 ? position.calculateRequiredMarginForFutures(spec) : newOrderMargin;

        // ────────────────────────────────────────────────────────────────────
        // ② crossFreeMargin：同 currency 下的账户级可抵扣（仅 CROSS 仓位参与浮盈抵扣）
        // ────────────────────────────────────────────────────────────────────
        // PnL：只有 CROSS 仓位的浮盈算进 cross 账户资本；ISOLATED 仓位隔离，PnL 不给别的单当资本。
        // 已锁保证金：无论 marginMode 都要从 spendable 里剥离——accounts 未扣，实际这部分已被锁在仓位里。
        // 本仓（posRecord == position）：其 margin 已含在 positionMargin 里，此处只补加自己的浮盈（如属 CROSS）。
        // 其它仓（跨 symbol 或 HEDGE 同 symbol 对侧腿）：CROSS → 加 PnL 减 margin；ISOLATED → 只减 margin。
        // 用对象引用 `==` 判本仓——HEDGE 下同 symbol 有 LONG/SHORT 两条独立 record，key 靠 +− 区分，
        // 但 record.symbol 字段本身无符号，靠对象引用比 symbol 相等更精确。
        // 各仓贡献必须按各自 symbol 的 scale 折算到 currency 再累加——不同 symbol 的 sizePrice 单位
        // (baseScaleK×quoteScaleK) 可不同，裸加后统一折算会串味（少算/多算保证金）。本仓 margin 已在 positionMargin 里。
        long crossFreeMargin = 0L; // currency 精度
        for (final SymbolPositionRecord posRecord : userProfile.positions) {
            if (posRecord == position) {
                if (posRecord.marginMode == MarginMode.CROSS) {
                    crossFreeMargin += CoreArithmeticUtils.sizePriceToCurrencyScale(
                        posRecord.estimatePnl(lastPriceCache.get(posRecord.symbol)), spec, currencySpec);
                }
            } else if (posRecord.currency == spec.quoteCurrency) {
                final CoreSymbolSpecification otherSpec =
                    symbolSpecificationProvider.getSymbolSpecification(posRecord.symbol);
                if (posRecord.marginMode == MarginMode.CROSS) {
                    crossFreeMargin += CoreArithmeticUtils.sizePriceToCurrencyScale(
                        posRecord.estimatePnl(lastPriceCache.get(posRecord.symbol)), otherSpec, currencySpec);
                }
                crossFreeMargin -= CoreArithmeticUtils.sizePriceToCurrencyScale(
                    posRecord.calculateRequiredMarginForFutures(otherSpec), otherSpec, currencySpec);
            }
        }

        // ────────────────────────────────────────────────────────────────────
        // ③ pendingFee：本单成交时按 taker rate 应扣的手续费（这里只做容量判定，R2 才实际扣）
        // ────────────────────────────────────────────────────────────────────
        // BUDGET 走专用函数（跟 pendingHoldBudget 同源），把 cmd.price 当总预算而不是单价。
        final long pendingFee =
            isBudgetOrder ? position.calculatePendingFeeForOrderBudget(spec, cmd.action, cmd.size, cmd.price)
                : position.calculatePendingFeeForOrder(spec, cmd.action, cmd.size, cmd.price);

        // ────────────────────────────────────────────────────────────────────
        // ④ openLoss：开仓瞬间浮亏预留，防"开仓即爆仓"
        // ────────────────────────────────────────────────────────────────────
        // BID 超付（orderPrice > mark）：openLoss = (orderPrice − mark) × openingSize
        // ASK 贱卖（orderPrice < mark）：openLoss = (mark − orderPrice) × openingSize
        // openingSize 只算真正"开新敞口"的手数：ONEWAY 反向单先抵掉 openVolume，剩余 (cmd.size − openVolume)
        // 才是新开对侧的部分；HEDGE 每条腿独立，openingSize = cmd.size 全部计入。
        // 即便 newOrderMargin == -1（净敞口不扩），反向大单的对侧开仓部分仍有立即浮亏风险，须算 openLoss。
        // BUDGET 单成交价由撮合决定（cmd.price 是总预算不是单价），跳过此检查。
        long openLoss = 0L;
        if (!isBudgetOrder) {
            final boolean oppositeToPos = userProfile.positionMode == PositionMode.ONEWAY && position.openVolume > 0
                && ((cmd.action == OrderAction.BID && position.direction == PositionDirection.SHORT)
                    || (cmd.action == OrderAction.ASK && position.direction == PositionDirection.LONG));
            final long openingSize =
                oppositeToPos ? Math.max(0L, Math.subtractExact(cmd.size, position.openVolume)) : cmd.size;
            if (openingSize > 0L) {
                final long markPrice = lastPriceCache.get(cmd.symbol).markPrice;
                final long orderCost = Math.multiplyExact(openingSize, cmd.price);
                final long markCost = Math.multiplyExact(openingSize, markPrice);
                openLoss = cmd.action == OrderAction.BID ? Math.max(0L, Math.subtractExact(orderCost, markCost))
                    : Math.max(0L, Math.subtractExact(markCost, orderCost));
            }
        }

        // ────────────────────────────────────────────────────────────────────
        // ⑤ NSF 比较：账户可支配（accounts − 现货冻结 − 借贷抵押）≥ 需求
        // ────────────────────────────────────────────────────────────────────
        final int currency = position.currency;
        // 可支配 = accounts − 现货冻结 − 借贷抵押（借贷抵押不能顶期货保证金，否则贷款变裸债）
        final long spendable = userProfile.accounts.get(currency) - userProfile.exchangeLocked.get(currency)
            - loanCollateralLocked(userProfile, currency);
        // 本单需求按新单 spec 折算；crossFreeMargin 已是各仓各自 scale 折算后的 currency 值，直接相减
        final long required = CoreArithmeticUtils.sizePriceToCurrencyScale(positionMargin + pendingFee + openLoss, spec,
            currencySpec) - crossFreeMargin;
        return required <= spendable;
    }

    /**
     * 现货下单前置（per-shard）：按 action 锁定 quote(BID) 或 base(ASK) 到 exchangeLocked，accounts 不动。三阶段：
     * <ol>
     *   <li>算 orderLockAmount（BID = notional + taker fee；ASK = size），并 currency-scale 换算；</li>
     *   <li>NSF check（accounts − 现货冻结 − 借贷抵押 + 期货净盈余 ≥ orderLockAmount）；</li>
     *   <li>exchangeLocked 累加 + 发 sendLockEvent。</li>
     * </ol>
     */
    private CommandResultCode placeExchangeOrder(final OrderCommand cmd, final UserProfile userProfile,
        final CoreSymbolSpecification spec) {
        final boolean isBid = cmd.action == OrderAction.BID;
        final int currency = isBid ? spec.quoteCurrency : spec.baseCurrency;
        final long size = cmd.size;
        final CoreCurrencySpecification currencySpec = currencySpecificationProvider.getCurrencySpecification(currency);
        long orderLockAmount;
        if (isBid) {
            if (cmd.orderType == OrderType.FOK_BUDGET || cmd.orderType == OrderType.IOC_BUDGET) {
                if (cmd.reserveBidPrice != cmd.price) {
                    return CommandResultCode.RISK_INVALID_RESERVE_BID_PRICE;
                }
                orderLockAmount = CoreArithmeticUtils.calculateAmountBidTakerFeeForBudget(size, cmd.price, spec);
                if (logDebug)
                    CoreArithmeticUtils.logAmountBidTakerFeeForBudget(orderLockAmount, size, cmd.price, spec);
            } else {
                if (cmd.reserveBidPrice < cmd.price) {
                    return CommandResultCode.RISK_INVALID_RESERVE_BID_PRICE;
                }
                orderLockAmount = CoreArithmeticUtils.calculateAmountBidTakerFee(size, cmd.reserveBidPrice, spec);
                if (logDebug)
                    CoreArithmeticUtils.logAmountBidTakerFee(orderLockAmount, size, cmd.reserveBidPrice, spec);
            }
            orderLockAmount = CoreArithmeticUtils.sizePriceToCurrencyScale(orderLockAmount, spec, currencySpec);
        } else {
            if (CoreArithmeticUtils.isAskPriceTooLow(cmd.price, spec)) {
                return CommandResultCode.RISK_ASK_PRICE_LOWER_THAN_FEE;
            }
            orderLockAmount = CoreArithmeticUtils.calculateAmountAsk(size);
            if (logDebug)
                log.debug("hold sell {}", orderLockAmount);
            orderLockAmount = CoreArithmeticUtils.symbolToCurrencyScale(orderLockAmount, spec, currencySpec);
        }
        long freeFuturesMargin = 0L;
        if (cfgMarginTradingEnabled) {
            freeFuturesMargin = calculateFreeFuturesMargin(userProfile, currency, spec.symbolId);
        }
        final long balance = userProfile.accounts.get(currency);
        final long existingLocked = userProfile.exchangeLocked.get(currency);
        // 借贷抵押必扣：不能被现货挂单锁走，否则贷款变裸债
        final long loanLocked = loanCollateralLocked(userProfile, currency);
        if (logDebug) {
            log.debug("R1 uid={} : orderLockAmount={} accounts[{}]={} exchangeLocked={} loanLocked={} freeFuturesMargin={}",
                userProfile.uid, orderLockAmount, currency, balance, existingLocked, loanLocked, freeFuturesMargin);
        }
        if (balance - existingLocked - loanLocked - orderLockAmount + freeFuturesMargin < 0) {
            return CommandResultCode.RISK_NSF;
        }
        userProfile.exchangeLocked.addToValue(currency, orderLockAmount);
        final long locked = calculateLocked(userProfile, currency);
        this.eventsHelper.sendLockEvent(cmd, spec.symbolId, currency, balance - locked, locked);
        return CommandResultCode.VALID_FOR_MATCHING_ENGINE;
    }

    // ====================================================================================
    // R1 其它分支：CLOSE_POSITION / SETTLE_PNL / 强平 size 收敛 + 共享 NSF 助手
    // ====================================================================================

    /**
     * 合约交割：以 cmd.price 强平所有非空仓位、未实现盈亏入账。逐仓：① 跳过 openVolume==0；
     * ② close + refund extraMargin + remove position；③ 发 PnL settlement 事件。
     */
    private void settlePnl(OrderCommand cmd) {
        final int symbol = cmd.symbol;
        final CoreSymbolSpecification spec = symbolSpecificationProvider.getSymbolSpecification(symbol);
        userProfileService.getUserProfiles()
            .forEachValue(userProfile -> userProfile.processPositionRecord(symbol, position -> {
                if (position.openVolume == 0) {
                    return;
                }
                OrderAction closeAction =
                    position.direction == PositionDirection.LONG ? OrderAction.ASK : OrderAction.BID;
                position.closeCurrentPositionFutures(closeAction, position.openVolume, cmd.price);

                CoreCurrencySpecification currencySpec =
                    currencySpecificationProvider.getCurrencySpecification(position.currency);
                long quoteLocked = calculateLocked(userProfile, position.currency);
                refundExtraMargin(cmd, FundEventsHelper.SYSTEM_TRIGGERED_ORDER_ID, spec, position, userProfile,
                    currencySpec, quoteLocked);
                removePositionRecord(spec, position, userProfile, currencySpec);

                long quoteBalance = userProfile.accounts.get(position.currency);
                eventsHelper.sendPnlSettlementEvent(cmd, FundEventsHelper.SYSTEM_TRIGGERED_ORDER_ID, position,
                    quoteBalance - quoteLocked, quoteLocked);
            }));
    }

    /**
     * 强平 / ADL / IF takeover 等系统命令在 enqueue 到下单时机与实际 R1 处理之间，仓位可能已变；
     * 这里以 R1 时刻的 openVolume 重新收敛 cmd.size，保证 ME 见到的 size 准确。
     */
    private CommandResultCode normalizeCmdPositionSize(final OrderCommand cmd) {
        final UserProfile userProfile = userProfileService.getUserProfile(cmd.uid);
        if (userProfile == null) {
            return CommandResultCode.AUTH_INVALID_USER;
        }
        int positionRecordKey = userProfile.createPositionsKey(cmd.symbol, cmd.action, cmd.command);
        SymbolPositionRecord position = userProfile.positions.get(positionRecordKey);
        if (position == null) {
            return CommandResultCode.SUCCESS;
        }
        // FORCE_LIQUIDATION 用被强平者平仓视角（action 与 position direction 反向）；
        // IF_TAKEOVER / AUTO_DELEVERAGING 用 counterparty 接管视角（action 与 position direction 同向，
        // 参见 LiquidationEngine 的 buildForceCmd / buildIFCmd / buildADLCmd）。
        // 视角不一致——这里不能套 reduce-only 的方向 guard，纯按 openVolume 收敛 size。
        cmd.size = Math.min(cmd.size, position.openVolume);
        return CommandResultCode.VALID_FOR_MATCHING_ENGINE;
    }

    /**
     * 可提/可转出余额（提现 与 内部转账共用的 NSF 口径）：
     * {@code accounts − 现货冻结 − 借贷抵押 (+ 期货净盈余，仅 margin trading 开启)}。
     * 现货冻结 / 借贷抵押必扣（都不能提走 / 转走）。
     */
    long withdrawableBalance(final UserProfile userProfile, final int currency) {
        final long balance = userProfile.accounts.get(currency);
        final long spotLocked = userProfile.exchangeLocked.get(currency);
        final long loanLocked = loanCollateralLocked(userProfile, currency);
        final long freeFuturesMargin = cfgMarginTradingEnabled ? calculateFreeFuturesMargin(userProfile, currency) : 0L;
        return balance - spotLocked - loanLocked + freeFuturesMargin;
    }

    /**
     * 某 currency 上期货的净盈余（currency 精度），用作提现 / 加保证金 NSF 的可用额度补充：
     * 已/未实现盈亏减去各仓所需保证金后的余量（可正可负）。不指定 curPosSymbol，逐仓浮盈一律不计入（保守）。
     */
    long calculateFreeFuturesMargin(final UserProfile userProfile, final int currency) {
        return calculateFreeFuturesMargin(userProfile, currency, -1);
    }

    /**
     * 同上，但允许 {@code curPosSymbol} 对应逐仓的未实现盈亏计入分摊——现货下单场景下，用户在该 symbol 上逐仓仓位的浮盈
     * 可为同 symbol 的新单提供额度。取"计浮盈按初始保证金扣" 与 "不计浮盈按维持保证金扣" 的较小值（更保守）。
     */
    private long calculateFreeFuturesMargin(final UserProfile userProfile, final int currency, final int curPosSymbol) {
        final CoreCurrencySpecification currencySpec = currencySpecificationProvider.getCurrencySpecification(currency);
        long realizedPnl = 0L;
        long unrealizedPnl = 0L;
        long isolatedRequiredMargin = 0L;
        long crossInitialMargin = 0L;
        long crossMaintenanceMargin = 0L;
        for (final SymbolPositionRecord position : userProfile.positions) {
            if (position.currency != currency) {
                continue;
            }
            final CoreSymbolSpecification spec = symbolSpecificationProvider.getSymbolSpecification(position.symbol);
            final LastPriceCacheRecord lastPrice = lastPriceCache.get(position.symbol);
            realizedPnl += CoreArithmeticUtils.sizePriceToCurrencyScale(position.profit, spec, currencySpec);
            if (position.marginMode == MarginMode.CROSS) {
                unrealizedPnl += CoreArithmeticUtils
                    .sizePriceToCurrencyScale(position.estimateUnrealizedProfit(lastPrice), spec, currencySpec);
                final long initialMargin = position.calculateRequiredMarginForFutures(spec);
                crossInitialMargin += CoreArithmeticUtils.sizePriceToCurrencyScale(initialMargin, spec, currencySpec);
                // 维持保证金口径：把已锁的初始保证金换成维持保证金
                final long maintenanceMargin = initialMargin - position.openInitMarginSum
                    + position.calculateMaintenanceMargin(spec, lastPrice);
                crossMaintenanceMargin +=
                    CoreArithmeticUtils.sizePriceToCurrencyScale(maintenanceMargin, spec, currencySpec);
            } else {
                // 逐仓浮盈只能参与自身 symbol 的分摊，且须折算到 currency（与 cross 分支及其它项一致，防 scale 串味）
                if (position.symbol == curPosSymbol) {
                    unrealizedPnl += CoreArithmeticUtils
                        .sizePriceToCurrencyScale(position.estimateUnrealizedProfit(lastPrice), spec, currencySpec);
                }
                isolatedRequiredMargin += calculateLockedMargin(position, spec, currencySpec);
            }
        }
        return Math.min(
            // 计入未实现盈亏：全仓按初始保证金扣
            realizedPnl + unrealizedPnl - crossInitialMargin - isolatedRequiredMargin,
            // 不计未实现盈亏：全仓按维持保证金扣
            realizedPnl - crossMaintenanceMargin - isolatedRequiredMargin);
    }

    private <R extends ReportResult> Optional<R> handleReportQuery(ReportQuery<R> reportQuery) {
        return reportQuery.process(this);
    }

    /**
     * 该 uid 是否归本 shard 处理。用户态按 {@code uid &amp; shardMask} 分片，只有命中的 RiskEngine 才碰这个用户的账户 / 持仓，
     * 其余 shard 对该 uid 的命令 no-op。单 shard 部署（shardMask==0）时恒 true。
     */
    public boolean uidForThisHandler(final long uid) {
        return (shardMask == 0) || ((uid & shardMask) == shardId);
    }

    /**
     * CLOSE_POSITION R1：取已有同方向 position，按 maxClosableSize 收敛 cmd.size，沿用 cmd.leverage / marginMode，
     * pendingHold 占用并发 lockPending。无 position 或 closeSize ≤ 0 直接 SUCCESS（不下单也不报错）。
     */
    private CommandResultCode closePositionRiskCheck(final OrderCommand cmd) {
        final UserProfile userProfile = userProfileService.getUserProfile(cmd.uid);
        if (userProfile == null) {
            cmd.resultCode = CommandResultCode.AUTH_INVALID_USER;
            log.warn("User profile {} not found", cmd.uid);
            return CommandResultCode.AUTH_INVALID_USER;
        }
        final CoreSymbolSpecification spec = symbolSpecificationProvider.getSymbolSpecification(cmd.symbol);
        if (spec == null) {
            log.warn("Symbol {} not found", cmd.symbol);
            return CommandResultCode.INVALID_SYMBOL;
        }
        if (cfgIgnoreRiskProcessing) {
            return CommandResultCode.VALID_FOR_MATCHING_ENGINE;
        }
        if (!SymbolType.isFuturesContract(spec.type)) {
            return CommandResultCode.UNSUPPORTED_SYMBOL_TYPE;
        }
        if (!cfgMarginTradingEnabled) {
            return CommandResultCode.RISK_MARGIN_TRADING_DISABLED;
        }

        int positionRecordKey = userProfile.createPositionsKey(spec.symbolId, cmd.action, cmd.command);
        SymbolPositionRecord position = userProfile.positions.get(positionRecordKey);
        if (position == null) {
            return CommandResultCode.SUCCESS;
        }
        long closeSize = maxClosableSize(position, cmd.action, cmd.size);
        if (closeSize <= 0) {
            return CommandResultCode.SUCCESS;
        }
        cmd.size = closeSize;
        cmd.leverage = position.getLeverage();
        cmd.marginMode = position.marginMode;

        position.pendingHold(cmd.action, cmd.size, cmd.price);
        long totalBalance = userProfile.accounts.get(position.currency);
        long locked = calculateLocked(userProfile, position.currency);
        long free = totalBalance - locked;
        eventsHelper.sendLockPendingEvent(cmd, position, free, locked);

        return CommandResultCode.VALID_FOR_MATCHING_ENGINE;
    }

    /**
     * 可平量 = 只有 position direction 与 action 反向时才有意义。 EMPTY / 同向 action 直接返 0，让上游 no-op，防止把"reduce-only / CLOSE"误用成开新敞口。
     */
    private long maxClosableSize(SymbolPositionRecord pos, OrderAction action, long requestedSize) {
        if (!pos.direction.isOppositeToAction(action)) {
            return 0;
        }
        return Math.min(requestedSize, pos.openVolume);
    }

    /**
     * 撮合事件后置处理分派器（per-shard）：按 spec.type 分发到 spot / 期货分支，末尾更新 markPrice cache。三阶段：
     * <ol>
     *   <li>早 return（无 event / BINARY）；</li>
     *   <li>按 spec.type dispatch（spot: reject/reduce → buy/sell；期货: do-while per-event + finalize + 推 liquidation 状态机）；</li>
     *   <li>更新 markPrice（优先 marketData 首档，否则 fallback 到第一笔 trade）。</li>
     * </ol>
     */
    public boolean handlerRiskRelease(final long seq, final OrderCommand cmd) {
        final int symbol = cmd.symbol;
        final L2MarketData marketData = cmd.marketData;
        MatcherTradeEvent mte = cmd.matcherEvent;
        if (mte == null || mte.eventType == MatcherEventType.BINARY_EVENT) {
            return false;
        }
        if (cmd.command == OrderCommandType.RESET_FEE) {
            do {
                resetFeeProcessor.applyEvent(cmd, mte, null, null);
                mte = mte.nextEvent;
            } while (mte != null);
            return false;
        }
        if (cmd.command == OrderCommandType.INTERNAL_TRANSFER) {
            do {
                internalTransferProcessor.applyEvent(cmd, mte, null, null);
                mte = mte.nextEvent;
            } while (mte != null);
            return false;
        }
        if (cmd.command == OrderCommandType.REPRICE_LOAN_RATES) {
            do {
                loanRatePricingProcessor.applyEvent(cmd, mte, null, null);
                mte = mte.nextEvent;
            } while (mte != null);
            loanService.getFloatingRate().setLastRepriceTs(cmd.timestamp);
            return false;
        }
        final CoreSymbolSpecification spec = symbolSpecificationProvider.getSymbolSpecification(symbol);
        if (spec == null) {
            throw new IllegalStateException("Symbol not found: " + symbol);
        }
        final boolean takerSell = cmd.action == OrderAction.ASK;
        final UserProfile takerUp =
            uidForThisHandler(cmd.uid) ? userProfileService.getUserProfileOrAddSuspended(cmd.uid) : null;

        if (spec.type == SymbolType.CURRENCY_EXCHANGE_PAIR) {
            // REJECT always comes first; REDUCE is always single event
            if (mte.eventType == MatcherEventType.REDUCE || mte.eventType == MatcherEventType.REJECT) {
                if (takerUp != null) {
                    handleMatcherRejectReduceEventExchange(cmd, mte, spec, takerSell, takerUp);
                }
                mte = mte.nextEvent;
            }
            if (mte != null) {
                if (takerSell) {
                    handleMatcherEventsExchangeSell(cmd, mte, spec, takerUp);
                } else {
                    handleMatcherEventsExchangeBuy(cmd, mte, spec, takerUp);
                }
            }
            // Loan 强平：spot 标准结算后钩子，把 quote proceeds 路由到 loan/pool/fees/LIF
            if (takerUp != null && (cmd.command == OrderCommandType.LOAN_FORCE_LIQUIDATE
                || cmd.command == OrderCommandType.LOAN_CROSS_FORCE_LIQUIDATE)) {
                if (cmd.command == OrderCommandType.LOAN_FORCE_LIQUIDATE) {
                    loanCommandDispatcher.postProcessLoanForceLiquidate(cmd, spec, takerUp);
                } else {
                    loanCommandDispatcher.postProcessLoanCrossForceLiquidate(cmd, spec, takerUp);
                }
            }
        } else {
            final SymbolPositionRecord takerSpr = (takerUp != null)
                ? takerUp.positions.get(takerUp.createPositionsKey(symbol, cmd.action, cmd.command)) : null;
            final CoreCurrencySpecification currencySpec =
                currencySpecificationProvider.getCurrencySpecification(spec.quoteCurrency);

            // takerOtherLocked = 全量locked − takerSpr 自身贡献；循环内只需实时算 takerSpr 自身贡献再加回来。
            long takerOtherLocked = 0;
            if (takerUp != null) {
                takerOtherLocked = calculateLocked(takerUp, spec.quoteCurrency);
            }
            if (takerSpr != null) {
                takerOtherLocked -= calculateLockedMargin(takerSpr, spec, currencySpec);
            }

            // R2 per-event：cmd.command 整批不变，把 dispatch 提到 loop 外
            if (cmd.command == OrderCommandType.IF_TAKEOVER) {
                do {
                    ifProcessor.applyEvent(cmd, mte, spec, currencySpec);
                    mte = mte.nextEvent;
                } while (mte != null);
            } else if (cmd.command == OrderCommandType.AUTO_DELEVERAGING) {
                do {
                    adlProcessor.applyEvent(cmd, mte, spec, currencySpec);
                    mte = mte.nextEvent;
                } while (mte != null);
            } else if (cmd.command == OrderCommandType.SETTLE_FUNDINGFEES) {
                do {
                    fundingFeeProcessor.applyEvent(cmd, mte, spec, currencySpec);
                    mte = mte.nextEvent;
                } while (mte != null);
                liquidationEngine.checkPositions(cmd);
            } else {
                do {
                    handleMatcherEventMargin(cmd, mte, spec, cmd.action, takerUp, takerSpr, currencySpec,
                        takerOtherLocked);
                    mte = mte.nextEvent;
                } while (mte != null);
            }

            // R2 finalize：cmd 级清理
            if (cmd.command == OrderCommandType.IF_TAKEOVER) {
                ifProcessor.finalizeForCommand(cmd, takerUp, takerSpr, spec, currencySpec);
            } else if (cmd.command == OrderCommandType.AUTO_DELEVERAGING) {
                adlProcessor.finalizeForCommand(cmd, takerUp, takerSpr, spec, currencySpec);
            } else if (cmd.command == OrderCommandType.FORCE_LIQUIDATION) {
                collectLiquidationFee(cmd, takerUp, takerSpr, spec, currencySpec);
            }
            // 强平类命令推进 liquidation 状态机
            if (takerSpr != null && (cmd.command == OrderCommandType.FORCE_LIQUIDATION
                || cmd.command == OrderCommandType.IF_TAKEOVER || cmd.command == OrderCommandType.AUTO_DELEVERAGING)) {
                liquidationEngine.advanceLiquidation(cmd, takerSpr);
            }
        }

        final LastPriceCacheRecord record = lastPriceCache.getIfAbsentPut(symbol, LastPriceCacheRecord::new);
        final boolean hasBook = marketData != null && marketData.askSize > 0 && marketData.bidSize > 0;
        final boolean spot = spec.type == SymbolType.CURRENCY_EXCHANGE_PAIR;
        long tradePrice = 0L;
        if (spot || !hasBook) {
            MatcherTradeEvent event = cmd.matcherEvent;
            while (event != null && event.eventType != MatcherEventType.TRADE) {
                event = event.nextEvent;
            }
            tradePrice = event == null ? 0L : event.price;
        }
        if (hasBook) {
            record.askPrice = marketData.askPrices[0];
            record.bidPrice = marketData.bidPrices[0];
        } else if (tradePrice > 0) {
            record.askPrice = tradePrice;
            record.bidPrice = tradePrice;
        }
        if (spot) {
            record.applyTradePrice(cmd.timestamp, tradePrice);
        }
        return false;
    }

    // ====================================================================================
    // 共用 utility（R1 + R2 都用）
    // ====================================================================================

    /**
     * 用户在某 currency 上的全量锁定额（缩放到 currency 精度），不变量 free = accounts − locked。四部分累加：
     * <ol>
     *   <li>所有同 currency 的期货持仓占用保证金（含 pending + 潜在 fee）；</li>
     *   <li>现货挂单冻结 exchangeLocked（未成交挂单）；</li>
     *   <li>Isolated 借贷抵押（collateralCurrency == currency 的各 loan record）；</li>
     *   <li>Cross 借贷抵押（账户级多币种池）。</li>
     * </ol>
     * 用于贷款抵押校验、调杠杆、事件上报、撮合结算后的余额校验。注意：提现 / 加保证金 / 现货下单 / 期货下单四处 NSF
     * <b>不</b>直接调本方法（它们自算期货净盈余），而是单独扣减 {@link #loanCollateralLocked} 隔离借贷抵押。
     */
    public long calculateLocked(UserProfile userProfile, int currency) {
        long locked = 0;
        CoreCurrencySpecification currencySpec = currencySpecificationProvider.getCurrencySpecification(currency);
        // ① 期货保证金
        for (SymbolPositionRecord position : userProfile.positions) {
            if (position.currency == currency) {
                CoreSymbolSpecification spec = symbolSpecificationProvider.getSymbolSpecification(position.symbol);
                locked += calculateLockedMargin(position, spec, currencySpec);
            }
        }
        // ② 现货挂单锁定
        locked += userProfile.exchangeLocked.get(currency);
        // ③④ 借贷抵押（Isolated collateralCurrency 匹配的各 loan + Cross 账户级池）
        locked += loanCollateralLocked(userProfile, currency);
        return locked;
    }

    /**
     * 借贷抵押占用（currency 精度）：③ Isolated 中 collateralCurrency==currency 的各 loan 抵押 + ④ Cross 账户级抵押池。
     *
     * <p>抵押虚拟锁定在 accounts，故期货下单 / 逐仓加保证金 / 提现 / 现货下单各 NSF 必须扣减本项，
     * 否则贷款抵押会被当自由资金顶保证金或提走，贷款变裸债、损失由借贷池承担。
     */
    long loanCollateralLocked(UserProfile userProfile, int currency) {
        long locked = 0;
        for (IsolatedLoanRecord loan : userProfile.isolatedLoans) {
            if (loan.collateralCurrency == currency) {
                locked += loan.collateralAmount;
            }
        }
        locked += userProfile.crossLoanCollateral.get(currency);
        return locked;
    }

    /**
     * 单个 position 的期货保证金占用，折算到 currency 记账单位——好让 {@link #calculateLocked} 能把不同 symbol 的占用
     * 累加到同一 currency 上（各 symbol 的撮合内部单位 baseScaleK×quoteScaleK 不同，不折算无法相加）。
     * 占用额取 {@link SymbolPositionRecord#calculateRequiredMarginForFutures}（已成交仓位 + pending 挂单 + 潜在 fee）。
     */
    private long calculateLockedMargin(SymbolPositionRecord position, CoreSymbolSpecification spec,
        CoreCurrencySpecification currencySpec) {
        long required = position.calculateRequiredMarginForFutures(spec);
        return CoreArithmeticUtils.sizePriceToCurrencyScale(required, spec, currencySpec);
    }

    // ====================================================================================
    // R2 主线：现货 handlers（dispatcher 顺序：REJECT/REDUCE → SELL → BUY）
    // ====================================================================================

    /**
     * 撤单 / 拒单事件处理：只涉及单方（active 单的 owner），仅释放 exchangeLocked，accounts 不动。
     * 守恒：accounts 不变 + exchangeLocked 减少 → accountBalances bucket（= accounts − exchangeLocked）自然增加同等额度、
     * exchangeLocked bucket 减少同等额度，全局 sum delta = 0。
     */
    private void handleMatcherRejectReduceEventExchange(final OrderCommand cmd, final MatcherTradeEvent mte,
        final CoreSymbolSpecification spec, final boolean takerSell, final UserProfile takerUp) {
        final int currency = takerSell ? spec.baseCurrency : spec.quoteCurrency;
        final CoreCurrencySpecification currencySpec = currencySpecificationProvider.getCurrencySpecification(currency);
        long release;
        if (takerSell) {
            // ASK：下单时按 base 数量直冻（calculateAmountAsk(size) = size），残量直接退。
            release = CoreArithmeticUtils.symbolToCurrencyScale(CoreArithmeticUtils.calculateAmountAsk(mte.size), spec,
                currencySpec);
        } else {
            // BID：下单时按 quote (notional + taker fee) 冻结，按订单类型决定释放公式。
            long releaseInSp;
            if (cmd.command == OrderCommandType.PLACE_ORDER && cmd.orderType == OrderType.FOK_BUDGET) {
                releaseInSp = CoreArithmeticUtils.calculateAmountBidTakerFeeForBudget(mte.size, mte.price, spec);
            } else if (cmd.orderType == OrderType.IOC_BUDGET && mte.nextEvent == null) {
                // IOC_BUDGET 完全被拒：释放整笔预算锁定。
                releaseInSp = CoreArithmeticUtils.calculateAmountBidTakerFeeForBudget(cmd.size, cmd.price, spec);
            } else if (cmd.orderType == OrderType.IOC_BUDGET) {
                // IOC_BUDGET 部分成交残量：BUY handler 已经释放了整笔预算，这里释放 0。
                releaseInSp = 0L;
            } else {
                releaseInSp = CoreArithmeticUtils.calculateAmountBidTakerFee(mte.size, mte.bidderHoldPrice, spec);
            }
            release = CoreArithmeticUtils.sizePriceToCurrencyScale(releaseInSp, spec, currencySpec);
        }
        takerUp.exchangeLocked.addToValue(currency, -release);
        if (release > 0) {
            long balance = takerUp.accounts.get(currency);
            long locked = calculateLocked(takerUp, currency);
            this.eventsHelper.sendUnLockEvent(cmd, spec.symbolId, currency, balance - locked, locked);
        }
    }

    /**
     * 卖单成交事件处理（per-shard）：taker 付 base 收 quote；同 shard 内的 maker 是买方，付 quote 收 base。两阶段：
     * <ol>
     *   <li>循环内逐事件结算 maker（释放冻结 + 入 base + 扣 quote 实付）；</li>
     *   <li>循环结束后用聚合量结算 taker（释放 base 冻结 + 入 quote = notional − takerFee）+ 平台 fees 入账。</li>
     * </ol>
     */
    private void handleMatcherEventsExchangeSell(final OrderCommand cmd, MatcherTradeEvent mte,
        final CoreSymbolSpecification spec, final UserProfile takerUp) {
        long takerSize = 0L;
        long makerSize = 0L;

        long takerNotional = 0L;
        long makerNotional = 0L;

        final int quoteCurrency = spec.quoteCurrency;
        final CoreCurrencySpecification baseCurrencySpec =
            currencySpecificationProvider.getCurrencySpecification(spec.baseCurrency);
        final CoreCurrencySpecification quoteCurrencySpec =
            currencySpecificationProvider.getCurrencySpecification(spec.quoteCurrency);

        while (mte != null) {
            assert mte.eventType == MatcherEventType.TRADE;

            if (takerUp != null) {
                takerNotional += Math.multiplyExact(mte.size, mte.price);
                takerSize += mte.size;
            }

            if (uidForThisHandler(mte.matchedOrderUid)) {
                final long size = mte.size;
                final UserProfile makerUp = userProfileService.getUserProfileOrAddSuspended(mte.matchedOrderUid);

                // maker 下单时按 taker 费率 + bidderHoldPrice 冻结（保守，不知会以哪边成交）。
                // 这里释放整块冻结，按"实际成交价 + maker 费率"扣实付。
                long holdQuote = CoreArithmeticUtils.calculateAmountBidTakerFee(size, mte.bidderHoldPrice, spec);
                holdQuote = CoreArithmeticUtils.sizePriceToCurrencyScale(holdQuote, spec, quoteCurrencySpec);
                long quoteRefund =
                    CoreArithmeticUtils.calculateAmountBidReleaseCorrMaker(size, mte.bidderHoldPrice, mte.price, spec);
                quoteRefund = CoreArithmeticUtils.sizePriceToCurrencyScale(quoteRefund, spec, quoteCurrencySpec);
                makerUp.exchangeLocked.addToValue(quoteCurrency, -holdQuote);
                // 净 +(refund − hold) = −实付 = −(size·price + maker fee)。
                long quoteBalance = makerUp.accounts.addToValue(quoteCurrency, quoteRefund - holdQuote);

                // calculateAmountAsk(size) = size：ASK 侧不收 fee（fee 走 quote 侧），转手 base 数量即冻结量。
                long baseGained = CoreArithmeticUtils.calculateAmountAsk(size);
                baseGained = CoreArithmeticUtils.symbolToCurrencyScale(baseGained, spec, baseCurrencySpec);
                long baseBalance = makerUp.accounts.addToValue(spec.baseCurrency, baseGained);

                long quoteLocked = calculateLocked(makerUp, quoteCurrency);
                long baseLocked = calculateLocked(makerUp, spec.baseCurrency);
                if (quoteRefund > 0) {
                    this.eventsHelper.sendUnLockEvent(cmd, mte.matchedOrderId, makerUp.uid, spec.symbolId,
                        quoteCurrency, quoteBalance - quoteLocked, quoteLocked);
                }
                this.eventsHelper.sendTransferEvent(cmd, mte.matchedOrderId, makerUp.uid, quoteCurrency, spec.symbolId,
                    quoteBalance - quoteLocked, quoteLocked);
                this.eventsHelper.sendTransferEvent(cmd, mte.matchedOrderId, makerUp.uid, spec.baseCurrency,
                    spec.symbolId, baseBalance - baseLocked, baseLocked);

                makerNotional += Math.multiplyExact(mte.size, mte.price);
                makerSize += size;
            }

            mte = mte.nextEvent;
        }

        // hoist：takerFee 在 taker 结算块和下面 fees 池都要用，避免重复 ceilMulMulDiv。
        final long avgTakerPrice = takerSize > 0 ? takerNotional / takerSize : 0;
        final long takerFee = CoreArithmeticUtils.calculateTakerFee(takerSize, avgTakerPrice, spec);

        if (takerUp != null) {
            // taker 是卖方：释放 base 冻结、实际扣 base；加 quote = notional − takerFee。
            long basePaid = CoreArithmeticUtils.symbolToCurrencyScale(CoreArithmeticUtils.calculateAmountAsk(takerSize),
                spec, baseCurrencySpec);
            takerUp.exchangeLocked.addToValue(spec.baseCurrency, -basePaid);
            long baseBalance = takerUp.accounts.addToValue(spec.baseCurrency, -basePaid);

            long toBeAdded =
                CoreArithmeticUtils.sizePriceToCurrencyScale(takerNotional - takerFee, spec, quoteCurrencySpec);
            long quoteBalance = takerUp.accounts.addToValue(quoteCurrency, toBeAdded);

            long quoteLocked = calculateLocked(takerUp, quoteCurrency);
            long baseLocked = calculateLocked(takerUp, spec.baseCurrency);
            this.eventsHelper.sendTransferEvent(cmd, cmd.orderId, takerUp.uid, quoteCurrency, spec.symbolId,
                quoteBalance - quoteLocked, quoteLocked);
            this.eventsHelper.sendTransferEvent(cmd, cmd.orderId, takerUp.uid, spec.baseCurrency, spec.symbolId,
                baseBalance - baseLocked, baseLocked);
        }

        if (takerSize != 0 || makerSize != 0) {
            // fees 池入账用 avg-price 重算 takerFee+makerFee 后做单次 sizePriceToCurrencyScale，
            // 避免 per-event ceil + 多次 scale 转换累积 dust（与 maker 块的 per-event 截断不对称是有意的：
            // 单笔 dust 沉积在 exchangeLocked，SUSPEND 时 sweep 到 fees，全局守恒）。
            long avgMakerPrice = makerSize > 0 ? makerNotional / makerSize : 0;
            long makerFee = CoreArithmeticUtils.calculateMakerFee(makerSize, avgMakerPrice, spec);

            long toBeAdded = CoreArithmeticUtils.sizePriceToCurrencyScale(takerFee + makerFee, spec, quoteCurrencySpec);
            fees.addToValue(quoteCurrency, toBeAdded);
        }
    }

    /**
     * 买单成交事件处理（per-shard）：taker 付 quote 收 base；同 shard 内的 maker 是卖方，付 base 收 quote。两阶段：
     * <ol>
     *   <li>循环内逐事件结算 maker（释放 base 冻结 + 入 quote − maker fee）；</li>
     *   <li>循环结束后结算 taker（按 bidderHoldPrice 退价差/费差 + 入 base）+ 平台 fees 入账。</li>
     * </ol>
     * bidderHoldPrice 是 taker 下单时的参考冻结价（limit order = 限价、FOK/IOC_BUDGET = reserveBidPrice），通常 ≥ 实际成交价，
     * 差额在结算时退给用户。
     */
    private void handleMatcherEventsExchangeBuy(final OrderCommand cmd, MatcherTradeEvent mte,
        final CoreSymbolSpecification spec, final UserProfile takerUp) {
        long takerSize = 0L;
        long makerSize = 0L;

        long takerNotional = 0L;
        long takerHoldNotional = 0L;
        long makerNotional = 0L;
        final int quoteCurrency = spec.quoteCurrency;
        final CoreCurrencySpecification baseCurrencySpec =
            currencySpecificationProvider.getCurrencySpecification(spec.baseCurrency);
        final CoreCurrencySpecification quoteCurrencySpec =
            currencySpecificationProvider.getCurrencySpecification(spec.quoteCurrency);

        while (mte != null) {
            assert mte.eventType == MatcherEventType.TRADE;

            if (takerUp != null) {
                takerNotional += Math.multiplyExact(mte.size, mte.price);
                takerHoldNotional += Math.multiplyExact(mte.size, mte.bidderHoldPrice);
                takerSize += mte.size;
            }

            if (uidForThisHandler(mte.matchedOrderUid)) {
                final long size = mte.size;
                final UserProfile makerUp = userProfileService.getUserProfileOrAddSuspended(mte.matchedOrderUid);
                // calculateAmountBid(size, price) = size × price：原始成交对价（未扣 maker fee）。
                final long quoteGained = CoreArithmeticUtils.calculateAmountBid(size, mte.price);

                // maker 是卖方，下单时按 base 数量直接冻结（calculateAmountAsk(size) = size），这里全释放并实际扣 base。
                long basePaid = CoreArithmeticUtils.symbolToCurrencyScale(CoreArithmeticUtils.calculateAmountAsk(size),
                    spec, baseCurrencySpec);
                makerUp.exchangeLocked.addToValue(spec.baseCurrency, -basePaid);
                long baseBalance = makerUp.accounts.addToValue(spec.baseCurrency, -basePaid);

                // maker 收到 quote = 成交对价 − maker fee。
                long fee = CoreArithmeticUtils.calculateMakerFee(size, mte.price, spec);
                long toBeAdded =
                    CoreArithmeticUtils.sizePriceToCurrencyScale(quoteGained - fee, spec, quoteCurrencySpec);
                long quoteBalance = makerUp.accounts.addToValue(quoteCurrency, toBeAdded);

                long quoteLocked = calculateLocked(makerUp, quoteCurrency);
                long baseLocked = calculateLocked(makerUp, spec.baseCurrency);
                this.eventsHelper.sendTransferEvent(cmd, mte.matchedOrderId, makerUp.uid, quoteCurrency, spec.symbolId,
                    quoteBalance - quoteLocked, quoteLocked);
                this.eventsHelper.sendTransferEvent(cmd, mte.matchedOrderId, makerUp.uid, spec.baseCurrency,
                    spec.symbolId, baseBalance - baseLocked, baseLocked);

                makerNotional += Math.multiplyExact(mte.size, mte.price);
                makerSize += size;
            }

            mte = mte.nextEvent;
        }

        // hoist：takerFee 在 taker 块和下面 fees 池都要用，避免重复 ceilMulMulDiv。
        final long avgTakerPrice = takerSize > 0 ? takerNotional / takerSize : 0;
        final long takerFee = CoreArithmeticUtils.calculateTakerFee(takerSize, avgTakerPrice, spec);

        if (takerUp != null) {
            long leftover;
            long holdQuote;
            if (cmd.command == OrderCommandType.PLACE_ORDER
                && (cmd.orderType == OrderType.FOK_BUDGET || cmd.orderType == OrderType.IOC_BUDGET)) {
                // FOK_BUDGET/IOC_BUDGET：冻结的是预算上限 heldTotal，未匹配部分 leftover 原样退。
                long heldTotal = CoreArithmeticUtils.calculateAmountBidTakerFeeForBudget(cmd.size, cmd.price, spec);
                leftover = heldTotal - (takerNotional + takerFee);
                takerHoldNotional = takerNotional;
                holdQuote = CoreArithmeticUtils.sizePriceToCurrencyScale(heldTotal, spec, quoteCurrencySpec);
            } else {
                // 普通单：feeHeld 按 bidderHoldPrice 冻、takerFee 按实际成交价收，差额 leftover 退给用户。
                long feeHeld = CoreArithmeticUtils.calculateTakerFee(takerSize, takerHoldNotional / takerSize, spec);
                leftover = feeHeld - takerFee;
                holdQuote =
                    CoreArithmeticUtils.sizePriceToCurrencyScale(takerHoldNotional + feeHeld, spec, quoteCurrencySpec);
            }
            // 价差(holdNotional − notional) + leftover = 应退 quote。
            long quoteRefund = CoreArithmeticUtils
                .sizePriceToCurrencyScale(takerHoldNotional - takerNotional + leftover, spec, quoteCurrencySpec);

            takerUp.exchangeLocked.addToValue(quoteCurrency, -holdQuote);
            // 净 +(refund − hold) = −实付 = −(notional + takerFee)。
            long quoteBalance = takerUp.accounts.addToValue(quoteCurrency, quoteRefund - holdQuote);
            long quoteLocked = calculateLocked(takerUp, quoteCurrency);
            if (quoteRefund > 0) {
                this.eventsHelper.sendUnLockEvent(cmd, spec.symbolId, quoteCurrency, quoteBalance - quoteLocked,
                    quoteLocked);
            }
            long toBeAdded = CoreArithmeticUtils.symbolToCurrencyScale(takerSize, spec, baseCurrencySpec);
            long baseBalance = takerUp.accounts.addToValue(spec.baseCurrency, toBeAdded);

            long baseLocked = calculateLocked(takerUp, spec.baseCurrency);
            this.eventsHelper.sendTransferEvent(cmd, cmd.orderId, takerUp.uid, quoteCurrency, spec.symbolId,
                quoteBalance - quoteLocked, quoteLocked);
            this.eventsHelper.sendTransferEvent(cmd, cmd.orderId, takerUp.uid, spec.baseCurrency, spec.symbolId,
                baseBalance - baseLocked, baseLocked);
        }

        if (takerSize != 0 || makerSize != 0) {
            long avgMakerPrice = makerSize > 0 ? makerNotional / makerSize : 0;
            long makerFee = CoreArithmeticUtils.calculateMakerFee(makerSize, avgMakerPrice, spec);

            long toBeAdded = CoreArithmeticUtils.sizePriceToCurrencyScale(takerFee + makerFee, spec, quoteCurrencySpec);
            fees.addToValue(quoteCurrency, toBeAdded);
        }
    }

    // ====================================================================================
    // R2 主线：期货 handlers + 共用 helpers
    // ====================================================================================

    /**
     * 期货成交 / 撤拒事件处理（per-shard）：保证金从用户账户扣 / 退，fee 入平台 fees 池，profit 在持仓清零时结算回账户。两阶段：
     * <ol>
     *   <li>taker 块（用上游传入的 takerUp/takerSpr + 预算好的 takerOtherLocked）；</li>
     *   <li>maker 块（uid 归本 shard 时，就地查 makerUp/makerSpr 并现算 makerOtherLocked）。</li>
     * </ol>
     * TRADE 三步：释放本笔挂单 pending → 平反向仓 closedSize（收 fee）→ 反手开同向 sizeToOpen（收 fee）；REJECT/REDUCE 仅退 pending
     * （不动账户）。任一分支结束后若 spr.isEmpty 触发清零路径（refund extraMargin + remove + PnL 结算）。
     */
    private void handleMatcherEventMargin(final OrderCommand cmd, final MatcherTradeEvent mte,
        final CoreSymbolSpecification spec, final OrderAction takerAction, final UserProfile takerUp,
        final SymbolPositionRecord takerSpr, final CoreCurrencySpecification currencySpec,
        final long takerOtherLocked) {
        final int quoteCurrency = spec.quoteCurrency;

        if (takerUp != null && takerSpr == null) {
            log.warn(
                "handleMatcherEventMargin skip taker side: takerSpr==null cmd={} uid={} symbol={} eventType={} eventSize={}",
                cmd.command, cmd.uid, cmd.symbol, mte.eventType, mte.size);
        }
        if (takerUp != null && takerSpr != null) {
            final boolean isLiquidation =
                LiquidationService.isLiquidationOrderId(cmd.orderId, takerSpr.symbol, takerSpr.uid);
            final long takerRoutingKey = isLiquidation ? FundEventsHelper.SYSTEM_TRIGGERED_ORDER_ID : cmd.orderId;
            if (mte.eventType == MatcherEventType.TRADE) {
                // 反向 fill 走 close→open 两步：先平已有反向仓（closedSize），剩余手数再反手开同向（sizeToOpen），各自独立收 taker fee。
                long preVolume = takerSpr.openVolume;

                // 本笔成交对应的挂单 pending 先释放；IOC/FOK 等无 pending 的 taker 返 0，跳过事件。
                long pendingReleasedSize = takerSpr.pendingRelease(takerAction, mte.size);
                if (pendingReleasedSize > 0) {
                    long quoteBalance = takerUp.accounts.get(takerSpr.currency);
                    // takerOtherLocked 已扣除 takerSpr 入口贡献，叠加当前 lockedMargin 还原总占用。
                    long quoteLocked = takerOtherLocked + calculateLockedMargin(takerSpr, spec, currencySpec);
                    eventsHelper.sendUnlockPendingEvent(cmd, cmd.orderId, takerRoutingKey, takerSpr,
                        quoteBalance - quoteLocked, quoteLocked);
                }

                // sizeToOpen = 剩余开同向新仓的手数；两者之和 == mte.size。
                final long sizeToOpen = takerSpr.closeCurrentPositionFutures(takerAction, mte.size, mte.price);
                // closedSize = 本次成交里平掉已有反向仓的手数；
                long closedSize = Math.max(0, preVolume - takerSpr.openVolume);

                if (closedSize > 0) {
                    // 强平 fill 也走这条收 taker fee — liquidationFee 由 LiquidationEngine 独立计费，与此路径互不重叠。
                    long closeFee = CoreArithmeticUtils.calculateTakerFee(closedSize, mte.price, spec);
                    closeFee = CoreArithmeticUtils.sizePriceToCurrencyScale(closeFee, spec, currencySpec);
                    long quoteBalance = takerUp.accounts.addToValue(quoteCurrency, -closeFee);
                    fees.addToValue(quoteCurrency, closeFee);
                    long quoteLocked = takerOtherLocked + calculateLockedMargin(takerSpr, spec, currencySpec);
                    eventsHelper.sendClosePositionEvent(cmd, cmd.orderId, takerRoutingKey, isLiquidation, takerSpr,
                        quoteBalance - quoteLocked, quoteLocked);
                }

                if (sizeToOpen > 0) {
                    // openPositionMargin 用 markPrice（不是 mte.price）算初始保证金占用；mte.price 只进 openPriceSum 供后续 PnL。
                    takerSpr.openPositionMargin(takerAction, sizeToOpen, mte.price, spec,
                        lastPriceCache.get(spec.symbolId));

                    long openFee = CoreArithmeticUtils.calculateTakerFee(sizeToOpen, mte.price, spec);
                    openFee = CoreArithmeticUtils.sizePriceToCurrencyScale(openFee, spec, currencySpec);
                    long quoteBalance = takerUp.accounts.addToValue(quoteCurrency, -openFee);
                    fees.addToValue(quoteCurrency, openFee);
                    long quoteLocked = takerOtherLocked + calculateLockedMargin(takerSpr, spec, currencySpec);
                    eventsHelper.sendOpenPositionEvent(cmd, cmd.orderId, takerRoutingKey, takerSpr,
                        quoteBalance - quoteLocked, quoteLocked);
                }
            } else if (mte.eventType == MatcherEventType.REJECT || mte.eventType == MatcherEventType.REDUCE) {
                // 撤/拒：仅退还挂单 pending（不动账户），后续 isEmpty 决定是否清理 position record。
                takerSpr.pendingRelease(takerAction, mte.size);

                long quoteLocked = takerOtherLocked + calculateLockedMargin(takerSpr, spec, currencySpec);
                long quoteBalance = takerUp.accounts.get(takerSpr.currency);
                eventsHelper.sendUnlockPendingEvent(cmd, cmd.orderId, takerRoutingKey, takerSpr,
                    quoteBalance - quoteLocked, quoteLocked);
            }
            if (takerSpr.isEmpty()) {
                // 仓位清零（openVolume + pendingBuy + pendingSell 全 0）触发清算：
                // ① extraMargin 退回账户（定额追加保证金本就属于用户）；
                // ② removePositionRecord 内部把累计 profit 加到账户 + 回收 record 到对象池；
                // ③ 若有 profit 结算，发 PnL 事件让外围对账。
                // 此处 takerSpr 自身 lockedMargin 必为 0，refund 的 locked 参数直接传 takerOtherLocked。
                refundExtraMargin(cmd, cmd.orderId, takerRoutingKey, spec, takerSpr, takerUp, currencySpec,
                    takerOtherLocked);
                // record 回池后字段不可信，先快照 profit。
                long profitToSettle = takerSpr.profit;
                removePositionRecord(spec, takerSpr, takerUp, currencySpec);
                if (profitToSettle != 0) {
                    long quoteLockedAfter = calculateLocked(takerUp, takerSpr.currency);
                    long quoteBalance = takerUp.accounts.get(takerSpr.currency);
                    eventsHelper.sendPnlSettlementEvent(cmd, cmd.orderId, takerRoutingKey, takerSpr,
                        quoteBalance - quoteLockedAfter, quoteLockedAfter);
                }
            }
        }

        if (mte.eventType == MatcherEventType.TRADE && uidForThisHandler(mte.matchedOrderUid)) {
            // maker 是吃单对手方，方向恒与 taker 相反；下方所有 SPR 操作都用 makerAction。
            final OrderAction makerAction = takerAction.opposite();
            UserProfile makerUp = userProfileService.getUserProfileOrAddSuspended(mte.matchedOrderUid);
            SymbolPositionRecord makerSpr = makerUp.getPositionRecordOrThrowEx(
                makerUp.createPositionsKey(spec.symbolId, makerAction, mte.matchedOrderCommandType));
            long preVolume = makerSpr.openVolume;

            // makerOtherLocked = 全量 − makerSpr 自身（其它 position + exchangeLocked）。
            // 跟 takerOtherLocked 不同：taker 的由上游已算好传入，maker 是本块首次发现的用户，得就地一次性算。
            long makerOtherLocked =
                calculateLocked(makerUp, makerSpr.currency) - calculateLockedMargin(makerSpr, spec, currencySpec);

            long pendingReleasedSize = makerSpr.pendingRelease(makerAction, mte.size);
            if (pendingReleasedSize > 0) {
                long quoteLocked = makerOtherLocked + calculateLockedMargin(makerSpr, spec, currencySpec);
                long quoteBalance = makerUp.accounts.get(makerSpr.currency);
                eventsHelper.sendUnlockPendingEvent(cmd, mte.matchedOrderId, makerSpr, quoteBalance - quoteLocked,
                    quoteLocked);
            }

            // closedSize = 本次成交里平掉已有反向仓的手数；sizeToOpen = 剩余开同向新仓的手数；两者之和 == mte.size。
            final long sizeToOpen = makerSpr.closeCurrentPositionFutures(makerAction, mte.size, mte.price);
            long closedSize = Math.max(0, preVolume - makerSpr.openVolume);

            if (closedSize > 0) {
                // Maker side fill 不进入 liquidation 流程 — 强平判别在 taker.cmd.orderId 上，故 isLiquidation 硬编码 false。
                long closeFee = CoreArithmeticUtils.calculateMakerFee(closedSize, mte.price, spec);
                closeFee = CoreArithmeticUtils.sizePriceToCurrencyScale(closeFee, spec, currencySpec);
                long quoteBalance = makerUp.accounts.addToValue(quoteCurrency, -closeFee);
                fees.addToValue(quoteCurrency, closeFee);
                long quoteLocked = makerOtherLocked + calculateLockedMargin(makerSpr, spec, currencySpec);
                eventsHelper.sendClosePositionEvent(cmd, mte.matchedOrderId, false, makerSpr,
                    quoteBalance - quoteLocked, quoteLocked);
            }

            if (sizeToOpen > 0) {
                makerSpr.openPositionMargin(makerAction, sizeToOpen, mte.price, spec,
                    lastPriceCache.get(spec.symbolId));

                long openFee = CoreArithmeticUtils.calculateMakerFee(sizeToOpen, mte.price, spec);
                openFee = CoreArithmeticUtils.sizePriceToCurrencyScale(openFee, spec, currencySpec);
                long quoteBalance = makerUp.accounts.addToValue(quoteCurrency, -openFee);
                fees.addToValue(quoteCurrency, openFee);
                long quoteLocked = makerOtherLocked + calculateLockedMargin(makerSpr, spec, currencySpec);
                eventsHelper.sendOpenPositionEvent(cmd, mte.matchedOrderId, makerSpr, quoteBalance - quoteLocked,
                    quoteLocked);
            }
            if (makerSpr.isEmpty()) {
                // 仓位清零（openVolume + pendingBuy + pendingSell 全 0）触发清算：
                // ① extraMargin 退回账户（定额追加保证金本就属于用户）；
                // ② removePositionRecord 内部把累计 profit 加到账户 + 回收 record 到对象池；
                // ③ 若有 profit 结算，发 PnL 事件让外围对账。
                // 此处 makerSpr 自身 lockedMargin 必为 0，refund 的 locked 参数直接传 makerOtherLocked。
                refundExtraMargin(cmd, mte.matchedOrderId, spec, makerSpr, makerUp, currencySpec, makerOtherLocked);
                // record 回池后字段不可信，先快照 profit。
                long profitToSettle = makerSpr.profit;
                removePositionRecord(spec, makerSpr, makerUp, currencySpec);
                if (profitToSettle != 0) {
                    long quoteLockedAfter = calculateLocked(makerUp, makerSpr.currency);
                    long quoteBalance = makerUp.accounts.get(makerSpr.currency);
                    eventsHelper.sendPnlSettlementEvent(cmd, mte.matchedOrderId, makerSpr,
                        quoteBalance - quoteLockedAfter, quoteLockedAfter);
                }
            }
        }
    }

    /**
     * 强平费收取（per-shard）：按 cmd.matcherEvent 链路里本 handler 实际成交的 taker size 计 liquidationFee，
     * 扣账户、入 liquidation pool、发 event。三阶段：
     * <ol>
     *   <li>null guard / 累计本 handler 的 size 与 size×price（仅 TRADE）；</li>
     *   <li>算 fee → 扣 takerUp 账户 → credit 到 liquidationService；</li>
     *   <li>发 liquidationFee event（携带 free / locked 快照）。</li>
     * </ol>
     */
    private void collectLiquidationFee(final OrderCommand cmd, final UserProfile takerUp,
        final SymbolPositionRecord takerSpr, final CoreSymbolSpecification spec,
        final CoreCurrencySpecification currencySpec) {
        if (takerSpr == null) {
            return;
        }
        long takerSize = 0L;
        long takerSizePrice = 0L;
        MatcherTradeEvent mte = cmd.matcherEvent;
        while (mte != null) {
            if (mte.eventType == MatcherEventType.TRADE) {
                takerSize += mte.size;
                takerSizePrice += Math.multiplyExact(mte.size, mte.price);
            }
            mte = mte.nextEvent;
        }
        if (takerSize == 0) {
            return;
        }
        long notional = CoreArithmeticUtils.calculateLiquidationFee(takerSize, takerSizePrice / takerSize, spec);
        long quoteFee = CoreArithmeticUtils.sizePriceToCurrencyScale(notional, spec, currencySpec);
        takerUp.accounts.addToValue(takerSpr.currency, -quoteFee);
        liquidationService.creditLiquidationFee(takerSpr.symbol, notional);

        long quoteLocked = calculateLocked(takerUp, spec.quoteCurrency);
        long quoteFree = takerUp.accounts.get(spec.quoteCurrency) - quoteLocked;
        eventsHelper.sendLiquidationFeeEvent(cmd, cmd.orderId, FundEventsHelper.SYSTEM_TRIGGERED_ORDER_ID, takerSpr,
            quoteFree, quoteLocked);
    }

    /** 退还补充保证金（eventOrderId 与 routingKey 相同的常见情形）。见下方三参重载。 */
    public void refundExtraMargin(OrderCommand cmd, long orderId, CoreSymbolSpecification spec,
        SymbolPositionRecord record, UserProfile userProfile, CoreCurrencySpecification currencySpec, long locked) {
        refundExtraMargin(cmd, orderId, orderId, spec, record, userProfile, currencySpec, locked);
    }

    /**
     * 持仓清零 / 强平 / 交割时把 position.extraMargin 退还回账户并发 marginRefund 事件。extraMargin 以 sizePriceScale 存储，
     * 退款换算回 currencyScale（与 MARGIN_ADJUSTMENT 存入的逆操作），退完置 0；extraMargin==0 时 no-op。
     */
    public void refundExtraMargin(OrderCommand cmd, long eventOrderId, long routingKey, CoreSymbolSpecification spec,
        SymbolPositionRecord record, UserProfile userProfile, CoreCurrencySpecification currencySpec, long locked) {
        if (record.extraMargin > 0) {
            // extraMargin 以 sizePriceScale (= baseScaleK × quoteScaleK) 存储，
            // 退款回账户时需换算回 currencyScaleK，与 MARGIN_ADJUSTMENT 存入时的逆操作对称。
            // 注意：这里用 sizePriceToCurrencyScale 而非 symbolToCurrencyScale，
            // 因为 symbolToCurrencyScale 期望输入为 quoteScaleK，会差 baseScaleK 倍。
            long refund = CoreArithmeticUtils.sizePriceToCurrencyScale(record.extraMargin, spec, currencySpec);
            long balance = userProfile.accounts.addToValue(record.currency, refund);
            eventsHelper.sendMarginRefundEvent(cmd, eventOrderId, routingKey, record, balance - locked, locked);
            record.extraMargin = 0;
        }
    }

    /**
     * 移除一条持仓记录：先把残留已实现盈亏（profit，sizePrice→currency）结算回账户，再从强平索引摘除、从 positions 移除、
     * 归还对象池。调用前一般已 {@link #refundExtraMargin} 退还补充保证金。
     */
    public void removePositionRecord(CoreSymbolSpecification spec, SymbolPositionRecord record, UserProfile userProfile,
        CoreCurrencySpecification currencySpec) {
        if (record.profit != 0) {
            long profit = CoreArithmeticUtils.sizePriceToCurrencyScale(record.profit, spec, currencySpec);
            userProfile.accounts.addToValue(record.currency, profit);
        }
        liquidationEngine.onPositionClosed(userProfile, record);
        userProfile.positions.removeKey(userProfile.createPositionsKey(record));
        objectsPool.put(ObjectsPool.SYMBOL_POSITION_RECORD, record);
    }

    // ====================================================================================
    // 序列化 / reset / 内部类
    // ====================================================================================

    /** 序列化本 shard 全部风控状态进 raft snapshot。字段顺序必须与 {@link State} 读取顺序严格一致。 */
    @Override
    public void writeMarshallable(BytesOut bytes) {
        bytes.writeInt(shardId).writeLong(shardMask);
        symbolSpecificationProvider.writeMarshallable(bytes);
        currencySpecificationProvider.writeMarshallable(bytes);
        userProfileService.writeMarshallable(bytes);
        liquidationService.writeMarshallable(bytes);
        loanService.writeMarshallable(bytes);
        binaryCommandsProcessor.writeMarshallable(bytes);
        SerializationUtils.marshallIntHashMap(lastPriceCache, bytes);
        SerializationUtils.marshallIntLongHashMap(fees, bytes);
        SerializationUtils.marshallIntLongHashMap(adjustments, bytes);
        SerializationUtils.marshallIntLongHashMap(suspends, bytes);
    }

    /** ApiReset：清空本 shard 全部业务状态（各 service / provider / cache / fee-adjustment-suspend bucket），引擎回到空白。 */
    public void reset() {
        userProfileService.reset();
        liquidationService.reset();
        loanService.reset();
        symbolSpecificationProvider.reset();
        currencySpecificationProvider.reset();
        binaryCommandsProcessor.reset();
        lastPriceCache.clear();
        fees.clear();
        adjustments.clear();
        suspends.clear();
    }

    @AllArgsConstructor
    @Getter
    private static class State {
        private final SymbolSpecificationProvider symbolSpecificationProvider;
        private final CurrencySpecificationProvider currencySpecificationProvider;
        private final UserProfileService userProfileService;
        private final LiquidationService liquidationService;
        private final LoanService loanService;
        private final BinaryCommandsProcessor binaryCommandsProcessor;
        private final IntObjectHashMap<LastPriceCacheRecord> lastPriceCache;
        private final IntLongHashMap fees;
        private final IntLongHashMap adjustments;
        private final IntLongHashMap suspends;
    }
}
