package exchange.core2.tests.integration;

import exchange.core2.core.ITradeEventsHandler;
import exchange.core2.core.common.CoreSymbolSpecification;
import exchange.core2.core.common.MarginMode;
import exchange.core2.core.common.Order;
import exchange.core2.core.common.OrderAction;
import exchange.core2.core.common.OrderType;
import exchange.core2.core.common.PositionDirection;
import exchange.core2.core.common.SymbolPositionRecord;
import exchange.core2.core.common.SymbolType;
import exchange.core2.core.common.api.ApiPlaceOrder;
import exchange.core2.core.common.api.reports.SingleUserReportResult;
import exchange.core2.core.common.api.reports.TotalCurrencyBalanceReportResult;
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
 * Integration tests for futures trading fee calculation with dynamic fees
 * Tests CoreArithmeticUtils.calculateTakerFee() and CoreArithmeticUtils.calculateMakerFee()
 * in different futures trading scenarios with position opening/closing logic.
 * <p>
 * Focuses on verifying that SimpleEventsProcessor#sendFuturesExecutionReport correctly
 * calculates fees using dynamic fee calculation methods for futures contracts.
 */
@Slf4j
@ExtendWith(MockitoExtension.class)
class ITFuturesTradingFeeCalculationTest {

    private SimpleEventsProcessor4Test processor;

    @Mock
    private IEventsHandler4Test handler;

    @Captor
    ArgumentCaptor<ITradeEventsHandler.FuturesExecutionReport> futuresExecutionReportCaptor;

    // Use actual symbol from initFutureSymbols - BTC symbol (fixed fees)
    private static final CoreSymbolSpecification BTC_SYMBOL = CoreSymbolSpecification.builder()
            .symbolId(10000)
            .type(SymbolType.FUTURES_CONTRACT_PERPETUAL)
            .baseCurrency(CURRENECY_XBT)
            .quoteCurrency(CURRENECY_USD)
            .baseScaleK(1)
            .quoteScaleK(1)
            .makerFee(10)
            .takerFee(20)
            .build();

    // ETH symbol with dynamic fees
    private static final CoreSymbolSpecification ETH_SYMBOL = CoreSymbolSpecification.builder()
            .symbolId(10001)
            .type(SymbolType.FUTURES_CONTRACT_PERPETUAL)
            .baseCurrency(CURRENECY_ETH)
            .quoteCurrency(CURRENECY_USD)
            .baseScaleK(1)
            .quoteScaleK(1)
            .makerFee(1)
            .takerFee(2)
            .feeScaleK(100)
            .build();

    @BeforeEach
    public void before() {
        processor = new SimpleEventsProcessor4Test(handler, true);
    }

    private PerformanceConfiguration getPerformanceConfiguration() {
        return PerformanceConfiguration.baseBuilder().build();
    }

    /**
     * Test GTC order maker/taker fee calculation for futures - position opening
     */
    @Test
    @Timeout(10)
    public void testFuturesGtcMakerTakerFeeCalculation() throws ExecutionException, InterruptedException {
        long makerUid = UID_1;
        long takerUid = UID_2;
        long size = 10L;        // 10 contracts
        long price = 50000L;    // $50,000 per XBT
        long makerOrderId = 1001L;
        long takerOrderId = 1002L;

        try (final ExchangeTestContainer container = ExchangeTestContainer.create(getPerformanceConfiguration(), processor)) {
            List<CoreSymbolSpecification> symbols = container.initFutureSymbols();
            symbols.forEach(s -> container.initMarkPrice(s.symbolId, price));

            // Create users with sufficient funds for futures trading
            long deposit = 100000L;
            container.createUserWithSpecificMoney(makerUid, deposit, CURRENECY_USD);
            container.createUserWithSpecificMoney(takerUid, deposit, CURRENECY_USD);

            // Use container helper methods for futures orders
            container.createBidWithOrderId(makerOrderId, makerUid, (int) size, price, symbols.get(0).symbolId, MarginMode.CROSS);
            container.createAskWithOrderId(takerOrderId, takerUid, (int) size, price, symbols.get(0).symbolId, MarginMode.CROSS);

            // Verify positions opened correctly
            container.validateUserState(makerUid, profile -> {
                SingleUserReportResult.Position position = profile.getPositions().get(symbols.get(0).symbolId).get(0);
                assertThat("Maker should have LONG position", position.getDirection(), is(PositionDirection.LONG));
                assertThat("Maker position size should match", position.getOpenVolume(), is(size));
            });

            container.validateUserState(takerUid, profile -> {
                SingleUserReportResult.Position position = profile.getPositions().get(symbols.get(0).symbolId).get(0);
                assertThat("Taker should have SHORT position", position.getDirection(), is(PositionDirection.SHORT));
                assertThat("Taker position size should match", position.getOpenVolume(), is(size));
            });

        } finally {
            // Verify execution reports and fee calculations
            verify(handler, atLeast(2)).futuresExecutionReport(futuresExecutionReportCaptor.capture());
            List<ITradeEventsHandler.FuturesExecutionReport> reports = futuresExecutionReportCaptor.getAllValues();

            // Filter trade execution reports (not NEW orders)
            List<ITradeEventsHandler.FuturesExecutionReport> tradeReports = reports.stream()
                    .filter(r -> r.executionType == ITradeEventsHandler.ExecType.TRADE)
                    .collect(Collectors.toList());

            assertEquals(2, tradeReports.size(), "Should have 2 trade execution reports");

            // Find maker and taker reports
            ITradeEventsHandler.FuturesExecutionReport makerReport = tradeReports.stream()
                    .filter(r -> r.isMaker)
                    .findFirst()
                    .orElse(null);

            ITradeEventsHandler.FuturesExecutionReport takerReport = tradeReports.stream()
                    .filter(r -> !r.isMaker)
                    .findFirst()
                    .orElse(null);

            assertNotNull(makerReport, "Should have maker execution report");
            assertNotNull(takerReport, "Should have taker execution report");

            // Calculate expected fees using BTC symbol (fixed fees)
            long expectedMakerFee = CoreArithmeticUtils.calculateMakerFee(size, price, BTC_SYMBOL);
            long expectedTakerFee = CoreArithmeticUtils.calculateTakerFee(size, price, BTC_SYMBOL);

            // Verify fees
            assertThat("Maker fee should be correct", makerReport.fee, is(expectedMakerFee));
            assertThat("Taker fee should be correct", takerReport.fee, is(expectedTakerFee));

            log.info("Futures GTC Maker/Taker fees verified: Maker={}, Taker={}", expectedMakerFee, expectedTakerFee);
        }
    }

