package exchange.core2.core.processors;

import org.eclipse.collections.impl.map.mutable.primitive.IntLongHashMap;
import org.eclipse.collections.impl.set.mutable.primitive.IntHashSet;

import exchange.core2.core.common.CommonByShard;
import exchange.core2.core.common.CoreCurrencySpecification;
import exchange.core2.core.common.CoreSymbolSpecification;
import exchange.core2.core.common.MatcherEventType;
import exchange.core2.core.common.MatcherTradeEvent;
import exchange.core2.core.common.cmd.OrderCommand;
import exchange.core2.core.orderbook.OrderBookEventsHelper;
import exchange.core2.core.processors.loan.LoanService;
import exchange.core2.core.processors.loan.rate.FloatingRateModel;
import lombok.extern.slf4j.Slf4j;

/**
 * REPRICE_LOAN_RATES 两步处理器（动态利率按全局利用率重定价）：
 * <ul>
 *   <li><b>R1</b> {@link #collectInput}：每 shard 把本地 {@code loanPoolBorrowed} / {@code loanPoolAvailable}
 *       写进 {@code cmd.commonByShard[shard].amounts}——<b>复用这一张 map</b>，key 编码区分两侧：
 *       <b>borrowed 存 key = currency（≥0）、available 存 key = ~currency（&lt;0）</b>，一一对应无冲突。</li>
 *   <li><b>merge</b> {@link #buildMatcherEvents}：跨 shard 按 key 符号解码求和 → 每币种全局利用率
 *       {@code util = ΣB / (ΣB+ΣA)}，每币种产一条 event 携带 {@code util}。曲线参数在 FloatingRateModel（各 shard 相同），
 *       matcher 阶段拿不到 → 只算 util，利率放到 R2 算。</li>
 *   <li><b>R2</b> {@link #applyEvent}：<b>每个 shard</b>（无 shardId==0 短路）先推 Floating 累加器、再按 util 过曲线写
 *       {@code floatingRate.currentRateBps[currency]}——各 shard 写入相同值。只写利率缓存、不碰余额，无守恒影响。</li>
 * </ul>
 */
@Slf4j
public final class LoanRatePricingProcessor extends TwoStepCommandProcessor {

    public LoanRatePricingProcessor(RiskEngine riskEngine) {
        super(null, riskEngine);
    }

    public LoanRatePricingProcessor(OrderBookEventsHelper eventsHelper) {
        super(eventsHelper, null);
    }

    @Override
    public void collectInput(OrderCommand cmd) {
        super.collectInput(cmd);
        final LoanService loanService = riskEngine.getLoanService();
        final IntLongHashMap shardData = cmd.commonByShard[riskEngine.getShardId()].amounts;
        final IntLongHashMap borrowed = loanService.getLoanPoolBorrowed();
        final IntLongHashMap available = loanService.getLoanPoolAvailable();
        for (int currency : borrowed.keySet().toArray()) {
            long v = borrowed.get(currency);
            if (v != 0) {
                shardData.put(currency, v);   // borrowed → key = currency
            }
        }
        for (int currency : available.keySet().toArray()) {
            long v = available.get(currency);
            if (v != 0) {
                shardData.put(~currency, v);  // available → key = ~currency
            }
        }
    }

    @Override
    protected void buildMatcherEvents(OrderCommand cmd) {
        final IntLongHashMap totalBorrowed = new IntLongHashMap();
        final IntLongHashMap totalAvailable = new IntLongHashMap();
        for (CommonByShard shardData : cmd.commonByShard) {
            shardData.amounts.forEachKeyValue((k, v) -> {
                if (k >= 0) {
                    totalBorrowed.addToValue(k, v);
                } else {
                    totalAvailable.addToValue(~k, v);
                }
            });
        }
        final IntHashSet currencies = new IntHashSet();
        currencies.addAll(totalBorrowed.keySet());
        currencies.addAll(totalAvailable.keySet());
        if (currencies.isEmpty()) {
            cmd.matcherEvent = null;
            return;
        }
        MatcherTradeEvent head = null;
        MatcherTradeEvent tail = null;
        for (int currency : currencies.toSortedArray()) {   // 排序：跨节点确定性
            long util = FloatingRateModel.utilizationBps(totalBorrowed.get(currency), totalAvailable.get(currency));
            MatcherTradeEvent ev = eventsHelper.newMatcherEvent();
            ev.eventType = MatcherEventType.LOAN_REPRICE_EVENT;
            ev.matchedOrderUid = currency;   // 载 currency
            ev.size = util;                  // 载全局利用率（bps）
            if (head == null) {
                head = ev;
            } else {
                tail.nextEvent = ev;
            }
            tail = ev;
        }
        cmd.matcherEvent = head;
    }

    @Override
    public void applyEvent(OrderCommand cmd, MatcherTradeEvent ev, CoreSymbolSpecification spec,
        CoreCurrencySpecification currencySpec) {
        super.applyEvent(cmd, ev, spec, currencySpec);
        if (ev.eventType != MatcherEventType.LOAN_REPRICE_EVENT) {
            return;
        }
        final int currency = (int) ev.matchedOrderUid;
        final LoanService loanService = riskEngine.getLoanService();
        loanService.getFloatingRate().advanceAccumulator(currency, cmd.timestamp); // 先用旧 currentRate 推进 Floating 累加器
        loanService.getFloatingRate().repriceCurrency(currency, ev.size);             // 再把 util 过曲线写新生效利率
    }
}
