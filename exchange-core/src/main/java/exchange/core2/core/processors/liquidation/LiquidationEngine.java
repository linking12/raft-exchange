package exchange.core2.core.processors.liquidation;

import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import lombok.Setter;
import org.eclipse.collections.api.list.MutableList;
import org.eclipse.collections.api.map.primitive.MutableIntObjectMap;
import org.eclipse.collections.api.tuple.primitive.LongObjectPair;
import org.eclipse.collections.impl.list.mutable.FastList;
import org.eclipse.collections.impl.map.mutable.primitive.IntObjectHashMap;
import org.eclipse.collections.impl.tuple.primitive.PrimitiveTuples;

import exchange.core2.core.ExchangeApi;
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
import exchange.core2.core.processors.CurrencySpecificationProvider;
import exchange.core2.core.processors.FundEventsHelper;
import exchange.core2.core.processors.RiskEngine.LastPriceCacheRecord;
import exchange.core2.core.processors.SymbolSpecificationProvider;
import exchange.core2.core.processors.UserProfileService;
import exchange.core2.core.processors.liquidation.LiquidationContext.LiquidationState;
import exchange.core2.core.utils.AffinityThreadFactory;
import exchange.core2.core.utils.CoreArithmeticUtils;
import lombok.extern.slf4j.Slf4j;

/**
 * Liquidation Engine for checking user profiles and triggering liquidations.
 */
@Slf4j
public final class LiquidationEngine extends SimpleScheduledService {
    private final int shardId;
    private final FundEventsHelper eventsHelper;

    @Setter
    private ExchangeApi exchangeApi;
    private SymbolSpecificationProvider symbolSpecificationProvider;
    private CurrencySpecificationProvider currencySpecificationProvider;
    private UserProfileService userProfileService;
    private IntObjectHashMap<LastPriceCacheRecord> lastPriceCache;

    public LiquidationEngine(Supplier<FundEvent> eventSupplier, int shardId, int numShards) {
        super(Long.parseLong(System.getProperty("raftexchange.liquidation.interval", "2")), TimeUnit.SECONDS,
            new AffinityThreadFactory(AffinityThreadFactory.ThreadAffinityMode.THREAD_AFFINITY_ENABLE_PER_LOGICAL_CORE, "LiquidationEngine-"));
        this.shardId = shardId;
        this.eventsHelper = new FundEventsHelper(eventSupplier, shardId, numShards);
    }

    public void updateProvider(SymbolSpecificationProvider symbolSpecProvider, CurrencySpecificationProvider currencySpecProvider,
        UserProfileService userService, IntObjectHashMap<LastPriceCacheRecord> lastPriceService) {
        symbolSpecificationProvider = symbolSpecProvider;
        currencySpecificationProvider = currencySpecProvider;
        userProfileService = userService;
        lastPriceCache = lastPriceService;
        eventsHelper.setSymbolSpecificationProvider(symbolSpecificationProvider);
        eventsHelper.setCurrencySpecificationProvider(currencySpecificationProvider);
        eventsHelper.setUserProfileService(userProfileService);
        eventsHelper.setLastPriceCache(lastPriceCache);
    }

    @Override
    protected void runOneIteration() throws Exception {
        log.debug("Checking liquidation for shard {}", shardId);
        try {
            checkLiquidations();
        } catch (Throwable e) {
            log.error("Error during liquidation check for shard {}", shardId, e);
        }
    }

    public void triggerOnce() {
        try {
            log.info("Manual trigger: Checking liquidation for shard {}", shardId);
            checkLiquidations();
        } catch (Throwable e) {
            log.error("Manual trigger failed for shard {}", shardId, e);
        }
    }