    /**
     * Test IOC order fee calculation for futures
     */
    @Test
    @Timeout(10)
    public void testFuturesIocTakerFeeCalculation() throws ExecutionException, InterruptedException {
        long makerUid = UID_1;
        long takerUid = UID_2;
        long size = 5L;
        long price = 45000L;
        long makerOrderId = 2001L;
        long takerOrderId = 2002L;

        try (final ExchangeTestContainer container = ExchangeTestContainer.create(getPerformanceConfiguration(), processor)) {
            List<CoreSymbolSpecification> symbols = container.initFutureSymbols();
            symbols.forEach(s -> container.initMarkPrice(s.symbolId, price));

            long deposit = 100000L;
            container.createUserWithSpecificMoney(makerUid, deposit, CURRENECY_USD);
            container.createUserWithSpecificMoney(takerUid, deposit, CURRENECY_USD);

            // Place maker order (GTC BID)
            container.createBidWithOrderId(makerOrderId, makerUid, (int) size, price, symbols.get(0).symbolId, MarginMode.CROSS);

            // Place IOC taker order (ASK) - need to use direct command for IOC
            ApiPlaceOrder iocOrder = ApiPlaceOrder.builder()
                    .uid(takerUid)
                    .orderId(takerOrderId)
                    .price(price)
                    .reservePrice(price)
                    .size(size)
                    .action(ASK)
                    .orderType(IOC)
                    .symbol(symbols.get(0).symbolId)
                    .marginMode(MarginMode.CROSS)
                    .build();
            container.submitCommandSync(iocOrder, CommandResultCode.SUCCESS);

        } finally {
            verify(handler, atLeast(2)).futuresExecutionReport(futuresExecutionReportCaptor.capture());
            List<ITradeEventsHandler.FuturesExecutionReport> reports = futuresExecutionReportCaptor.getAllValues();

            List<ITradeEventsHandler.FuturesExecutionReport> tradeReports = reports.stream()
                    .filter(r -> r.executionType == ITradeEventsHandler.ExecType.TRADE)
                    .collect(Collectors.toList());

            assertEquals(2, tradeReports.size(), "Should have 2 trade execution reports");

            // Verify IOC taker fee calculation
            ITradeEventsHandler.FuturesExecutionReport takerReport = tradeReports.stream()
                    .filter(r -> !r.isMaker && r.orderType == IOC)
                    .findFirst()
                    .orElse(null);

            assertNotNull(takerReport, "Should have IOC taker execution report");

            long expectedTakerFee = CoreArithmeticUtils.calculateTakerFee(size, price, BTC_SYMBOL);
            assertThat("IOC taker fee should be correct", takerReport.fee, is(expectedTakerFee));

            log.info("Futures IOC Taker fee verified: {}", expectedTakerFee);
        }
    }

    /**
     * Test position closing scenarios - fees only charged on opening portions
     */
    @Test
    @Timeout(10)
    public void testFuturesPositionClosingFeeCalculation() throws ExecutionException, InterruptedException {
        long makerUid = UID_1;
        long takerUid = UID_2;
        long openSize = 20L;
        long closeSize = 10L;   // Partial close
        long price = 48000L;

        try (final ExchangeTestContainer container = ExchangeTestContainer.create(getPerformanceConfiguration(), processor)) {
            List<CoreSymbolSpecification> symbols = container.initFutureSymbols();
            symbols.forEach(s -> container.initMarkPrice(s.symbolId, price));

            long deposit = 100000L;
            container.createUserWithSpecificMoney(makerUid, deposit, CURRENECY_USD);
            container.createUserWithSpecificMoney(takerUid, deposit, CURRENECY_USD);
            container.createUserWithSpecificMoney(UID_3, deposit, CURRENECY_USD);

            // Step 1: Open positions
            container.createBidWithOrderId(3001L, makerUid, (int) openSize, price, symbols.get(0).symbolId, MarginMode.CROSS);
            container.createAskWithOrderId(3002L, takerUid, (int) openSize, price, symbols.get(0).symbolId, MarginMode.CROSS);

            // Clear previous reports
            reset(handler);

            // Step 2: Partial close (takerUid goes from SHORT to smaller SHORT)
            container.createBidWithOrderId(3003L, takerUid, (int) closeSize, price, symbols.get(0).symbolId, MarginMode.CROSS);
            // Need another user to take the opposite side
            container.createAskWithOrderId(3004L, UID_3, (int) closeSize, price, symbols.get(0).symbolId, MarginMode.CROSS);

        } finally {
            // Verify closing trade fees
            verify(handler, atLeast(2)).futuresExecutionReport(futuresExecutionReportCaptor.capture());
            List<ITradeEventsHandler.FuturesExecutionReport> reports = futuresExecutionReportCaptor.getAllValues();

            List<ITradeEventsHandler.FuturesExecutionReport> tradeReports = reports.stream()
                    .filter(r -> r.executionType == ITradeEventsHandler.ExecType.TRADE)
                    .collect(Collectors.toList());

            assertTrue(tradeReports.size() >= 2, "Should have at least 2 trade execution reports for closing");

            // The closing portion should have reduced/no fees, opening portion should have full fees
            ITradeEventsHandler.FuturesExecutionReport makerReport = tradeReports.stream()
                    .filter(r -> r.isMaker)
                    .findFirst()
                    .orElse(null);

            assertThat("close order should charge no fee", makerReport.fee, is(0L));

            // UID_3 opens new position (should pay full fee), takerUid closes position (should pay no fee for closing portion)
            ITradeEventsHandler.FuturesExecutionReport takerReport = tradeReports.stream()
                    .filter(r -> !r.isMaker)
                    .findFirst()
                    .orElse(null);

            assertNotNull(takerReport, "Should have IOC taker execution report");

            long expectedTakerFee = CoreArithmeticUtils.calculateTakerFee(closeSize, price, BTC_SYMBOL);
            assertThat("IOC taker fee should be correct", takerReport.fee, is(expectedTakerFee));

            log.info("Futures position closing fee calculation verified with {} reports", tradeReports.size());
        }
    }

