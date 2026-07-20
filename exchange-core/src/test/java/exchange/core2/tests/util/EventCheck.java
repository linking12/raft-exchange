package exchange.core2.tests.util;

import exchange.core2.core.IFundEventsHandler;
import org.hamcrest.core.Is;

import static org.hamcrest.MatcherAssert.assertThat;

public class EventCheck {
    public static void checkEvent(IFundEventsHandler.FundEventReport evt) {
        assertThat(0L, Is.is(evt.getPositions().getUnrealizedProfit()));
        assertThat(0L, Is.is(evt.getPositions().getLiquidationPrice()));
        assertThat(0L, Is.is(evt.getPositions().getMarginRatioScaleK()));
    }

    public static void checkEventPending(IFundEventsHandler.FundEventReport evt) {
        assertThat(0L, Is.is(evt.getPositions().getBidsNotional()));
        assertThat(0L, Is.is(evt.getPositions().getAsksNotional()));
        assertThat(0L, Is.is(evt.getPositions().getBidsQty()));
        assertThat(0L, Is.is(evt.getPositions().getAsksQty()));
    }
}
