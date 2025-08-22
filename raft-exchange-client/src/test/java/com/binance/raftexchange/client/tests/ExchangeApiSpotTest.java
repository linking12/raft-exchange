package com.binance.raftexchange.client.tests;

import com.binance.raftexchange.client.CommandResultView;
import com.binance.raftexchange.client.ExchangeApi;
import com.binance.raftexchange.client.SingleUserReportResultView;
import com.binance.raftexchange.stubs.CoreCurrencySpecification;
import com.binance.raftexchange.stubs.OrderAction;
import com.binance.raftexchange.stubs.OrderType;
import com.binance.raftexchange.stubs.SymbolType;
import com.binance.raftexchange.stubs.response.CommandResultCode;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import static com.binance.raftexchange.client.ExchangeApiHelper.buildSlotValueMap;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.core.Is.is;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;

public class ExchangeApiSpotTest extends BaseTest {

    // 提现
    @Test
    public void testWithdraw() throws Exception {
        long orderId = getInitOrderId(Long.class);
        double usdtWithdraw = -30.32;

        double balance = getBalance(UID_6, USDT_ID);
        exchangeApi.adjustUserBalance(UID_6, orderId, USDT_ID, usdtWithdraw);

        double balance2 = getBalance(UID_6, USDT_ID);
        assertThat(balance2, is(balance + usdtWithdraw));
    }

    // 下单但是没有成交
    @Test
    public void testPlaceOrder() throws Exception {

        long orderId = getInitOrderId(Long.class);

        // 先算出来初始资金以及order数量
        SingleUserReportResultView queryU1 = exchangeApi.queryUserReport(UID_1).join();
        double initUsdtBalance = queryU1.getAccounts().get(USDT_ID);
        int initU1OrderNum = queryU1.getOrders() != null && queryU1.getOrders().get(BNB_USDT_SPOT) != null ? queryU1.getOrders().get(BNB_USDT_SPOT).size() : 0;
        SingleUserReportResultView queryU2 = exchangeApi.queryUserReport(UID_2).join();
        double initBnbBalance = queryU2.getAccounts().get(BNB_ID);
        int initU2OrderNum = queryU2.getOrders() != null && queryU2.getOrders().get(BNB_USDT_SPOT) != null ? queryU2.getOrders().get(BNB_USDT_SPOT).size() : 0;

        // 提交订单应该成功
        CommandResultView result1 = exchangeApi.placeOrder(UID_1, orderId++, BNB_USDT_SPOT, OrderAction.BID, OrderType.GTC, 850.2, 855, 0.5, null, 0);
        CommandResultView result2 = exchangeApi.placeOrder(UID_2, orderId++, BNB_USDT_SPOT, OrderAction.ASK, OrderType.GTC, 852, 0, 1, null, 0);

        assertThat(result1.getResultCode(), is(CommandResultCode.SUCCESS));
        assertThat(result2.getResultCode(), is(CommandResultCode.SUCCESS));
        Thread.sleep(1000);

        // 拿到当前资金
        SingleUserReportResultView reportU1 = exchangeApi.queryUserReport(UID_1).get();
        SingleUserReportResultView reportU2 = exchangeApi.queryUserReport(UID_2).get();

        // 未成单，占用资金 0.5 * 855 * 1.015 = 433.9125
        // 使用 BigDecimal 进行精确计算
        BigDecimal initUsdtBalanceBigDecimal = new BigDecimal(String.valueOf(initUsdtBalance));
        BigDecimal quantity = new BigDecimal("0.5");
        BigDecimal price = new BigDecimal("855");
        BigDecimal feeRate = new BigDecimal("1.015");
        BigDecimal frozenAmount = quantity.multiply(price).multiply(feeRate);
        BigDecimal expectedUsdtBalance = initUsdtBalanceBigDecimal.subtract(frozenAmount);

        assertThat(reportU1.getAccounts().get(USDT_ID), is(expectedUsdtBalance.doubleValue()));
        assertThat(reportU2.getAccounts().get(BNB_ID), is(initBnbBalance - 1));
        assertThat(reportU1.getOrders().get(BNB_USDT_SPOT).size(), is(initU1OrderNum + 1));
        assertThat(reportU2.getOrders().get(BNB_USDT_SPOT).size(), is(initU2OrderNum + 1));
    }

    // 测试取消订单
    @Test
    public void testCancelOrder() throws Exception {

        int orderId = getInitOrderId(Integer.class);

        double price = 100.2;
        CommandResultView view1 = exchangeApi.placeOrder(UID_5, orderId, BNB_USDT_SPOT, OrderAction.BID, OrderType.GTC, price, 1100, 0.5, null, 0);
        assertThat(view1.getResultCode(), is(CommandResultCode.SUCCESS));

        SingleUserReportResultView.OrderView order1 = getOrder(UID_5, BNB_USDT_SPOT, orderId);
        assertThat(order1 != null, is(true));
        assertThat(order1.getPrice(), is(price));

        CommandResultView view2 = exchangeApi.cancelOrder(UID_5, orderId, BNB_USDT_SPOT);
        assertThat(view2.getResultCode(), is(CommandResultCode.SUCCESS));

        SingleUserReportResultView.OrderView order2 = getOrder(UID_5, BNB_USDT_SPOT, orderId);
        assertThat(order2 == null, is(true));
    }