    /**
     * Test dynamic fee calculation with different fee rates
     */
    @Test
    @Timeout(10)
    public void testDynamicFeeCalculationAccuracy() {
        // Test various fee calculation scenarios
        long size = 15L;
        long price = 50000L;

        // Use existing dynamic fee symbol
        CoreSymbolSpecification dynamicSymbol = SYMBOLSPEC_DYNAMIC_FEE_XBT_USD;

        // Calculate expected fees with dynamic fee formula: (size * price * feeRate) / feeScaleK
        long expectedMakerFee = (size * price * dynamicSymbol.makerFee) / dynamicSymbol.feeScaleK;
        long expectedTakerFee = (size * price * dynamicSymbol.takerFee) / dynamicSymbol.feeScaleK;

        // Test actual calculation
        long actualMakerFee = CoreArithmeticUtils.calculateMakerFee(size, price, dynamicSymbol);
        long actualTakerFee = CoreArithmeticUtils.calculateTakerFee(size, price, dynamicSymbol);

        assertThat("Maker fee calculation should match formula", actualMakerFee, is(expectedMakerFee));
        assertThat("Taker fee calculation should match formula", actualTakerFee, is(expectedTakerFee));

        // Test with different sizes
        long[] testSizes = {1L, 5L, 10L, 100L, 1000L};
        for (long testSize : testSizes) {
            long expectedMaker = (testSize * price * dynamicSymbol.makerFee) / dynamicSymbol.feeScaleK;
            long expectedTaker = (testSize * price * dynamicSymbol.takerFee) / dynamicSymbol.feeScaleK;

            long actualMaker = CoreArithmeticUtils.calculateMakerFee(testSize, price, dynamicSymbol);
            long actualTaker = CoreArithmeticUtils.calculateTakerFee(testSize, price, dynamicSymbol);

            assertThat("Maker fee for size " + testSize, actualMaker, is(expectedMaker));
            assertThat("Taker fee for size " + testSize, actualTaker, is(expectedTaker));
        }

        log.info("Dynamic fee calculation accuracy verified: Maker={}% ({}), Taker={}% ({})",
                (double) dynamicSymbol.makerFee / dynamicSymbol.feeScaleK * 100,
                actualMakerFee,
                (double) dynamicSymbol.takerFee / dynamicSymbol.feeScaleK * 100,
                actualTakerFee);
    }

    /**
     * Test actual dynamic fee trading with ETH futures symbol (has feeScaleK)
     */
    @Test
    @Timeout(10)
    public void testDynamicFeeTradingWithEthSymbol() throws ExecutionException, InterruptedException {
        long makerUid = UID_1;
        long takerUid = UID_2;
        long size = 5L;
        long price = 3000L; // ETH price
        long makerOrderId = 7001L;
        long takerOrderId = 7002L;

        try (final ExchangeTestContainer container = ExchangeTestContainer.create(getPerformanceConfiguration(), processor)) {
            List<CoreSymbolSpecification> symbols = container.initFutureSymbols();
            // Use the second symbol (ETH) which has feeScaleK=100 for dynamic fees
            CoreSymbolSpecification ethSymbol = symbols.get(1);
            container.initMarkPrice(ethSymbol.symbolId, price);

            long deposit = 100000L;
            container.createUserWithSpecificMoney(makerUid, deposit, CURRENECY_USD);
            container.createUserWithSpecificMoney(takerUid, deposit, CURRENECY_USD);

            // Use ETH symbol for trading
            container.createBidWithOrderId(makerOrderId, makerUid, (int) size, price, ethSymbol.symbolId, MarginMode.CROSS);
            container.createAskWithOrderId(takerOrderId, takerUid, (int) size, price, ethSymbol.symbolId, MarginMode.CROSS);

        } finally {
            verify(handler, atLeast(2)).futuresExecutionReport(futuresExecutionReportCaptor.capture());
            List<ITradeEventsHandler.FuturesExecutionReport> reports = futuresExecutionReportCaptor.getAllValues();

            List<ITradeEventsHandler.FuturesExecutionReport> tradeReports = reports.stream()
                    .filter(r -> r.executionType == ITradeEventsHandler.ExecType.TRADE)
                    .collect(Collectors.toList());

            assertEquals(2, tradeReports.size(), "Should have 2 trade execution reports");

            // Find maker and taker reports
            ITradeEventsHandler.FuturesExecutionReport makerReport = tradeReports.stream()
                    .filter(r -> r.isMaker)
                    .findFirst()
                    .orElse(null);

            ITradeEventsHandler.FuturesExecutionReport takerReport = tradeReports.stream()
                    .filter(r -> !r.isMaker)
                    .findFirst()
                    .orElse(null);

            assertNotNull(makerReport, "Should have maker execution report");
            assertNotNull(takerReport, "Should have taker execution report");

            // ETH symbol with dynamic fees: makerFee=1, takerFee=2, feeScaleK=100 (1%, 2%)
            long expectedMakerFee = CoreArithmeticUtils.calculateMakerFee(size, price, ETH_SYMBOL);
            long expectedTakerFee = CoreArithmeticUtils.calculateTakerFee(size, price, ETH_SYMBOL);

            assertThat("Dynamic maker fee should be correct", makerReport.fee, is(expectedMakerFee));
            assertThat("Dynamic taker fee should be correct", takerReport.fee, is(expectedTakerFee));

            log.info("Dynamic fee trading verified: Maker={}% ({}), Taker={}% ({})",
                    1.0, // 1/100 * 100 = 1%
                    expectedMakerFee,
                    2.0, // 2/100 * 100 = 2%
                    expectedTakerFee);
        }
    }

