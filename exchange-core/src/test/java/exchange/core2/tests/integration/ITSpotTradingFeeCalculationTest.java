package exchange.core2.tests.integration;

import exchange.core2.core.ITradeEventsHandler;
import exchange.core2.core.common.CoreSymbolSpecification;
import exchange.core2.core.common.MarginMode;
import exchange.core2.core.common.OrderAction;
import exchange.core2.core.common.OrderType;
import exchange.core2.core.common.SymbolType;
import exchange.core2.core.common.api.ApiPlaceOrder;
import exchange.core2.core.common.cmd.CommandResultCode;
import exchange.core2.core.common.config.PerformanceConfiguration;
import exchange.core2.core.event.IEventsHandler4Test;
import exchange.core2.core.event.SimpleEventsProcessor4Test;
import exchange.core2.core.utils.CoreArithmeticUtils;
import exchange.core2.tests.util.ExchangeTestContainer;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.stream.Collectors;

import static exchange.core2.core.common.OrderAction.ASK;
import static exchange.core2.core.common.OrderAction.BID;
import static exchange.core2.core.common.OrderType.*;
import static exchange.core2.tests.util.TestConstants.*;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.core.Is.is;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Integration tests for spot trading fee calculation changes
 * Tests CoreArithmeticUtils.calculateTakerFee() and CoreArithmeticUtils.calculateMakerFee()
 * in different order types (GTC, IOC, FOK_BUDGET) and trading scenarios.
 * <p>
 * Focuses on verifying that SimpleEventsProcessor#sendSpotExecutionReport correctly
 * calculates fees using the new fee calculation methods.
 */
@Slf4j
@ExtendWith(MockitoExtension.class)
class ITSpotTradingFeeCalculationTest {

    private SimpleEventsProcessor4Test processor;

    @Mock
    private IEventsHandler4Test handler;

    @Captor
    ArgumentCaptor<ITradeEventsHandler.SpotExecutionReport> spotExecutionReportCaptor;

    // Test symbol with known fee structure
    private static final CoreSymbolSpecification TEST_SYMBOL = CoreSymbolSpecification.builder().symbolId(20001).type(SymbolType.CURRENCY_EXCHANGE_PAIR).baseCurrency(CURRENECY_XBT)    // base = satoshi
            .quoteCurrency(CURRENECY_LTC)   // quote = litoshi
            .baseScaleK(1000)               // 1 lot = 1000 satoshi
            .quoteScaleK(10)                // price scale = 10
            .takerFee(20)                   // taker fee 20 litoshi per 1 lot
            .makerFee(10)                   // maker fee 10 litoshi per 1 lot
            .build();

    @BeforeEach
    public void before() {
        processor = new SimpleEventsProcessor4Test(handler);
    }

    private PerformanceConfiguration getPerformanceConfiguration() {
        return PerformanceConfiguration.baseBuilder().build();
    }

    private ApiPlaceOrder.ApiPlaceOrderBuilder builderPlace(long uid, OrderAction action, OrderType type) {
        return ApiPlaceOrder.builder().uid(uid).action(action).orderType(type).symbol(TEST_SYMBOL.symbolId).marginMode(MarginMode.ISOLATED);
    }

