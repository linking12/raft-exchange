package exchange.core2.core.processors.settlement;

import exchange.core2.core.common.MatcherEventType;
import exchange.core2.core.common.MatcherTradeEvent;
import exchange.core2.core.common.cmd.CommandResultCode;
import exchange.core2.core.common.cmd.OrderCommand;
import exchange.core2.core.orderbook.OrderBookEventsHelper;

/**
 * Base class for liquidation-related matching planners (ADL / IF).
 */
public abstract class AbstractSettlementProcessor {
    protected final OrderBookEventsHelper eventsHelper;

    protected AbstractSettlementProcessor(OrderBookEventsHelper eventsHelper) {
        this.eventsHelper = eventsHelper;
    }

    public final CommandResultCode process(OrderCommand cmd) {
        if (cmd.resultCode != CommandResultCode.VALID_FOR_MATCHING_ENGINE) {
            return cmd.resultCode;
        }
        buildMatcherEvents(cmd);
        return CommandResultCode.SUCCESS;
    }

    protected abstract void buildMatcherEvents(OrderCommand cmd);

    protected MatcherTradeEvent buildRejectEvent() {
        MatcherTradeEvent ev = eventsHelper.newMatcherEvent();
        ev.eventType = MatcherEventType.REJECT;
        return ev;
    }
}