    /**
     * Test maker 1-to-many scenario for futures
     */
    @Test
    @Timeout(10)
    public void testFuturesMakerOneToManyFeeCalculation() throws ExecutionException, InterruptedException {
        long makerUid = UID_1;
        long taker1Uid = UID_2;
        long taker2Uid = UID_3;
        long taker3Uid = UID_4;

        long totalSize = 30L;
        long size1 = 10L;
        long size2 = 12L;
        long size3 = 8L;
        long price = 52000L;

        try (final ExchangeTestContainer container = ExchangeTestContainer.create(getPerformanceConfiguration(), processor)) {
            List<CoreSymbolSpecification> symbols = container.initFutureSymbols();
            symbols.forEach(s -> container.initMarkPrice(s.symbolId, price));

            long deposit = 100000L;
            container.createUserWithSpecificMoney(makerUid, deposit, CURRENECY_USD);
            container.createUserWithSpecificMoney(taker1Uid, deposit, CURRENECY_USD);
            container.createUserWithSpecificMoney(taker2Uid, deposit, CURRENECY_USD);
            container.createUserWithSpecificMoney(taker3Uid, deposit, CURRENECY_USD);

            // Place large maker order (BID)
            container.createBidWithOrderId(4001L, makerUid, (int) totalSize, price, symbols.get(0).symbolId, MarginMode.CROSS);

            // Multiple takers fill the maker order
            container.createAskWithOrderId(4002L, taker1Uid, (int) size1, price, symbols.get(0).symbolId, MarginMode.CROSS);

            // IOC order needs special handling
            ApiPlaceOrder iocOrder2 = ApiPlaceOrder.builder()
                    .uid(taker2Uid)
                    .orderId(4003L)
                    .price(price)
                    .reservePrice(price)
                    .size(size2)
                    .action(ASK)
                    .orderType(IOC)
                    .symbol(symbols.get(0).symbolId)
                    .marginMode(MarginMode.CROSS)
                    .build();
            container.submitCommandSync(iocOrder2, CommandResultCode.SUCCESS);

            container.createAskWithOrderId(4004L, taker3Uid, (int) size3, price, symbols.get(0).symbolId, MarginMode.CROSS);

        } finally {
            verify(handler, atLeast(6)).futuresExecutionReport(futuresExecutionReportCaptor.capture());
            List<ITradeEventsHandler.FuturesExecutionReport> reports = futuresExecutionReportCaptor.getAllValues();

            List<ITradeEventsHandler.FuturesExecutionReport> tradeReports = reports.stream()
                    .filter(r -> r.executionType == ITradeEventsHandler.ExecType.TRADE)
                    .collect(Collectors.toList());

            assertTrue(tradeReports.size() >= 6, "Should have at least 6 trade execution reports");

            // Verify maker fees for each trade
            List<ITradeEventsHandler.FuturesExecutionReport> makerReports = tradeReports.stream()
                    .filter(r -> r.isMaker && r.userId == makerUid)
                    .collect(Collectors.toList());

            assertEquals(3, makerReports.size(), "Should have 3 maker execution reports");

            // Verify each maker trade fee
            for (ITradeEventsHandler.FuturesExecutionReport makerReport : makerReports) {
                long expectedFee = CoreArithmeticUtils.calculateMakerFee(makerReport.lastQty, makerReport.lastPx, BTC_SYMBOL);
                assertThat("Maker fee should be correct for trade size " + makerReport.lastQty, makerReport.fee, is(expectedFee));
            }

            // Verify total maker fees
            long totalMakerFee = makerReports.stream().mapToLong(r -> r.fee).sum();
            long expectedTotalMakerFee = CoreArithmeticUtils.calculateMakerFee(size1, price, BTC_SYMBOL) +
                    CoreArithmeticUtils.calculateMakerFee(size2, price, BTC_SYMBOL) +
                    CoreArithmeticUtils.calculateMakerFee(size3, price, BTC_SYMBOL);

            assertThat("Total maker fee should be correct", totalMakerFee, is(expectedTotalMakerFee));

            log.info("Futures Maker 1-to-many fees verified: Total={}, Individual=[{}, {}, {}]",
                    totalMakerFee,
                    CoreArithmeticUtils.calculateMakerFee(size1, price, BTC_SYMBOL),
                    CoreArithmeticUtils.calculateMakerFee(size2, price, BTC_SYMBOL),
                    CoreArithmeticUtils.calculateMakerFee(size3, price, BTC_SYMBOL));
        }
    }