    private void checkLiquidations() {
        IntObjectHashMap<MutableList<SymbolPositionRecord>> profitablePositionsBySymbol = IntObjectHashMap.newMap();

        userProfileService.getUserProfiles().forEachValue(userProfile -> {
            if (userProfile == null)
                return;
            // 遍历每个用户的所有持仓
            MutableIntObjectMap<SymbolPositionRecord> positions = userProfile.positions.asUnmodifiable();
            IntObjectHashMap<List<SymbolPositionRecord>> crossPositionsByCurrency = IntObjectHashMap.newMap();
            positions.forEachValue(position -> {
                if (position == null || position.openVolume == 0) {
                    return; // 跳过空仓位
                }
                int symbol = position.symbol;
                CoreSymbolSpecification spec = symbolSpecificationProvider.getSymbolSpecification(symbol);
                if (!SymbolType.isFuturesContract(spec.type)) {
                    return; // 跳过非期货持仓
                }
                // 获取最新价格记录
                LastPriceCacheRecord priceRecord = lastPriceCache.get(symbol);
                if (priceRecord == null) {
                    log.debug("No price record for symbol={}", symbol);
                    return;
                }
                // 逐仓模式下
                if (position.marginMode == MarginMode.ISOLATED) {
                    // 加入盈利仓位集合
                    tryAddProfitablePosition(position, priceRecord, profitablePositionsBySymbol);
                    // 检查强平状态
                    checkLiquidationIsolated(userProfile, spec, priceRecord, position, eventsHelper);
                }
                // 全仓模式下，聚合用户下所有的开仓币种
                else {
                    crossPositionsByCurrency.getIfAbsentPut(spec.quoteCurrency, FastList.newList()).add(position);
                }
            });
            // 检查用户全仓模式下的强平状态
            checkLiquidationCross(userProfile, crossPositionsByCurrency, symbolSpecificationProvider, currencySpecificationProvider,
                    lastPriceCache, eventsHelper, profitablePositionsBySymbol);
        });

        // -------- ADL------------
        userProfileService.setProfitablePositionsBySymbol(profitablePositionsBySymbol);
    }

    private void checkLiquidationIsolated(UserProfile userProfile, CoreSymbolSpecification spec, LastPriceCacheRecord priceRecord,
        SymbolPositionRecord position, FundEventsHelper eventsHelper) {
        // 未实现盈亏，基于标记价格（markPrice），反映持仓的市场价值变化
        long profit = position.estimateUnrealizedProfit(priceRecord);
        // 当前持仓所需的初始保证金
        long initMargin = position.openInitMarginSum;
        // 账户总权益 = 初始保证金 + 未实现盈亏 + 补充保证金
        long equity = initMargin + profit + position.extraMargin;
        // 维持保证金，基于持仓量和规格定义的最低资金要求，若低于此值需强平
        long maintenanceMargin = position.calculateMaintenanceMargin(spec, priceRecord);
        // 预警阈值，设为维持保证金的 1.2 倍，用于提前提醒用户追加资金
        long warningThreshold = maintenanceMargin * 6 / 5;
        // 权益低于维持保证金，触发强平以保护系统免受进一步亏损
        if (equity < maintenanceMargin) {
            long price = calculateLiquidationPrice(position, priceRecord);
            // 计算强平数量
            long x = CoreArithmeticUtils.calculateSizeToLiquidate(position, spec, priceRecord);
            long sizeToLiquidate = Math.min(position.openVolume, x);
            if (sizeToLiquidate > 0) {
                startLiquidationFlow(userProfile, position, price, sizeToLiquidate, eventsHelper);
            }
        }
        // 权益低于预警阈值但高于维持保证金，发送 Margin Call 提醒用户追加资金
        else if (equity < warningThreshold) {
            sendWarningEvent(userProfile, position, eventsHelper, equity, warningThreshold);
        }
    }

