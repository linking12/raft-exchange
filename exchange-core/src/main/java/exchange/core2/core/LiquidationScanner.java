package exchange.core2.core;

import java.util.Collection;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import org.eclipse.collections.api.map.primitive.MutableIntObjectMap;
import org.eclipse.collections.api.map.primitive.MutableLongObjectMap;

import exchange.core2.core.common.CoreSymbolSpecification;
import exchange.core2.core.common.FundEvent;
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
import exchange.core2.core.processors.FundEventsHelper;
import exchange.core2.core.processors.RiskEngine;
import exchange.core2.core.processors.SymbolSpecificationProvider;
import exchange.core2.core.processors.RiskEngine.LastPriceCacheRecord;
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

    public LiquidationScanner(ExchangeApi api, Collection<RiskEngine> riskEngines, int riskEnginesNum) {
        this.api = api;
        this.riskEngines = riskEngines;
        this.fundEventsHelpers = riskEngines.stream().collect(
            Collectors.toMap(RiskEngine::getShardId, r -> new FundEventsHelper(() -> r.getSharedPool().getFundEventChain(), r.getShardId(), riskEnginesNum)));
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
            }, 2, 2, TimeUnit.SECONDS);
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
        MutableLongObjectMap<UserProfile> userProfiles = riskEngine.getUserProfileService().getUserProfiles().asUnmodifiable();
        MutableIntObjectMap<LastPriceCacheRecord> lastPriceCache = riskEngine.getLastPriceCache().asUnmodifiable();
        FundEventsHelper eventsHelper = fundEventsHelpers.get(riskEngine.getShardId());
        userProfiles.forEachValue(userProfile -> {
            if (userProfile == null) return;
            // 遍历每个用户的所有持仓
            MutableIntObjectMap<SymbolPositionRecord> positions = userProfile.positions.asUnmodifiable();
            positions.forEachValue(position -> {
                if (position == null) return;
                int symbol = position.symbol;
                CoreSymbolSpecification spec = symbolSpecificationProvider.getSymbolSpecification(symbol);
                if (spec.type != SymbolType.FUTURES_CONTRACT) {
                    return; // 跳过非期货持仓
                }
                // 获取最新价格记录
                LastPriceCacheRecord priceRecord = lastPriceCache.get(symbol);
                if (priceRecord == null) {
                    log.debug("No price record for symbol={}", symbol);
                    return;
                }
                // 强平判断和处理逻辑
                evaluateForLiquidation(userProfile, spec, priceRecord, position, eventsHelper);
            });
        });
    }

    private void evaluateForLiquidation(UserProfile userProfile, CoreSymbolSpecification spec, LastPriceCacheRecord priceRecord, SymbolPositionRecord position,
        FundEventsHelper eventsHelper) {
        // 仅处理有持仓（方向非 EMPTY）的用户
        if (position != null && position.direction != PositionDirection.EMPTY) {
            // 当前账户可用余额，已扣除开仓时的初始保证金，反映用户可自由支配的资金
            long balance = userProfile.accounts.get(spec.quoteCurrency);
            // 未实现盈亏，基于标记价格（markPrice），反映持仓的市场价值变化
            long profit = position.liquidateEstimateProfit(spec, priceRecord);
            // 当前持仓所需的初始保证金，实时计算，已通过 accounts 减少隐式冻结
            long locked = position.calculateRequiredMarginForFutures(spec);
            // 账户总权益 = 初始保证金 + 未实现盈亏，表示账户的整体抗风险能力
            long equity = locked + profit;
            // 维持保证金，基于持仓量和规格定义的最低资金要求，若低于此值需强平
            long maintenanceMargin = position.calculateMaintenanceMargin(spec);
            // 预警阈值，设为维持保证金的 1.2 倍，用于提前提醒用户追加资金
            long warningThreshold = (long)(maintenanceMargin * 1.2);
            // 权益低于维持保证金，触发强平以保护系统免受进一步亏损
            if (equity < maintenanceMargin) {
                // 计算资金缺口：需要多少权益恢复到维持保证金水平
                long deficit = maintenanceMargin - equity;
                // 强平价格：多头用买价（bidPrice），空头用卖价（askPrice），反映市场可执行价格
                long price = position.direction == PositionDirection.LONG ? priceRecord.bidPrice : priceRecord.askPrice;
                // 若市场无流动性（价格为 0 或 MAX_VALUE），回退到平均开仓价作为兜底
                if (price == 0 || price == Long.MAX_VALUE) {
                    price = position.openVolume > 0 ? position.openPriceSum / position.openVolume : priceRecord.markPrice;
                    if (price == 0)
                        price = 1; // 防止除零，默认最小价格
                    log.debug("Fallback to average open price={} for symbol={}", price, position.symbol);
                }
                /**
                 * 计算强平数量： 找一个x，满足：x × price ≥ deficit + x × taker_fee x ≥ deficit / (price - taker_fee)
                 */
                long x = (long)Math.ceil((double)deficit / (price - spec.takerFee));
                long sizeToLiquidate = Math.min(position.openVolume, x);
                if (sizeToLiquidate > 0) {
                    // 确定强平方向：多头卖出（ASK）清算，空头买入（BID）清算
                    OrderAction action = position.direction == PositionDirection.LONG ? OrderAction.ASK : OrderAction.BID;
                    long orderId = generateLiquidationOrderId(position.symbol, position.uid); // IOC的单子不插入orderBook的
                    CompletableFuture<OrderCommand> liquidationFuture =
                        api.submitCommandAsyncFullResponse(ApiLiquidationOrder.builder().orderType(OrderType.IOC) // 目前是限价IOC，不过price是按市场价算的，只要深度足够就能成交
                            .orderId(orderId).uid(position.uid).symbol(position.symbol).price(price).size(sizeToLiquidate).action(action).build());
                    liquidationFuture.whenCompleteAsync((cmd, err) -> {
                        /**
                         * 如果第一个事件是reject，说明没有完全成交。 todo 后续需要降级处理 IFC ADL等
                         * 
                         * @see exchange.core2.core.orderbook.OrderBookDirectImpl#newOrderMatchIoc
                         */
                        MatcherTradeEvent firstEvent = cmd.matcherEvent;
                        if (firstEvent.eventType == MatcherEventType.REJECT) {
                            long remainSize = firstEvent.size;
                        }
                    });
                    // 生成强平事件，记录用户、仓位和交易细节，便于审计和通知
                    FundEvent event = eventsHelper.sendLiquidationAlertEvent(orderId, position, balance - locked, locked, price, sizeToLiquidate);
                    api.submitCommand(ApiSystemLiquidationNotify.builder().fundEvent(event).build());
                    log.debug("Liquidated: uid={} symbol={} size={} price={}", userProfile.uid, position.symbol, sizeToLiquidate, price);
                }
            }
            // 权益低于预警阈值但高于维持保证金，发送 Margin Call 提醒用户追加资金
            else if (equity < warningThreshold) {
                // 发送提醒事件，包含当前余额和冻结保证金，便于用户了解资金状态
                FundEvent event = eventsHelper.sendMarginAdjustmentEvent(position, balance - locked, locked);
                api.submitCommand(ApiSystemLiquidationNotify.builder().fundEvent(event).build());
                log.debug("Margin call: uid={} symbol={} equity={} threshold={}", userProfile.uid, position.symbol, equity, warningThreshold);
            }
        }
    }

    private static long generateLiquidationOrderId(int symbol, long uid) {
        long uidHash = (uid * 31 + 17) & 0xFFFFF; // 取前 20 bit
        long tsPart = (System.currentTimeMillis() / 1000) & 0xFFF; // 取后12bit，支持4096秒 ≈ 1.13小时内不重复
        return ((long)symbol << 32) | (uidHash << 12) | tsPart;
    }

    public static boolean isLiquidationOrderId(long orderId, int symbol, long uid) {
        long expectedSymbol = (orderId >>> 32); // 高 32 位
        if (expectedSymbol != symbol) return false;

        long expectedUidHash = (uid * 31 + 17) & 0xFFFFF; // 计算 uidHash
        long actualUidHash = (orderId >>> 12) & 0xFFFFF;  // 中间 20 位
        return expectedUidHash == actualUidHash;
    }
}