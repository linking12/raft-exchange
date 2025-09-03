package com.binance.raftexchange.client.tests;

import com.binance.raftexchange.client.CommandResultView;
import com.binance.raftexchange.client.ExchangeApi;
import com.binance.raftexchange.client.SingleUserReportResultView;
import com.binance.raftexchange.stubs.*;
import com.binance.raftexchange.stubs.response.CommandResultCode;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;

import static com.binance.raftexchange.client.ExchangeApiHelper.buildSlotValueMap;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.core.Is.is;

public class ExchangeApiFutureTest extends BaseTest {

    // 下单
    @Test
    public void testPlaceOrder() throws Exception {

        long orderId = getInitOrderId(Long.class);

        // 先算出来初始资金以及order数量
        SingleUserReportResultView queryU1 = exchangeApi.queryUserReport(UID_11).join();
        SingleUserReportResultView queryU2 = exchangeApi.queryUserReport(UID_12).join();
        double usdtBalance1 = queryU1.getAccounts().get(USDT_ID);
        double usdtBalance2 = queryU2.getAccounts().get(USDT_ID);

        // 提交订单应该成功
        CommandResultView result1 = exchangeApi.placeOrder(UID_11, orderId, BNB_USDT_FU, OrderAction.BID, OrderType.GTC, 850.2, 855, 0.5, MarginMode.ISOLATED, 1);
        CommandResultView result2 = exchangeApi.placeOrder(UID_12, orderId + 1, BNB_USDT_FU, OrderAction.ASK, OrderType.GTC, 852, 0, 1, MarginMode.ISOLATED, 1);

        assertThat(result1.getResultCode(), is(CommandResultCode.SUCCESS));
        assertThat(result2.getResultCode(), is(CommandResultCode.SUCCESS));

        // 拿到当前资金
        SingleUserReportResultView reportU1 = exchangeApi.queryUserReport(UID_11).get();
        SingleUserReportResultView reportU2 = exchangeApi.queryUserReport(UID_12).get();
        double aUsdtBalance1 = reportU1.getAccounts().get(USDT_ID);
        double aUsdtBalance2 = reportU2.getAccounts().get(USDT_ID);

        // 未成单
        assertThat(usdtBalance1, is(aUsdtBalance1));
        assertThat(usdtBalance2, is(aUsdtBalance2));

        List<SingleUserReportResultView.PositionView> position1 = getPosition(UID_11, BNB_USDT_FU);
        List<SingleUserReportResultView.PositionView> position2 = getPosition(UID_12, BNB_USDT_FU);

        assertThat(position1.size(), is(1));
        assertThat(position2.size(), is(1));
        assertThat(position1.get(0).getOpenVolume(), is(0L));
        assertThat(position2.get(0).getOpenVolume(), is(0L));
    }

    // 测试取消订单
    @Test
    public void testCancelOrder() throws Exception {

        int orderId = getInitOrderId(Integer.class);

        double price = 250.2;
        CommandResultView view1 = exchangeApi.placeOrder(UID_13, orderId, BNB_USDT_FU, OrderAction.BID, OrderType.GTC, price, price, 0.5, MarginMode.CROSS, 1);
        assertThat(view1.getResultCode(), is(CommandResultCode.SUCCESS));

        List<SingleUserReportResultView.PositionView> position1 = getPosition(UID_13, BNB_USDT_FU);
        assertThat(position1.size(), is(1));

        CommandResultView view2 = exchangeApi.cancelOrder(UID_13, orderId, BNB_USDT_FU);
        assertThat(view2.getResultCode(), is(CommandResultCode.SUCCESS));

        List<SingleUserReportResultView.PositionView> position2 = getPosition(UID_13, BNB_USDT_FU);
        assertThat(position2 == null, is(true));
    }

    // 调整杠杆
    @Test
    public void testAdjustLeverage() throws Exception {

        int orderId = getInitOrderId(Integer.class);

        double price = 850.2;
        CommandResultView view1 = exchangeApi.placeOrder(UID_15, orderId, BNB_USDT_FU, OrderAction.BID, OrderType.GTC, price, price, 0.5, MarginMode.ISOLATED, 5);
        assertThat(view1.getResultCode(), is(CommandResultCode.SUCCESS));

        List<SingleUserReportResultView.PositionView> position1 = getPosition(UID_15, BNB_USDT_FU);
        assertThat(position1.size(), is(1));
        assertThat(position1.get(0).getLeverage(), is(5));

        CommandResultView view2 = exchangeApi.adjustLeverage(UID_15, BNB_USDT_FU, 10);
        assertThat(view2.getResultCode(), is(CommandResultCode.SUCCESS));

        Thread.sleep(1000L);
        List<SingleUserReportResultView.PositionView> position2 = getPosition(UID_15, BNB_USDT_FU);
        assertThat(position2.size(), is(1));
        assertThat(position2.get(0).getLeverage(), is(10));

        exchangeApi.cancelOrder(UID_15, orderId, BNB_USDT_FU);
    }

