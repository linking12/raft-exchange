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
 * REPRICE_LOAN_RATES 两步处理器：按全局利用率重定价动态利率。R1 {@link #collectInput} 每 shard 写本地池数据， merge {@link #buildMatcherEvents} 跨
 * shard 求和算每币种 util，R2 {@link #applyEvent} 每 shard 过曲线写生效利率。
 * <p>
 * R1 复用同一张 {@code amounts} map 存两侧，靠 key 符号区分：borrowed 存 key = currency（≥0）、available 存 key = ~currency（&lt;0），一一对应无冲突。
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
                shardData.put(currency, v); // borrowed → key = currency
            }
        }
        for (int currency : available.keySet().toArray()) {
            long v = available.get(currency);
            if (v != 0) {
                shardData.put(~currency, v); // available → key = ~currency
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
        for (int currency : currencies.toSortedArray()) { // 排序：跨节点确定性
            long util = FloatingRateModel.utilizationBps(totalBorrowed.get(currency), totalAvailable.get(currency));
            MatcherTradeEvent ev = eventsHelper.newMatcherEvent();
            ev.eventType = MatcherEventType.LOAN_REPRICE_EVENT;
            ev.matchedOrderUid = currency; // 载 currency
            ev.size = util; // 载全局利用率（bps）
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
        final int currency = (int)ev.matchedOrderUid;
        final LoanService loanService = riskEngine.getLoanService();
        loanService.getFloatingRate().advanceAccumulator(currency, cmd.timestamp); // 先用旧 currentRate 推进 Floating 累加器
        loanService.getFloatingRate().repriceCurrency(currency, ev.size); // 再把 util 过曲线写新生效利率
    }
}
