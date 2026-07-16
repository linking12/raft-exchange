package exchange.core2.core.processors.liquidation;

import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.function.ToLongFunction;

import org.eclipse.collections.api.map.primitive.MutableIntObjectMap;
import org.eclipse.collections.api.set.primitive.MutableLongSet;
import org.eclipse.collections.api.tuple.primitive.LongObjectPair;
import org.eclipse.collections.impl.list.mutable.FastList;
import org.eclipse.collections.impl.map.mutable.primitive.IntObjectHashMap;
import org.eclipse.collections.impl.map.mutable.primitive.ObjectLongHashMap;
import org.eclipse.collections.impl.set.mutable.primitive.LongHashSet;
import org.eclipse.collections.impl.tuple.primitive.PrimitiveTuples;

import exchange.core2.core.common.CoreCurrencySpecification;
import exchange.core2.core.common.CoreSymbolSpecification;
import exchange.core2.core.common.FundEvent;
import exchange.core2.core.common.MarginMode;
import exchange.core2.core.common.MatcherEventType;
import exchange.core2.core.common.MatcherTradeEvent;
import exchange.core2.core.common.OrderAction;
import exchange.core2.core.common.OrderType;
import exchange.core2.core.common.PositionDirection;
import exchange.core2.core.common.SymbolPositionRecord;
import exchange.core2.core.common.SymbolType;
import exchange.core2.core.common.UserProfile;
import exchange.core2.core.common.api.ApiAutoDeleveraging;
import exchange.core2.core.common.api.ApiIFTakeOver;
import exchange.core2.core.common.api.ApiLiquidationOrder;
import exchange.core2.core.common.api.ApiSystemLiquidationNotify;
import exchange.core2.core.common.cmd.OrderCommand;
import exchange.core2.core.common.cmd.OrderCommandType;
import exchange.core2.core.common.config.ExchangeConfiguration;
import exchange.core2.core.processors.CurrencySpecificationProvider;
import exchange.core2.core.processors.FundEventsHelper;
import exchange.core2.core.processors.RiskEngine.LastPriceCacheRecord;
import exchange.core2.core.processors.SymbolSpecificationProvider;
import exchange.core2.core.processors.UserProfileService;
import exchange.core2.core.processors.liquidation.LiquidationFlow.LiquidationState;
import exchange.core2.core.processors.loan.LoanLiquidationEngine;
import exchange.core2.core.processors.loan.LoanService;
import exchange.core2.core.utils.CoreArithmeticUtils;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

/**
 * 期货强平引擎，每个 RiskEngine 分片一个。<b>事件驱动、on-lane 检测</b>——强平检查跑在 disruptor 单写者线程的命令 apply 里，只读一致复制态，无跨线程竞态。
 *
 * <p>
 * <b>检测流程</b>：入口 {@link #checkPositions}——{@code cmd.symbol >= 0} 靠 {@link #symbolToUsers} 索引只查该 symbol 的持有者
 * （targeted，价格 / 资金费触发）；{@code cmd.symbol < 0}（{@code LIQUIDATION_SCAN}）全量整扫兜底。判定破产的仓位经父类 {@code submit} 提交
 * {@code FORCE_LIQUIDATION}；强平命令 apply 后由 {@link #advanceLiquidation} 推进 FORCE→IF→ADL 状态机。
 *
 * <p>
 * <b>leader gate</b>：{@link #checkPositions} / {@link #advanceLiquidation} 用父类 {@link #isRunning()} 门控，follower
 * no-op。流程态 {@link LiquidationFlow} 挂在 {@code SymbolPositionRecord.liquidationFlow}，纯内存、不进 snapshot / stateHash——换届后新
 * leader 侧为空，残余仓被当作破产仓重发 FORCE 恢复，正确性靠 R1 {@code normalizeCmdPositionSize} 对 size 的夹取保证。
 *
 * <p>
 * <b>索引维护/重建</b>：{@link #symbolToUsers}（symbol → uid）在开仓 / 平仓 apply 时由所有节点确定性维护（不 gate）、不进 snapshot； 快照恢复经
 * {@link #updateProvider} 重建。
 *
 * <p>
 * <b>loan 集成</b>：{@link #loanLiquidationEngine} 是构造时创建的稳定单例，现货借贷强平子域委托对象；{@link #checkPositions} 末尾委托其
 * {@link LoanLiquidationEngine#checkLoans checkLoans}，{@link #updateProvider} 把同一套 provider 转发给它刷新。
 */
