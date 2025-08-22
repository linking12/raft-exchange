package com.binance.raftexchange.client.tests;

import com.binance.raftexchange.client.CommandResultView;
import com.binance.raftexchange.client.ExchangeApi;
import com.binance.raftexchange.client.ExchangeApiHelper;
import com.binance.raftexchange.client.SingleUserReportResultView;
import com.binance.raftexchange.stubs.OrderAction;
import com.binance.raftexchange.stubs.OrderType;
import com.binance.raftexchange.stubs.SymbolType;
import org.junit.Test;

import static com.binance.raftexchange.client.ExchangeApiHelper.buildSlotValueMap;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.core.Is.is;

public class ExchangeApiTest {

    private long UID_1 = 10001L;
    private long UID_2 = 10002L;

    private int BNB_ID = 1;
    private int USDT_ID = 2;
    private int BNB_USDT_SPOT = 11;
    private int BNB_USDT_FU = 12;

    @Test
    public void testExchangeApi() throws Exception {
        ExchangeApi exchangeApi = ExchangeApi.connect("127.0.0.1", 5001);
        exchangeApi.addCurrency(BNB_ID, "BNB", 8);
        exchangeApi.addCurrency(USDT_ID, "USDT", 6);

        exchangeApi.addSymbol(BNB_USDT_SPOT, SymbolType.CURRENCY_EXCHANGE_PAIR, BNB_ID, USDT_ID, 1000, 100000,
                15, 10, 1000, 0, 0, null, 0, null);
        exchangeApi.addSymbol(BNB_USDT_FU, SymbolType.CURRENCY_EXCHANGE_PAIR, BNB_ID, USDT_ID, 1000, 100000,
                15, 10, 1000, 100, 0,
                buildSlotValueMap(100L, 10L, 200L, 20L, 300L, 30L), 100,
                buildSlotValueMap(1000L, 75L, 2000L, 50L, 3000L, 10L));

        exchangeApi.addUser(UID_1);
        exchangeApi.addUser(UID_2);

        long adjustId = 100000L;
        exchangeApi.adjustUserBalance(UID_1, adjustId++, USDT_ID, 8888);
        exchangeApi.adjustUserBalance(UID_2, adjustId++, BNB_ID, 10.78);

        long orderId = 100001L;
        CommandResultView resultU1 = exchangeApi.placeOrder(UID_1, orderId++, BNB_USDT_SPOT, OrderAction.BID, OrderType.GTC, 850.2, 855, 0.5, null, 0);
        CommandResultView resultU2 = exchangeApi.placeOrder(UID_2, orderId++, BNB_USDT_SPOT, OrderAction.ASK, OrderType.GTC, 852, 0, 1, null, 0);

        SingleUserReportResultView reportU1 = exchangeApi.queryUserReport(UID_1).get();
        // 未成单，占用资金 0.5 * 855(按高的reverse价格算) * 1.015(1.5%手续费) = 433.9125
        // 可用余额：8888 - 433.9125 = 8454.0875
        assertThat(reportU1.getAccounts().get(USDT_ID), is(8888 - 0.5 * 855 * 1.015));
        SingleUserReportResultView reportU2 = exchangeApi.queryUserReport(UID_2).get();

        exchangeApi.close();
    }
}
