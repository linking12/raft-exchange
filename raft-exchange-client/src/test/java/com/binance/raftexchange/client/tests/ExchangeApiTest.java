package com.binance.raftexchange.client.tests;

import com.binance.raftexchange.client.ExchangeApi;
import com.binance.raftexchange.client.ExchangeApiHelper;
import com.binance.raftexchange.client.SingleUserReportResultView;
import com.binance.raftexchange.stubs.OrderAction;
import com.binance.raftexchange.stubs.OrderType;
import com.binance.raftexchange.stubs.SymbolType;
import org.junit.Test;

import java.util.concurrent.ExecutionException;

public class ExchangeApiTest {

    private long UID_1 = 10001L;
    private long UID_2 = 10002L;

    private int BNB_ID = 1;
    private int USDT_ID = 2;
    private int BNB_USDT_SPOT = 11;
    private int BNB_USDT_FU = 11;

    @Test
    public void testExchangeApi() throws Exception {
        ExchangeApi exchangeApi = ExchangeApi.connect("127.0.0.1", 5001);
        exchangeApi.addCurrency(BNB_ID, "BNB", 3);
        exchangeApi.addCurrency(USDT_ID, "USDT", 3);
        exchangeApi.addSymbol(BNB_USDT_SPOT, SymbolType.CURRENCY_EXCHANGE_PAIR, BNB_ID, USDT_ID, 1000, 1000,
                15, 10, 1000, 0, 0, null, 0, null);
        exchangeApi.addSymbol(BNB_USDT_FU, SymbolType.CURRENCY_EXCHANGE_PAIR, BNB_ID, USDT_ID, 1000, 1000,
                15, 10, 1000, 100, 0,
                ExchangeApiHelper.buildSlotValueMap(100L, 10L, 200L, 20L, 300L, 30L), 100,
                ExchangeApiHelper.buildSlotValueMap(1000L, 75L, 2000L, 50L, 3000L, 10L));

        exchangeApi.addUser(UID_1);
        exchangeApi.addUser(UID_2);

        exchangeApi.adjustUserBalance(UID_1, USDT_ID, 8888);
        exchangeApi.adjustUserBalance(UID_2, BNB_ID, 10.78);

        long orderId = 100001L;
        exchangeApi.placeOrder(UID_1, orderId++, BNB_USDT_SPOT, OrderAction.BID, OrderType.IOC, 850.2, 855, 0.5, null, 0);
        exchangeApi.placeOrder(UID_2, orderId++, BNB_USDT_SPOT, OrderAction.ASK, OrderType.IOC, 850.2, 0, 1, null, 0);

        SingleUserReportResultView reportU1 = exchangeApi.queryUserReport(UID_1).get();
        SingleUserReportResultView reportU2 = exchangeApi.queryUserReport(UID_2).get();

        exchangeApi.close();
    }
}