@Slf4j
@Getter
public final class LiquidationEngine extends LiquidationScheduledService {
    // isolated 仓位无跨仓保证金分摊，破产价计算固定传 0
    private static final ToLongFunction<SymbolPositionRecord> NO_CROSS = p -> 0L;
    private final FundEventsHelper eventsHelper;
    private final IntObjectHashMap<MutableLongSet> symbolToUsers; // symbol → 持有者 uid 集合
    private final LoanLiquidationEngine loanLiquidationEngine;
    private SymbolSpecificationProvider symbolSpecificationProvider;
    private CurrencySpecificationProvider currencySpecificationProvider;
    private UserProfileService userProfileService;
    private IntObjectHashMap<LastPriceCacheRecord> lastPriceCache;
    private LoanService loanService;

    public LiquidationEngine(Supplier<FundEvent> eventSupplier, int shardId,
        ExchangeConfiguration exchangeConfiguration) {
        super(Long.parseLong(System.getProperty("raftexchange.liquidation.interval", "300")), TimeUnit.SECONDS,
            exchangeConfiguration.getPerformanceCfg().getLiquidationThreadFactory(), shardId);
        this.eventsHelper = new FundEventsHelper(eventSupplier, shardId);
        this.symbolToUsers = IntObjectHashMap.newMap();
        this.loanLiquidationEngine = new LoanLiquidationEngine(eventsHelper, this::getCommandSubmitter);
    }

    public void updateProvider(SymbolSpecificationProvider symbolSpecProvider,
        CurrencySpecificationProvider currencySpecProvider, UserProfileService userService,
        IntObjectHashMap<LastPriceCacheRecord> lastPriceService, LoanService loanSvc) {
        symbolSpecificationProvider = symbolSpecProvider;
        currencySpecificationProvider = currencySpecProvider;
        userProfileService = userService;
        lastPriceCache = lastPriceService;
        eventsHelper.setSymbolSpecificationProvider(symbolSpecificationProvider);
        eventsHelper.setCurrencySpecificationProvider(currencySpecificationProvider);
        eventsHelper.setUserProfileService(userProfileService);
        eventsHelper.setLastPriceCache(lastPriceCache);
        this.loanService = loanSvc;
        symbolToUsers.clear();
        userProfileService.getUserProfiles().forEachValue(userProfile -> {
            if (userProfile == null) {
                return;
            }
            userProfile.positions.forEachValue(pos -> {
                if (pos == null || pos.openVolume == 0) {
                    return;
                }
                final CoreSymbolSpecification spec = symbolSpecificationProvider.getSymbolSpecification(pos.symbol);
                if (spec != null && SymbolType.isFuturesContract(spec.type)) {
                    onPositionOpened(userProfile, pos);
                }
            });
        });
        loanLiquidationEngine.updateProvider(symbolSpecProvider, currencySpecProvider, userService, lastPriceService,
            loanSvc);
    }

    /**
     * 强平检测入口，命令 apply 时调用（leader-only，父类 {@link #isRunning()} 门控）。{@code cmd.symbol >= 0} 只查该 symbol
     * 的持有者（targeted）；{@code cmd.symbol < 0}（{@code LIQUIDATION_SCAN}）全量整扫兜底。末尾委托
     * {@link LoanLiquidationEngine#checkLoans}，检测现货借贷侧强平。
     */
    public void checkPositions(OrderCommand cmd) {
        if (!isRunning()) {
            return;
        }
        if (cmd.symbol >= 0) {
            final MutableLongSet holders = symbolToUsers.get(cmd.symbol);
            if (holders != null) {
                holders.forEach(uid -> checkUser(userProfileService.getUserProfile(uid), cmd.timestamp));
            }
        } else {
            userProfileService.getUserProfiles().forEachValue(userProfile -> {
                checkUser(userProfile, cmd.timestamp);
            });
        }
        loanLiquidationEngine.checkLoans(cmd);
    }

    /** 开仓 apply：把 uid 登记进 symbol → 持有者索引（所有节点确定性维护，不 gate）。 */
    public void onPositionOpened(UserProfile userProfile, SymbolPositionRecord pos) {
        symbolToUsers.getIfAbsentPut(pos.symbol, LongHashSet::new).add(userProfile.uid);
    }

