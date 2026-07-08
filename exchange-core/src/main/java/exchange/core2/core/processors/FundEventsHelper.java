package exchange.core2.core.processors;

import static exchange.core2.core.ExchangeCore.EVENTS_POOLING;

import java.util.function.Supplier;

import exchange.core2.core.common.CoreCurrencySpecification;
import exchange.core2.core.common.CoreSymbolSpecification;
import exchange.core2.core.common.FundEvent;
import exchange.core2.core.common.FundEvent.FundEventType;
import exchange.core2.core.common.MarginMode;
import exchange.core2.core.common.SymbolPositionRecord;
import exchange.core2.core.common.UserProfile;
import exchange.core2.core.common.cmd.OrderCommand;
import exchange.core2.core.processors.RiskEngine.LastPriceCacheRecord;
import exchange.core2.core.utils.CoreArithmeticUtils;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.collections.impl.map.mutable.primitive.IntObjectHashMap;

/**
 * 引擎内 FundEvent 的构建 + 路由 helper。每个 RiskEngine shard 持有一个实例。
 *
 * <h3>事件出口</h3>
 * 事件挂到 {@link OrderCommand#takerFundEvents} 或 {@link OrderCommand#makerFundEventsByShard}[shardId]；
 * 下游 kafka publisher 从这两个桶拼 FundEventReport 发 topic。
 *
 * <h3>桶选择规则（{@link #addFundEvent} 决策）</h3>
 * 按 routing key 判：{@code routingKey == cmd.orderId} → taker 桶；否则 → 本 shard 的 maker 桶。
 * 调用方应遵循：
 * <ol>
 *   <li>真实 user-initiated single-shard order → taker 桶</li>
 *   <li>真实 maker counterparty（orderbook 撮合）→ maker 桶</li>
 *   <li>没有真实 taker/maker（系统触发）→ maker 桶</li>
 *   <li>多 shard 写同一 cmd 有 race → maker 桶（每 shard 独立 slot 无冲突）</li>
 * </ol>
 *
 * <h3>event.orderId vs routingKey 解耦</h3>
 * 部分 sender 有 5-arg 重载允许 {@code (eventOrderId, routingKey)} 不同——
 * 典型 IF/ADL counterparty：event.orderId 用 {@code cmd.orderId}（合成追踪 id 给下游关联），
 * routingKey 用 {@link #SYSTEM_TRIGGERED_ORDER_ID} 强制落 maker 桶避 race。
 * 4-arg 重载 delegate 到 5-arg、两值相等（用于 taker/maker 真实 order 场景）。
 */
@Slf4j
@RequiredArgsConstructor
public class FundEventsHelper {
    /** 系统触发、无外部 order 时使用；用作 routing key 时由于 {@code != cmd.orderId} 必落 maker 桶。 */
    public static final long SYSTEM_TRIGGERED_ORDER_ID = -1L;

    private final Supplier<FundEvent> eventSupplier;
    private final int riskEngineShardId;
    @Setter
    private SymbolSpecificationProvider symbolSpecificationProvider;
    @Setter
    private CurrencySpecificationProvider currencySpecificationProvider;
    @Setter
    private UserProfileService userProfileService;
    @Setter
    private IntObjectHashMap<LastPriceCacheRecord> lastPriceCache;

    private FundEvent eventsChainHead;

    /** 现货事件骨架（账户余额维度，无 symbol/position 信息）。 */
    private FundEvent buildSpotEvent(long orderId, long uid, FundEventType type, int currency, long free, long locked) {
        CoreCurrencySpecification currencySpec = currencySpecificationProvider.getCurrencySpecification(currency);
        FundEvent event = newFundEvent();
        event.orderId = orderId;
        event.uid = uid;
        event.eventType = type;
        event.currency = currency;
        event.currencyScaleK = currencySpec.getCurrencyScaleK();
        event.free = free;
        event.locked = locked;
        return event;
    }

