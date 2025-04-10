package exchange.core2.core.event;

import exchange.core2.core.IEventsHandler;

public interface IEventsHandler4Test extends IEventsHandler {

    void rejectEvent(RejectEvent rejectEvent);

    void commandResult(ApiCommandResult commandResult);

}