    private void checkLiquidationCross(UserProfile userProfile, IntObjectHashMap<List<SymbolPositionRecord>> crossPositionsByCurrency,
                                       SymbolSpecificationProvider symbolSpecificationProvider, CurrencySpecificationProvider currencySpecificationProvider,
                                       MutableIntObjectMap<LastPriceCacheRecord> lastPriceCache, FundEventsHelper eventsHelper,
                                       IntObjectHashMap<MutableList<SymbolPositionRecord>> profitablePositionsBySymbol) {
        crossPositionsByCurrency.forEachKeyValue((currency, records) -> {
            // 计算总盈亏和维持保证金
            long totalProfit = 0;
            long totalMaintenanceMargin = 0;
            List<LongObjectPair<SymbolPositionRecord>> riskPairs = FastList.newList(records.size());
            for (SymbolPositionRecord position : records) {
                CoreSymbolSpecification spec = symbolSpecificationProvider.getSymbolSpecification(position.symbol);
                CoreCurrencySpecification currencySpec = currencySpecificationProvider.getCurrencySpecification(currency);
                LastPriceCacheRecord priceRecord = lastPriceCache.get(position.symbol);
                long profit = position.estimatePnl(priceRecord);
                long maintenance = position.calculateMaintenanceMargin(spec, priceRecord);
                profit = CoreArithmeticUtils.sizePriceToCurrencyScale(profit, spec, currencySpec);
                maintenance = CoreArithmeticUtils.sizePriceToCurrencyScale(maintenance, spec, currencySpec);
                totalProfit += profit;
                totalMaintenanceMargin += maintenance;
                // 每个仓位的风险系数：risk = (profit - maintenance) / maintenance
                long risk = (profit - maintenance) * 100 / maintenance;
                riskPairs.add(PrimitiveTuples.pair(risk, position));
            }
            long balance = userProfile.accounts.get(currency);
            long equity = balance + totalProfit;
            long warningThreshold = totalMaintenanceMargin * 6 / 5;
            // ===== ADL（cross） gating：必须足够安全 =====
            if (equity >= warningThreshold && totalProfit > 0) {
                long factor = (equity - totalMaintenanceMargin) * 100 / totalMaintenanceMargin;
                factor = Math.max(0, Math.min(factor, 100));
                for (SymbolPositionRecord position : records) {
                    LastPriceCacheRecord priceRecord = lastPriceCache.get(position.symbol);
                    if (tryAddProfitablePosition(position, priceRecord, profitablePositionsBySymbol)) {
                        position.adlEligibility = factor;
                    }
                }
            }
            // 强平检查
            if (equity >= warningThreshold)
                return;
            riskPairs.sort(Comparator.comparingLong(LongObjectPair::getOne));// 升序排序 risk值越小风险越大
            if (equity < totalMaintenanceMargin) {
                long deficit = totalMaintenanceMargin - equity;
                forceCrossLiquidation(userProfile, riskPairs, deficit, eventsHelper, symbolSpecificationProvider, lastPriceCache);
            } else {
                sendWarningEvent(userProfile, riskPairs.get(0).getTwo(), eventsHelper, equity, warningThreshold);
            }
        });
    }

    private boolean tryAddProfitablePosition(SymbolPositionRecord position, LastPriceCacheRecord priceRecord,
                                             IntObjectHashMap<MutableList<SymbolPositionRecord>> profitablePositionsBySymbol) {
        if (priceRecord == null) {
            return false;
        }
        long unrealizedPnl = position.estimateUnrealizedProfit(priceRecord);
        if (unrealizedPnl <= 0) {
            return false;
        }
        profitablePositionsBySymbol.getIfAbsentPut(position.symbol, FastList::new).add(position);
        return true;
    }

    private void sendWarningEvent(UserProfile userProfile, SymbolPositionRecord position, FundEventsHelper eventsHelper, long equity, long warningThreshold) {
        FundEvent event = eventsHelper.sendMarginAlertEvent(position);
        exchangeApi.submitCommand(ApiSystemLiquidationNotify.builder().fundEvent(event).build());
        log.debug("Margin call: uid={} symbol={} equity={} threshold={}", userProfile.uid, position.symbol, equity, warningThreshold);
    }