    /** 平仓 apply：从索引摘除 uid。HEDGE 双向持仓下，仅当该 symbol 已无其它方向仓位时才移除，避免误删仍有敞口的持有者。 */
    public void onPositionClosed(UserProfile userProfile, SymbolPositionRecord pos) {
        final boolean holdsOther = userProfile.positions.anySatisfy(p -> p != pos && p.symbol == pos.symbol);
        if (holdsOther) {
            return;
        }
        final MutableLongSet s = symbolToUsers.get(pos.symbol);
        if (s != null) {
            s.remove(userProfile.uid);
            if (s.isEmpty()) {
                symbolToUsers.remove(pos.symbol);
            }
        }
    }

    /** 逐仓分类：ISOLATED 立即判定；CROSS 按 quote 币种分组，交给 {@link #checkCross} 统一算账户级风险。 */
    private void checkUser(UserProfile userProfile, long ts) {
        if (userProfile == null) {
            return;
        }
        final MutableIntObjectMap<SymbolPositionRecord> positions = userProfile.positions.asUnmodifiable();
        final IntObjectHashMap<List<SymbolPositionRecord>> crossPositionsByCurrency = IntObjectHashMap.newMap();
        positions.forEachValue(position -> {
            if (position == null || position.openVolume == 0) {
                return;
            }
            final CoreSymbolSpecification spec = symbolSpecificationProvider.getSymbolSpecification(position.symbol);
            if (!SymbolType.isFuturesContract(spec.type)) {
                return;
            }
            final LastPriceCacheRecord priceRecord = lastPriceCache.get(position.symbol);
            if (priceRecord == null) {
                log.debug("No price record for symbol={}", position.symbol);
                return;
            }
            if (position.marginMode == MarginMode.ISOLATED) {
                checkIsolated(userProfile, spec, priceRecord, position);
            } else {
                crossPositionsByCurrency.getIfAbsentPut(spec.quoteCurrency, FastList.newList()).add(position);
            }
        });
        checkCross(userProfile, crossPositionsByCurrency);
    }

    private void checkIsolated(UserProfile userProfile, CoreSymbolSpecification spec, LastPriceCacheRecord priceRecord,
        SymbolPositionRecord position) {
        final long profit = position.estimateUnrealizedProfit(priceRecord);
        final long equity = position.openInitMarginSum + profit + position.extraMargin;
        final long maintenanceMargin = position.calculateMaintenanceMargin(spec, priceRecord);
        final long warningThreshold = Math.multiplyExact(maintenanceMargin, 6) / 5; // 1.2× 维持保证金：预警线
        if (equity < maintenanceMargin) {
            final long price = position.calculateBankruptcyPrice(spec, NO_CROSS);
            final long sizeToLiquidate = Math.min(position.openVolume,
                CoreArithmeticUtils.calculateSizeToLiquidate(position, spec, priceRecord));
            if (sizeToLiquidate > 0) {
                startLiquidationFlow(userProfile, position, price, sizeToLiquidate);
            }
        } else if (equity < warningThreshold) {
            sendWarningEvent(userProfile, position, equity, warningThreshold);
        }
    }

