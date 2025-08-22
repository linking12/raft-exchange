package com.binance.raftexchange.client.tests;

import com.binance.raftexchange.client.ExchangeApi;
import com.binance.raftexchange.client.SingleUserReportResultView;
import com.binance.raftexchange.stubs.SymbolType;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;

import static com.binance.raftexchange.client.ExchangeApiHelper.buildSlotValueMap;

public class BaseTest {
    long UID_1 = 10001L;
    long UID_2 = 10002L;
    long UID_3 = 10003L;
    long UID_4 = 10004L;
    long UID_5 = 10005L;
    long UID_6 = 10006L;
    long UID_7 = 10007L;

    long UID_11 = 20001L;
    long UID_12 = 20002L;
    long UID_13 = 20003L;
    long UID_14 = 20004L;
    long UID_15 = 20005L;
    long UID_16 = 20006L;
    long UID_17 = 20007L;
    long UID_18 = 20008L;
    long UID_19 = 20009L;
    long UID_20 = 20010L;

    int BNB_ID = 1;
    int USDT_ID = 2;
    int BNB_USDT_SPOT = 11;
    int BNB_USDT_FU = 12;

    double usdtDeposit = 10000D;
    double bnbDeposit = 100;

    ExchangeApi exchangeApi;

    @Before
    public void beforeTest() throws Exception {
        exchangeApi = ExchangeApi.connect("127.0.0.1", 5001);
        this.doInit();
    }

    @After
    public void afterTest() {
        exchangeApi.close();
    }

    @Test
    public void doInit() throws Exception {
        SingleUserReportResultView result = exchangeApi.queryUserReport(UID_1).get();
        // 查询用户, 币种以及交易对是否存在, 若不存在时创建
        if (result != null && result.getAccounts() != null && result.getAccounts().get(USDT_ID) != null && result.getAccounts().get(USDT_ID) > 0) {
            return;
        }

        exchangeApi.addCurrency(BNB_ID, "BNB", 8);
        exchangeApi.addCurrency(USDT_ID, "USDT", 6);

        exchangeApi.addSymbol(BNB_USDT_SPOT, SymbolType.CURRENCY_EXCHANGE_PAIR, BNB_ID, USDT_ID, 1000, 100000,
                15, 10, 1000, 0, 0, null, 0, null);

        exchangeApi.addSymbol(BNB_USDT_FU, SymbolType.FUTURES_CONTRACT_PERPETUAL, BNB_ID, USDT_ID, 1000, 100000,
                15, 10, 1000, 10, 100,
                buildSlotValueMap(100L, 10L, 200L, 20L, 300L, 30L), 100,
                buildSlotValueMap(1000L, 75L, 2000L, 50L, 3000L, 10L));

        // spot users
        long tx = 10000L;
        long userId = UID_1;
        for (int i = 0; i < 20; i++) {
            exchangeApi.addUser(userId + i);
            exchangeApi.adjustUserBalance(userId + i, tx + i, USDT_ID, usdtDeposit);
        }
        exchangeApi.adjustUserBalance(UID_2, 10021L,  BNB_ID, bnbDeposit);
        exchangeApi.adjustUserBalance(UID_4, 10022L, BNB_ID, bnbDeposit);


        double markPrice = 750.2;
        exchangeApi.adjustMarkPrice(BNB_USDT_FU, markPrice);
    }

    public <T extends Number> T getInitOrderId(Class<T> type) {
        long timestamp = System.currentTimeMillis();
        if (type == Integer.class) {
            int truncatedValue = (int) (timestamp % 1_000_000_000);
            return type.cast(truncatedValue);
        } else if (type == Long.class) {
            return type.cast(timestamp);
        } else {
            throw new IllegalArgumentException("Unsupported type: " + type.getName() + ". Only Integer and Long are supported.");
        }
    }

    public SingleUserReportResultView getReport(long userId) throws ExecutionException, InterruptedException {
        return exchangeApi.queryUserReport(userId).get();
    }

    public double getBalance(long userId, int currency) throws ExecutionException, InterruptedException {
        SingleUserReportResultView view = getReport(userId);
        if (view == null) {
            return 0;
        }
        if (!view.getAccounts().containsKey(currency)) {
            return 0;
        }
        return view.getAccounts().get(currency);
    }

    public SingleUserReportResultView.OrderView getOrder(long userId, int symbol, int orderId) throws ExecutionException, InterruptedException {
        SingleUserReportResultView view = getReport(userId);
        Map<Integer, List<SingleUserReportResultView.OrderView>> allOrders = view.getOrders();
        if (allOrders == null) {
            return null;
        }
        List<SingleUserReportResultView.OrderView> orders = allOrders.get(symbol);
        if (orders == null) {
            return null;
        }
        return orders.stream().filter(o -> o.getOrderId() == orderId).findFirst().orElse(null);
    }

    public List<SingleUserReportResultView.PositionView> getPosition(long userId, int symbol) throws ExecutionException, InterruptedException {
        SingleUserReportResultView view = getReport(userId);
        Map<Integer, List<SingleUserReportResultView.PositionView>> allPositions = view.getPositions();
        if (allPositions == null) {
            return null;
        }
        List<SingleUserReportResultView.PositionView> positions = allPositions.get(symbol);
        if (positions == null) {
            return null;
        }
        return positions;
    }

}
