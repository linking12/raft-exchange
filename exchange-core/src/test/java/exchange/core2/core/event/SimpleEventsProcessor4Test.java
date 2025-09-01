package exchange.core2.core.event;

import exchange.core2.core.SimpleEventsProcessor;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

@Getter
@Slf4j
public class SimpleEventsProcessor4Test extends SimpleEventsProcessor {

    private IEventsHandler4Test eventsHandler;

    public SimpleEventsProcessor4Test(IEventsHandler4Test eventsHandler) {
        super(eventsHandler, eventsHandler);
        this.eventsHandler = eventsHandler;
    }

}