    /** 逐 quote 币种判定全仓联合风险：equity 跌破维持保证金则从最危险仓位起逐仓强平至覆盖亏空；跌破预警线则发预警。 */
    private void checkCross(UserProfile userProfile,
        IntObjectHashMap<List<SymbolPositionRecord>> crossPositionsByCurrency) {
        if (crossPositionsByCurrency.isEmpty()) {
            return;
        }
        final ObjectLongHashMap<SymbolPositionRecord> alloc =
            userProfile.crossMarginBaseAllocation(symbolSpecificationProvider::getSymbolSpecification,
                currencySpecificationProvider::getCurrencySpecification, lastPriceCache);
        crossPositionsByCurrency.forEachKeyValue((currency, records) -> {
            final CoreCurrencySpecification currencySpec =
                currencySpecificationProvider.getCurrencySpecification(currency);
            long totalProfit = 0;
            long totalMaintenanceMargin = 0;
            final List<LongObjectPair<SymbolPositionRecord>> riskPairs = FastList.newList(records.size());
            for (SymbolPositionRecord position : records) {
                final CoreSymbolSpecification spec =
                    symbolSpecificationProvider.getSymbolSpecification(position.symbol);
                final LastPriceCacheRecord priceRecord = lastPriceCache.get(position.symbol);
                long maintenance = position.calculateMaintenanceMargin(spec, priceRecord);
                if (maintenance == 0) {
                    continue;
                }
                final long profit =
                    CoreArithmeticUtils.sizePriceToCurrencyScale(position.estimatePnl(priceRecord), spec, currencySpec);
                maintenance = CoreArithmeticUtils.sizePriceToCurrencyScale(maintenance, spec, currencySpec);
                totalProfit += profit;
                totalMaintenanceMargin += maintenance;
                final long risk = Math.multiplyExact(profit - maintenance, 100) / maintenance;
                riskPairs.add(PrimitiveTuples.pair(risk, position));
            }
            final long balance = userProfile.calculateCrossAvailable(currency, currencySpec,
                symbolSpecificationProvider::getSymbolSpecification);
            final long equity = balance + totalProfit;
            final long warningThreshold = Math.multiplyExact(totalMaintenanceMargin, 6) / 5; // 1.2× 维持保证金：预警线
            if (equity >= warningThreshold) {
                return;
            }
            riskPairs.sort(Comparator.comparingLong(LongObjectPair::getOne)); // 风险度升序：最危险的仓位优先强平
            if (equity < totalMaintenanceMargin) {
                forceCrossLiquidation(userProfile, riskPairs, totalMaintenanceMargin - equity, alloc);
            } else {
                sendWarningEvent(userProfile, riskPairs.get(0).getTwo(), equity, warningThreshold);
            }
        });
    }

    /** 按风险度升序（最危险优先）逐仓强平，直至释放保证金覆盖 deficit 或仓位耗尽。 */
    private void forceCrossLiquidation(UserProfile userProfile,
        List<LongObjectPair<SymbolPositionRecord>> positionPairs, long deficit,
        ObjectLongHashMap<SymbolPositionRecord> alloc) {
        long marginReleased = 0;
        for (LongObjectPair<SymbolPositionRecord> pair : positionPairs) {
            if (marginReleased >= deficit)
                break;
            final SymbolPositionRecord position = pair.getTwo();
            final CoreSymbolSpecification spec = symbolSpecificationProvider.getSymbolSpecification(position.symbol);
            final LastPriceCacheRecord priceRecord = lastPriceCache.get(position.symbol);
            final long price = position.calculateBankruptcyPrice(spec, alloc::get);
            final long sizeToLiquidate = Math.min(position.openVolume,
                CoreArithmeticUtils.calculateSizeToLiquidate(position, spec, priceRecord));
            if (sizeToLiquidate > 0) {
                marginReleased +=
                    CoreArithmeticUtils.calculateDeficitAfterLiquidate(sizeToLiquidate, position, spec, priceRecord);
                startLiquidationFlow(userProfile, position, price, sizeToLiquidate);
            }
        }
    }

    /** 提交 FORCE_LIQUIDATION 并发强平预警事件；已有进行中流程（幂等保护）则跳过。 */
    private void startLiquidationFlow(UserProfile userProfile, SymbolPositionRecord position, long price, long size) {
        if (position.liquidationFlow != null) {
            return;
        }
        final long orderId = LiquidationService.generateLiquidationOrderId(position);
        submit(buildForceCmd(position, orderId, price, size), null);
        final FundEvent event = eventsHelper.sendLiquidationAlertEvent(orderId, position);
        submit(ApiSystemLiquidationNotify.builder().fundEvent(event).build(), null);
        log.debug("Liquidated: uid={} symbol={} size={} price={}", userProfile.uid, position.symbol, size, price);
    }

    /** 越预警线通知：走 leader-local ringbuffer、bypass raft 的 best-effort 事件；去重/限流由下游消费方负责。 */
    private void sendWarningEvent(UserProfile userProfile, SymbolPositionRecord position, long equity,
        long warningThreshold) {
        final FundEvent event = eventsHelper.sendMarginAlertEvent(position);
        submit(ApiSystemLiquidationNotify.builder().fundEvent(event).build(), null);
        log.debug("Margin call: uid={} symbol={} equity={} threshold={}", userProfile.uid, position.symbol, equity,
            warningThreshold);
    }