    /** 期货事件骨架（仅 symbol 维度，无 SymbolPositionRecord 可传时用——典型 funding fee 仓位已关场景）。 */
    private FundEvent buildBaseFuturesEvent(long orderId, FundEventType type, long uid, int symbol, int currency,
        long free, long locked) {
        CoreSymbolSpecification spec = symbolSpecificationProvider.getSymbolSpecification(symbol);
        CoreCurrencySpecification currencySpec = currencySpecificationProvider.getCurrencySpecification(currency);
        LastPriceCacheRecord priceRecord = lastPriceCache.get(symbol);
        FundEvent event = newFundEvent();
        event.orderId = orderId;
        event.eventType = type;
        event.uid = uid;
        event.currency = currency;
        event.currencyScaleK = currencySpec.getCurrencyScaleK();
        event.symbol = symbol;
        event.baseScaleK = spec.baseScaleK;
        event.quoteScaleK = spec.quoteScaleK;
        event.markPrice = priceRecord != null ? priceRecord.markPrice : 0;
        event.free = free;
        event.locked = locked;
        return event;
    }

    /** 期货事件骨架（携 SymbolPositionRecord 全字段 + 通过 {@link #calc} 算出的 marginRatio / liquidationPrice 等估算字段）。 */
    private FundEvent buildFuturesEvent(long orderId, FundEventType type, SymbolPositionRecord position, long free,
        long locked) {
        CoreSymbolSpecification spec = symbolSpecificationProvider.getSymbolSpecification(position.symbol);
        CoreCurrencySpecification currencySpec =
            currencySpecificationProvider.getCurrencySpecification(position.currency);
        LastPriceCacheRecord priceRecord = lastPriceCache.get(position.symbol);
        FundEvent event = newFundEvent();
        event.orderId = orderId;
        event.eventType = type;
        event.uid = position.uid;
        event.currency = position.currency;
        event.currencyScaleK = currencySpec.getCurrencyScaleK();
        event.symbol = position.symbol;
        event.baseScaleK = spec.baseScaleK;
        event.quoteScaleK = spec.quoteScaleK;
        event.direction = position.direction;
        event.openVolume = position.openVolume;
        event.openInitMarginSum = position.openInitMarginSum;
        event.openPriceSum = position.openPriceSum;
        event.profit = position.profit;
        event.pendingSellSize = position.pendingSellSize;
        event.pendingBuySize = position.pendingBuySize;
        event.pendingSellAvgPrice = position.pendingSellAvgPrice;
        event.pendingBuyAvgPrice = position.pendingBuyAvgPrice;
        event.leverage = position.getLeverage();
        event.marginMode = position.marginMode;
        event.extraMargin = position.extraMargin;
        calc(event, position, spec, currencySpec, priceRecord);
        event.markPrice = priceRecord != null ? priceRecord.markPrice : 0;
        event.free = free;
        event.locked = locked;
        return event;
    }

    /**
     * 填 event 的 unrealizedProfit / liquidationPrice / marginRatio 估算字段（下发客户端/风控）。
     * CROSS 模式按"全仓 PnL + 全仓维持保证金"汇总；ISOLATE 模式只看本仓位。
     */
    private void calc(FundEvent event, SymbolPositionRecord position, CoreSymbolSpecification spec,
        CoreCurrencySpecification currencySpec, LastPriceCacheRecord priceRecord) {
        if (position.openVolume == 0) {
            return; // 无持仓，不计算
        }
        long balance = 0;
        long totalPnl = 0;
        long totalMM = 0;
        long totalMargin = 0;
        if (position.marginMode == MarginMode.CROSS) {
            UserProfile userProfile = userProfileService.getUserProfile(position.uid);
            for (SymbolPositionRecord pos : userProfile.positions) {
                if (pos.marginMode == MarginMode.CROSS && pos.currency == position.currency) {
                    // 每个 pos 用自己 symbol 的 spec 和 priceRecord —— 不同 symbol 有不同 MMR bracket 表和 markPrice，
                    // 沿用外层 position 的 spec/priceRecord 会导致 totalPnl / totalMM 在多币种账户下系统性错串。
                    CoreSymbolSpecification posSpec = symbolSpecificationProvider.getSymbolSpecification(pos.symbol);
                    LastPriceCacheRecord posPriceRecord = lastPriceCache.get(pos.symbol);
                    if (posSpec == null || posPriceRecord == null) {
                        continue;
                    }
                    totalPnl += pos.estimatePnl(posPriceRecord);
                    totalMM += pos.calculateMaintenanceMargin(posSpec, posPriceRecord);
                }
            }
            // cross 真实可用 = accounts − exchangeLocked − Σ 逐仓虚拟锁定，用 CoreArithmeticUtils 共用 helper
            // 与 LiquidationEngine#checkLiquidationCross 和 SingleUserReportQuery 口径完全对齐，避免下发的
            // liquidationPrice / marginRatioScaleK 偏乐观、跟真实强平触发点脱节。
            balance = userProfile.calculateCrossAvailable(position.currency, currencySpec,
                symbolSpecificationProvider::getSymbolSpecification);
            balance = CoreArithmeticUtils.currencyToSizePriceScale(balance, spec, currencySpec);
            totalMargin = balance + totalPnl;
        } else {
            totalMargin =
                position.openInitMarginSum + position.estimateUnrealizedProfit(priceRecord) + position.extraMargin;
        }
        event.unrealizedProfit = position.estimateUnrealizedProfit(priceRecord);
        event.liquidationPrice = position.estimateLiquidationPrice(spec, priceRecord, balance, totalPnl, totalMM);
        event.marginRatioScaleK = position.estimateMarginRatioScaleK(spec, priceRecord, totalMargin);
    }

