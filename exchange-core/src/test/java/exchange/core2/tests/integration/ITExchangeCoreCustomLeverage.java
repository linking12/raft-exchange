package exchange.core2.tests.integration;

import exchange.core2.core.common.*;
import exchange.core2.core.common.api.ApiAdjustUserBalance;
import exchange.core2.core.common.api.reports.TotalCurrencyBalanceReportResult;
import exchange.core2.core.common.cmd.CommandResultCode;
import exchange.core2.core.common.api.ApiPlaceOrder;
import exchange.core2.core.common.config.PerformanceConfiguration;
import exchange.core2.tests.util.ExchangeTestContainer;
import org.junit.Test;

import static exchange.core2.tests.util.TestConstants.*;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.core.Is.is;
import static org.junit.Assert.assertEquals;

public final class ITExchangeCoreCustomLeverage {


    @Test
    public void testCustomLeverageOpenPosition() throws Exception {
        try (final ExchangeTestContainer container = ExchangeTestContainer.create(PerformanceConfiguration.DEFAULT)) {

            CoreSymbolSpecification spec = CoreSymbolSpecification.builder()
                    .symbolId(10001)
                    .type(SymbolType.FUTURES_CONTRACT)
                    .baseCurrency(11).quoteCurrency(12)
                    .marginBuy(1000).marginSell(1000)
                    .feeScaleK(1_000_000)
                    .makerFee(0).takerFee(0)
                    .maxLeverage(50)
                    .build();

            container.addSymbol(spec);
            container.createUserWithMoney(UID_1, spec.quoteCurrency, 10_000);
            container.createUserWithMoney(UID_2, spec.quoteCurrency, 10_000);

            container.submitCommandSync(ApiPlaceOrder.builder()
                    .uid(UID_1)
                    .orderId(10001L)
                    .price(1000)
                    .reservePrice(1000)
                    .size(100)
                    .symbol(spec.symbolId)
                    .action(OrderAction.BID)
                    .orderType(OrderType.GTC)
                    .leverage(10) // 不加杠杆他只能开10手，10倍杠杆才能开出100手
                    .build(), CommandResultCode.SUCCESS);

            container.submitCommandSync(ApiPlaceOrder.builder()
                    .uid(UID_2)
                    .orderId(10002L)
                    .price(1000)
                    .reservePrice(1000)
                    .size(10)
                    .symbol(spec.symbolId)
                    .action(OrderAction.ASK)
                    .orderType(OrderType.IOC)
                    .build(), CommandResultCode.SUCCESS);

            TotalCurrencyBalanceReportResult report = container.totalBalanceReport();

            assertEquals(20_000, report.getAccountBalances().get(spec.quoteCurrency));
            assertEquals(10, report.getOpenInterestLong().get(spec.symbolId));
            assertEquals(10, report.getOpenInterestShort().get(spec.symbolId));

            container.submitCommandSync(ApiPlaceOrder.builder()
                    .uid(UID_1)
                    .orderId(10003L)
                    .price(1000)
                    .reservePrice(1000)
                    .size(1)
                    .symbol(spec.symbolId)
                    .action(OrderAction.BID)
                    .orderType(OrderType.GTC)
                    .leverage(10)
                    .build(), CommandResultCode.RISK_NSF); // 用户1应该1手都开不出来了
        }
    }

    @Test
    public void testRejectInvalidLeverage() throws Exception {
        try (final ExchangeTestContainer container = ExchangeTestContainer.create(PerformanceConfiguration.DEFAULT)) {

            CoreSymbolSpecification spec = CoreSymbolSpecification.builder()
                    .symbolId(10002)
                    .type(SymbolType.FUTURES_CONTRACT)
                    .baseCurrency(11).quoteCurrency(12)
                    .marginBuy(1000).marginSell(1000)
                    .maxLeverage(20)
                    .feeScaleK(1_000_000)
                    .build();

            container.addSymbol(spec);
            container.createUserWithMoney(UID_1, spec.quoteCurrency, 100_000);
            ApiPlaceOrder badOrder = ApiPlaceOrder.builder()
                    .uid(UID_1)
                    .orderId(20001L)
                    .price(1000)
                    .reservePrice(1000)
                    .size(1)
                    .symbol(spec.symbolId)
                    .action(OrderAction.BID)
                    .orderType(OrderType.GTC)
                    .leverage(100) // 超出最大杠杆20倍
                    .build();

            container.submitCommandSync(badOrder, CommandResultCode.RISK_INVALID_LEVERAGE);
        }
    }


