package exchange.core2.tests.integration;

import exchange.core2.core.common.CoreSymbolSpecification;
import exchange.core2.core.common.OrderAction;
import exchange.core2.core.common.OrderType;
import exchange.core2.core.common.SymbolType;
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
}