    /* ============================== 现货 ============================== */

    /** 用户存款。user 主动 cmd → taker 桶。 */
    public FundEvent sendDepositEvent(OrderCommand cmd, int currency, long free, long locked) {
        FundEvent event = buildSpotEvent(cmd.orderId, cmd.uid, FundEventType.DEPOSIT, currency, free, locked);
        addFundEvent(cmd, cmd.orderId, event);
        return event;
    }

    /** 用户提现。user 主动 cmd → taker 桶。 */
    public FundEvent sendWithdrawEvent(OrderCommand cmd, int currency, long free, long locked) {
        FundEvent event = buildSpotEvent(cmd.orderId, cmd.uid, FundEventType.WITHDRAW, currency, free, locked);
        addFundEvent(cmd, cmd.orderId, event);
        return event;
    }

    /** 现货下单冻结。user 主动 cmd → taker 桶。 */
    public FundEvent sendLockEvent(OrderCommand cmd, int symbol, int currency, long free, long locked) {
        FundEvent event = buildSpotEvent(cmd.orderId, cmd.uid, FundEventType.LOCKED, currency, free, locked);
        event.symbol = symbol;
        addFundEvent(cmd, cmd.orderId, event);
        return event;
    }

    /** 现货撤单/取消解冻——taker 路径（自己的挂单） → taker 桶。 */
    public FundEvent sendUnLockEvent(OrderCommand cmd, int symbol, int currency, long free, long locked) {
        FundEvent event = buildSpotEvent(cmd.orderId, cmd.uid, FundEventType.UNLOCKED, currency, free, locked);
        event.symbol = symbol;
        addFundEvent(cmd, cmd.orderId, event);
        return event;
    }

    /** 现货解冻——maker 路径（撮合到对手方时给 maker 发解冻）。orderId 传 mte.matchedOrderId，落 maker 桶。 */
    public FundEvent sendUnLockEvent(OrderCommand cmd, long orderId, long uid, int symbol, int currency, long free,
        long locked) {
        FundEvent event = buildSpotEvent(orderId, uid, FundEventType.UNLOCKED, currency, free, locked);
        event.symbol = symbol;
        addFundEvent(cmd, orderId, event);
        return event;
    }

    /** 现货成交资金转移。taker 调用方传 cmd.orderId，maker 调用方传 mte.matchedOrderId。 */
    public FundEvent sendTransferEvent(OrderCommand cmd, long orderId, long uid, int currency, int symbol, long free,
        long locked) {
        FundEvent event = buildSpotEvent(orderId, uid, FundEventType.TRANSFER, currency, free, locked);
        event.symbol = symbol;
        addFundEvent(cmd, orderId, event);
        return event;
    }

    /* ============================== 期货 ============================== */

    /** 期货下单挂单（pending lock）。user 主动 cmd → taker 桶；direction=empty 表示首次开仓还无仓位。 */
    public FundEvent sendLockPendingEvent(OrderCommand cmd, SymbolPositionRecord position, long free, long locked) {
        FundEvent event = buildFuturesEvent(cmd.orderId, FundEventType.LOCK_PENDING, position, free, locked);
        addFundEvent(cmd, cmd.orderId, event);
        return event;
    }