    @Test
    public void testLiquidationTriggeredByHighLeverage() throws Exception {
        try (final ExchangeTestContainer container = ExchangeTestContainer.create(PerformanceConfiguration.DEFAULT)) {

            CoreSymbolSpecification spec = CoreSymbolSpecification.builder()
                    .symbolId(10003)
                    .type(SymbolType.FUTURES_CONTRACT)
                    .baseCurrency(11).quoteCurrency(12)
                    .marginBuy(1000).marginSell(1000).maintenanceMargin(50)
                    .feeScaleK(1_000_000)
                    .maxLeverage(50)
                    .build();
            container.addSymbol(spec);
            container.createUserWithMoney(UID_1, spec.quoteCurrency, 1000);
            container.createUserWithMoney(UID_2, spec.quoteCurrency, 100_000);

            container.submitCommandSync(ApiPlaceOrder.builder()
                    .uid(UID_1)
                    .orderId(30001L)
                    .price(1000)
                    .reservePrice(1000)
                    .size(50)
                    .symbol(spec.symbolId)
                    .action(OrderAction.BID)
                    .orderType(OrderType.GTC)
                    .leverage(50) // 50倍开50手
                    .build(), CommandResultCode.SUCCESS);

            container.submitCommandSync(ApiPlaceOrder.builder()
                    .uid(UID_2)
                    .orderId(30002L)
                    .price(1000)
                    .reservePrice(1000)
                    .size(50)
                    .symbol(spec.symbolId)
                    .action(OrderAction.ASK)
                    .orderType(OrderType.GTC)
                    .build(), CommandResultCode.SUCCESS);

            assertEquals(50, container.getUserProfile(UID_1).getPositions().get(spec.symbolId).getOpenVolume());

            // 准备980的买单，用来和强平成交
            container.submitCommandSync(ApiPlaceOrder.builder()
                    .uid(UID_2)
                    .orderId(30003L)
                    .price(980)
                    .reservePrice(990)
                    .size(50)
                    .symbol(spec.symbolId)
                    .action(OrderAction.BID)
                    .orderType(OrderType.GTC)
                    .build(), CommandResultCode.SUCCESS);

            // 模拟价格下跌，跌幅超过19[=(1000-50)/50]要强平了
            container.updateCurrentPriceTo(980, spec.symbolId, spec.quoteCurrency);

            container.getUserProfile(UID_1); // 触发R2做完，再触发强平检查
            container.getExchangeCore().getLiquidationScanner().triggerOnce();

            // 检查用户被强平1手
            container.validateUserState(UID_1, profile -> {
                assertThat(profile.getPositions().get(spec.symbolId).getOpenVolume(), is(49L));
            });
        }

    }

    // 用户提现时需要考虑leverage
    @Test
    public void testCustomLeverageWithdraw() throws Exception {
        try (final ExchangeTestContainer container = ExchangeTestContainer.create(PerformanceConfiguration.DEFAULT)) {

            CoreSymbolSpecification spec = CoreSymbolSpecification.builder()
                    .symbolId(10001)
                    .type(SymbolType.FUTURES_CONTRACT)
                    .baseCurrency(11).quoteCurrency(12)
                    .marginBuy(1000).marginSell(1000)
                    .feeScaleK(100)
                    .makerFee(1).takerFee(2)
                    .maxLeverage(50)
                    .build();

            container.addSymbol(spec);
            container.createUserWithMoney(UID_1, spec.quoteCurrency, 10_000);
            container.createUserWithMoney(UID_2, spec.quoteCurrency, 10_000);

            container.submitCommandSync(ApiPlaceOrder.builder()
                    .uid(UID_1)
                    .orderId(10001L)
                    .price(1000)
                    .reservePrice(1000)
                    .size(100)
                    .symbol(spec.symbolId)
                    .action(OrderAction.BID)
                    .orderType(OrderType.GTC)
                    .leverage(10) // 不加杠杆他只能开10手，10倍杠杆才能开出100手
                    .build(), CommandResultCode.SUCCESS);

            container.validateUserState(UID_1, profile -> {
                assertThat(profile.getPositions().get(spec.symbolId).getOpenVolume(), is(0L));
                assertThat(profile.getPositions().get(spec.symbolId).getDirection(), is(PositionDirection.EMPTY));
                assertThat(profile.getPositions().get(spec.symbolId).getPendingBuySize(), is(100L));
                assertThat(profile.getPositions().get(spec.symbolId).getPendingSellSize(), is(0L));
            });

            // do withdraw
            ApiAdjustUserBalance withdrawCmd = ApiAdjustUserBalance.builder()
                    .uid(UID_1)
                    .transactionId(container.getRandomTransactionId())
                    .amount(-1L)
                    .currency(spec.quoteCurrency)
                    .build();

            container.submitCommandSync(withdrawCmd, CommandResultCode.RISK_NSF);

            container.validateUserState(UID_1, profile -> {
                assertThat(profile.getAccounts().get(spec.quoteCurrency), is(10000L));
            });
        }
    }