    /**
     * Test taker 1-to-many scenario for futures with different prices
     */
    @Test
    @Timeout(10)
    public void testFuturesTakerOneToManyFeeCalculation() throws ExecutionException, InterruptedException {
        long maker1Uid = UID_1;
        long maker2Uid = UID_2;
        long maker3Uid = UID_3;
        long takerUid = UID_4;

        long size1 = 8L;
        long size2 = 9L;
        long size3 = 7L;
        long totalSize = size1 + size2 + size3;

        long price1 = 49000L;
        long price2 = 49100L;
        long price3 = 49200L;

        try (final ExchangeTestContainer container = ExchangeTestContainer.create(getPerformanceConfiguration(), processor)) {
            List<CoreSymbolSpecification> symbols = container.initFutureSymbols();
            symbols.forEach(s -> container.initMarkPrice(s.symbolId, price1));

            long deposit = 100000L;
            container.createUserWithSpecificMoney(maker1Uid, deposit, CURRENECY_USD);
            container.createUserWithSpecificMoney(maker2Uid, deposit, CURRENECY_USD);
            container.createUserWithSpecificMoney(maker3Uid, deposit, CURRENECY_USD);
            container.createUserWithSpecificMoney(takerUid, deposit, CURRENECY_USD);

            // Place multiple maker orders (ASK) at different prices
            container.createAskWithOrderId(5001L, maker1Uid, (int) size1, price1, symbols.get(0).symbolId, MarginMode.CROSS);
            container.createAskWithOrderId(5002L, maker2Uid, (int) size2, price2, symbols.get(0).symbolId, MarginMode.CROSS);
            container.createAskWithOrderId(5003L, maker3Uid, (int) size3, price3, symbols.get(0).symbolId, MarginMode.CROSS);

            // Place large taker order (BID) that fills all makers
            container.createBidWithOrderId(5004L, takerUid, (int) totalSize, price3, symbols.get(0).symbolId, MarginMode.CROSS);

        } finally {
            verify(handler, atLeast(6)).futuresExecutionReport(futuresExecutionReportCaptor.capture());
            List<ITradeEventsHandler.FuturesExecutionReport> reports = futuresExecutionReportCaptor.getAllValues();

            List<ITradeEventsHandler.FuturesExecutionReport> tradeReports = reports.stream()
                    .filter(r -> r.executionType == ITradeEventsHandler.ExecType.TRADE)
                    .collect(Collectors.toList());

            assertTrue(tradeReports.size() >= 6, "Should have at least 6 trade execution reports");

            // Verify taker fees for each trade
            List<ITradeEventsHandler.FuturesExecutionReport> takerReports = tradeReports.stream()
                    .filter(r -> !r.isMaker && r.userId == takerUid)
                    .collect(Collectors.toList());

            assertEquals(3, takerReports.size(), "Should have 3 taker execution reports");

            // Verify each taker trade fee
            for (ITradeEventsHandler.FuturesExecutionReport takerReport : takerReports) {
                long expectedFee = CoreArithmeticUtils.calculateTakerFee(takerReport.lastQty, takerReport.lastPx, BTC_SYMBOL);
                assertThat("Taker fee should be correct for trade size " + takerReport.lastQty + " at price " + takerReport.lastPx,
                        takerReport.fee, is(expectedFee));
            }

            // Verify total taker fees
            long totalTakerFee = takerReports.stream().mapToLong(r -> r.fee).sum();
            long expectedTotalTakerFee = CoreArithmeticUtils.calculateTakerFee(size1, price1, BTC_SYMBOL) +
                    CoreArithmeticUtils.calculateTakerFee(size2, price2, BTC_SYMBOL) +
                    CoreArithmeticUtils.calculateTakerFee(size3, price3, BTC_SYMBOL);

            assertThat("Total taker fee should be correct", totalTakerFee, is(expectedTotalTakerFee));

            log.info("Futures Taker 1-to-many fees verified: Total={}, Individual=[{}, {}, {}]",
                    totalTakerFee,
                    CoreArithmeticUtils.calculateTakerFee(size1, price1, BTC_SYMBOL),
                    CoreArithmeticUtils.calculateTakerFee(size2, price2, BTC_SYMBOL),
                    CoreArithmeticUtils.calculateTakerFee(size3, price3, BTC_SYMBOL));
        }
    }

    /**
     * Test mixed order types with futures trading
     */
    @Test
    @Timeout(10)
    public void testFuturesMixedOrderTypesFeeCalculation() throws ExecutionException, InterruptedException {
        long maker1Uid = UID_1;
        long maker2Uid = UID_2;
        long taker1Uid = UID_3;
        long taker2Uid = UID_4;

        long size = 6L;
        long price = 51000L;

        try (final ExchangeTestContainer container = ExchangeTestContainer.create(getPerformanceConfiguration(), processor)) {
            List<CoreSymbolSpecification> symbols = container.initFutureSymbols();
            symbols.forEach(s -> container.initMarkPrice(s.symbolId, price));

            long deposit = 100000L;
            container.createUserWithSpecificMoney(maker1Uid, deposit, CURRENECY_USD);
            container.createUserWithSpecificMoney(maker2Uid, deposit, CURRENECY_USD);
            container.createUserWithSpecificMoney(taker1Uid, deposit, CURRENECY_USD);
            container.createUserWithSpecificMoney(taker2Uid, deposit, CURRENECY_USD);

            // Place GTC maker orders
            container.createBidWithOrderId(6001L, maker1Uid, (int) size, price, symbols.get(0).symbolId, MarginMode.CROSS);
            container.createBidWithOrderId(6002L, maker2Uid, (int) size, price, symbols.get(0).symbolId, MarginMode.CROSS);

            // GTC taker
            container.createAskWithOrderId(6003L, taker1Uid, (int) size, price, symbols.get(0).symbolId, MarginMode.CROSS);

            // IOC taker
            ApiPlaceOrder iocOrder = ApiPlaceOrder.builder()
                    .uid(taker2Uid)
                    .orderId(6004L)
                    .price(price)
                    .reservePrice(price)
                    .size(size)
                    .action(ASK)
                    .orderType(IOC)
                    .symbol(symbols.get(0).symbolId)
                    .marginMode(MarginMode.CROSS)
                    .build();
            container.submitCommandSync(iocOrder, CommandResultCode.SUCCESS);

        } finally {
            verify(handler, atLeast(4)).futuresExecutionReport(futuresExecutionReportCaptor.capture());
            List<ITradeEventsHandler.FuturesExecutionReport> reports = futuresExecutionReportCaptor.getAllValues();

            List<ITradeEventsHandler.FuturesExecutionReport> tradeReports = reports.stream()
                    .filter(r -> r.executionType == ITradeEventsHandler.ExecType.TRADE)
                    .collect(Collectors.toList());

            assertTrue(tradeReports.size() >= 4, "Should have at least 4 trade execution reports");

            // Verify different order types have correct fees
            List<ITradeEventsHandler.FuturesExecutionReport> gtcTakerReports = tradeReports.stream()
                    .filter(r -> !r.isMaker && r.orderType == GTC)
                    .collect(Collectors.toList());

            List<ITradeEventsHandler.FuturesExecutionReport> iocTakerReports = tradeReports.stream()
                    .filter(r -> !r.isMaker && r.orderType == IOC)
                    .collect(Collectors.toList());

            assertTrue(gtcTakerReports.size() >= 1, "Should have GTC taker reports");
            assertTrue(iocTakerReports.size() >= 1, "Should have IOC taker reports");

            // Verify fees are calculated correctly for each order type
            for (ITradeEventsHandler.FuturesExecutionReport report : gtcTakerReports) {
                long expectedFee = CoreArithmeticUtils.calculateTakerFee(report.lastQty, report.lastPx, BTC_SYMBOL);
                assertThat("GTC taker fee should be correct", report.fee, is(expectedFee));
            }

            for (ITradeEventsHandler.FuturesExecutionReport report : iocTakerReports) {
                long expectedFee = CoreArithmeticUtils.calculateTakerFee(report.lastQty, report.lastPx, BTC_SYMBOL);
                assertThat("IOC taker fee should be correct", report.fee, is(expectedFee));
            }

            log.info("Futures mixed order types fees verified: GTC takers={}, IOC takers={}", gtcTakerReports.size(), iocTakerReports.size());
        }
    }