    // 订单成交
    @Test
    public void testOpenPosition() throws Exception {
        long orderId1 = getInitOrderId(Long.class);
        long orderId2 = orderId1 + 1;

        List<SingleUserReportResultView.PositionView> position1 = getPosition(UID_16, BNB_USDT_FU);
        List<SingleUserReportResultView.PositionView> position2 = getPosition(UID_17, BNB_USDT_FU);
        long openVolume1 = position1 != null && position1.size() > 0 ? position1.get(0).getOpenVolume() : 0;
        long openVolume2 = position2 != null && position2.size() > 0 ? position2.get(0).getOpenVolume() : 0;

        // 下单
        CommandResultView view1 = exchangeApi.placeOrder(UID_16, orderId1, BNB_USDT_FU, OrderAction.BID, OrderType.GTC, 850.2, 855, 0.5, MarginMode.ISOLATED, 1);
        CommandResultView view2 = exchangeApi.placeOrder(UID_17, orderId2, BNB_USDT_FU, OrderAction.ASK, OrderType.GTC, 850, 850, 0.5, MarginMode.ISOLATED, 1);
        assertThat(view1.getResultCode(), is(CommandResultCode.SUCCESS));
        assertThat(view2.getResultCode(), is(CommandResultCode.SUCCESS));
        Thread.sleep(1000L);

        List<SingleUserReportResultView.PositionView> aPosition1 = getPosition(UID_16, BNB_USDT_FU);
        List<SingleUserReportResultView.PositionView> aPosition2 = getPosition(UID_17, BNB_USDT_FU);
        long aOpenVolume1 = aPosition1.get(0).getOpenVolume();
        long aOpenVolume2 = aPosition2.get(0).getOpenVolume();

        // 断言检查
        assertThat(aOpenVolume1, is(openVolume1 + 500L));
        assertThat(aOpenVolume2, is(openVolume2 + 500L));
    }

    // 双向持仓
    @Test
    public void testEnableHedgeMode() throws Exception {
        long orderId1 = getInitOrderId(Long.class);
        long orderId2 = orderId1 + 1;

        CommandResultView view1 = exchangeApi.adjustPositionMode(UID_18, PositionMode.HEDGE);
        assertThat(view1.getResultCode(), is(CommandResultCode.SUCCESS));

        // 下单
        CommandResultView view2 = exchangeApi.placeOrder(UID_18, orderId1, BNB_USDT_FU, OrderAction.BID, OrderType.GTC, 850.2, 850.2, 0.5, MarginMode.ISOLATED, 1);
        CommandResultView view3 = exchangeApi.placeOrder(UID_18, orderId2, BNB_USDT_FU, OrderAction.ASK, OrderType.GTC, 900.4, 900.4, 0.5, MarginMode.ISOLATED, 1);
        assertThat(view2.getResultCode(), is(CommandResultCode.SUCCESS));
        assertThat(view3.getResultCode(), is(CommandResultCode.SUCCESS));
        Thread.sleep(1000L);

        List<SingleUserReportResultView.PositionView> aPosition = getPosition(UID_18, BNB_USDT_FU);

        assertThat(aPosition.size(), is(2));
        assertThat(aPosition.get(0).getPendingBuyAvgPrice(), is(850.2));
        assertThat(aPosition.get(0).getDirection(), is(PositionDirection.LONG));
        assertThat(aPosition.get(1).getDirection(), is(PositionDirection.SHORT));
        assertThat(aPosition.get(1).getPendingSellAvgPrice(), is(900.4));
    }

    // 增加保证金
    @Test
    public void testAddExtraMargin() throws Exception {
        long orderId1 = getInitOrderId(Long.class);

        List<SingleUserReportResultView.PositionView> position1 = getPosition(UID_20, BNB_USDT_FU);
        double extraMargin = position1 != null && position1.get(0) != null ? position1.get(0).getExtraMargin() : 0;

        // 下单
        CommandResultView view1 = exchangeApi.placeOrder(UID_20, orderId1, BNB_USDT_FU, OrderAction.BID, OrderType.GTC, 850.2, 855, 0.5, MarginMode.ISOLATED, 1);
        assertThat(view1.getResultCode(), is(CommandResultCode.SUCCESS));

        CommandResultView view2 = exchangeApi.adjustMargin(UID_20, MarginMode.ISOLATED, BNB_USDT_FU, 500);
        assertThat(view2.getResultCode(), is(CommandResultCode.SUCCESS));

        List<SingleUserReportResultView.PositionView> aPosition = getPosition(UID_20, BNB_USDT_FU);
        double aExtraMargin = aPosition.get(0).getExtraMargin();

        // 断言检查
        assertThat(aExtraMargin, is(extraMargin + 50));
    }

}
