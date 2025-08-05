package exchange.core2.core;

import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import org.eclipse.collections.api.map.primitive.MutableIntObjectMap;
import org.eclipse.collections.api.map.primitive.MutableLongObjectMap;
import org.eclipse.collections.api.tuple.primitive.DoubleObjectPair;
import org.eclipse.collections.impl.list.mutable.FastList;
import org.eclipse.collections.impl.map.mutable.primitive.IntObjectHashMap;
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
import exchange.core2.core.common.api.ApiLiquidationOrder;
import exchange.core2.core.common.api.ApiSystemLiquidationNotify;
import exchange.core2.core.common.cmd.OrderCommand;
import exchange.core2.core.processors.CurrencySpecificationProvider;
import exchange.core2.core.processors.FundEventsHelper;
import exchange.core2.core.processors.RiskEngine;
import exchange.core2.core.processors.SymbolSpecificationProvider;
import exchange.core2.core.processors.RiskEngine.LastPriceCacheRecord;
import exchange.core2.core.utils.CoreArithmeticUtils;
import lombok.extern.slf4j.Slf4j;

/**
 * Liquidation scanner for checking user profiles and triggering liquidations.
 */
@Slf4j
public final class LiquidationScanner {

    private final ExchangeApi api;
    private final Collection<RiskEngine> riskEngines;
    private final Map<Integer, FundEventsHelper> fundEventsHelpers;
    private final ScheduledExecutorService scheduler;
    private final long scanIntervalSec = Long.parseLong(System.getProperty("raftexchange.liquidation.interval", "2"));

    public LiquidationScanner(ExchangeApi api, Collection<RiskEngine> riskEngines, int riskEnginesNum) {
        this.api = api;
        this.riskEngines = riskEngines;
        this.fundEventsHelpers =
            riskEngines.stream().collect(Collectors.toMap(RiskEngine::getShardId, r -> {
                FundEventsHelper eventsHelper = new FundEventsHelper(() -> r.getSharedPool().getFundEventChain(), r.getShardId(), riskEnginesNum);
                eventsHelper.setSymbolSpecificationProvider(r.getSymbolSpecificationProvider());
                eventsHelper.setCurrencySpecificationProvider(r.getCurrencySpecificationProvider());
                eventsHelper.setUserProfileService(r.getUserProfileService());
                eventsHelper.setLastPriceCache(r.getLastPriceCache());
                return eventsHelper;
            }));
        this.scheduler = Executors.newScheduledThreadPool(riskEngines.size(), r -> new Thread(r, "LiquidationScanner"));
    }

    public void start() {
        for (RiskEngine riskEngine : riskEngines) {
            scheduler.scheduleWithFixedDelay(() -> {
                log.debug("Checking liquidation for shard {}", riskEngine.getShardId());
                try {
                    checkLiquidations(riskEngine);
                } catch (Throwable e) {
                    log.error("Error during liquidation check for shard {}", riskEngine.getShardId(), e);
                }
            }, scanIntervalSec, scanIntervalSec, TimeUnit.SECONDS);
        }
    }