    /**
     * Test FOK_BUDGET order fee calculation for futures
     */
    @Test
    @Timeout(10)
    public void testFuturesFokBudgetTakerFeeCalculation() throws ExecutionException, InterruptedException {
        long makerUid = UID_1;
        long takerUid = UID_2;
        long size = 8L;
        long price = 46000L;
        long budget = size * price; // Exact budget for full fill
        long makerOrderId = 7001L;
        long takerOrderId = 7002L;

        try (final ExchangeTestContainer container = ExchangeTestContainer.create(getPerformanceConfiguration(), processor)) {
            List<CoreSymbolSpecification> symbols = container.initFutureSymbols();
            symbols.forEach(s -> container.initMarkPrice(s.symbolId, price));

            long deposit = 100000L;
            container.createUserWithSpecificMoney(makerUid, deposit, CURRENECY_USD);
            container.createUserWithSpecificMoney(takerUid, deposit, CURRENECY_USD);

            // Place maker order (GTC ASK)
            container.createAskWithOrderId(makerOrderId, makerUid, (int) size, price, symbols.get(0).symbolId, MarginMode.CROSS);

            // Place FOK_BUDGET taker order (BID)
            ApiPlaceOrder fokBudgetOrder = ApiPlaceOrder.builder()
                    .uid(takerUid)
                    .orderId(takerOrderId)
                    .price(budget)
                    .reservePrice(budget)
                    .size(size)
                    .action(BID)
                    .orderType(FOK_BUDGET)
                    .symbol(symbols.get(0).symbolId)
                    .marginMode(MarginMode.CROSS)
                    .build();
            container.submitCommandSync(fokBudgetOrder, CommandResultCode.SUCCESS);

        } finally {
            verify(handler, atLeast(2)).futuresExecutionReport(futuresExecutionReportCaptor.capture());
            List<ITradeEventsHandler.FuturesExecutionReport> reports = futuresExecutionReportCaptor.getAllValues();

            List<ITradeEventsHandler.FuturesExecutionReport> tradeReports = reports.stream()
                    .filter(r -> r.executionType == ITradeEventsHandler.ExecType.TRADE)
                    .collect(Collectors.toList());

            assertEquals(2, tradeReports.size(), "Should have 2 trade execution reports");

            // Verify FOK_BUDGET taker fee calculation
            ITradeEventsHandler.FuturesExecutionReport takerReport = tradeReports.stream()
                    .filter(r -> !r.isMaker && r.orderType == FOK_BUDGET)
                    .findFirst()
                    .orElse(null);

            assertNotNull(takerReport, "Should have FOK_BUDGET taker execution report");

            long expectedTakerFee = CoreArithmeticUtils.calculateTakerFee(size, price, BTC_SYMBOL);
            assertThat("FOK_BUDGET taker fee should be correct", takerReport.fee, is(expectedTakerFee));

            log.info("Futures FOK_BUDGET Taker fee verified: {}", expectedTakerFee);
        }
    }

    /**
     * Test that futures fee calculation uses correct size and price parameters
     */
    @Test
    @Timeout(10)
    public void testFuturesFeeCalculationParameters() throws ExecutionException, InterruptedException {
        long makerUid = UID_1;
        long takerUid = UID_2;
        long size = 13L; // Non-round number
        long price = 47123L; // Non-round price
        long globalFeesCollected = 0;

        try (final ExchangeTestContainer container = ExchangeTestContainer.create(getPerformanceConfiguration(), processor)) {
            List<CoreSymbolSpecification> symbols = container.initFutureSymbols();
            symbols.forEach(s -> container.initMarkPrice(s.symbolId, price));

            long deposit = 100000L;
            container.createUserWithSpecificMoney(makerUid, deposit, CURRENECY_USD);
            container.createUserWithSpecificMoney(takerUid, deposit, CURRENECY_USD);

            // Place orders with specific size and price
            container.createBidWithOrderId(8001L, makerUid, (int) size, price, symbols.get(0).symbolId, MarginMode.CROSS);
            container.createAskWithOrderId(8002L, takerUid, (int) size, price, symbols.get(0).symbolId, MarginMode.CROSS);

            TotalCurrencyBalanceReportResult finalBalance = container.totalBalanceReport();
            long finalTotalFees = finalBalance.getFees().get(CURRENECY_USD);
            globalFeesCollected = finalTotalFees;
            assertThat(finalBalance.isGlobalBalancesAllZero(), is(true));

        } finally {
            verify(handler, atLeast(2)).futuresExecutionReport(futuresExecutionReportCaptor.capture());
            List<ITradeEventsHandler.FuturesExecutionReport> reports = futuresExecutionReportCaptor.getAllValues();

            List<ITradeEventsHandler.FuturesExecutionReport> tradeReports = reports.stream()
                    .filter(r -> r.executionType == ITradeEventsHandler.ExecType.TRADE)
                    .collect(Collectors.toList());

            assertEquals(2, tradeReports.size(), "Should have 2 trade execution reports");

            long sumFees = 0;
            // Verify that fees are calculated using correct size and price
            for (ITradeEventsHandler.FuturesExecutionReport report : tradeReports) {
                assertThat("Trade size should match", report.lastQty, is(size));
                assertThat("Trade price should match", report.lastPx, is(price));

                long expectedFee = report.isMaker ?
                        CoreArithmeticUtils.calculateMakerFee(size, price, BTC_SYMBOL) :
                        CoreArithmeticUtils.calculateTakerFee(size, price, BTC_SYMBOL);
                assertThat("Fee should be calculated with correct parameters", report.fee, is(expectedFee));
                sumFees = sumFees + expectedFee;
            }

            log.info("Futures fee calculation parameters verified for size={}, price={}", size, price);
            assertTrue(globalFeesCollected == sumFees, "Global fees should match or exceed sum of individual fees");
        }
    }