    @Test
    public void testMoveOrder() throws Exception {

        int orderId = getInitOrderId(Integer.class);

        double price1 = 850.2;
        double price2 = 851;
        CommandResultView view1 = exchangeApi.placeOrder(UID_5, orderId, BNB_USDT_SPOT, OrderAction.BID, OrderType.GTC, price1, 855, 0.5, null, 0);
        assertThat(view1.getResultCode(), is(CommandResultCode.SUCCESS));

        SingleUserReportResultView.OrderView order1 = getOrder(UID_5, BNB_USDT_SPOT, orderId);
        assertThat(order1.getPrice(), is(price1));

        CommandResultView view2 = exchangeApi.moveOrder(UID_5, orderId, BNB_USDT_SPOT, price2);
        assertThat(view2.getResultCode(), is(CommandResultCode.SUCCESS));

        SingleUserReportResultView.OrderView order2 = getOrder(UID_5, BNB_USDT_SPOT, orderId);
        assertThat(order2.getPrice(), is(price2));
    }

    // 订单成交, check fee
    @Test
    public void testCheckFee() throws Exception {
        long orderId1 = getInitOrderId(Long.class);
        long orderId2 = orderId1 + 1;

        // before
        // U3 下单 850.2 买入 0.5 BNB
        // U4 下单 850 卖出 0.5 BNB
        double usdt1 = getBalance(UID_3, USDT_ID);
        double bnb1 = getBalance(UID_3, BNB_ID);
        double usdt2 = getBalance(UID_4, USDT_ID);
        double bnb2 = getBalance(UID_4, BNB_ID);

        // 下单
        CommandResultView view1 = exchangeApi.placeOrder(UID_3, orderId1, BNB_USDT_SPOT, OrderAction.BID, OrderType.GTC, 850.2, 855, 0.5, null, 0);
        CommandResultView view2 = exchangeApi.placeOrder(UID_4, orderId2, BNB_USDT_SPOT, OrderAction.ASK, OrderType.GTC, 850, 0, 0.5, null, 0);
        assertThat(view1.getResultCode(), is(CommandResultCode.SUCCESS));
        assertThat(view2.getResultCode(), is(CommandResultCode.SUCCESS));

        Thread.sleep(2000);
        // U3 成交 850.2 买入 0.5 BNB
        // U4 成交 850.2 卖出 0.5 BNB
        double aUsdt1 = getBalance(UID_3, USDT_ID);
        double aBnb1 = getBalance(UID_3, BNB_ID);
        double aUsdt2 = getBalance(UID_4, USDT_ID);
        double aBnb2 = getBalance(UID_4, BNB_ID);

        // do check
        BigDecimal quantity = new BigDecimal("0.5");
        BigDecimal price = new BigDecimal("850.2");
        BigDecimal makerFeeRate = new BigDecimal("0.01"); // 10/1000
        BigDecimal takerFeeRate = new BigDecimal("0.015"); // 15/1000
        BigDecimal usdt1BigDecimal = new BigDecimal(String.valueOf(usdt1));
        BigDecimal bnb1BigDecimal = new BigDecimal(String.valueOf(bnb1));
        BigDecimal usdt2BigDecimal = new BigDecimal(String.valueOf(usdt2));
        BigDecimal bnb2BigDecimal = new BigDecimal(String.valueOf(bnb2));

        // 计算 makerFee 和 takerFee
        BigDecimal makerFee = quantity.multiply(price).multiply(makerFeeRate); // 0.5 * 850.2 * 0.01
        BigDecimal takerFee = quantity.multiply(price).multiply(takerFeeRate); // 0.5 * 850.2 * 0.015

        // 计算预期余额
        BigDecimal expectedUsdt1 = usdt1BigDecimal.subtract(quantity.multiply(price)).subtract(makerFee); // usdt1 - 0.5 * 850.2 - makerFee
        BigDecimal expectedBnb1 = bnb1BigDecimal.add(quantity); // bnb1 + 0.5
        BigDecimal expectedUsdt2 = usdt2BigDecimal.add(quantity.multiply(price)).subtract(takerFee); // usdt2 + 0.5 * 850.2 - takerFee
        BigDecimal expectedBnb2 = bnb2BigDecimal.subtract(quantity); // bnb2 - 0.5

        // 断言检查
        assertThat(aUsdt1, is(expectedUsdt1.doubleValue()));
        assertThat(aBnb1, is(expectedBnb1.doubleValue()));
        assertThat(aUsdt2, is(expectedUsdt2.doubleValue()));
        assertThat(aBnb2, is(expectedBnb2.doubleValue()));
    }
}