    // 下不同leverage的订单
    @Test
    public void testTwoLeverageOrders() throws Exception {
        try (final ExchangeTestContainer container = ExchangeTestContainer.create(PerformanceConfiguration.DEFAULT)) {
            CoreSymbolSpecification spec = container.initSymbol();
            container.createUserWithMoney(UID_1, spec.quoteCurrency, 10_000);
            container.createUserWithMoney(UID_2, spec.quoteCurrency, 10_000);

            // 先下一手10倍的买单
            long size1 = 1;
            long orderId1 = 10001L;
            ApiPlaceOrder order1 = ApiPlaceOrder.builder()
                    .uid(UID_1)
                    .orderId(orderId1)
                    .price(1000)
                    .reservePrice(1000)
                    .size(size1)
                    .symbol(spec.symbolId)
                    .action(OrderAction.BID)
                    .orderType(OrderType.GTC)
                    .leverage(10)
                    .build();

            container.submitCommandSync(order1, CommandResultCode.SUCCESS);

            container.validateUserState(UID_1, profile -> {
                assertThat(profile.getPositions().get(spec.symbolId).getOpenVolume(), is(0L));
                assertThat(profile.getPositions().get(spec.symbolId).getDirection(), is(PositionDirection.EMPTY));
                assertThat(profile.getPositions().get(spec.symbolId).getPendingBuySize(), is(size1));
                assertThat(profile.getPositions().get(spec.symbolId).getPendingSellSize(), is(0L));
            });

            // 再下一手20倍的买单
            long size2 = 5L;
            long orderId2 = 10002L;
            ApiPlaceOrder order2 = ApiPlaceOrder.builder()
                    .uid(UID_1)
                    .orderId(orderId2)
                    .price(1100)
                    .reservePrice(1100)
                    .size(size2)
                    .symbol(spec.symbolId)
                    .action(OrderAction.BID)
                    .orderType(OrderType.GTC)
                    .leverage(20)
                    .build();
            container.submitCommandSync(order2, CommandResultCode.SUCCESS);

            container.validateUserState(UID_1, profile -> {
                assertThat(profile.getPositions().get(spec.symbolId).getOpenVolume(), is(0L));
                assertThat(profile.getPositions().get(spec.symbolId).getDirection(), is(PositionDirection.EMPTY));
                assertThat(profile.getPositions().get(spec.symbolId).getPendingBuySize(), is(size1 + size2));
                assertThat(profile.getPositions().get(spec.symbolId).getPendingSellSize(), is(0L));

                assertThat(profile.getOrders().getFirst().get(0).size, is(size1));
                assertThat(profile.getOrders().getFirst().get(0).orderId, is(order1.orderId));
                assertThat(profile.getOrders().getFirst().get(0).filled, is(0L));

                assertThat(profile.getOrders().getFirst().get(1).size, is(size2));
                assertThat(profile.getOrders().getFirst().get(1).orderId, is(order2.orderId));
                assertThat(profile.getOrders().getFirst().get(1).filled, is(0L));
            });
        }
    }