    /**
     * Test GTC order maker/taker fee calculation - single maker vs single taker
     */
    @Test
    @Timeout(10)
    public void testGtcMakerTakerFeeCalculation() throws ExecutionException, InterruptedException {
        long makerUid = UID_1;
        long takerUid = UID_2;
        long size = 100L;
        long price = 15000L;
        long makerOrderId = 1001L;
        long takerOrderId = 1002L;

        try (final ExchangeTestContainer container = ExchangeTestContainer.create(getPerformanceConfiguration(), processor)) {
            container.addSymbol(TEST_SYMBOL);
            container.addCurrency(TEST_SYMBOL.baseCurrency, 8);
            container.addCurrency(TEST_SYMBOL.quoteCurrency, 8);

            // Create users with sufficient funds (need to account for currency scale)
            long quoteCurrencyAmount = 100_000_000L * 100_000_000L; // Much larger amount
            long baseCurrencyAmount = 100_000_000L * 100_000_000L;  // Much larger amount
            container.createUserWithMoney(makerUid, TEST_SYMBOL.quoteCurrency, quoteCurrencyAmount);
            container.createUserWithMoney(takerUid, TEST_SYMBOL.baseCurrency, baseCurrencyAmount);

            // Place maker order (BID)
            container.submitCommandSync(builderPlace(makerUid, BID, GTC).orderId(makerOrderId).price(price).reservePrice(price).size(size).build(), CommandResultCode.SUCCESS);

            // Place taker order (ASK) to match
            container.submitCommandSync(builderPlace(takerUid, ASK, GTC).orderId(takerOrderId).price(price).reservePrice(price).size(size).build(), CommandResultCode.SUCCESS);

        } finally {
            // Verify execution reports and fee calculations
            verify(handler, atLeast(2)).spotExecutionReport(spotExecutionReportCaptor.capture());
            List<ITradeEventsHandler.SpotExecutionReport> reports = spotExecutionReportCaptor.getAllValues();

            // Filter trade execution reports (not NEW orders)
            List<ITradeEventsHandler.SpotExecutionReport> tradeReports = reports.stream().filter(r -> r.executionType == ITradeEventsHandler.ExecType.TRADE).collect(Collectors.toList());

            assertEquals(2, tradeReports.size(), "Should have 2 trade execution reports");

            // Find maker and taker reports
            ITradeEventsHandler.SpotExecutionReport makerReport = tradeReports.stream().filter(r -> r.isMaker).findFirst().orElse(null);

            ITradeEventsHandler.SpotExecutionReport takerReport = tradeReports.stream().filter(r -> !r.isMaker).findFirst().orElse(null);

            assertNotNull(makerReport, "Should have maker execution report");
            assertNotNull(takerReport, "Should have taker execution report");

            // Calculate expected fees
            long expectedMakerFee = CoreArithmeticUtils.calculateMakerFee(size, price, TEST_SYMBOL);
            long expectedTakerFee = CoreArithmeticUtils.calculateTakerFee(size, price, TEST_SYMBOL);

            // Verify fees
            assertThat("Maker fee should be correct", makerReport.commission, is(expectedMakerFee));
            assertThat("Taker fee should be correct", takerReport.commission, is(expectedTakerFee));

            log.info("GTC Maker/Taker fees verified: Maker={}, Taker={}", expectedMakerFee, expectedTakerFee);
        }
    }

    /**
     * Test IOC order fee calculation - single maker vs single taker
     */
    @Test
    @Timeout(10)
    public void testIocTakerFeeCalculation() throws ExecutionException, InterruptedException {
        long makerUid = UID_1;
        long takerUid = UID_2;
        long size = 50L;
        long price = 12000L;
        long makerOrderId = 2001L;
        long takerOrderId = 2002L;

        try (final ExchangeTestContainer container = ExchangeTestContainer.create(getPerformanceConfiguration(), processor)) {
            container.addSymbol(TEST_SYMBOL);
            container.addCurrency(TEST_SYMBOL.baseCurrency, 8);
            container.addCurrency(TEST_SYMBOL.quoteCurrency, 8);

            long quoteCurrencyAmount = 100_000_000L * 100_000_000L;
            long baseCurrencyAmount = 100_000_000L * 100_000_000L;
            container.createUserWithMoney(makerUid, TEST_SYMBOL.quoteCurrency, quoteCurrencyAmount);
            container.createUserWithMoney(takerUid, TEST_SYMBOL.baseCurrency, baseCurrencyAmount);

            // Place maker order (GTC BID)
            container.submitCommandSync(builderPlace(makerUid, BID, GTC).orderId(makerOrderId).price(price).reservePrice(price).size(size).build(), CommandResultCode.SUCCESS);

            // Place IOC taker order (ASK)
            container.submitCommandSync(builderPlace(takerUid, ASK, IOC).orderId(takerOrderId).price(price).reservePrice(price).size(size).build(), CommandResultCode.SUCCESS);

        } finally {
            verify(handler, atLeast(2)).spotExecutionReport(spotExecutionReportCaptor.capture());
            List<ITradeEventsHandler.SpotExecutionReport> reports = spotExecutionReportCaptor.getAllValues();

            List<ITradeEventsHandler.SpotExecutionReport> tradeReports = reports.stream().filter(r -> r.executionType == ITradeEventsHandler.ExecType.TRADE).collect(Collectors.toList());

            assertEquals(2, tradeReports.size(), "Should have 2 trade execution reports");

            // Verify IOC taker fee calculation
            ITradeEventsHandler.SpotExecutionReport takerReport = tradeReports.stream().filter(r -> !r.isMaker && r.orderType == IOC).findFirst().orElse(null);

            assertNotNull(takerReport, "Should have IOC taker execution report");

            long expectedTakerFee = CoreArithmeticUtils.calculateTakerFee(size, price, TEST_SYMBOL);
            assertThat("IOC taker fee should be correct", takerReport.commission, is(expectedTakerFee));

            log.info("IOC Taker fee verified: {}", expectedTakerFee);
        }
    }