    private void forceCrossLiquidation(UserProfile userProfile, List<LongObjectPair<SymbolPositionRecord>> positionPairs, long deficit,
        FundEventsHelper eventsHelper, SymbolSpecificationProvider symbolSpecificationProvider, MutableIntObjectMap<LastPriceCacheRecord> lastPriceCache) {
        long marginReleased = 0;
        for (LongObjectPair<SymbolPositionRecord> pair : positionPairs) {
            if (marginReleased >= deficit)
                break;
            SymbolPositionRecord position = pair.getTwo();
            CoreSymbolSpecification spec = symbolSpecificationProvider.getSymbolSpecification(position.symbol);
            LastPriceCacheRecord priceRecord = lastPriceCache.get(position.symbol);
            long price = calculateLiquidationPrice(position, priceRecord);
            long sizeToLiquidate = CoreArithmeticUtils.calculateSizeToLiquidate(position, spec, priceRecord);
            sizeToLiquidate = Math.min(sizeToLiquidate, position.openVolume);
            if (sizeToLiquidate > 0) {
                // 假设能成交，先行更新释放量
                long estimatedReleasedMargin = CoreArithmeticUtils.calculateDeficitAfterLiquidate(sizeToLiquidate, position, spec, priceRecord);
                marginReleased += estimatedReleasedMargin;
                // 提交强平单
                startLiquidationFlow(userProfile, position, price, sizeToLiquidate, eventsHelper);
            }
        }
    }

    private long calculateLiquidationPrice(SymbolPositionRecord position, LastPriceCacheRecord priceRecord) {
        // 强平价格：多头用买价（bidPrice），空头用卖价（askPrice），反映市场可执行价格
        long price = position.direction == PositionDirection.LONG ? priceRecord.bidPrice : priceRecord.askPrice;
        // 若市场无流动性（价格为 0 或 MAX_VALUE），回退到平均开仓价作为兜底
        if (price == 0 || price == Long.MAX_VALUE) {
            price = position.openVolume > 0 ? CoreArithmeticUtils.ceilDivide(position.openPriceSum, position.openVolume) : priceRecord.markPrice;
            if (price == 0)
                price = 1; // 防止除零，默认最小价格
            log.debug("Fallback to average open price={} for symbol={}", price, position.symbol);
        }
        return price;
    }

    private void startLiquidationFlow(UserProfile userProfile, SymbolPositionRecord position, long price, long size, FundEventsHelper eventsHelper) {
        // 初始化强平上下文（流程启动点）
        LiquidationContext ctx = position.liquidationCtx;
        if (ctx == null || ctx.state == LiquidationState.CLOSED) {
            position.liquidationCtx = new LiquidationContext(price, size);
        } else {
            // 已在强平流程中，避免重复启动
            return;
        }
        // 确定强平方向：多头卖出（ASK）清算，空头买入（BID）清算
        OrderAction action = position.direction == PositionDirection.LONG ? OrderAction.ASK : OrderAction.BID;
        long orderId = generateLiquidationOrderId(position.symbol, position.uid); // IOC的单子不插入orderBook的
        exchangeApi.submitCommand(ApiLiquidationOrder.builder().orderType(OrderType.IOC) // 目前是限价IOC，不过price是按市场价算的，只要深度足够就能成交
            .orderId(orderId).uid(position.uid).symbol(position.symbol).price(price).size(size).action(action).build());
        // 生成强平事件，记录用户、仓位和交易细节，便于审计和通知
        FundEvent event = eventsHelper.sendLiquidationAlertEvent(orderId, position);
        exchangeApi.submitCommand(ApiSystemLiquidationNotify.builder().fundEvent(event).build());
        log.debug("Liquidated: uid={} symbol={} size={} price={}", userProfile.uid, position.symbol, size, price);
    }

    private static long generateLiquidationOrderId(int symbol, long uid) {
        long uidHash = (uid * 31 + 17) & 0xFFFFF; // 取前 20 bit
        long tsPart = (System.currentTimeMillis() / 1000) & 0xFFF; // 取后12bit，支持4096秒 ≈ 1.13小时内不重复
        return ((long)symbol << 32) | (uidHash << 12) | tsPart;
    }