    /**
     * Test futures maker 1-to-many with partial fills - verify fee consistency with global balance
     */
    @Test
    @Timeout(10)
    public void testFuturesMakerOneToManyPartialFillFeeConsistency() throws ExecutionException, InterruptedException {
        long makerUid = UID_1;
        long taker1Uid = UID_2;
        long taker2Uid = UID_3;
        long taker3Uid = UID_4;

        // Maker order size is larger than all takers combined - will be partially filled
        long makerTotalSize = 50L;
        long size1 = 8L;
        long size2 = 12L;
        long size3 = 15L; // Total taker size = 35L, less than maker's 50L
        long price = 48500L;

        // Declare globalFeesCollected outside try block
        long globalFeesCollected = 0;

        try (final ExchangeTestContainer container = ExchangeTestContainer.create(getPerformanceConfiguration(), processor)) {
            List<CoreSymbolSpecification> symbols = container.initFutureSymbols();
            symbols.forEach(s -> container.initMarkPrice(s.symbolId, price));

            long deposit = 100000L;
            container.createUserWithSpecificMoney(makerUid, deposit, CURRENECY_USD);
            container.createUserWithSpecificMoney(taker1Uid, deposit, CURRENECY_USD);
            container.createUserWithSpecificMoney(taker2Uid, deposit, CURRENECY_USD);
            container.createUserWithSpecificMoney(taker3Uid, deposit, CURRENECY_USD);

            // Get initial balance report
            TotalCurrencyBalanceReportResult initialBalance = container.totalBalanceReport();
            long initialTotalFees = initialBalance.getFees().get(CURRENECY_USD);

            // Place large maker order (BID) - will be partially filled
            container.createBidWithOrderId(9001L, makerUid, (int) makerTotalSize, price, symbols.get(0).symbolId, MarginMode.CROSS);

            // Multiple takers partially fill the maker order
            container.createAskWithOrderId(9002L, taker1Uid, (int) size1, price, symbols.get(0).symbolId, MarginMode.CROSS);

            ApiPlaceOrder iocOrder2 = ApiPlaceOrder.builder()
                    .uid(taker2Uid)
                    .orderId(9003L)
                    .price(price)
                    .reservePrice(price)
                    .size(size2)
                    .action(ASK)
                    .orderType(IOC)
                    .symbol(symbols.get(0).symbolId)
                    .marginMode(MarginMode.CROSS)
                    .build();
            container.submitCommandSync(iocOrder2, CommandResultCode.SUCCESS);

            container.createAskWithOrderId(9004L, taker3Uid, (int) size3, price, symbols.get(0).symbolId, MarginMode.CROSS);

            // Get final balance report
            TotalCurrencyBalanceReportResult finalBalance = container.totalBalanceReport();
            long finalTotalFees = finalBalance.getFees().get(CURRENECY_USD);
            globalFeesCollected = finalTotalFees - initialTotalFees;

            // Check if maker order still exists (might be fully filled if order book matching differs)
            container.validateUserState(makerUid, profile -> {
                log.info("Maker user orders after trading: {}", profile.fetchIndexedOrders().size());
            });

        } finally {
            // Verify execution reports and fee calculations
            verify(handler, atLeast(6)).futuresExecutionReport(futuresExecutionReportCaptor.capture());
            List<ITradeEventsHandler.FuturesExecutionReport> reports = futuresExecutionReportCaptor.getAllValues();

            List<ITradeEventsHandler.FuturesExecutionReport> tradeReports = reports.stream()
                    .filter(r -> r.executionType == ITradeEventsHandler.ExecType.TRADE)
                    .collect(Collectors.toList());

            assertTrue(tradeReports.size() >= 6, "Should have at least 6 trade execution reports");

            // Calculate total fees from execution reports
            long totalMakerFeesFromReports = tradeReports.stream()
                    .filter(r -> r.isMaker && r.userId == makerUid)
                    .mapToLong(r -> r.fee)
                    .sum();

            long totalTakerFeesFromReports = tradeReports.stream()
                    .filter(r -> !r.isMaker)
                    .mapToLong(r -> r.fee)
                    .sum();

            long totalFeesFromReports = totalMakerFeesFromReports + totalTakerFeesFromReports;

            // Calculate expected fees
            long expectedMakerFees = CoreArithmeticUtils.calculateMakerFee(size1, price, BTC_SYMBOL) +
                    CoreArithmeticUtils.calculateMakerFee(size2, price, BTC_SYMBOL) +
                    CoreArithmeticUtils.calculateMakerFee(size3, price, BTC_SYMBOL);

            long expectedTakerFees = CoreArithmeticUtils.calculateTakerFee(size1, price, BTC_SYMBOL) +
                    CoreArithmeticUtils.calculateTakerFee(size2, price, BTC_SYMBOL) +
                    CoreArithmeticUtils.calculateTakerFee(size3, price, BTC_SYMBOL);

            long expectedTotalFees = expectedMakerFees + expectedTakerFees;

            // Verify fee calculations are reasonable
            assertTrue(totalMakerFeesFromReports > 0, "Should have collected some maker fees");
            assertTrue(totalTakerFeesFromReports > 0, "Should have collected some taker fees");
            assertTrue(globalFeesCollected > 0, "Should have collected global fees");

            // Log values for debugging
            log.info("Futures maker 1-to-many partial fill fees: " +
                            "Global={}, Reports={}, Maker={}, Taker={}, Expected={}",
                    globalFeesCollected, totalFeesFromReports,
                    totalMakerFeesFromReports, totalTakerFeesFromReports, expectedTotalFees);

            assertThat(globalFeesCollected == totalFeesFromReports, is(true));

            // Verify the fee totals are reasonable relative to trade volume
            long totalTradeVolume = (size1 + size2 + size3) * price;
            assertTrue(totalFeesFromReports < totalTradeVolume / 10,
                    "Total fees should be reasonable relative to trade volume"); // Less than 10% fees
        }
    }

