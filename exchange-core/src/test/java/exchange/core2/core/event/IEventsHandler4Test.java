package exchange.core2.core.event;

import exchange.core2.core.ITradeEventsHandler;
import exchange.core2.core.IFundEventsHandler;

public interface IEventsHandler4Test extends ITradeEventsHandler, IFundEventsHandler {

    IEventsHandler4Test handler = new IEventsHandler4Test() {

        @Override
        public void process(FundEventReport fundEventReport) {
            fundEventReport(fundEventReport);
        }

        @Override
        public void process(SpotExecutionReport executionReport) {
            spotExecutionReport(executionReport);
        }

        @Override
        public void process(FuturesExecutionReport executionReport) {
            futuresExecutionReport(executionReport);
        }

        @Override
        public void orderBook(ITradeEventsHandler.OrderBook orderBook) {

        }

        @Override
        public void spotExecutionReport(ITradeEventsHandler.SpotExecutionReport executionReport) {

        }

        @Override
        public void futuresExecutionReport(ITradeEventsHandler.FuturesExecutionReport executionReport) {

        }

        @Override
        public void fundEventReport(IFundEventsHandler.FundEventReport fundEventReport) {

        }
    };
}