    /** 期货 pending 释放（撤单/成交后）。4-arg 形式 routing 等于 eventOrderId（taker 真实 order 场景）。 */
    public FundEvent sendUnlockPendingEvent(OrderCommand cmd, long orderId, SymbolPositionRecord position, long free,
        long locked) {
        return sendUnlockPendingEvent(cmd, orderId, orderId, position, free, locked);
    }

    /** 期货 pending 释放（解耦 routing）。FORCE 走该重载时 routingKey=SYSTEM_TRIGGERED 落 maker 桶。 */
    public FundEvent sendUnlockPendingEvent(OrderCommand cmd, long eventOrderId, long routingKey,
        SymbolPositionRecord position, long free, long locked) {
        FundEvent event = buildFuturesEvent(eventOrderId, FundEventType.UNLOCK_PENDING, position, free, locked);
        addFundEvent(cmd, routingKey, event);
        return event;
    }

    /** 期货平仓。{@code isLiquidation=true} 时 eventType=LIQUIDATION_CLOSE 否则 CLOSE_POSITION。4-arg 形式 routing 同步。 */
    public FundEvent sendClosePositionEvent(OrderCommand cmd, long orderId, boolean isLiquidation,
        SymbolPositionRecord position, long free, long locked) {
        return sendClosePositionEvent(cmd, orderId, orderId, isLiquidation, position, free, locked);
    }

    /** 期货平仓（解耦 routing）。FORCE 调用方走该重载落 maker 桶。 */
    public FundEvent sendClosePositionEvent(OrderCommand cmd, long eventOrderId, long routingKey, boolean isLiquidation,
        SymbolPositionRecord position, long free, long locked) {
        FundEvent event = buildFuturesEvent(eventOrderId,
            isLiquidation ? FundEventType.LIQUIDATION_CLOSE : FundEventType.CLOSE_POSITION, position, free, locked);
        addFundEvent(cmd, routingKey, event);
        return event;
    }

    /** 期货开仓（成交后实际 open）。 */
    public FundEvent sendOpenPositionEvent(OrderCommand cmd, long orderId, SymbolPositionRecord position, long free,
        long locked) {
        return sendOpenPositionEvent(cmd, orderId, orderId, position, free, locked);
    }

    /** 期货开仓（解耦 routing）。 */
    public FundEvent sendOpenPositionEvent(OrderCommand cmd, long eventOrderId, long routingKey,
        SymbolPositionRecord position, long free, long locked) {
        FundEvent event = buildFuturesEvent(eventOrderId, FundEventType.OPEN_POSITION, position, free, locked);
        addFundEvent(cmd, routingKey, event);
        return event;
    }

    /** 强平 taker fee 收取（FORCE 触发）。 */
    public FundEvent sendLiquidationFeeEvent(OrderCommand cmd, long orderId, SymbolPositionRecord position, long free,
        long locked) {
        return sendLiquidationFeeEvent(cmd, orderId, orderId, position, free, locked);
    }

    /** 强平 taker fee 收取（解耦 routing）。FORCE 单 shard 但系统触发，调用方传 routingKey=SYSTEM_TRIGGERED 落 maker 桶。 */
    public FundEvent sendLiquidationFeeEvent(OrderCommand cmd, long eventOrderId, long routingKey,
        SymbolPositionRecord position, long free, long locked) {
        FundEvent event = buildFuturesEvent(eventOrderId, FundEventType.LIQUIDATION_FEE, position, free, locked);
        addFundEvent(cmd, routingKey, event);
        return event;
    }

    /**
     * 维持保证金告警（仓位接近强平阈值）。<b>不挂桶</b>——由 LiquidationEngine 定期 scan 触发，
     * 调用方拿返回的 event 封装到独立 SYSTEM_LIQUIDATION_NOTIFY cmd 走 publishUntracked。
     */
    public FundEvent sendMarginAlertEvent(SymbolPositionRecord position) {
        return buildFuturesEvent(SYSTEM_TRIGGERED_ORDER_ID, FundEventType.MARGIN_ALERT, position, 0, 0);
    }