    /**
     * Test futures taker 1-to-many with partial fills - verify fee consistency with global balance
     */
    @Test
    @Timeout(10)
    public void testFuturesTakerOneToManyPartialFillFeeConsistency() throws ExecutionException, InterruptedException {
        long maker1Uid = UID_1;
        long maker2Uid = UID_2;
        long maker3Uid = UID_3;
        long takerUid = UID_4;

        // Makers have limited sizes - taker order will be partially filled
        long size1 = 6L;
        long size2 = 8L;
        long size3 = 9L; // Total maker size = 23L
        long takerTotalSize = 30L; // Larger than available makers - will be partially filled

        long price1 = 45000L;
        long price2 = 45100L;
        long price3 = 45200L;

        // Declare globalFeesCollected outside try block
        long globalFeesCollected = 0;

        try (final ExchangeTestContainer container = ExchangeTestContainer.create(getPerformanceConfiguration(), processor)) {
            List<CoreSymbolSpecification> symbols = container.initFutureSymbols();
            symbols.forEach(s -> container.initMarkPrice(s.symbolId, price1));

            long deposit = 100000L;
            container.createUserWithSpecificMoney(maker1Uid, deposit, CURRENECY_USD);
            container.createUserWithSpecificMoney(maker2Uid, deposit, CURRENECY_USD);
            container.createUserWithSpecificMoney(maker3Uid, deposit, CURRENECY_USD);
            container.createUserWithSpecificMoney(takerUid, deposit, CURRENECY_USD);

            // Get initial balance report
            TotalCurrencyBalanceReportResult initialBalance = container.totalBalanceReport();
            long initialTotalFees = initialBalance.getFees().get(CURRENECY_USD);

            // Place multiple small maker orders (ASK) at different prices
            container.createAskWithOrderId(10001L, maker1Uid, (int) size1, price1, symbols.get(0).symbolId, MarginMode.CROSS);
            container.createAskWithOrderId(10002L, maker2Uid, (int) size2, price2, symbols.get(0).symbolId, MarginMode.CROSS);
            container.createAskWithOrderId(10003L, maker3Uid, (int) size3, price3, symbols.get(0).symbolId, MarginMode.CROSS);

            // Place large taker order (BID) that will be partially filled
            container.createBidWithOrderId(10004L, takerUid, (int) takerTotalSize, price3, symbols.get(0).symbolId, MarginMode.CROSS);

            // Get final balance report
            TotalCurrencyBalanceReportResult finalBalance = container.totalBalanceReport();
            long finalTotalFees = finalBalance.getFees().get(CURRENECY_USD);
            globalFeesCollected = finalTotalFees - initialTotalFees;

            // Verify that trades happened by checking that makers are filled
            container.validateUserState(takerUid, profile -> {
                Order order = profile.fetchIndexedOrders().get(10004L);
                if (order != null) {
                    log.info("Taker order exists with size={}, original={}", order.size, takerTotalSize);
                } else {
                    log.info("Taker order completely filled");
                }
            });

            // Verify that all maker orders were completely filled (this proves trades happened)
            container.validateUserState(maker1Uid, profile -> {
                assertTrue(profile.fetchIndexedOrders().isEmpty() ||
                                !profile.fetchIndexedOrders().containsKey(10001L),
                        "Maker1 order should be completely filled");
            });
            container.validateUserState(maker2Uid, profile -> {
                assertTrue(profile.fetchIndexedOrders().isEmpty() ||
                                !profile.fetchIndexedOrders().containsKey(10002L),
                        "Maker2 order should be completely filled");
            });
            container.validateUserState(maker3Uid, profile -> {
                assertTrue(profile.fetchIndexedOrders().isEmpty() ||
                                !profile.fetchIndexedOrders().containsKey(10003L),
                        "Maker3 order should be completely filled");
            });

        } finally {
            // Verify execution reports and fee calculations
            verify(handler, atLeast(6)).futuresExecutionReport(futuresExecutionReportCaptor.capture());
            List<ITradeEventsHandler.FuturesExecutionReport> reports = futuresExecutionReportCaptor.getAllValues();

            List<ITradeEventsHandler.FuturesExecutionReport> tradeReports = reports.stream()
                    .filter(r -> r.executionType == ITradeEventsHandler.ExecType.TRADE)
                    .collect(Collectors.toList());

            assertTrue(tradeReports.size() >= 6, "Should have at least 6 trade execution reports");

            // Calculate total fees from execution reports
            long totalMakerFeesFromReports = tradeReports.stream()
                    .filter(r -> r.isMaker)
                    .mapToLong(r -> r.fee)
                    .sum();

            long totalTakerFeesFromReports = tradeReports.stream()
                    .filter(r -> !r.isMaker && r.userId == takerUid)
                    .mapToLong(r -> r.fee)
                    .sum();

            long totalFeesFromReports = totalMakerFeesFromReports + totalTakerFeesFromReports;

            // Calculate expected fees
            long expectedMakerFees = CoreArithmeticUtils.calculateMakerFee(size1, price1, BTC_SYMBOL) +
                    CoreArithmeticUtils.calculateMakerFee(size2, price2, BTC_SYMBOL) +
                    CoreArithmeticUtils.calculateMakerFee(size3, price3, BTC_SYMBOL);

            long expectedTakerFees = CoreArithmeticUtils.calculateTakerFee(size1, price1, BTC_SYMBOL) +
                    CoreArithmeticUtils.calculateTakerFee(size2, price2, BTC_SYMBOL) +
                    CoreArithmeticUtils.calculateTakerFee(size3, price3, BTC_SYMBOL);

            long expectedTotalFees = expectedMakerFees + expectedTakerFees;

            // Verify fee calculations are reasonable
            assertTrue(totalMakerFeesFromReports > 0, "Should have collected some maker fees");
            assertTrue(totalTakerFeesFromReports > 0, "Should have collected some taker fees");
            assertTrue(globalFeesCollected > 0, "Should have collected global fees");

            // Log values for debugging
            log.info("Futures taker 1-to-many partial fill fees: " +
                            "Global={}, Reports={}, Maker={}, Taker={}, Expected={}",
                    globalFeesCollected, totalFeesFromReports,
                    totalMakerFeesFromReports, totalTakerFeesFromReports, expectedTotalFees);

            assertThat(globalFeesCollected == totalFeesFromReports, is(true));

            // Verify the fee totals are reasonable relative to trade volume
            long totalTradeVolume = size1 * price1 + size2 * price2 + size3 * price3;
            assertTrue(totalFeesFromReports < totalTradeVolume / 10,
                    "Total fees should be reasonable relative to trade volume"); // Less than 10% fees
        }
    }
}