    /**
     * 强平命令 apply 后推进 FORCE→IF→ADL 状态机（leader-only）。flow 为空且命令是 FORCE 时新建流程（含换届后残余仓恢复）； 否则按当前 state 校验命令合法性，防重复 / 错序推进。
     */
    public void advanceLiquidation(OrderCommand cmd, SymbolPositionRecord pos) {
        if (!isRunning()) {
            return;
        }
        final LiquidationFlow flow = pos.liquidationFlow;
        if (flow == null) {
            if (cmd.command != OrderCommandType.FORCE_LIQUIDATION) {
                log.warn("Illegal liquidation cmd={} on null ctx: skip uid={} symbol={}", cmd.command, pos.uid,
                    pos.symbol);
                return;
            }
            pos.liquidationFlow = new LiquidationFlow(cmd.price, cmd.size, cmd.orderId);
        } else {
            final LiquidationState expected = switch (cmd.command) {
                case FORCE_LIQUIDATION -> LiquidationState.LIQUIDATING;
                case IF_TAKEOVER -> LiquidationState.WAIT_IF_EXECUTION;
                case AUTO_DELEVERAGING -> LiquidationState.WAIT_ADL_EXECUTION;
                default -> null;
            };
            if (flow.state != expected) {
                log.warn("Duplicate liquidation cmd={} ctx.state={} expected={} uid={} symbol={}: skip", cmd.command,
                    flow.state, expected, pos.uid, pos.symbol);
                return;
            }
        }
        switch (cmd.command) {
            case FORCE_LIQUIDATION -> onForceApplied(cmd, pos);
            case IF_TAKEOVER -> onIfTakeoverApplied(cmd, pos);
            case AUTO_DELEVERAGING -> pos.liquidationFlow = null;
            default -> {
            }
        }
    }

    /** FORCE 单 apply 回调：完全成交则闭环；REJECT（部分成交剩余）则转 IF 接管。 */
    private void onForceApplied(OrderCommand cmd, SymbolPositionRecord pos) {
        final LiquidationFlow flow = pos.liquidationFlow;
        final MatcherTradeEvent firstEvent = cmd.matcherEvent;
        if (firstEvent.eventType != MatcherEventType.REJECT) {
            pos.liquidationFlow = null;
            return;
        }
        // REJECT 事件携带的剩余量
        flow.size = firstEvent.size;
        flow.state = LiquidationState.WAIT_IF_EXECUTION;
        log.warn("Publish IF takeover: uid={} symbol={} size={} price={}", pos.uid, pos.symbol, flow.size,
            flow.bankruptcyPrice);
        submit(buildIFCmd(pos, flow), null);
    }

    /** IF 单 apply 回调：接管成功则闭环；REJECT 则转 ADL 摊派。 */
    private void onIfTakeoverApplied(OrderCommand cmd, SymbolPositionRecord pos) {
        final LiquidationFlow flow = pos.liquidationFlow;
        if (cmd.matcherEvent.eventType != MatcherEventType.REJECT) {
            pos.liquidationFlow = null;
            return;
        }
        flow.state = LiquidationState.WAIT_ADL_EXECUTION;
        log.warn("Publish ADL: uid={} symbol={} size={} price={}", pos.uid, pos.symbol, flow.size,
            flow.bankruptcyPrice);
        submit(buildADLCmd(pos, flow), null);
    }

    private ApiLiquidationOrder buildForceCmd(SymbolPositionRecord pos, long orderId, long price, long size) {
        return ApiLiquidationOrder.builder().orderType(OrderType.IOC).orderId(orderId).uid(pos.uid).symbol(pos.symbol)
            .price(price).size(size).action(pos.direction == PositionDirection.LONG ? OrderAction.ASK : OrderAction.BID)
            .build();
    }

    private ApiIFTakeOver buildIFCmd(SymbolPositionRecord pos, LiquidationFlow flow) {
        return ApiIFTakeOver.builder().orderId(LiquidationService.generateIFOrderId(flow.originalOrderId)).uid(pos.uid)
            .symbol(pos.symbol).action(pos.direction == PositionDirection.LONG ? OrderAction.BID : OrderAction.ASK)
            .size(flow.size).price(flow.bankruptcyPrice).build();
    }

    private ApiAutoDeleveraging buildADLCmd(SymbolPositionRecord pos, LiquidationFlow flow) {
        return ApiAutoDeleveraging.builder().orderId(LiquidationService.generateADLOrderId(flow.originalOrderId))
            .uid(pos.uid).symbol(pos.symbol)
            .action(pos.direction == PositionDirection.LONG ? OrderAction.BID : OrderAction.ASK).size(flow.size)
            .price(flow.bankruptcyPrice).build();
    }

}