    /**
     * 强平流程开始告警。<b>不挂桶</b>——同 {@link #sendMarginAlertEvent} 模式独立 NOTIFY cmd；
     * orderId 携带 {@code LiquidationService.generateLiquidationOrderId} 合成 id，
     * 给下游做"alert → close → fee"全链路关联键。
     */
    public FundEvent sendLiquidationAlertEvent(long orderId, SymbolPositionRecord position) {
        FundEvent event = buildFuturesEvent(orderId, FundEventType.LIQUIDATION_ALERT, position, 0, 0);
        return event;
    }

    /**
     * Isolated loan 预警事件（LTV ≥ marginCall 阈值）。<b>不挂桶</b>——由 LoanLiquidationEngine
     * 定期 scan 触发（per-loanId 5min 节流），返回 event 让 caller 封装 ApiSystemLiquidationNotify 走 publishUntracked。
     */
    public FundEvent sendLoanIsolatedMarginCallEvent(long uid, long loanId, int loanCcy, long ltvBps) {
        FundEvent event = newFundEvent();
        event.orderId = SYSTEM_TRIGGERED_ORDER_ID;
        event.eventType = FundEventType.LOAN_MARGIN_CALL;
        event.uid = uid;
        event.currency = loanCcy;
        event.symbol = (int)loanId;   // borrow field：Isolated 场景 loanId 承载在 symbol
        event.free = ltvBps;          // borrow field：LTV(bps) 承载在 free
        return event;
    }

    /**
     * Cross 账户级 loan 预警（LTV ≥ marginCall 阈值）。<b>不挂桶</b>——per-uid 5min 节流。
     * loanId=0（账户级，非单笔）。
     */
    public FundEvent sendLoanCrossMarginCallEvent(long uid, long ltvBps) {
        FundEvent event = newFundEvent();
        event.orderId = SYSTEM_TRIGGERED_ORDER_ID;
        event.eventType = FundEventType.LOAN_MARGIN_CALL;
        event.uid = uid;
        event.symbol = 0;             // Cross 账户级：symbol=0 区分于 Isolated 单笔
        event.free = ltvBps;
        return event;
    }

    /** 用户手动调整保证金（增/减 extraMargin）。user 主动 cmd → taker 桶。 */
    public FundEvent sendMarginAdjustmentEvent(OrderCommand cmd, SymbolPositionRecord position, long free,
        long locked) {
        FundEvent event = buildFuturesEvent(cmd.orderId, FundEventType.MARGIN_ADJUST, position, free, locked);
        addFundEvent(cmd, cmd.orderId, event);
        return event;
    }

    /** extraMargin 退款（仓位清零时把追加保证金还回账户）。 */
    public FundEvent sendMarginRefundEvent(OrderCommand cmd, long orderId, SymbolPositionRecord position, long free,
        long locked) {
        return sendMarginRefundEvent(cmd, orderId, orderId, position, free, locked);
    }

    /** extraMargin 退款（解耦 routing）。IF/ADL counterparty + FORCE 用该重载落 maker 桶。 */
    public FundEvent sendMarginRefundEvent(OrderCommand cmd, long eventOrderId, long routingKey,
        SymbolPositionRecord position, long free, long locked) {
        FundEvent event = buildFuturesEvent(eventOrderId, FundEventType.MARGIN_REFUND, position, free, locked);
        addFundEvent(cmd, routingKey, event);
        return event;
    }

    /** 永续合约资金费率结算（批量收/付）。系统触发、无 user order → 直接 SYSTEM_TRIGGERED 双值入 maker 桶。 */
    public FundEvent sendFundingFeeEvent(OrderCommand cmd, SymbolPositionRecord position, long free, long locked) {
        FundEvent event =
            buildFuturesEvent(SYSTEM_TRIGGERED_ORDER_ID, FundEventType.FUNDINGFEE_SETTLEMENT, position, free, locked);
        addFundEvent(cmd, SYSTEM_TRIGGERED_ORDER_ID, event);
        return event;
    }

    /** 资金费率结算——仓位已关场景（无 SymbolPositionRecord 可传，走 base futures event）。 */
    public FundEvent sendFundingFeeEventForClosedPosition(OrderCommand cmd, long uid, int symbol, int currency,
        long free, long locked) {
        FundEvent event = buildBaseFuturesEvent(SYSTEM_TRIGGERED_ORDER_ID, FundEventType.FUNDINGFEE_SETTLEMENT, uid,
            symbol, currency, free, locked);
        addFundEvent(cmd, SYSTEM_TRIGGERED_ORDER_ID, event);
        return event;
    }