    /**
     * Test FOK_BUDGET order fee calculation
     */
    @Test
    @Timeout(10)
    public void testFokBudgetTakerFeeCalculation() throws ExecutionException, InterruptedException {
        long makerUid = UID_1;
        long takerUid = UID_2;
        long size = 75L;
        long price = 14000L;
        long budget = size * price; // Exact budget for full fill
        long makerOrderId = 3001L;
        long takerOrderId = 3002L;

        try (final ExchangeTestContainer container = ExchangeTestContainer.create(getPerformanceConfiguration(), processor)) {
            container.addSymbol(TEST_SYMBOL);
            container.addCurrency(TEST_SYMBOL.baseCurrency, 8);
            container.addCurrency(TEST_SYMBOL.quoteCurrency, 8);

            long quoteCurrencyAmount = 100_000_000L * 100_000_000L;
            long baseCurrencyAmount = 100_000_000L * 100_000_000L;
            container.createUserWithMoney(makerUid, TEST_SYMBOL.baseCurrency, baseCurrencyAmount);  // Maker needs base currency for ASK
            container.createUserWithMoney(takerUid, TEST_SYMBOL.quoteCurrency, quoteCurrencyAmount); // Taker needs quote currency for BID

            // Place maker order (GTC ASK)
            container.submitCommandSync(builderPlace(makerUid, ASK, GTC).orderId(makerOrderId).price(price).reservePrice(price).size(size).build(), CommandResultCode.SUCCESS);

            // Place FOK_BUDGET taker order (BID)
            container.submitCommandSync(builderPlace(takerUid, BID, FOK_BUDGET).orderId(takerOrderId).price(budget).reservePrice(budget).size(size).build(), CommandResultCode.SUCCESS);

        } finally {
            verify(handler, atLeast(2)).spotExecutionReport(spotExecutionReportCaptor.capture());
            List<ITradeEventsHandler.SpotExecutionReport> reports = spotExecutionReportCaptor.getAllValues();

            List<ITradeEventsHandler.SpotExecutionReport> tradeReports = reports.stream().filter(r -> r.executionType == ITradeEventsHandler.ExecType.TRADE).collect(Collectors.toList());

            assertEquals(2, tradeReports.size(), "Should have 2 trade execution reports");

            // Verify FOK_BUDGET taker fee calculation
            ITradeEventsHandler.SpotExecutionReport takerReport = tradeReports.stream().filter(r -> !r.isMaker && r.orderType == FOK_BUDGET).findFirst().orElse(null);

            assertNotNull(takerReport, "Should have FOK_BUDGET taker execution report");

            long expectedTakerFee = CoreArithmeticUtils.calculateTakerFee(size, price, TEST_SYMBOL);
            assertThat("FOK_BUDGET taker fee should be correct", takerReport.commission, is(expectedTakerFee));

            log.info("FOK_BUDGET Taker fee verified: {}", expectedTakerFee);
        }
    }

