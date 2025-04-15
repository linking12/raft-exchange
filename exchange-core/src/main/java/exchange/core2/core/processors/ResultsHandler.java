package exchange.core2.core.processors;

import com.lmax.disruptor.EventHandler;
import exchange.core2.core.common.cmd.OrderCommand;
import exchange.core2.core.common.cmd.OrderCommandType;
import lombok.RequiredArgsConstructor;
import net.openhft.chronicle.wire.TriConsumer;

@RequiredArgsConstructor
public final class ResultsHandler implements EventHandler<OrderCommand> {

    private final TriConsumer<OrderCommand, Long, Boolean> resultsConsumer;

    private boolean processingEnabled = true;

    @Override
    public void onEvent(OrderCommand cmd, long sequence, boolean processFundEvents) {

        if (cmd.command == OrderCommandType.GROUPING_CONTROL) {
            processingEnabled = cmd.orderId == 1;
        }

        if (processingEnabled) {
            resultsConsumer.accept(cmd, sequence, processFundEvents);
        }

    }
}