    /** PnL 结算（仓位清零时触发）。 */
    public FundEvent sendPnlSettlementEvent(OrderCommand cmd, long orderId, SymbolPositionRecord position, long free,
        long locked) {
        return sendPnlSettlementEvent(cmd, orderId, orderId, position, free, locked);
    }

    /** PnL 结算（解耦 routing）。IF/ADL 多 shard counterparty + FORCE 用该重载落 maker 桶。 */
    public FundEvent sendPnlSettlementEvent(OrderCommand cmd, long eventOrderId, long routingKey,
        SymbolPositionRecord position, long free, long locked) {
        FundEvent event = buildFuturesEvent(eventOrderId, FundEventType.PNL_SETTLEMENT, position, free, locked);
        addFundEvent(cmd, routingKey, event);
        return event;
    }

    /**
     * IF 接管关 counterparty 仓位。event.orderId 用 cmd.orderId（合成 IF id 给下游追踪），
     * routing=SYSTEM_TRIGGERED 强制 maker 桶（多 shard counterparty 写避 race）。
     */
    public FundEvent sendIFClosePositionEvent(OrderCommand cmd, SymbolPositionRecord position, long free, long locked) {
        FundEvent event = buildFuturesEvent(cmd.orderId, FundEventType.IF_POSITION_CLOSE, position, free, locked);
        addFundEvent(cmd, SYSTEM_TRIGGERED_ORDER_ID, event);
        return event;
    }

    /**
     * ADL 关 counterparty 仓位。{@code cmd.uid == position.uid} 时（破产用户自己的仓位）发 ADL_ORIGIN_CLOSE，
     * 否则发 ADL_POSITION_CLOSE 给被 ADL 抽中的盈利 counterparty。routing 同 {@link #sendIFClosePositionEvent}。
     */
    public FundEvent sendADLClosePositionEvent(OrderCommand cmd, SymbolPositionRecord position, long free,
        long locked) {
        boolean isOrigin = cmd.uid == position.uid;
        FundEvent event = buildFuturesEvent(cmd.orderId,
            isOrigin ? FundEventType.ADL_ORIGIN_CLOSE : FundEventType.ADL_POSITION_CLOSE, position, free, locked);
        addFundEvent(cmd, SYSTEM_TRIGGERED_ORDER_ID, event);
        return event;
    }

    /** 重置费（管理员场景）。无 user order → 双值 SYSTEM_TRIGGERED 入 maker 桶。 */
    public FundEvent sendResetFeeEvent(OrderCommand cmd, int currency, long amount) {
        FundEvent event = buildSpotEvent(SYSTEM_TRIGGERED_ORDER_ID, 0, FundEventType.RESET_FEE, currency, amount, 0);
        addFundEvent(cmd, SYSTEM_TRIGGERED_ORDER_ID, event);
        return event;
    }

    // ================================================================
    // 现货借贷 loan 事件（详见 loan.md §9.5）
    // ================================================================

    /** loan 事件骨架（跟现货 buildSpotEvent 结构类似，无 symbol/position 信息；currency == 0 时不查 currencySpec）。 */
    private FundEvent buildLoanEvent(long orderId, long uid, int currency, FundEventType type, byte loanMode,
        long loanAmount, long loanExtra) {
        FundEvent event = newFundEvent();
        event.orderId = orderId;
        event.uid = uid;
        event.eventType = type;
        event.currency = currency;
        event.currencyScaleK = currency == 0 ? 0
            : currencySpecificationProvider.getCurrencySpecification(currency).getCurrencyScaleK();
        event.loanMode = loanMode;
        event.loanAmount = loanAmount;
        event.loanExtra = loanExtra;
        return event;
    }