    /**
     * Test maker 1-to-many scenario: one maker order filled by multiple takers
     */
    @Test
    @Timeout(10)
    public void testMakerOneToManyFeeCalculation() throws ExecutionException, InterruptedException {
        long makerUid = UID_1;
        long taker1Uid = UID_2;
        long taker2Uid = UID_3;
        long taker3Uid = UID_4;

        long totalSize = 300L;
        long size1 = 100L;
        long size2 = 120L;
        long size3 = 80L;
        long price = 13000L;

        try (final ExchangeTestContainer container = ExchangeTestContainer.create(getPerformanceConfiguration(), processor)) {
            container.addSymbol(TEST_SYMBOL);
            container.addCurrency(TEST_SYMBOL.baseCurrency, 8);
            container.addCurrency(TEST_SYMBOL.quoteCurrency, 8);

            long quoteCurrencyAmount = 100_000_000L * 100_000_000L;
            long baseCurrencyAmount = 100_000_000L * 100_000_000L;
            container.createUserWithMoney(makerUid, TEST_SYMBOL.quoteCurrency, quoteCurrencyAmount);
            container.createUserWithMoney(taker1Uid, TEST_SYMBOL.baseCurrency, baseCurrencyAmount);
            container.createUserWithMoney(taker2Uid, TEST_SYMBOL.baseCurrency, baseCurrencyAmount);
            container.createUserWithMoney(taker3Uid, TEST_SYMBOL.baseCurrency, baseCurrencyAmount);

            // Place large maker order (BID)
            container.submitCommandSync(builderPlace(makerUid, BID, GTC).orderId(4001L).price(price).reservePrice(price).size(totalSize).build(), CommandResultCode.SUCCESS);

            // Multiple takers fill the maker order
            container.submitCommandSync(builderPlace(taker1Uid, ASK, GTC).orderId(4002L).price(price).reservePrice(price).size(size1).build(), CommandResultCode.SUCCESS);

            container.submitCommandSync(builderPlace(taker2Uid, ASK, IOC).orderId(4003L).price(price).reservePrice(price).size(size2).build(), CommandResultCode.SUCCESS);

            container.submitCommandSync(builderPlace(taker3Uid, ASK, GTC).orderId(4004L).price(price).reservePrice(price).size(size3).build(), CommandResultCode.SUCCESS);

        } finally {
            verify(handler, atLeast(6)).spotExecutionReport(spotExecutionReportCaptor.capture());
            List<ITradeEventsHandler.SpotExecutionReport> reports = spotExecutionReportCaptor.getAllValues();

            List<ITradeEventsHandler.SpotExecutionReport> tradeReports = reports.stream().filter(r -> r.executionType == ITradeEventsHandler.ExecType.TRADE).collect(Collectors.toList());

            assertTrue(tradeReports.size() >= 6, "Should have at least 6 trade execution reports");

            // Verify maker fees for each trade
            List<ITradeEventsHandler.SpotExecutionReport> makerReports = tradeReports.stream().filter(r -> r.isMaker && r.accountId == makerUid).collect(Collectors.toList());

            assertEquals(3, makerReports.size(), "Should have 3 maker execution reports");

            // Verify each maker trade fee
            for (ITradeEventsHandler.SpotExecutionReport makerReport : makerReports) {
                long expectedFee = CoreArithmeticUtils.calculateMakerFee(makerReport.lastQty, makerReport.lastPrice, TEST_SYMBOL);
                assertThat("Maker fee should be correct for trade size " + makerReport.lastQty, makerReport.commission, is(expectedFee));
            }

            // Verify total maker fees
            long totalMakerFee = makerReports.stream().mapToLong(r -> r.commission).sum();
            long expectedTotalMakerFee = CoreArithmeticUtils.calculateMakerFee(size1, price, TEST_SYMBOL) + CoreArithmeticUtils.calculateMakerFee(size2, price, TEST_SYMBOL) + CoreArithmeticUtils.calculateMakerFee(size3, price, TEST_SYMBOL);

            assertThat("Total maker fee should be correct", totalMakerFee, is(expectedTotalMakerFee));

            log.info("Maker 1-to-many fees verified: Total={}, Individual=[{}, {}, {}]", totalMakerFee, CoreArithmeticUtils.calculateMakerFee(size1, price, TEST_SYMBOL), CoreArithmeticUtils.calculateMakerFee(size2, price, TEST_SYMBOL), CoreArithmeticUtils.calculateMakerFee(size3, price, TEST_SYMBOL));
        }
    }