    // 下不同leverage的订单, check不同订单的leverage符合预期
    @Test
    public void testTwoLeverageOrders2() throws Exception {
        try (final ExchangeTestContainer container = ExchangeTestContainer.create(PerformanceConfiguration.DEFAULT)) {
            CoreSymbolSpecification spec = container.initSymbol();
            container.createUserWithMoney(UID_1, spec.quoteCurrency, 10_000);
            container.createUserWithMoney(UID_2, spec.quoteCurrency, 10_000);

            // 先下一手10倍的买单
            long size1 = 1;
            long orderId1 = 10001L;
            ApiPlaceOrder order1 = ApiPlaceOrder.builder()
                    .uid(UID_1)
                    .orderId(orderId1)
                    .price(1000)
                    .reservePrice(1000)
                    .size(size1)
                    .symbol(spec.symbolId)
                    .action(OrderAction.BID)
                    .orderType(OrderType.GTC)
                    .leverage(1)
                    .build();

            container.submitCommandSync(order1, CommandResultCode.SUCCESS);

            container.validateUserState(UID_1, profile -> {
                assertThat(profile.getPositions().get(spec.symbolId).getOpenVolume(), is(0L));
                assertThat(profile.getPositions().get(spec.symbolId).getDirection(), is(PositionDirection.EMPTY));
                assertThat(profile.getPositions().get(spec.symbolId).getPendingBuySize(), is(size1));
                assertThat(profile.getPositions().get(spec.symbolId).getPendingSellSize(), is(0L));
            });

            // 再下一手20倍的买单
            long size2 = 5L;
            long orderId2 = 10002L;
            ApiPlaceOrder order2 = ApiPlaceOrder.builder()
                    .uid(UID_1)
                    .orderId(orderId2)
                    .price(1100)
                    .reservePrice(1100)
                    .size(size2)
                    .symbol(spec.symbolId)
                    .action(OrderAction.BID)
                    .orderType(OrderType.GTC)
                    .leverage(50)
                    .build();
            container.submitCommandSync(order2, CommandResultCode.SUCCESS);

            container.validateUserState(UID_1, profile -> {
                assertThat(profile.getPositions().get(spec.symbolId).getOpenVolume(), is(0L));
                assertThat(profile.getPositions().get(spec.symbolId).getDirection(), is(PositionDirection.EMPTY));
                assertThat(profile.getPositions().get(spec.symbolId).getPendingBuySize(), is(size1 + size2));
                assertThat(profile.getPositions().get(spec.symbolId).getPendingSellSize(), is(0L));

                assertThat(profile.getOrders().getFirst().get(0).size, is(size1));
                assertThat(profile.getOrders().getFirst().get(0).orderId, is(order1.orderId));
                assertThat(profile.getOrders().getFirst().get(0).filled, is(0L));

                assertThat(profile.getOrders().getFirst().get(1).size, is(size2));
                assertThat(profile.getOrders().getFirst().get(1).orderId, is(order2.orderId));
                assertThat(profile.getOrders().getFirst().get(1).filled, is(0L));
            });

            long size3 = 1L;
            long orderId3 = 10003L;
            ApiPlaceOrder order3 = ApiPlaceOrder.builder()
                    .uid(UID_1)
                    .orderId(orderId3)
                    .price(1200)
                    .reservePrice(1100)
                    .size(size3)
                    .symbol(spec.symbolId)
                    .action(OrderAction.BID)
                    .orderType(OrderType.GTC)
                    .leverage(30)
                    .build();
            container.submitCommandSync(order3, CommandResultCode.SUCCESS);

        }
    }

    // 下不同leverage的订单, orderId相同应该报错, 此时之前下的订单不受影响
    @Test
    public void testTwoLeverageOrdersWithSameOrderId() throws Exception {
        try (final ExchangeTestContainer container = ExchangeTestContainer.create(PerformanceConfiguration.DEFAULT)) {
            CoreSymbolSpecification spec = container.initSymbol();
            long charge = 10000;
            container.createUserWithMoney(UID_1, spec.quoteCurrency, charge);

            // 先下一手10倍的买单
            long size1 = 1;
            long orderId1 = 10001L;
            ApiPlaceOrder order1 = ApiPlaceOrder.builder()
                    .uid(UID_1)
                    .orderId(orderId1)
                    .price(1000)
                    .reservePrice(1000)
                    .size(size1)
                    .symbol(spec.symbolId)
                    .action(OrderAction.BID)
                    .orderType(OrderType.GTC)
                    .leverage(10)
                    .build();

            container.submitCommandSync(order1, CommandResultCode.SUCCESS);

            container.validateUserState(UID_1, profile -> {
                assertThat(profile.getPositions().get(spec.symbolId).getOpenVolume(), is(0L));
                assertThat(profile.getPositions().get(spec.symbolId).getDirection(), is(PositionDirection.EMPTY));
                assertThat(profile.getPositions().get(spec.symbolId).getPendingBuySize(), is(size1));
                assertThat(profile.getPositions().get(spec.symbolId).getPendingSellSize(), is(0L));
            });

            // 再下一手20倍的买单, 在process order时会因为duplicate id报错
            long size2 = 5L;
            long orderId2 = orderId1;
            ApiPlaceOrder order2 = ApiPlaceOrder.builder()
                    .uid(UID_1)
                    .orderId(orderId2)
                    .price(1100)
                    .reservePrice(1100)
                    .size(size2)
                    .symbol(spec.symbolId)
                    .action(OrderAction.BID)
                    .orderType(OrderType.GTC)
                    .leverage(20)
                    .build();
            container.submitCommandSync(order2, CommandResultCode.SUCCESS);

            container.validateUserState(UID_1, profile -> {
                assertThat(profile.getAccounts().get(spec.quoteCurrency), is(charge));
                assertThat(profile.getPositions().get(spec.symbolId).getOpenVolume(), is(0L));
                assertThat(profile.getPositions().get(spec.symbolId).getDirection(), is(PositionDirection.EMPTY));
                assertThat(profile.getPositions().get(spec.symbolId).getPendingBuySize(), is(size1));
                assertThat(profile.getPositions().get(spec.symbolId).getPendingSellSize(), is(0L));

                assertThat(profile.getOrders().getFirst().size(), is(1));
            });
        }
    }


}
