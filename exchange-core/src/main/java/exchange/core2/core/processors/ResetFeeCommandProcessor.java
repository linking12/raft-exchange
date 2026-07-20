package exchange.core2.core.processors;

import org.eclipse.collections.impl.map.mutable.primitive.IntLongHashMap;

import exchange.core2.core.common.CommonByShard;
import exchange.core2.core.common.CoreCurrencySpecification;
import exchange.core2.core.common.CoreSymbolSpecification;
import exchange.core2.core.common.MatcherEventType;
import exchange.core2.core.common.MatcherTradeEvent;
import exchange.core2.core.common.cmd.OrderCommand;
import exchange.core2.core.orderbook.OrderBookEventsHelper;
import exchange.core2.core.processors.loan.LoanService;
import lombok.extern.slf4j.Slf4j;

/**
 * RESET_FEE 两步处理器：R1 每 shard 清 fees / 加 adjustments，边清边把 currency→amount 记到 cmd.commonByShard[shardId].amounts；matcher
 * 聚合各 shard 得到 per-currency 总额，产 RESET_FEE_EVENT 链挂 cmd.matcherEvent；R2 只 shardId==0 逐 event 调 sendResetFeeEvent 对外发一份。
 */
@Slf4j
public final class ResetFeeCommandProcessor extends TwoStepCommandProcessor {

    public ResetFeeCommandProcessor(RiskEngine riskEngine) {
        super(null, riskEngine);
    }

    public ResetFeeCommandProcessor(OrderBookEventsHelper eventsHelper) {
        super(eventsHelper, null);
    }

    @Override
    public void collectInput(OrderCommand cmd) {
        super.collectInput(cmd);
        final IntLongHashMap fees = riskEngine.getFees();
        final IntLongHashMap adjustments = riskEngine.getAdjustments();
        final IntLongHashMap shardData = cmd.commonByShard[riskEngine.getShardId()].amounts;
        final int[] currencies = fees.keySet().toArray();
        for (int currency : currencies) {
            long amount = fees.get(currency);
            fees.addToValue(currency, -amount);
            adjustments.addToValue(currency, +amount);
            shardData.put(currency, amount);
        }
        // 只扫真实收入：利息是赚到的钱可提取；强平费已划归 LIF 作准备金，提取它等于抽走兜底能力
        final LoanService loanService = riskEngine.getLoanService();
        sweepRevenueBucket(loanService.getInterestRevenue(), adjustments, shardData);
    }

    private void sweepRevenueBucket(IntLongHashMap bucket, IntLongHashMap adjustments, IntLongHashMap shardData) {
        for (int currency : bucket.keySet().toArray()) {
            long amount = bucket.get(currency);
            if (amount == 0) {
                continue;
            }
            bucket.addToValue(currency, -amount);
            adjustments.addToValue(currency, +amount);
            shardData.addToValue(currency, +amount);
        }
    }

    @Override
    protected void buildMatcherEvents(OrderCommand cmd) {
        final IntLongHashMap totals = new IntLongHashMap();
        for (CommonByShard shardData : cmd.commonByShard) {
            shardData.amounts.forEachKeyValue(totals::addToValue);
        }
        if (totals.isEmpty()) {
            cmd.matcherEvent = null;
            return;
        }
        MatcherTradeEvent head = null;
        MatcherTradeEvent tail = null;
        final int[] currencies = totals.keySet().toArray();
        for (int currency : currencies) {
            MatcherTradeEvent ev = eventsHelper.newMatcherEvent();
            ev.eventType = MatcherEventType.RESET_FEE_EVENT;
            ev.matchedOrderUid = currency;
            ev.size = totals.get(currency);
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
        if (ev.eventType != MatcherEventType.RESET_FEE_EVENT) {
            return;
        }
        if (riskEngine.getShardId() != 0) {
            return;
        }
        riskEngine.getEventsHelper().sendResetFeeEvent(cmd, (int)ev.matchedOrderUid, ev.size);
    }
}