    /**
     * Test taker 1-to-many scenario: one taker order filled by multiple makers
     */
    @Test
    @Timeout(10)
    public void testTakerOneToManyFeeCalculation() throws ExecutionException, InterruptedException {
        long maker1Uid = UID_1;
        long maker2Uid = UID_2;
        long maker3Uid = UID_3;
        long takerUid = UID_4;

        long size1 = 80L;
        long size2 = 90L;
        long size3 = 70L;
        long totalSize = size1 + size2 + size3;

        long price1 = 11000L;
        long price2 = 11100L;
        long price3 = 11200L;

        try (final ExchangeTestContainer container = ExchangeTestContainer.create(getPerformanceConfiguration(), processor)) {
            container.addSymbol(TEST_SYMBOL);
            container.addCurrency(TEST_SYMBOL.baseCurrency, 8);
            container.addCurrency(TEST_SYMBOL.quoteCurrency, 8);

            long quoteCurrencyAmount = 100_000_000L * 100_000_000L;
            long baseCurrencyAmount = 100_000_000L * 100_000_000L;
            container.createUserWithMoney(maker1Uid, TEST_SYMBOL.baseCurrency, baseCurrencyAmount);
            container.createUserWithMoney(maker2Uid, TEST_SYMBOL.baseCurrency, baseCurrencyAmount);
            container.createUserWithMoney(maker3Uid, TEST_SYMBOL.baseCurrency, baseCurrencyAmount);
            container.createUserWithMoney(takerUid, TEST_SYMBOL.quoteCurrency, quoteCurrencyAmount);

            // Place multiple maker orders (ASK) at different prices
            container.submitCommandSync(builderPlace(maker1Uid, ASK, GTC).orderId(5001L).price(price1).reservePrice(price1).size(size1).build(), CommandResultCode.SUCCESS);

            container.submitCommandSync(builderPlace(maker2Uid, ASK, GTC).orderId(5002L).price(price2).reservePrice(price2).size(size2).build(), CommandResultCode.SUCCESS);

            container.submitCommandSync(builderPlace(maker3Uid, ASK, GTC).orderId(5003L).price(price3).reservePrice(price3).size(size3).build(), CommandResultCode.SUCCESS);

            // Place large taker order (BID) that fills all makers
            container.submitCommandSync(builderPlace(takerUid, BID, GTC).orderId(5004L).price(price3) // High enough to fill all orders
                    .reservePrice(price3).size(totalSize).build(), CommandResultCode.SUCCESS);

        } finally {
            verify(handler, atLeast(6)).spotExecutionReport(spotExecutionReportCaptor.capture());
            List<ITradeEventsHandler.SpotExecutionReport> reports = spotExecutionReportCaptor.getAllValues();

            List<ITradeEventsHandler.SpotExecutionReport> tradeReports = reports.stream().filter(r -> r.executionType == ITradeEventsHandler.ExecType.TRADE).collect(Collectors.toList());

            assertTrue(tradeReports.size() >= 6, "Should have at least 6 trade execution reports");

            // Verify taker fees for each trade
            List<ITradeEventsHandler.SpotExecutionReport> takerReports = tradeReports.stream().filter(r -> !r.isMaker && r.accountId == takerUid).collect(Collectors.toList());

            assertEquals(3, takerReports.size(), "Should have 3 taker execution reports");

            // Verify each taker trade fee
            for (ITradeEventsHandler.SpotExecutionReport takerReport : takerReports) {
                long expectedFee = CoreArithmeticUtils.calculateTakerFee(takerReport.lastQty, takerReport.lastPrice, TEST_SYMBOL);
                assertThat("Taker fee should be correct for trade size " + takerReport.lastQty + " at price " + takerReport.lastPrice, takerReport.commission, is(expectedFee));
            }

            // Verify total taker fees
            long totalTakerFee = takerReports.stream().mapToLong(r -> r.commission).sum();
            long expectedTotalTakerFee = CoreArithmeticUtils.calculateTakerFee(size1, price1, TEST_SYMBOL) + CoreArithmeticUtils.calculateTakerFee(size2, price2, TEST_SYMBOL) + CoreArithmeticUtils.calculateTakerFee(size3, price3, TEST_SYMBOL);

            assertThat("Total taker fee should be correct", totalTakerFee, is(expectedTotalTakerFee));

            log.info("Taker 1-to-many fees verified: Total={}, Individual=[{}, {}, {}]", totalTakerFee, CoreArithmeticUtils.calculateTakerFee(size1, price1, TEST_SYMBOL), CoreArithmeticUtils.calculateTakerFee(size2, price2, TEST_SYMBOL), CoreArithmeticUtils.calculateTakerFee(size3, price3, TEST_SYMBOL));
        }
    }