    public static boolean isLiquidationOrderId(long orderId, int symbol, long uid) {
        long expectedSymbol = (orderId >>> 32); // 高 32 位
        if (expectedSymbol != symbol)
            return false;

        long expectedUidHash = (uid * 31 + 17) & 0xFFFFF; // 计算 uidHash
        long actualUidHash = (orderId >>> 12) & 0xFFFFF; // 中间 20 位
        return expectedUidHash == actualUidHash;
    }


    // ======== 强平状态机 ===========
    public void nextLiquidationState(OrderCommand cmd, SymbolPositionRecord pos) {
        switch (cmd.command) {
            case FORCE_LIQUIDATION -> onMarketDone(cmd, pos);

            case IF_TAKEOVER -> onIFTakeoverDone(cmd, pos);

            case AUTO_DELEVERAGING -> onADLDone(pos);
        }
    }


    private void onMarketDone(OrderCommand cmd, SymbolPositionRecord pos) {
        LiquidationContext ctx = pos.liquidationCtx;
        assert ctx.state == LiquidationState.LIQUIDATING;
        /**
         * 如果第一个事件是reject，说明没有完全成交。
         *
         * @see exchange.core2.core.orderbook.OrderBookDirectImpl#newOrderMatchIoc
         */
        MatcherTradeEvent firstEvent = cmd.matcherEvent;
        // 市场完全吃完，直接关闭
        if (firstEvent.eventType != MatcherEventType.REJECT) {
            ctx.state = LiquidationState.CLOSED;
            return;
        }
        // 还有剩余
        ctx.size = firstEvent.size;
        if (shouldTryIF()) {
            ctx.state = LiquidationState.WAIT_IF_EXECUTION;
            ApiIFTakeOver ifCmd = ApiIFTakeOver.builder()
                    .orderId(IFService.generateIFOrderId(cmd.orderId))
                    .uid(pos.uid).symbol(pos.symbol)
                    .action(pos.direction == PositionDirection.LONG ? OrderAction.BID : OrderAction.ASK)
                    .size(ctx.size).price(ctx.price).build();
            log.warn("ifCmd={}", ifCmd);
            exchangeApi.submitCommand(ifCmd);
        } else {
            submitADL(pos, ctx);
        }
    }

    private boolean shouldTryIF() {
        return false; // todo
    }

    private void onIFTakeoverDone(OrderCommand cmd, SymbolPositionRecord pos) {
        LiquidationContext ctx = pos.liquidationCtx;
        assert ctx.state == LiquidationState.WAIT_IF_EXECUTION;
        MatcherTradeEvent firstEvent = cmd.matcherEvent;
        // IF接仓，直接关闭
        if (firstEvent.eventType != MatcherEventType.REJECT) {
            ctx.state = LiquidationState.CLOSED;
            return;
        }
        submitADL(pos, ctx);
    }

    private void submitADL(SymbolPositionRecord pos, LiquidationContext ctx) {
        ctx.state = LiquidationState.WAIT_ADL_EXECUTION;
        ApiAutoDeleveraging adlCmd = ApiAutoDeleveraging.builder()
                .orderId(ADLUserPositionHelper.generateADLOrderId(pos))
                .uid(pos.uid).symbol(pos.symbol)
                .action(pos.direction == PositionDirection.LONG ? OrderAction.BID : OrderAction.ASK)
                .size(ctx.size).price(ctx.price).build();
        log.warn("adlCmd={}", adlCmd);
        exchangeApi.submitCommand(adlCmd);
    }

    private void onADLDone(SymbolPositionRecord pos) {
        LiquidationContext ctx = pos.liquidationCtx;
        assert ctx.state == LiquidationState.WAIT_ADL_EXECUTION;
        ctx.state = LiquidationState.CLOSED;
    }
}