    /**
     * loan 预警事件（Cross 账户级或 Isolated 单笔）。<b>不挂桶</b>——由 LiquidationEngine scanner lane 触发，
     * 调用方拿返回的 event 封装到独立 SYSTEM_LIQUIDATION_NOTIFY cmd 走 publishUntracked（跟 {@link #sendMarginAlertEvent} 同款）。
     *
     * @param uid           用户 uid
     * @param loanMode      0 = Isolated，1 = Cross
     * @param loanId        Isolated 时 = 具体 loanId；Cross MARGIN_CALL（账户级）时传 0
     * @param loanCcy       Isolated 时 = loan.loanCcy；Cross 时传 0（账户级无特定 currency）
     * @param ltvBps        当前 LTV（bps）
     * @param thresholdBps  触发预警的阈值（bps）
     */
    public FundEvent sendLoanMarginCallEvent(long uid, byte loanMode, long loanId, int loanCcy, long ltvBps,
        long thresholdBps) {
        return buildLoanEvent(loanId, uid, loanCcy, FundEventType.LOAN_MARGIN_CALL, loanMode, ltvBps, thresholdBps);
    }

    /**
     * loan 利息结算事件。<b>挂 taker 桶</b>（在 LOAN_REPAY / force-sell 等 cmd apply 里触发），
     * 让下游把利息从 fees 桶里拆出来作利息收入统计。
     *
     * @param loanMode        0 = Isolated，1 = Cross
     * @param loanId          具体 loanId
     * @param interestAmount  本次结算的利息量（currencyScale，正值）
     * @param loanCcy         利息计价 currency（= loan.loanCcy）
     */
    public FundEvent sendLoanInterestSettleEvent(OrderCommand cmd, long uid, byte loanMode, long loanId,
        long interestAmount, int loanCcy) {
        FundEvent event = buildLoanEvent(loanId, uid, loanCcy, FundEventType.LOAN_INTEREST_SETTLE, loanMode,
            interestAmount, 0);
        addFundEvent(cmd, cmd.orderId, event);
        return event;
    }

    /**
     * loan 坏账事件。<b>挂 taker 桶</b>（在 force-sell underwater 分支触发），给运营做审计回溯。
     * badDebt bucket 本身是权威真值（进 raft snapshot），本事件用于还原每笔来源。
     *
     * @param loanMode        0 = Isolated，1 = Cross
     * @param loanId          具体 loanId
     * @param loanCcy         坏账 currency（= loan.loanCcy）
     * @param badDebtAmount   本次坏账合计 principal + interest（currencyScale，正值）
     */
    public FundEvent sendLoanBadDebtEvent(OrderCommand cmd, long uid, byte loanMode, long loanId, int loanCcy,
        long badDebtAmount) {
        FundEvent event = buildLoanEvent(loanId, uid, loanCcy, FundEventType.LOAN_BAD_DEBT_INCURRED, loanMode,
            badDebtAmount, 0);
        addFundEvent(cmd, cmd.orderId, event);
        return event;
    }

    /**
     * 桶决策：{@code orderId == cmd.orderId} → cmd.takerFundEvents（单链表追加）；
     * 否则 → cmd.makerFundEventsByShard[本 shard]。后者 per-shard slot 由 OrderCommand ctor 预分配，
     * 多 shard 并发写各自 slot 无冲突；taker 桶则要求调用方保证单 shard 写。
     */
    private void addFundEvent(OrderCommand cmd, long orderId, FundEvent event) {
        if (orderId == cmd.orderId) {
            if (cmd.takerFundEvents == null) {
                cmd.takerFundEvents = event;
            } else {
                FundEvent current = cmd.takerFundEvents;
                while (current.nextEvent != null) {
                    current = current.nextEvent;
                }
                current.nextEvent = event;
            }
        } else {
            FundEvent head = cmd.makerFundEventsByShard[riskEngineShardId];
            if (head == null) {
                cmd.makerFundEventsByShard[riskEngineShardId] = event;
            } else {
                FundEvent current = head;
                while (current.nextEvent != null) {
                    current = current.nextEvent;
                }
                current.nextEvent = event;
            }
        }
    }

    /** FundEvent 对象池借出（EVENTS_POOLING=true 时走链头）；reset() 会断开 nextEvent 防止串链。 */
    private FundEvent newFundEvent() {
        if (EVENTS_POOLING) {
            if (eventsChainHead == null) {
                eventsChainHead = eventSupplier.get();
            }
            final FundEvent event = eventsChainHead;
            eventsChainHead = eventsChainHead.nextEvent;
            event.reset();
            return event;
        } else {
            return new FundEvent();
        }
    }
}
