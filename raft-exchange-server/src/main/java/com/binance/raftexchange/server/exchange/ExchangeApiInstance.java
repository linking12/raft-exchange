package com.binance.raftexchange.server.exchange;

import exchange.core2.core.ExchangeApi;
import exchange.core2.core.ExchangeCore;
import exchange.core2.core.IEventsHandler;
import exchange.core2.core.SimpleEventsProcessor;
import exchange.core2.core.common.config.ExchangeConfiguration;

public class ExchangeApiInstance {

	private final ExchangeApi exchangeApi;

	private static final ExchangeApiInstance INSTANCE = new ExchangeApiInstance();

	private ExchangeApiInstance() {
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

	public ExchangeApi getExchangeApi() {
		return this.exchangeApi;
	}

	public static ExchangeApi exchangeApi() {
		return INSTANCE.getExchangeApi();
	}

}