    public void stop(long timeout, TimeUnit timeUnit) {
        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(timeout, timeUnit)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
        }
    }

    public void triggerOnce() {
        for (RiskEngine riskEngine : riskEngines) {
            try {
                log.info("Manual trigger: Checking liquidation for shard {}", riskEngine.getShardId());
                checkLiquidations(riskEngine);
            } catch (Throwable e) {
                log.error("Manual trigger failed for shard {}", riskEngine.getShardId(), e);
            }
        }
    }

    private void checkLiquidations(RiskEngine riskEngine) {
        SymbolSpecificationProvider symbolSpecificationProvider = riskEngine.getSymbolSpecificationProvider();
        CurrencySpecificationProvider currencySpecificationProvider = riskEngine.getCurrencySpecificationProvider();
        MutableLongObjectMap<UserProfile> userProfiles = riskEngine.getUserProfileService().getUserProfiles().asUnmodifiable();
        MutableIntObjectMap<LastPriceCacheRecord> lastPriceCache = riskEngine.getLastPriceCache().asUnmodifiable();
        FundEventsHelper eventsHelper = fundEventsHelpers.get(riskEngine.getShardId());
        userProfiles.forEachValue(userProfile -> {
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
                // 逐仓模式下，直接检查强平状态
                if (position.marginMode == MarginMode.ISOLATED) {
                    checkLiquidationIsolated(userProfile, spec, priceRecord, position, eventsHelper);
                }
                // 全仓模式下，聚合用户下所有的开仓币种
                else {
                    crossPositionsByCurrency.getIfAbsentPut(spec.quoteCurrency, FastList.newList()).add(position);
                }
            });
            // 检查用户全仓模式下的强平状态
            checkLiquidationCross(userProfile, crossPositionsByCurrency, symbolSpecificationProvider, currencySpecificationProvider, lastPriceCache, eventsHelper);
        });
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
                executeLiquidationOrder(userProfile, position, spec, price, sizeToLiquidate, eventsHelper);
            }
        }
        // 权益低于预警阈值但高于维持保证金，发送 Margin Call 提醒用户追加资金
        else if (equity < warningThreshold) {
            sendWarningEvent(userProfile, position, eventsHelper, equity, warningThreshold);
        }
    }

    private void checkLiquidationCross(UserProfile userProfile, IntObjectHashMap<List<SymbolPositionRecord>> crossPositionsByCurrency,
                                       SymbolSpecificationProvider symbolSpecificationProvider, CurrencySpecificationProvider currencySpecificationProvider,
                                       MutableIntObjectMap<LastPriceCacheRecord> lastPriceCache, FundEventsHelper eventsHelper) {
        crossPositionsByCurrency.forEachKeyValue((currency, records) -> {
            // 计算总盈亏和维持保证金
            long totalProfit = 0;
            long totalMaintenanceMargin = 0;
            List<DoubleObjectPair<SymbolPositionRecord>> riskPairs = FastList.newList(records.size());
            for (SymbolPositionRecord position : records) {
                CoreSymbolSpecification spec = symbolSpecificationProvider.getSymbolSpecification(position.symbol);
                CoreCurrencySpecification currencySpec = currencySpecificationProvider.getCurrencySpecification(currency);
                LastPriceCacheRecord priceRecord = lastPriceCache.get(position.symbol);
                long profit = position.estimateProfit(priceRecord);
                long maintenance = position.calculateMaintenanceMargin(spec, priceRecord);
                profit = CoreArithmeticUtils.sizePriceToCurrencyScale(profit, spec, currencySpec);
                maintenance = CoreArithmeticUtils.sizePriceToCurrencyScale(maintenance, spec, currencySpec);
                totalProfit += profit;
                totalMaintenanceMargin += maintenance;
                // 每个仓位的风险系数：risk = (profit - maintenance) / maintenance
                double risk = (profit - maintenance) * 1.0 / maintenance;
                riskPairs.add(PrimitiveTuples.pair(risk, position));
            }
            long balance = userProfile.accounts.get(currency);
            long equity = balance + totalProfit;
            long warningThreshold = totalMaintenanceMargin * 6 / 5;
            if (equity >= warningThreshold)
                return;
            riskPairs.sort(Comparator.comparingDouble(DoubleObjectPair::getOne));// 升序排序 risk值越小风险越大
            if (equity < totalMaintenanceMargin) {
                long deficit = totalMaintenanceMargin - equity;
                forceCrossLiquidation(userProfile, riskPairs, deficit, eventsHelper, symbolSpecificationProvider, lastPriceCache);
            } else {
                sendWarningEvent(userProfile, riskPairs.get(0).getTwo(), eventsHelper, equity, warningThreshold);
            }
        });
    }

    private void sendWarningEvent(UserProfile userProfile, SymbolPositionRecord position, FundEventsHelper eventsHelper, long equity, long warningThreshold) {
        FundEvent event = eventsHelper.sendMarginAlertEvent(position);
        api.submitCommand(ApiSystemLiquidationNotify.builder().fundEvent(event).build());
        log.debug("Margin call: uid={} symbol={} equity={} threshold={}", userProfile.uid, position.symbol, equity, warningThreshold);
    }

    private void forceCrossLiquidation(UserProfile userProfile, List<DoubleObjectPair<SymbolPositionRecord>> positionPairs, long deficit,
        FundEventsHelper eventsHelper, SymbolSpecificationProvider symbolSpecificationProvider, MutableIntObjectMap<LastPriceCacheRecord> lastPriceCache) {
        long marginReleased = 0;
        for (DoubleObjectPair<SymbolPositionRecord> pair : positionPairs) {
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
                executeLiquidationOrder(userProfile, position, spec, price, sizeToLiquidate, eventsHelper);
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

    private void executeLiquidationOrder(UserProfile userProfile, SymbolPositionRecord position, CoreSymbolSpecification spec, long price, long size,
        FundEventsHelper eventsHelper) {
        // 确定强平方向：多头卖出（ASK）清算，空头买入（BID）清算
        OrderAction action = position.direction == PositionDirection.LONG ? OrderAction.ASK : OrderAction.BID;
        long orderId = generateLiquidationOrderId(position.symbol, position.uid); // IOC的单子不插入orderBook的
        CompletableFuture<OrderCommand> liquidationFuture = api.submitCommandAsyncFullResponse(ApiLiquidationOrder.builder().orderType(OrderType.IOC) // 目前是限价IOC，不过price是按市场价算的，只要深度足够就能成交
            .orderId(orderId).uid(position.uid).symbol(position.symbol).price(price).size(size).action(action).build());
        liquidationFuture.whenCompleteAsync((cmd, err) -> {
            /**
             * 如果第一个事件是reject，说明没有完全成交。
             * 
             * @see exchange.core2.core.orderbook.OrderBookDirectImpl#newOrderMatchIoc
             */
            MatcherTradeEvent firstEvent = cmd.matcherEvent;
            if (firstEvent.eventType == MatcherEventType.REJECT) {
                // 如果按市价单强平失败，再按照破产价强平
                long remainSize = firstEvent.size;
                long bankruptcyOrderId = generateLiquidationOrderId(position.symbol, position.uid);
                long bankruptcyPrice = calculateBankruptcyPrice(position, spec);
                api.submitCommandAsyncFullResponse(ApiLiquidationOrder.builder().orderType(OrderType.FOK_BUDGET).symbol(position.symbol)
                    .orderId(bankruptcyOrderId).uid(position.uid).price(bankruptcyPrice * remainSize).size(remainSize).action(action).build())
                    .whenCompleteAsync((cmd2, err2) -> {
                        if (cmd2.matcherEvent.eventType == MatcherEventType.REJECT) {
                            /**
                             * FOK的reject，是全额reject
                             * 
                             * @see exchange.core2.core.orderbook.OrderBookDirectImpl#newOrderMatchFokBudget
                             */
                            handleLiquidationFailure(position, cmd2.matcherEvent.size);
                        }
                    });
                FundEvent event = eventsHelper.sendLiquidationAlertEvent(bankruptcyOrderId, position, bankruptcyPrice, remainSize);
                api.submitCommand(ApiSystemLiquidationNotify.builder().fundEvent(event).build());
                log.debug("Liquidated(p2): uid={} symbol={} size={} price={}", userProfile.uid, position.symbol, remainSize, bankruptcyPrice);
            }
        });
        // 生成强平事件，记录用户、仓位和交易细节，便于审计和通知
        FundEvent event = eventsHelper.sendLiquidationAlertEvent(orderId, position, price, size);
        api.submitCommand(ApiSystemLiquidationNotify.builder().fundEvent(event).build());
        log.debug("Liquidated(p1): uid={} symbol={} size={} price={}", userProfile.uid, position.symbol, size, price);
    }

    /**
     * 计算破产价格
     */
    public long calculateBankruptcyPrice(SymbolPositionRecord position, CoreSymbolSpecification spec) {
        long totalMargin = position.openInitMarginSum + position.extraMargin;
        long liquidationFee = 0; // 暂时先不考虑强平费
        long maxLoss = totalMargin - liquidationFee;
        int sign = position.direction.getMultiplier();
        if (spec.isFixedFee()) {
            /**
             * 固定手续费的情况下： 总亏损 = openPriceSum - bankruptcyPrice × openVolume = totalMargin - liquidationFee - takerFee
             * bankruptcyPrice = openPriceSum - sign * (totalMargin - liquidationFee - takerFee) / openVolume
             */
            maxLoss -= spec.takerFee * position.openVolume;
            return CoreArithmeticUtils.ceilDivide(position.openPriceSum - sign * maxLoss, position.openVolume);
        } else {
            /**
             * 动态手续费的情况下： 总亏损 = openPriceSum - bankruptcyPrice × openVolume = totalMargin - liquidationFee - takerRate ×
             * bankruptcyPrice × openVolume bankruptcyPrice = openPriceSum - sign * (totalMargin - liquidationFee) /
             * (openVolume * (1 - takerFee / feeScaleK)) = (openPriceSum - sign * maxLoss) * feeScaleK / (openVolume *
             * feeScaleK - openVolume * takerFee)
             */
            return CoreArithmeticUtils.ceilDivide((position.openPriceSum - sign * maxLoss) * spec.feeScaleK,
                position.openVolume * (spec.feeScaleK - spec.takerFee));
        }
    }

    private void handleLiquidationFailure(SymbolPositionRecord position, long remainSize) {
        // TODO: 降级为 IFC / ADL 等处理逻辑
        log.warn("Liquidation REJECTED: uid={} symbol={} remainSize={}", position.uid, position.symbol, remainSize);
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
}