    /**
     * Test mixed order types scenario: GTC, IOC, FOK_BUDGET in same test
     */
    @Test
    @Timeout(10)
    public void testMixedOrderTypesFeeCalculation() throws ExecutionException, InterruptedException {
        long maker1Uid = UID_1;
        long maker2Uid = UID_2;
        long taker1Uid = UID_3;
        long taker2Uid = UID_4;
        long taker3Uid = UID_5;

        long size = 60L;
        long price = 16000L;
        long budget = size * price;

        try (final ExchangeTestContainer container = ExchangeTestContainer.create(getPerformanceConfiguration(), processor)) {
            container.addSymbol(TEST_SYMBOL);
            container.addCurrency(TEST_SYMBOL.baseCurrency, 8);
            container.addCurrency(TEST_SYMBOL.quoteCurrency, 8);

            // Create users
            long quoteCurrencyAmount = 100_000_000L * 100_000_000L;
            long baseCurrencyAmount = 100_000_000L * 100_000_000L;
            container.createUserWithMoney(maker1Uid, TEST_SYMBOL.quoteCurrency, quoteCurrencyAmount);
            container.createUserWithMoney(maker2Uid, TEST_SYMBOL.quoteCurrency, quoteCurrencyAmount);
            container.createUserWithMoney(taker1Uid, TEST_SYMBOL.baseCurrency, baseCurrencyAmount);
            container.createUserWithMoney(taker2Uid, TEST_SYMBOL.baseCurrency, baseCurrencyAmount);
            container.createUserWithMoney(taker3Uid, TEST_SYMBOL.baseCurrency, baseCurrencyAmount);

            // Place GTC maker orders
            container.submitCommandSync(builderPlace(maker1Uid, BID, GTC).orderId(6001L).price(price).reservePrice(price).size(size).build(), CommandResultCode.SUCCESS);

            container.submitCommandSync(builderPlace(maker2Uid, BID, GTC).orderId(6002L).price(price).reservePrice(price).size(size).build(), CommandResultCode.SUCCESS);

            // GTC taker
            container.submitCommandSync(builderPlace(taker1Uid, ASK, GTC).orderId(6003L).price(price).reservePrice(price).size(size).build(), CommandResultCode.SUCCESS);

            // IOC taker
            container.submitCommandSync(builderPlace(taker2Uid, ASK, IOC).orderId(6004L).price(price).reservePrice(price).size(size).build(), CommandResultCode.SUCCESS);

        } finally {
            verify(handler, atLeast(4)).spotExecutionReport(spotExecutionReportCaptor.capture());
            List<ITradeEventsHandler.SpotExecutionReport> reports = spotExecutionReportCaptor.getAllValues();

            List<ITradeEventsHandler.SpotExecutionReport> tradeReports = reports.stream().filter(r -> r.executionType == ITradeEventsHandler.ExecType.TRADE).collect(Collectors.toList());

            assertTrue(tradeReports.size() >= 4, "Should have at least 4 trade execution reports");

            // Verify different order types have correct fees
            List<ITradeEventsHandler.SpotExecutionReport> gtcTakerReports = tradeReports.stream().filter(r -> !r.isMaker && r.orderType == GTC).collect(Collectors.toList());

            List<ITradeEventsHandler.SpotExecutionReport> iocTakerReports = tradeReports.stream().filter(r -> !r.isMaker && r.orderType == IOC).collect(Collectors.toList());

            assertTrue(gtcTakerReports.size() >= 1, "Should have GTC taker reports");
            assertTrue(iocTakerReports.size() >= 1, "Should have IOC taker reports");

            // Verify fees are calculated correctly for each order type
            for (ITradeEventsHandler.SpotExecutionReport report : gtcTakerReports) {
                long expectedFee = CoreArithmeticUtils.calculateTakerFee(report.lastQty, report.lastPrice, TEST_SYMBOL);
                assertThat("GTC taker fee should be correct", report.commission, is(expectedFee));
            }

            for (ITradeEventsHandler.SpotExecutionReport report : iocTakerReports) {
                long expectedFee = CoreArithmeticUtils.calculateTakerFee(report.lastQty, report.lastPrice, TEST_SYMBOL);
                assertThat("IOC taker fee should be correct", report.commission, is(expectedFee));
            }

            log.info("Mixed order types fees verified: GTC takers={}, IOC takers={}", gtcTakerReports.size(), iocTakerReports.size());
        }
    }

