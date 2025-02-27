package com.binance.raftexchange.server.raft;

import java.io.DataInput;
import java.io.DataOutput;

import com.binance.raftexchange.server.exchange.SyncAdminApiAccountsController;
import com.binance.raftexchange.stubs.api.ApiCommand;
import com.binance.raftexchange.stubs.command.OrderCommand;
import com.google.protobuf.GeneratedMessageV3;
import org.jgroups.raft.StateMachine;

import exchange.core2.core.ExchangeApi;
import exchange.core2.core.ExchangeCore;
import exchange.core2.core.IEventsHandler;
import exchange.core2.core.SimpleEventsProcessor;
import exchange.core2.core.common.config.ExchangeConfiguration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ExchangeStateMachine implements StateMachine {

    private static final Logger LOG = LoggerFactory.getLogger(ExchangeStateMachine.class);

    private final ExchangeApi exchangeApi;

	public ExchangeStateMachine() {
		SimpleEventsProcessor eventsProcessor = new SimpleEventsProcessor(new IEventsHandler() {
			@Override
			public void tradeEvent(TradeEvent tradeEvent) {
				System.out.println("Trade event: " + tradeEvent);
			}

			@Override
			public void reduceEvent(ReduceEvent reduceEvent) {
				System.out.println("Reduce event: " + reduceEvent);
			}

			@Override
			public void rejectEvent(RejectEvent rejectEvent) {
				System.out.println("Reject event: " + rejectEvent);
			}

			@Override
			public void commandResult(ApiCommandResult commandResult) {
				System.out.println("Command result: " + commandResult);
			}

			@Override
			public void orderBook(OrderBook orderBook) {
				System.out.println("OrderBook event: " + orderBook);
			}
		});
		ExchangeConfiguration conf = ExchangeConfiguration.defaultBuilder().build();
		ExchangeCore exchangeCore = ExchangeCore.builder().resultsConsumer(eventsProcessor).exchangeConfiguration(conf)
				.build();
		exchangeCore.startup();
		ExchangeApi api = exchangeCore.getApi();
		this.exchangeApi = api;
	}

    @Override
    public byte[] apply(byte[] data, int offset, int length, boolean serialize_response) throws Exception {
        GeneratedMessageV3 grpcMessage = SerializeHelper.deserializeWithType(data, offset, length);
        if (grpcMessage instanceof ApiCommand) {
            ApiCommand.CommandCase commandCase = ((ApiCommand) grpcMessage).getCommandCase();
			switch (commandCase) {
				case BINARY_DATA:
					break;
				case PLACE_ORDER:
					break;
				case ADJUST_BALANCE:
					SyncAdminApiAccountsController.adjustBalance(((ApiCommand) grpcMessage).getAdjustBalance());
					break;
				case PERSIST_STATE:
					break;
				case ORDER_BOOK_REQUEST:
					break;
				case NOP:
					LOG.info("NOP Command received, no action taken.");
					break;
				case MOVE_ORDER:
					break;
				case CANCEL_ORDER:
					break;
				case ADD_USER:
					SyncAdminApiAccountsController.createUser(((ApiCommand) grpcMessage).getAddUser());
					break;
				case REDUCE_ORDER:
					break;
				case SUSPEND_USER:
					break;
				case RESET:
					break;
				case RESUME_USER:
					break;
				default:
					LOG.warn("Unsupported ApiCommand: {}", commandCase);
			}

        } else if (grpcMessage instanceof OrderCommand) {
            ((OrderCommand) grpcMessage).getCommand();
        }
        return null;
    }

	@Override
	public void readContentFrom(DataInput in) throws Exception {

	}

	@Override
	public void writeContentTo(DataOutput out) throws Exception {

	}

}