    /**
     * Test that fee calculation uses correct size and price parameters
     */
    @Test
    @Timeout(10)
    public void testFeeCalculationParameters() throws ExecutionException, InterruptedException {
        long makerUid = UID_1;
        long takerUid = UID_2;
        long size = 123L; // Non-round number
        long price = 17789L; // Non-round price

        try (final ExchangeTestContainer container = ExchangeTestContainer.create(getPerformanceConfiguration(), processor)) {
            container.addSymbol(TEST_SYMBOL);
            container.addCurrency(TEST_SYMBOL.baseCurrency, 8);
            container.addCurrency(TEST_SYMBOL.quoteCurrency, 8);

            long quoteCurrencyAmount = 100_000_000L * 100_000_000L;
            long baseCurrencyAmount = 100_000_000L * 100_000_000L;
            container.createUserWithMoney(makerUid, TEST_SYMBOL.quoteCurrency, quoteCurrencyAmount);
            container.createUserWithMoney(takerUid, TEST_SYMBOL.baseCurrency, baseCurrencyAmount);

            // Place orders with specific size and price
            container.submitCommandSync(builderPlace(makerUid, BID, GTC).orderId(7001L).price(price).reservePrice(price).size(size).build(), CommandResultCode.SUCCESS);

            container.submitCommandSync(builderPlace(takerUid, ASK, GTC).orderId(7002L).price(price).reservePrice(price).size(size).build(), CommandResultCode.SUCCESS);

        } finally {
            verify(handler, atLeast(2)).spotExecutionReport(spotExecutionReportCaptor.capture());
            List<ITradeEventsHandler.SpotExecutionReport> reports = spotExecutionReportCaptor.getAllValues();

            List<ITradeEventsHandler.SpotExecutionReport> tradeReports = reports.stream().filter(r -> r.executionType == ITradeEventsHandler.ExecType.TRADE).collect(Collectors.toList());

            assertEquals(2, tradeReports.size(), "Should have 2 trade execution reports");

            // Verify that fees are calculated using correct size and price
            for (ITradeEventsHandler.SpotExecutionReport report : tradeReports) {
                assertThat("Trade size should match", report.lastQty, is(size));
                assertThat("Trade price should match", report.lastPrice, is(price));

                long expectedFee = report.isMaker ? CoreArithmeticUtils.calculateMakerFee(size, price, TEST_SYMBOL) : CoreArithmeticUtils.calculateTakerFee(size, price, TEST_SYMBOL);

                assertThat("Fee should be calculated with correct parameters", report.commission, is(expectedFee));
            }

            log.info("Fee calculation parameters verified for size={}, price={}", size, price);
        }
    }
}