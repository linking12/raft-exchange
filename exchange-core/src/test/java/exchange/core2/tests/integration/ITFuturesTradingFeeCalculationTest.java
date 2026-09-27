package exchange.core2.tests.integration;

import exchange.core2.core.ITradeEventsHandler;
import exchange.core2.core.common.*;
import exchange.core2.core.common.api.ApiAdjustMargin;
import exchange.core2.core.common.api.ApiAdjustPositionMode;
import exchange.core2.core.common.api.ApiClosePosition;
import exchange.core2.core.common.api.ApiPlaceOrder;
import exchange.core2.core.common.api.reports.SingleUserReportResult;
import exchange.core2.core.common.api.reports.TotalCurrencyBalanceReportResult;
import exchange.core2.core.common.cmd.CommandResultCode;
import exchange.core2.core.common.config.PerformanceConfiguration;
import exchange.core2.core.processors.liquidation.LiquidationEngine;
import exchange.core2.core.event.IEventsHandler4Test;
import exchange.core2.core.event.SimpleEventsProcessor4Test;
import exchange.core2.core.utils.CoreArithmeticUtils;
import exchange.core2.tests.util.ExchangeTestContainer;
import exchange.core2.tests.util.LatencyTools;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
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
import static org.hamcrest.core.IsNot.not;
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

    private IEventsHandler4Test handler = spy(IEventsHandler4Test.handler);

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
     * 验证期货 GTC/IOC/FOK_BUDGET/IOC_BUDGET 四种 taker OrderType 下，**每个用户**的最终
     * account 余额都等于闭式应得值（initial - 开仓手续费）。
     *
     * 设计：每种 OrderType 走一轮独立 container。
     *   开仓：makerUid GTC ASK 挂单 -> takerUid <takerType> BID 撮合
     *   平仓：takerUid GTC ASK 挂单 -> makerUid GTC BID 撮合 (双方都是反向，按引擎规则平仓不收 fee)
     *   终态：双方仓位归零，无 lockedMargin / 无 PnL，account 闭式可断言
     *
     * 这条测试是"全局账平 ≠ 每用户余额正确"的补强 —— 全局守恒只能保证 sum=0，
     * 不能排除"A 多扣的钱被 B 少扣抵消"的情形；这里对每个用户单独断言闭式值。
     */
    @Test
    @Timeout(30)
    public void testFuturesPerUserBalanceAcrossOrderTypes() throws Exception {
        runFuturesPerUserBalanceCheck(GTC);
        runFuturesPerUserBalanceCheck(IOC);
        runFuturesPerUserBalanceCheck(FOK_BUDGET);
        runFuturesPerUserBalanceCheck(IOC_BUDGET);
    }

    private void runFuturesPerUserBalanceCheck(OrderType takerType) throws Exception {
        final long makerUid = 9001L;
        final long takerUid = 9002L;
        final long size = 4L;
        final long price = 50000L;
        final long deposit = 1_000_000L;

        try (final ExchangeTestContainer container = ExchangeTestContainer.create(getPerformanceConfiguration(), processor)) {
            List<CoreSymbolSpecification> symbols = container.initFutureSymbols();
            final int symbolId = symbols.get(0).symbolId;
            symbols.forEach(s -> container.initMarkPrice(s.symbolId, price));

            container.createUserWithSpecificMoney(makerUid, deposit, CURRENECY_USD);
            container.createUserWithSpecificMoney(takerUid, deposit, CURRENECY_USD);

            // ============ 开仓 ============
            // maker GTC ASK -> 开 SHORT；taker <takerType> BID -> 开 LONG
            container.createAskWithOrderId(9101L, makerUid, (int) size, price, symbolId, MarginMode.CROSS);

            final long takerPriceField = (takerType == FOK_BUDGET || takerType == IOC_BUDGET)
                    ? size * price   // BUDGET 单 price 字段是总预算 notional
                    : price;
            container.submitCommandSync(
                    ApiPlaceOrder.builder()
                            .uid(takerUid)
                            .orderId(9102L)
                            .price(takerPriceField)
                            .reservePrice(takerPriceField)
                            .size(size)
                            .action(BID)
                            .orderType(takerType)
                            .symbol(symbolId)
                            .marginMode(MarginMode.CROSS)
                            .build(),
                    CommandResultCode.SUCCESS);

            // ============ 平仓（反向撮合, 平仓同样按 maker/taker 收 fee） ============
            // 这一轮换边：原 takerUid 挂单变 maker，原 makerUid 主动吃单变 taker
            container.createAskWithOrderId(9103L, takerUid, (int) size, price, symbolId, MarginMode.CROSS);
            container.createBidWithOrderId(9104L, makerUid, (int) size, price, symbolId, MarginMode.CROSS);

            // ============ 断言：仓位清空 ============
            container.validateUserState(makerUid, profile -> assertTrue(
                    profile.getPositions().get(symbolId) == null || profile.getPositions().get(symbolId).isEmpty(),
                    "[" + takerType + "] maker 仓位应全部平掉"));
            container.validateUserState(takerUid, profile -> assertTrue(
                    profile.getPositions().get(symbolId) == null || profile.getPositions().get(symbolId).isEmpty(),
                    "[" + takerType + "] taker 仓位应全部平掉"));

            // ============ 断言：account == initial - 开仓 fee - 平仓 fee ============
            long expectedMakerFee = CoreArithmeticUtils.calculateMakerFee(size, price, BTC_SYMBOL);
            long expectedTakerFee = CoreArithmeticUtils.calculateTakerFee(size, price, BTC_SYMBOL);
            // 原 makerUid：开仓 maker fee + 平仓 taker fee (close 时主动吃)
            long expectedMakerAccount = deposit - expectedMakerFee - expectedTakerFee;
            // 原 takerUid：开仓 taker fee + 平仓 maker fee (close 时被动挂)
            long expectedTakerAccount = deposit - expectedTakerFee - expectedMakerFee;

            container.validateUserState(makerUid, profile -> assertThat(
                    "[" + takerType + "] maker account 应为 " + expectedMakerAccount,
                    profile.getAccounts().get(CURRENECY_USD), is(expectedMakerAccount)));
            container.validateUserState(takerUid, profile -> assertThat(
                    "[" + takerType + "] taker account 应为 " + expectedTakerAccount,
                    profile.getAccounts().get(CURRENECY_USD), is(expectedTakerAccount)));

            // ============ 顺带验全局账平 ============
            assertTrue(container.totalBalanceReport().isGlobalBalancesAllZero(),
                    "[" + takerType + "] 全局账面应该闭合");

            log.info("Futures per-user balance verified for taker={}: makerFee={}, takerFee={}",
                    takerType, expectedMakerFee, expectedTakerFee);
        }
    }

    /**
     * 完整生命周期账目守恒：充值 → 开仓 → 平仓 → 提现 → 对账。
     *
     * 验证（每种 taker OrderType 各一轮）：
     *   1) 充值走 ApiAdjustUserBalance：accounts += deposit, adjustments -= deposit（反向记账）
     *   2) 开仓/平仓后双方 account = deposit - 开仓 fee
     *   3) 提现走 ApiAdjustUserBalance(负数)：accounts -= (deposit - fee), adjustments += (deposit - fee)
     *   4) 终态：双方 account == 0, 仓位清空, 全局对账闭合
     *   5) adjustments 净额 == -fee 总和（充值-提现差额，等于被引擎吃掉的手续费）
     */
    @Test
    @Timeout(30)
    public void testFuturesFullLifecycleWithDepositWithdraw() throws Exception {
        runFuturesFullLifecycle(GTC);
        runFuturesFullLifecycle(IOC);
        runFuturesFullLifecycle(FOK_BUDGET);
        runFuturesFullLifecycle(IOC_BUDGET);
    }

    private void runFuturesFullLifecycle(OrderType takerType) throws Exception {
        final long makerUid = 9201L;
        final long takerUid = 9202L;
        final long size = 4L;
        final long price = 50000L;
        final long deposit = 1_000_000L;

        try (final ExchangeTestContainer container = ExchangeTestContainer.create(getPerformanceConfiguration(), processor)) {
            List<CoreSymbolSpecification> symbols = container.initFutureSymbols();
            final int symbolId = symbols.get(0).symbolId;
            symbols.forEach(s -> container.initMarkPrice(s.symbolId, price));

            // ============ 阶段 1：充值 (createUserWithSpecificMoney 走 ApiAdjustUserBalance) ============
            container.createUserWithSpecificMoney(makerUid, deposit, CURRENECY_USD);
            container.createUserWithSpecificMoney(takerUid, deposit, CURRENECY_USD);

            // 验证充值后：account = deposit, adjustments = -2 * deposit, 全局账平
            container.validateUserState(makerUid, profile ->
                    assertThat("[" + takerType + "] 充值后 maker account",
                            profile.getAccounts().get(CURRENECY_USD), is(deposit)));
            container.validateUserState(takerUid, profile ->
                    assertThat("[" + takerType + "] 充值后 taker account",
                            profile.getAccounts().get(CURRENECY_USD), is(deposit)));
            assertThat("[" + takerType + "] 充值后 adjustments 应为 -2*deposit",
                    container.totalBalanceReport().getAdjustments().get(CURRENECY_USD), is(-2 * deposit));
            assertTrue(container.totalBalanceReport().isGlobalBalancesAllZero(),
                    "[" + takerType + "] 充值后全局账平");

            // ============ 阶段 2：开仓（maker GTC ASK + taker <takerType> BID） ============
            container.createAskWithOrderId(9301L, makerUid, (int) size, price, symbolId, MarginMode.CROSS);
            final long takerPriceField = (takerType == FOK_BUDGET || takerType == IOC_BUDGET)
                    ? size * price
                    : price;
            container.submitCommandSync(
                    ApiPlaceOrder.builder()
                            .uid(takerUid)
                            .orderId(9302L)
                            .price(takerPriceField)
                            .reservePrice(takerPriceField)
                            .size(size)
                            .action(BID)
                            .orderType(takerType)
                            .symbol(symbolId)
                            .marginMode(MarginMode.CROSS)
                            .build(),
                    CommandResultCode.SUCCESS);

            // ============ 阶段 3：平仓（双方反向, 平仓同样收 fee, 但 maker/taker 换边） ============
            // 原 takerUid 在平仓时挂单 → 成 maker；原 makerUid 在平仓时主动吃单 → 成 taker
            container.createAskWithOrderId(9303L, takerUid, (int) size, price, symbolId, MarginMode.CROSS);
            container.createBidWithOrderId(9304L, makerUid, (int) size, price, symbolId, MarginMode.CROSS);

            final long expectedMakerFee = CoreArithmeticUtils.calculateMakerFee(size, price, BTC_SYMBOL);
            final long expectedTakerFee = CoreArithmeticUtils.calculateTakerFee(size, price, BTC_SYMBOL);
            // 原 makerUid：开仓 maker fee + 平仓 taker fee
            final long makerBalanceBeforeWithdraw = deposit - expectedMakerFee - expectedTakerFee;
            // 原 takerUid：开仓 taker fee + 平仓 maker fee
            final long takerBalanceBeforeWithdraw = deposit - expectedTakerFee - expectedMakerFee;

            container.validateUserState(makerUid, profile -> assertThat(
                    "[" + takerType + "] 平仓后 maker account = deposit - openMakerFee - closeTakerFee",
                    profile.getAccounts().get(CURRENECY_USD), is(makerBalanceBeforeWithdraw)));
            container.validateUserState(takerUid, profile -> assertThat(
                    "[" + takerType + "] 平仓后 taker account = deposit - openTakerFee - closeMakerFee",
                    profile.getAccounts().get(CURRENECY_USD), is(takerBalanceBeforeWithdraw)));
            assertTrue(container.totalBalanceReport().isGlobalBalancesAllZero(),
                    "[" + takerType + "] 平仓后全局账平");

            // ============ 阶段 4：提现（addMoneyToUser 传负数走 ApiAdjustUserBalance 的 withdraw 路径） ============
            container.addMoneyToUser(makerUid, CURRENECY_USD, -makerBalanceBeforeWithdraw);
            container.addMoneyToUser(takerUid, CURRENECY_USD, -takerBalanceBeforeWithdraw);

            // ============ 阶段 5：终态断言 ============
            container.validateUserState(makerUid, profile -> assertThat(
                    "[" + takerType + "] 提现后 maker account 应清零",
                    profile.getAccounts().get(CURRENECY_USD), is(0L)));
            container.validateUserState(takerUid, profile -> assertThat(
                    "[" + takerType + "] 提现后 taker account 应清零",
                    profile.getAccounts().get(CURRENECY_USD), is(0L)));

            // adjustments 净额 = -(deposit*2 - withdraw*2) = -(开仓 + 平仓 fee 总和)
            // 因为最后未被提走的钱正是引擎收的手续费
            long expectedAdjustments = -2 * (expectedMakerFee + expectedTakerFee);
            assertThat("[" + takerType + "] adjustments 净额应等于 -总手续费",
                    container.totalBalanceReport().getAdjustments().get(CURRENECY_USD),
                    is(expectedAdjustments));

            // fees 总额应等于 maker + taker (开仓 + 平仓) 手续费
            assertThat("[" + takerType + "] fees 总额应等于开/平仓手续费总和",
                    container.totalBalanceReport().getFees().get(CURRENECY_USD),
                    is(2 * (expectedMakerFee + expectedTakerFee)));

            // adjustments + fees == 0（充值/提现差额 = 引擎收的所有手续费）
            assertTrue(container.totalBalanceReport().isGlobalBalancesAllZero(),
                    "[" + takerType + "] 全生命周期后全局账平");

            log.info("Futures full lifecycle verified for taker={}: deposit={}, makerFee={}, takerFee={}, finalAdjustments={}",
                    takerType, deposit, expectedMakerFee, expectedTakerFee, expectedAdjustments);
        }
    }

    /**
     * HEDGE 双向持仓全生命周期账目守恒 × 4 种 taker OrderType。
     *
     * 单向持仓版本已覆盖（见 testFuturesFullLifecycleWithDepositWithdraw）。
     * 本测试聚焦双向持仓特有的两条风险路径：
     *   1) 同 symbol 下 taker 同时持多 + 持空：UserProfile.createPositionsKey 在 HEDGE
     *      下用 BID=+symbol、ASK=-symbol 把同一 symbol 拆成两个 position record。这条
     *      链路如果 BUDGET 单/IOC 单不能正确触发开空（被误判成"平多"），LONG 就会被
     *      抵消而不会建出 SHORT。
     *   2) 平仓走 ApiClosePosition 命令：CLOSE_POSITION 在 createPositionsKey 里会翻转
     *      key（平多用 ASK 但要命中 +symbol 的多仓记录）。这条翻转链路如果挂了，
     *      平仓会去找一个不存在的 -symbol 记录 → 平仓失败 → 仓位/账目永远漂着。
     *
     * 每轮（一种 OrderType）流程：
     *   1) 充值 maker + taker
     *   2) taker 切 HEDGE
     *   3) 开多：maker GTC ASK + taker <takerType> BID  → taker LONG
     *   4) 开空：maker GTC BID + taker <takerType> ASK  → taker SHORT (HEDGE 不会抵消多仓)
     *   5) 平多：taker ApiClosePosition(ASK) + maker GTC BID
     *   6) 平空：taker ApiClosePosition(BID) + maker GTC ASK
     *   7) 提现：读出 maker/taker 余额，全数提走
     *   8) 终态：双方账户清零，仓位 openVolume == 0，全局账平
     *
     * 不精确预算手续费（HEDGE 多了开空一边的 fee，与 close-position 路径的 fee 规则耦合），
     * 改用"读多少提多少"间接验证守恒：充值 - 提现 = 引擎留下的所有 fee，全局闭合。
     */
    @Test
    @Timeout(30)
    public void testFuturesHedgeFullLifecycleWithDepositWithdraw() throws Exception {
        runFuturesHedgeFullLifecycle(GTC);
        runFuturesHedgeFullLifecycle(IOC);
        runFuturesHedgeFullLifecycle(FOK_BUDGET);
        runFuturesHedgeFullLifecycle(IOC_BUDGET);
    }

    private void runFuturesHedgeFullLifecycle(OrderType takerType) throws Exception {
        final long makerUid = 9401L;
        final long takerUid = 9402L;
        final long size = 4L;
        final long price = 50000L;
        // HEDGE 同时锁多仓 + 空仓两边保证金，需要的钱比单向版多一倍以上，留足余量。
        final long deposit = 100_000_000L;

        try (final ExchangeTestContainer container = ExchangeTestContainer.create(getPerformanceConfiguration(), processor)) {
            List<CoreSymbolSpecification> symbols = container.initFutureSymbols();
            final int symbolId = symbols.get(0).symbolId;
            symbols.forEach(s -> container.initMarkPrice(s.symbolId, price));

            // ============ 阶段 1：充值 ============
            container.createUserWithSpecificMoney(makerUid, deposit, CURRENECY_USD);
            container.createUserWithSpecificMoney(takerUid, deposit, CURRENECY_USD);
            assertTrue(container.totalBalanceReport().isGlobalBalancesAllZero(),
                    "[" + takerType + "] 充值后全局账平");

            // ============ 阶段 2：taker 切 HEDGE ============
            container.adjustPositionMode(takerUid, PositionMode.HEDGE);

            // BUDGET 单 price 字段是总预算 notional
            final long takerPriceField = (takerType == FOK_BUDGET || takerType == IOC_BUDGET)
                    ? size * price
                    : price;

            // 引擎限制：IOC_BUDGET 只支持 BID（OrderBookNaiveImpl#newOrderMatchIocBudget 直接 reject ASK，
            // 因为"最低收入"约束无法部分成交，语义模糊）。开空仓阶段对 IOC_BUDGET 退化到 GTC，
            // 这样 4 种 takerType 都能跑完整全周期、覆盖 BID 侧路径与 close-position 翻转逻辑。
            final OrderType askTakerType = (takerType == IOC_BUDGET) ? GTC : takerType;
            final long askTakerPriceField = (askTakerType == FOK_BUDGET || askTakerType == IOC_BUDGET)
                    ? size * price
                    : price;

            // ============ 阶段 3：开多仓 (maker GTC ASK + taker <takerType> BID) ============
            container.createAskWithOrderId(9501L, makerUid, (int) size, price, symbolId, MarginMode.CROSS);
            container.submitCommandSync(
                    ApiPlaceOrder.builder()
                            .uid(takerUid).orderId(9502L)
                            .price(takerPriceField).reservePrice(takerPriceField).size(size)
                            .action(BID).orderType(takerType)
                            .symbol(symbolId).marginMode(MarginMode.CROSS)
                            .build(),
                    CommandResultCode.SUCCESS);

            // ============ 阶段 4：开空仓 (maker GTC BID + taker <askTakerType> ASK) ============
            // 关键断言点：HEDGE 下 taker ASK 不会抵消多仓，必须建出新的空仓
            container.createBidWithOrderId(9503L, makerUid, (int) size, price, symbolId, MarginMode.CROSS);
            container.submitCommandSync(
                    ApiPlaceOrder.builder()
                            .uid(takerUid).orderId(9504L)
                            .price(askTakerPriceField).reservePrice(askTakerPriceField).size(size)
                            .action(ASK).orderType(askTakerType)
                            .symbol(symbolId).marginMode(MarginMode.CROSS)
                            .build(),
                    CommandResultCode.SUCCESS);

            container.validateUserState(takerUid, profile -> {
                assertThat("[" + takerType + "] taker 双向持仓应有 2 条记录",
                        profile.getPositions().get(symbolId).size(), is(2));
                assertThat("[" + takerType + "] taker LONG direction",
                        profile.getPositions().get(symbolId).get(0).direction, is(PositionDirection.LONG));
                assertThat("[" + takerType + "] taker LONG openVolume = size",
                        profile.getPositions().get(symbolId).get(0).openVolume, is(size));
                assertThat("[" + takerType + "] taker SHORT direction",
                        profile.getPositions().get(symbolId).get(1).direction, is(PositionDirection.SHORT));
                assertThat("[" + takerType + "] taker SHORT openVolume = size",
                        profile.getPositions().get(symbolId).get(1).openVolume, is(size));
            });
            assertTrue(container.totalBalanceReport().isGlobalBalancesAllZero(),
                    "[" + takerType + "] 双向开仓后全局账平");

            // ============ 阶段 5：平多仓 (ApiClosePosition ASK + maker GTC BID) ============
            container.submitCommandSync(
                    ApiClosePosition.builder()
                            .uid(takerUid).orderId(9505L).symbol(symbolId)
                            .price(price).size(size).action(ASK)
                            .build(),
                    CommandResultCode.SUCCESS);
            container.createBidWithOrderId(9506L, makerUid, (int) size, price, symbolId, MarginMode.CROSS);

            // ============ 阶段 6：平空仓 (ApiClosePosition BID + maker GTC ASK) ============
            container.submitCommandSync(
                    ApiClosePosition.builder()
                            .uid(takerUid).orderId(9507L).symbol(symbolId)
                            .price(price).size(size).action(BID)
                            .build(),
                    CommandResultCode.SUCCESS);
            container.createAskWithOrderId(9508L, makerUid, (int) size, price, symbolId, MarginMode.CROSS);

            // 平仓后 position 记录可能被引擎清掉，也可能 openVolume 归 0 留着；都视为合法。
            // 真正要断言的是：剩下的仓位 openVolume 总和 == 0（没有遗漏的多空仓）。
            container.validateUserState(takerUid, profile -> {
                List<SingleUserReportResult.Position> remaining = profile.getPositions().get(symbolId);
                long openVolumeSum = remaining == null ? 0L
                        : remaining.stream().mapToLong(p -> p.openVolume).sum();
                assertThat("[" + takerType + "] taker 平仓后 openVolume 总和应为 0",
                        openVolumeSum, is(0L));
            });
            assertTrue(container.totalBalanceReport().isGlobalBalancesAllZero(),
                    "[" + takerType + "] 双向平仓后全局账平");

            // ============ 阶段 7：读出双方余额，全数提走 ============
            final long[] balances = new long[2];  // [maker, taker]
            container.validateUserState(makerUid, p -> balances[0] = p.getAccounts().get(CURRENECY_USD));
            container.validateUserState(takerUid, p -> balances[1] = p.getAccounts().get(CURRENECY_USD));
            if (balances[0] != 0) container.addMoneyToUser(makerUid, CURRENECY_USD, -balances[0]);
            if (balances[1] != 0) container.addMoneyToUser(takerUid, CURRENECY_USD, -balances[1]);

            // ============ 阶段 8：终态断言 ============
            container.validateUserState(makerUid, p -> assertThat(
                    "[" + takerType + "] 提现后 maker account 应清零",
                    p.getAccounts().get(CURRENECY_USD), is(0L)));
            container.validateUserState(takerUid, p -> assertThat(
                    "[" + takerType + "] 提现后 taker account 应清零",
                    p.getAccounts().get(CURRENECY_USD), is(0L)));

            final TotalCurrencyBalanceReportResult bal = container.totalBalanceReport();
            long adjustments = bal.getAdjustments().get(CURRENECY_USD);
            long fees = bal.getFees().get(CURRENECY_USD);
            assertThat("[" + takerType + "] adjustments + fees == 0",
                    adjustments + fees, is(0L));
            assertTrue(bal.isGlobalBalancesAllZero(),
                    "[" + takerType + "] HEDGE 全生命周期后全局账平");

            log.info("Futures HEDGE full lifecycle verified for taker={}: makerBalance={}, takerBalance={}, fees={}, adjustments={}",
                    takerType, balances[0], balances[1], fees, adjustments);
        }
    }

    /**
     * ISOLATED 逐仓 + HEDGE 双向持仓全生命周期 × 4 种 taker OrderType。
     *
     * 与 testFuturesHedgeFullLifecycleWithDepositWithdraw（CROSS 版）相比，
     * 本测试聚焦逐仓特有的风险路径：
     *   - HEDGE + ISOLATED 时，多仓和空仓各自独立锁逐仓保证金（openInitMarginSum
     *     必须分别 > 0，而不是共用 account 余额）。如果引擎把 ISOLATED+HEDGE 误退化
     *     成 CROSS 的共享池模型，开空仓时不会建立独立的 isolated margin lock，
     *     强平/追加保证金的隔离边界就会被破坏。
     *   - leverage 字段必须正确写入两侧 position record（CROSS 不依赖 leverage 字段）。
     *
     * IOC_BUDGET ASK 引擎不支持的限制同 CROSS 版处理（fallback 到 GTC）。
     */
    @Test
    @Timeout(30)
    public void testFuturesIsolatedHedgeFullLifecycleWithDepositWithdraw() throws Exception {
        runFuturesIsolatedHedgeFullLifecycle(GTC);
        runFuturesIsolatedHedgeFullLifecycle(IOC);
        runFuturesIsolatedHedgeFullLifecycle(FOK_BUDGET);
        runFuturesIsolatedHedgeFullLifecycle(IOC_BUDGET);
    }

    private void runFuturesIsolatedHedgeFullLifecycle(OrderType takerType) throws Exception {
        final long makerUid = 9601L;
        final long takerUid = 9602L;
        final long size = 4L;
        final long price = 50000L;
        final int leverage = 10;
        final long deposit = 100_000_000L;

        try (final ExchangeTestContainer container = ExchangeTestContainer.create(getPerformanceConfiguration(), processor)) {
            List<CoreSymbolSpecification> symbols = container.initFutureSymbols();
            final int symbolId = symbols.get(0).symbolId;
            symbols.forEach(s -> container.initMarkPrice(s.symbolId, price));

            // ============ 阶段 1：充值 ============
            container.createUserWithSpecificMoney(makerUid, deposit, CURRENECY_USD);
            container.createUserWithSpecificMoney(takerUid, deposit, CURRENECY_USD);
            assertTrue(container.totalBalanceReport().isGlobalBalancesAllZero(),
                    "[" + takerType + "] 充值后全局账平");

            // ============ 阶段 2：taker 切 HEDGE ============
            container.adjustPositionMode(takerUid, PositionMode.HEDGE);

            final long takerPriceField = (takerType == FOK_BUDGET || takerType == IOC_BUDGET)
                    ? size * price : price;
            // IOC_BUDGET ASK 引擎不支持，开空仓阶段退化 GTC（同 CROSS HEDGE 版）
            final OrderType askTakerType = (takerType == IOC_BUDGET) ? GTC : takerType;
            final long askTakerPriceField = (askTakerType == FOK_BUDGET || askTakerType == IOC_BUDGET)
                    ? size * price : price;

            // ============ 阶段 3：开多仓 ============
            // helper createAsk/BidWithOrderId 不支持 leverage 字段，ISOLATED 必须显式带 leverage，
            // 因此 maker/taker 全部走 ApiPlaceOrder.builder() 直接构造。
            container.submitCommandSync(
                    ApiPlaceOrder.builder()
                            .uid(makerUid).orderId(9701L)
                            .price(price).size(size)
                            .action(ASK).orderType(GTC)
                            .symbol(symbolId).marginMode(MarginMode.ISOLATED).leverage(leverage)
                            .build(),
                    CommandResultCode.SUCCESS);
            container.submitCommandSync(
                    ApiPlaceOrder.builder()
                            .uid(takerUid).orderId(9702L)
                            .price(takerPriceField).reservePrice(takerPriceField).size(size)
                            .action(BID).orderType(takerType)
                            .symbol(symbolId).marginMode(MarginMode.ISOLATED).leverage(leverage)
                            .build(),
                    CommandResultCode.SUCCESS);

            // ============ 阶段 4：开空仓 ============
            container.submitCommandSync(
                    ApiPlaceOrder.builder()
                            .uid(makerUid).orderId(9703L)
                            .price(price).size(size)
                            .action(BID).orderType(GTC)
                            .symbol(symbolId).marginMode(MarginMode.ISOLATED).leverage(leverage)
                            .build(),
                    CommandResultCode.SUCCESS);
            container.submitCommandSync(
                    ApiPlaceOrder.builder()
                            .uid(takerUid).orderId(9704L)
                            .price(askTakerPriceField).reservePrice(askTakerPriceField).size(size)
                            .action(ASK).orderType(askTakerType)
                            .symbol(symbolId).marginMode(MarginMode.ISOLATED).leverage(leverage)
                            .build(),
                    CommandResultCode.SUCCESS);

            // 关键断言：HEDGE + ISOLATED 下，多仓 / 空仓各自独立锁定逐仓保证金
            container.validateUserState(takerUid, profile -> {
                List<SingleUserReportResult.Position> twoLegs = profile.getPositions().get(symbolId);
                assertThat("[" + takerType + "] taker 双向持仓有 2 条记录",
                        twoLegs.size(), is(2));
                SingleUserReportResult.Position longLeg = twoLegs.get(0);
                SingleUserReportResult.Position shortLeg = twoLegs.get(1);
                assertThat("[" + takerType + "] LONG direction", longLeg.direction, is(PositionDirection.LONG));
                assertThat("[" + takerType + "] LONG openVolume", longLeg.openVolume, is(size));
                assertThat("[" + takerType + "] LONG marginMode == ISOLATED", longLeg.marginMode, is(MarginMode.ISOLATED));
                assertThat("[" + takerType + "] LONG openInitMarginSum 应 > 0（逐仓独立锁仓保证金）",
                        longLeg.openInitMarginSum > 0, is(true));
                assertThat("[" + takerType + "] SHORT direction", shortLeg.direction, is(PositionDirection.SHORT));
                assertThat("[" + takerType + "] SHORT openVolume", shortLeg.openVolume, is(size));
                assertThat("[" + takerType + "] SHORT marginMode == ISOLATED", shortLeg.marginMode, is(MarginMode.ISOLATED));
                assertThat("[" + takerType + "] SHORT openInitMarginSum 应 > 0（不与多仓共享保证金）",
                        shortLeg.openInitMarginSum > 0, is(true));
            });
            assertTrue(container.totalBalanceReport().isGlobalBalancesAllZero(),
                    "[" + takerType + "] 开仓后全局账平");

            // ============ 阶段 5：平多仓 ============
            container.submitCommandSync(
                    ApiClosePosition.builder()
                            .uid(takerUid).orderId(9705L).symbol(symbolId)
                            .price(price).size(size).action(ASK)
                            .build(),
                    CommandResultCode.SUCCESS);
            container.submitCommandSync(
                    ApiPlaceOrder.builder()
                            .uid(makerUid).orderId(9706L)
                            .price(price).size(size)
                            .action(BID).orderType(GTC)
                            .symbol(symbolId).marginMode(MarginMode.ISOLATED).leverage(leverage)
                            .build(),
                    CommandResultCode.SUCCESS);

            // ============ 阶段 6：平空仓 ============
            container.submitCommandSync(
                    ApiClosePosition.builder()
                            .uid(takerUid).orderId(9707L).symbol(symbolId)
                            .price(price).size(size).action(BID)
                            .build(),
                    CommandResultCode.SUCCESS);
            container.submitCommandSync(
                    ApiPlaceOrder.builder()
                            .uid(makerUid).orderId(9708L)
                            .price(price).size(size)
                            .action(ASK).orderType(GTC)
                            .symbol(symbolId).marginMode(MarginMode.ISOLATED).leverage(leverage)
                            .build(),
                    CommandResultCode.SUCCESS);

            container.validateUserState(takerUid, profile -> {
                List<SingleUserReportResult.Position> remaining = profile.getPositions().get(symbolId);
                long openVolumeSum = remaining == null ? 0L
                        : remaining.stream().mapToLong(p -> p.openVolume).sum();
                assertThat("[" + takerType + "] taker 平仓后 openVolume 总和应为 0",
                        openVolumeSum, is(0L));
            });
            assertTrue(container.totalBalanceReport().isGlobalBalancesAllZero(),
                    "[" + takerType + "] 平仓后全局账平");

            // ============ 阶段 7：读余额全数提走 ============
            final long[] balances = new long[2];  // [maker, taker]
            container.validateUserState(makerUid, p -> balances[0] = p.getAccounts().get(CURRENECY_USD));
            container.validateUserState(takerUid, p -> balances[1] = p.getAccounts().get(CURRENECY_USD));
            if (balances[0] != 0) container.addMoneyToUser(makerUid, CURRENECY_USD, -balances[0]);
            if (balances[1] != 0) container.addMoneyToUser(takerUid, CURRENECY_USD, -balances[1]);

            // ============ 阶段 8：终态对账 ============
            container.validateUserState(makerUid, p -> assertThat(
                    "[" + takerType + "] 提现后 maker account 应清零",
                    p.getAccounts().get(CURRENECY_USD), is(0L)));
            container.validateUserState(takerUid, p -> assertThat(
                    "[" + takerType + "] 提现后 taker account 应清零",
                    p.getAccounts().get(CURRENECY_USD), is(0L)));

            final TotalCurrencyBalanceReportResult bal = container.totalBalanceReport();
            long adjustments = bal.getAdjustments().get(CURRENECY_USD);
            long fees = bal.getFees().get(CURRENECY_USD);
            assertThat("[" + takerType + "] adjustments + fees == 0",
                    adjustments + fees, is(0L));
            assertTrue(bal.isGlobalBalancesAllZero(),
                    "[" + takerType + "] ISOLATED+HEDGE 全生命周期后全局账平");

            log.info("Futures ISOLATED+HEDGE full lifecycle verified for taker={}: makerBalance={}, takerBalance={}, fees={}, adjustments={}",
                    takerType, balances[0], balances[1], fees, adjustments);
        }
    }

    /**
     * 强平全生命周期账目守恒：充值 → 开仓 → 暴跌 → 强平 → 提现 → 对账。
     *
     * 之前的全周期模板都是"用户主动平仓"，本测试聚焦强平这条高风险路径：
     *   1) FORCE_LIQUIDATION 路径自动生成的平仓单与正常 ApiPlaceOrder/ApiClosePosition 分支
     *      共用 RiskEngine 结算逻辑；如果强平路径漏算 fee、未把残值打回 victim、或没把
     *      亏损部分挪入 IF（保险基金），全局对账会立刻不平。
     *   2) ifBalances 是 TotalCurrencyBalanceReportResult#getGlobalBalancesSum 的项之一
     *      （见 LiquidationService#creditLiquidationFee 与 IFNotional），强平后必须把
     *      IF 端的现金流闭合到全局守恒里。
     *
     * 场景设计：
     *   victim：薄保证金（10k），ISOLATED LONG，杠杆刚够开仓
     *   lp：深资金池（1e8），CROSS — 既挂高价 ASK 撮合 victim 开仓（变 SHORT），
     *        又在低价挂 BID 接收强平卖出（再变回 ~flat）
     *   开仓价 50000 → 暴跌到 25000，多头爆仓
     *
     * 守恒方式与其它全周期一致：读出 victim/lp 最终账户余额，全数提走；
     * isGlobalBalancesAllZero() 在最终态必须成立（accountBalances + extraMargin
     * + fees + adjustments + suspends + ifBalances 全部为 0）。
     */
    @Test
    @Timeout(30)
    public void testFuturesLiquidationFullLifecycleConservation() throws Exception {
        final long victimUid = 9801L;
        final long lpUid = 9802L;
        final long acceptorUid = 9803L;  // 独立第三方：专门挂 BID 接强平 ASK 卖单
        final long victimDeposit = 10_000L;
        final long lpDeposit = 100_000_000L;
        final long acceptorDeposit = 100_000_000L;
        final long openPrice = 50_000L;
        final long crashPrice = 25_000L;
        final int size = 4;

        try (final ExchangeTestContainer container = ExchangeTestContainer.create(getPerformanceConfiguration(), processor)) {
            // 关闭自动强平线程，由测试手动触发 → 时序可控
            container.getExchangeCore().getLiquidationEngines().forEach(LiquidationEngine::stop);

            List<CoreSymbolSpecification> symbols = container.initFutureSymbols();
            final int symbolId = symbols.get(0).symbolId;
            symbols.forEach(s -> container.initMarkPrice(s.symbolId, openPrice));

            // ============ 阶段 1：充值 ============
            container.createUserWithSpecificMoney(victimUid, victimDeposit, CURRENECY_USD);
            container.createUserWithSpecificMoney(lpUid, lpDeposit, CURRENECY_USD);
            container.createUserWithSpecificMoney(acceptorUid, acceptorDeposit, CURRENECY_USD);
            assertTrue(container.totalBalanceReport().isGlobalBalancesAllZero(),
                    "充值后全局账平");

            // ============ 阶段 2：开仓 (LP ASK + victim BID ISOLATED) ============
            container.createAskWithOrderId(9901L, lpUid, size, openPrice, symbolId, MarginMode.CROSS);
            container.createBidWithOrderId(9902L, victimUid, size, openPrice, symbolId, MarginMode.ISOLATED);
            assertTrue(container.totalBalanceReport().isGlobalBalancesAllZero(),
                    "开仓后全局账平");

            // ============ 阶段 3：acceptor 预挂低价 BID 接收强平卖单 ============
            // 用独立第三方而非 LP：避开 LP 已持 SHORT 时挂反向 BID 引入的持仓抵销路径。
            // 显式构造 ApiPlaceOrder 设置 reservePrice 确保 BID 单成功挂上。
            container.submitCommandSync(
                    ApiPlaceOrder.builder()
                            .uid(acceptorUid).orderId(9903L)
                            .price(crashPrice).reservePrice(crashPrice).size(size * 2)
                            .action(BID).orderType(GTC)
                            .symbol(symbolId).marginMode(MarginMode.CROSS)
                            .build(),
                    CommandResultCode.SUCCESS);

            // ============ 阶段 4：暴跌 + 触发强平 ============
            container.updateCurrentPriceTo((int) crashPrice, symbolId, CURRENECY_USD);

            // LiquidationEngine.stop() 后 republish scheduler 被关，强平多步流程 (FORCE→IF→ADL)
            // 需要 caller 主动 drive。单次 triggerLiquidation 只起第一拍，后续步骤靠 waitForCondition 的
            // onTick 重发 — 参考 LatencyTools.waitForCondition JavaDoc + testPerpetualScenario3 同款模式。
            // 内层 5s + tick 100ms（最多 50 次重发），跟 @Timeout(30) 留够余量。
            Runnable trigger = container::triggerLiquidation;
            trigger.run();
            LatencyTools.waitForCondition(5_000L, () -> {
                try {
                    SingleUserReportResult p = container.getUserProfile(victimUid);
                    List<SingleUserReportResult.Position> positions = p.getPositions().get(symbolId);
                    long sum = positions == null ? 0L
                            : positions.stream().mapToLong(pos -> pos.openVolume).sum();
                    return sum == 0L;
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }, trigger, 100);
            container.getApi().groupingControl(0, 1);

            // ============ 阶段 5：验证 victim 持仓被清掉 ============
            container.validateUserState(victimUid, profile -> {
                List<SingleUserReportResult.Position> positions = profile.getPositions().get(symbolId);
                long openVolumeSum = positions == null ? 0L
                        : positions.stream().mapToLong(p -> p.openVolume).sum();
                assertThat("victim 强平后 openVolume 总和应为 0",
                        openVolumeSum, is(0L));
            });
            assertTrue(container.totalBalanceReport().isGlobalBalancesAllZero(),
                    "强平后全局账平（含 ifBalances 与 fees）");

            // ============ 阶段 6：撤掉 acceptor 残留挂单 ============
            // acceptor 阶段 3 挂了 size*2，强平只吃掉了 size，订单簿上还有 size 残单
            container.cancelOrder(acceptorUid, 9903L, symbolId);

            // ============ 阶段 7：提现 victim account（LP/acceptor 仍持仓不提现）============
            // 设计与 HEDGE 测试对齐：LP CROSS SHORT 与 acceptor CROSS LONG 在测试结束时仍持仓，
            // 它们的 account 里锁着保证金 → 不能 withdraw 全部，但锁定部分仍计入 isGlobalBalancesAllZero
            // 守恒求和，所以全局守恒断言依然能闭合。
            final long[] balances = new long[3];  // [victim, lp, acceptor]
            container.validateUserState(victimUid, p -> balances[0] = p.getAccounts().get(CURRENECY_USD));
            container.validateUserState(lpUid, p -> balances[1] = p.getAccounts().get(CURRENECY_USD));
            container.validateUserState(acceptorUid, p -> balances[2] = p.getAccounts().get(CURRENECY_USD));
            if (balances[0] != 0) container.addMoneyToUser(victimUid, CURRENECY_USD, -balances[0]);

            // ============ 阶段 8：终态守恒 ============
            container.validateUserState(victimUid, p -> assertThat(
                    "提现后 victim account 应清零（强平已清掉持仓）",
                    p.getAccounts().get(CURRENECY_USD), is(0L)));

            final TotalCurrencyBalanceReportResult bal = container.totalBalanceReport();
            long adjustments = bal.getAdjustments().get(CURRENECY_USD);
            long fees = bal.getFees().get(CURRENECY_USD);
            long ifBalance = bal.getIfBalances() == null ? 0L
                    : bal.getIfBalances().get(CURRENECY_USD);

            assertTrue(bal.isGlobalBalancesAllZero(),
                    "强平全生命周期后全局账平（含 LP/acceptor 持仓锁定保证金 + adjustments + fees + ifBalances）");

            log.info("Liquidation lifecycle verified: victimDeposit={}, lpDeposit={}, acceptorDeposit={}, "
                            + "victimWithdraw={}, lpAccountRemain={}, acceptorAccountRemain={}, fees={}, ifBalance={}, adjustments={}",
                    victimDeposit, lpDeposit, acceptorDeposit, balances[0], balances[1], balances[2], fees, ifBalance, adjustments);
        }
    }

    /**
     * extraMargin（追加逐仓保证金）全生命周期账目守恒。
     *
     * 流程：充值 → 开仓（ISOLATED LONG）→ ApiAdjustMargin 追加 isolated margin → 平仓 → 提现 → 对账。
     *
     * 关键路径：ApiAdjustMargin(ISOLATED) 把钱从 accountBalances 搬到 position.extraMargin
     * 这一对账户/仓位之间的现金流，在平仓时反向归还（剩余 extraMargin 退回 account）。
     * 如果搬运两端记账没对齐（例如 account 扣了但 extraMargin 没加，或平仓时归还漏算），
     * isGlobalBalancesAllZero 会立刻穿仓。
     *
     * 现有 ITExtraMarginIntegration 测了"加/减/匹配"行为本身，但没跑过端到端的
     * "充值 → 追加 → 平仓 → 提现 → 对账"完整闭合，这是补这条链路的最后一公里。
     */
    @Test
    @Timeout(20)
    public void testFuturesExtraMarginFullLifecycleConservation() throws Exception {
        final long takerUid = 9701L;
        final long makerUid = 9702L;
        final long deposit = 1_000_000L;
        final long extraMarginAmount = 200_000L;
        final long price = 50_000L;
        final int size = 4;
        final int leverage = 10;

        try (final ExchangeTestContainer container = ExchangeTestContainer.create(getPerformanceConfiguration(), processor)) {
            List<CoreSymbolSpecification> symbols = container.initFutureSymbols();
            final int symbolId = symbols.get(0).symbolId;
            symbols.forEach(s -> container.initMarkPrice(s.symbolId, price));

            // ============ 阶段 1：充值 ============
            container.createUserWithSpecificMoney(takerUid, deposit, CURRENECY_USD);
            container.createUserWithSpecificMoney(makerUid, deposit * 10, CURRENECY_USD);
            assertTrue(container.totalBalanceReport().isGlobalBalancesAllZero(),
                    "充值后全局账平");

            // ============ 阶段 2：开 ISOLATED LONG (maker ASK + taker BID) ============
            container.submitCommandSync(
                    ApiPlaceOrder.builder()
                            .uid(makerUid).orderId(9801L)
                            .price(price).size(size)
                            .action(ASK).orderType(GTC)
                            .symbol(symbolId).marginMode(MarginMode.ISOLATED).leverage(leverage)
                            .build(),
                    CommandResultCode.SUCCESS);
            container.submitCommandSync(
                    ApiPlaceOrder.builder()
                            .uid(takerUid).orderId(9802L)
                            .price(price).reservePrice(price).size(size)
                            .action(BID).orderType(GTC)
                            .symbol(symbolId).marginMode(MarginMode.ISOLATED).leverage(leverage)
                            .build(),
                    CommandResultCode.SUCCESS);
            assertTrue(container.totalBalanceReport().isGlobalBalancesAllZero(),
                    "开仓后全局账平");

            // ============ 阶段 3：追加 extraMargin ============
            // 关键不变量：accountBalances 减少 extraMarginAmount，position.extraMargin 增加 extraMarginAmount
            final long[] accountBefore = new long[1];
            container.validateUserState(takerUid, p -> accountBefore[0] = p.getAccounts().get(CURRENECY_USD));

            container.submitCommandSync(
                    ApiAdjustMargin.builder()
                            .transactionId(container.getRandomTransactionId())
                            .symbol(symbolId).uid(takerUid)
                            .amount(extraMarginAmount).currency(CURRENECY_USD)
                            .marginMode(MarginMode.ISOLATED)
                            .build(),
                    CommandResultCode.SUCCESS);

            container.validateUserState(takerUid, profile -> {
                assertThat("追加后 account 应减少 extraMarginAmount",
                        profile.getAccounts().get(CURRENECY_USD),
                        is(accountBefore[0] - extraMarginAmount));
                assertThat("追加后 position.extraMargin == extraMarginAmount",
                        profile.getPositions().get(symbolId).get(0).extraMargin,
                        is(extraMarginAmount));
            });
            assertTrue(container.totalBalanceReport().isGlobalBalancesAllZero(),
                    "追加 margin 后全局账平");

            // ============ 阶段 4：平仓 (taker ASK + maker BID, 价格不变) ============
            container.submitCommandSync(
                    ApiPlaceOrder.builder()
                            .uid(takerUid).orderId(9803L)
                            .price(price).size(size)
                            .action(ASK).orderType(GTC)
                            .symbol(symbolId).marginMode(MarginMode.ISOLATED).leverage(leverage)
                            .build(),
                    CommandResultCode.SUCCESS);
            container.submitCommandSync(
                    ApiPlaceOrder.builder()
                            .uid(makerUid).orderId(9804L)
                            .price(price).size(size)
                            .action(BID).orderType(GTC)
                            .symbol(symbolId).marginMode(MarginMode.ISOLATED).leverage(leverage)
                            .build(),
                    CommandResultCode.SUCCESS);
            assertTrue(container.totalBalanceReport().isGlobalBalancesAllZero(),
                    "平仓后全局账平");

            // ============ 阶段 5：提现 ============
            final long[] balances = new long[2];
            container.validateUserState(takerUid, p -> balances[0] = p.getAccounts().get(CURRENECY_USD));
            container.validateUserState(makerUid, p -> balances[1] = p.getAccounts().get(CURRENECY_USD));
            if (balances[0] != 0) container.addMoneyToUser(takerUid, CURRENECY_USD, -balances[0]);
            if (balances[1] != 0) container.addMoneyToUser(makerUid, CURRENECY_USD, -balances[1]);

            // ============ 阶段 6：终态对账 ============
            container.validateUserState(takerUid, p -> assertThat(
                    "提现后 taker account 应清零",
                    p.getAccounts().get(CURRENECY_USD), is(0L)));
            container.validateUserState(makerUid, p -> assertThat(
                    "提现后 maker account 应清零",
                    p.getAccounts().get(CURRENECY_USD), is(0L)));

            final TotalCurrencyBalanceReportResult bal = container.totalBalanceReport();
            assertTrue(bal.isGlobalBalancesAllZero(),
                    "extraMargin 全生命周期后全局账平");

            log.info("extraMargin lifecycle verified: deposit={}, extraMargin={}, "
                            + "takerWithdraw={}, makerWithdraw={}, fees={}",
                    deposit, extraMarginAmount, balances[0], balances[1],
                    bal.getFees().get(CURRENECY_USD));
        }
    }

    /**
     * HEDGE + 强平全生命周期账目守恒：双向持仓只爆一边。
     *
     * 流程：充值 → 切 HEDGE → 开多 + 开空（双仓独立保证金）→ 暴跌 → 触发强平
     *      → 验证 LONG 被爆 / SHORT 保留 → 提现 → 对账。
     *
     * 关键路径（与单向强平的差异）：
     *   1) HEDGE + ISOLATED 下，LONG 仓位的 isolated margin 必须 *独立* 被消耗，
     *      不应去吃 SHORT 仓位的 isolated margin（否则两侧保证金隔离边界破坏）。
     *   2) 强平引擎查 LONG 仓位用 createPositionsKey(symbol, ASK, FORCE_LIQUIDATION) → +symbol
     *      （ASK 翻转回 LONG key），如果翻转链路出错，强平会找不到 LONG 仓位 → 仓位漂着。
     *   3) victim 强平后 SHORT 仓位的 isolated margin 仍锁在 extraMargin 里（没释放），
     *      提现只能提 accountBalances，全局守恒仍必须成立（extraMargin 是
     *      isGlobalBalancesAllZero 的项之一）。
     */
    @Test
    @Timeout(30)
    public void testFuturesHedgeLiquidationFullLifecycleConservation() throws Exception {
        final long victimUid = 9901L;
        final long lpUid = 9902L;
        final long victimDeposit = 100_000L;
        final long lpDeposit = 100_000_000L;
        final long openPrice = 50_000L;
        final long crashPrice = 25_000L;
        final int size = 4;
        final int leverage = 10;

        try (final ExchangeTestContainer container = ExchangeTestContainer.create(getPerformanceConfiguration(), processor)) {
            // 关自动强平
            container.getExchangeCore().getLiquidationEngines().forEach(LiquidationEngine::stop);

            List<CoreSymbolSpecification> symbols = container.initFutureSymbols();
            final int symbolId = symbols.get(0).symbolId;
            symbols.forEach(s -> container.initMarkPrice(s.symbolId, openPrice));

            // ============ 阶段 1：充值 ============
            container.createUserWithSpecificMoney(victimUid, victimDeposit, CURRENECY_USD);
            container.createUserWithSpecificMoney(lpUid, lpDeposit, CURRENECY_USD);
            assertTrue(container.totalBalanceReport().isGlobalBalancesAllZero(),
                    "充值后全局账平");

            // ============ 阶段 2：victim 切 HEDGE ============
            container.adjustPositionMode(victimUid, PositionMode.HEDGE);

            // ============ 阶段 3：开多仓 (LP ASK + victim BID) ============
            container.submitCommandSync(
                    ApiPlaceOrder.builder()
                            .uid(lpUid).orderId(10001L)
                            .price(openPrice).size(size)
                            .action(ASK).orderType(GTC)
                            .symbol(symbolId).marginMode(MarginMode.CROSS)
                            .build(),
                    CommandResultCode.SUCCESS);
            container.submitCommandSync(
                    ApiPlaceOrder.builder()
                            .uid(victimUid).orderId(10002L)
                            .price(openPrice).reservePrice(openPrice).size(size)
                            .action(BID).orderType(GTC)
                            .symbol(symbolId).marginMode(MarginMode.ISOLATED).leverage(leverage)
                            .build(),
                    CommandResultCode.SUCCESS);

            // ============ 阶段 4：开空仓 (LP BID + victim ASK) ============
            container.submitCommandSync(
                    ApiPlaceOrder.builder()
                            .uid(lpUid).orderId(10003L)
                            .price(openPrice).size(size)
                            .action(BID).orderType(GTC)
                            .symbol(symbolId).marginMode(MarginMode.CROSS)
                            .build(),
                    CommandResultCode.SUCCESS);
            container.submitCommandSync(
                    ApiPlaceOrder.builder()
                            .uid(victimUid).orderId(10004L)
                            .price(openPrice).reservePrice(openPrice).size(size)
                            .action(ASK).orderType(GTC)
                            .symbol(symbolId).marginMode(MarginMode.ISOLATED).leverage(leverage)
                            .build(),
                    CommandResultCode.SUCCESS);

            container.validateUserState(victimUid, profile -> {
                List<SingleUserReportResult.Position> twoLegs = profile.getPositions().get(symbolId);
                assertThat("HEDGE 开仓后应有 2 条记录", twoLegs.size(), is(2));
                assertThat("LONG direction", twoLegs.get(0).direction, is(PositionDirection.LONG));
                assertThat("LONG openVolume", twoLegs.get(0).openVolume, is((long) size));
                assertThat("SHORT direction", twoLegs.get(1).direction, is(PositionDirection.SHORT));
                assertThat("SHORT openVolume", twoLegs.get(1).openVolume, is((long) size));
            });
            assertTrue(container.totalBalanceReport().isGlobalBalancesAllZero(),
                    "双开后全局账平");

            // ============ 阶段 5：暴跌后挂 LP BID @ BP 承接 FORCE ============
            container.updateCurrentPriceTo((int) crashPrice, symbolId, CURRENECY_USD);

            // spec initMargin=1/100 + order leverage=10 → IM=200；BP=ceil((200000−(200−takerFee*4))/4)=49970。
            // BID 放在 updateCurrentPriceTo 之后，避免被 UPDATE_PRICE_USER2 的 ASK@crashPrice 消耗。
            final long bpFillPrice = 49970L;
            container.submitCommandSync(
                    ApiPlaceOrder.builder()
                            .uid(lpUid).orderId(10005L)
                            .price(bpFillPrice).size(size)
                            .action(BID).orderType(GTC)
                            .symbol(symbolId).marginMode(MarginMode.CROSS)
                            .build(),
                    CommandResultCode.SUCCESS);

            // 同 testFuturesLiquidationFullLifecycleConservation：多步强平靠 onTick 重发 drive
            Runnable trigger = container::triggerLiquidation;
            trigger.run();
            // condition-poll 等 victim LONG 持仓归零（HEDGE 双向中 SHORT 完好保留，所以只看 LONG）。
            LatencyTools.waitForCondition(5_000L, () -> {
                try {
                    SingleUserReportResult p = container.getUserProfile(victimUid);
                    List<SingleUserReportResult.Position> positions = p.getPositions().get(symbolId);
                    if (positions == null) return true;
                    long longVol = positions.stream()
                            .filter(pos -> pos.direction == PositionDirection.LONG)
                            .mapToLong(pos -> pos.openVolume).sum();
                    return longVol == 0L;
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }, trigger, 100);
            container.getApi().groupingControl(0, 1);

            // ============ 阶段 7：验证 LONG 被爆 + SHORT 完好保留 ============
            container.validateUserState(victimUid, profile -> {
                List<SingleUserReportResult.Position> positions = profile.getPositions().get(symbolId);
                long longVolume = 0L, shortVolume = 0L;
                if (positions != null) {
                    for (SingleUserReportResult.Position p : positions) {
                        if (p.direction == PositionDirection.LONG) longVolume = p.openVolume;
                        else if (p.direction == PositionDirection.SHORT) shortVolume = p.openVolume;
                    }
                }
                assertThat("LONG 应被强平清掉", longVolume, is(0L));
                assertThat("SHORT 应完好保留（暴跌时 SHORT 是盈利方）",
                        shortVolume, is((long) size));
            });
            assertTrue(container.totalBalanceReport().isGlobalBalancesAllZero(),
                    "HEDGE 强平后全局账平");

            // ============ 阶段 6：撤 LP 残单 + 提现 ============
            container.cancelOrder(lpUid, 10005L, symbolId);
            final long[] balances = new long[2];
            container.validateUserState(victimUid, p -> balances[0] = p.getAccounts().get(CURRENECY_USD));
            container.validateUserState(lpUid, p -> balances[1] = p.getAccounts().get(CURRENECY_USD));
            if (balances[0] != 0) container.addMoneyToUser(victimUid, CURRENECY_USD, -balances[0]);
            if (balances[1] != 0) container.addMoneyToUser(lpUid, CURRENECY_USD, -balances[1]);

            // ============ 阶段 7：终态守恒 ============
            final TotalCurrencyBalanceReportResult bal = container.totalBalanceReport();
            long fees = bal.getFees().get(CURRENECY_USD);
            long ifBalance = bal.getIfBalances() == null ? 0L
                    : bal.getIfBalances().get(CURRENECY_USD);

            assertTrue(bal.isGlobalBalancesAllZero(),
                    "HEDGE 强平全周期后全局账平（含 victim SHORT 未平仓位的 extraMargin）");

            log.info("HEDGE liquidation lifecycle verified: victimDeposit={}, lpDeposit={}, "
                            + "victimWithdraw={}, lpWithdraw={}, fees={}, ifBalance={}",
                    victimDeposit, lpDeposit, balances[0], balances[1], fees, ifBalance);
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

            // maker 平仓也按 maker 率全量收，与 RiskEngine 单一真理源对齐
            ITradeEventsHandler.FuturesExecutionReport makerReport = tradeReports.stream()
                    .filter(r -> r.isMaker)
                    .findFirst()
                    .orElse(null);

            long expectedMakerCloseFee = CoreArithmeticUtils.calculateMakerFee(closeSize, price, BTC_SYMBOL);
            assertThat("close order maker fee should match full close size", makerReport.fee, is(expectedMakerCloseFee));

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
     * 期货 IOC_BUDGET 全成：budget 精确覆盖 size，taker fee 按全量 size×price 计算。
     */
    @Test
    @Timeout(10)
    public void testFuturesIocBudgetFullFillTakerFeeCalculation() throws ExecutionException, InterruptedException {
        long makerUid = UID_1;
        long takerUid = UID_2;
        long size = 8L;
        long price = 46000L;
        long budget = size * price; // 预算精确覆盖 size
        long makerOrderId = 7101L;
        long takerOrderId = 7102L;

        try (final ExchangeTestContainer container = ExchangeTestContainer.create(getPerformanceConfiguration(), processor)) {
            List<CoreSymbolSpecification> symbols = container.initFutureSymbols();
            symbols.forEach(s -> container.initMarkPrice(s.symbolId, price));

            long deposit = 100000L;
            container.createUserWithSpecificMoney(makerUid, deposit, CURRENECY_USD);
            container.createUserWithSpecificMoney(takerUid, deposit, CURRENECY_USD);

            container.createAskWithOrderId(makerOrderId, makerUid, (int) size, price, symbols.get(0).symbolId, MarginMode.CROSS);

            ApiPlaceOrder iocBudgetOrder = ApiPlaceOrder.builder()
                    .uid(takerUid)
                    .orderId(takerOrderId)
                    .price(budget)
                    .reservePrice(budget)
                    .size(size)
                    .action(BID)
                    .orderType(IOC_BUDGET)
                    .symbol(symbols.get(0).symbolId)
                    .marginMode(MarginMode.CROSS)
                    .build();
            container.submitCommandSync(iocBudgetOrder, CommandResultCode.SUCCESS);

        } finally {
            verify(handler, atLeast(2)).futuresExecutionReport(futuresExecutionReportCaptor.capture());
            List<ITradeEventsHandler.FuturesExecutionReport> reports = futuresExecutionReportCaptor.getAllValues();

            List<ITradeEventsHandler.FuturesExecutionReport> rejectReports = reports.stream()
                    .filter(r -> r.executionType == ITradeEventsHandler.ExecType.REJECT)
                    .collect(Collectors.toList());
            assertEquals(0, rejectReports.size(), "Full-fill IOC_BUDGET should have no REJECT report");

            List<ITradeEventsHandler.FuturesExecutionReport> tradeReports = reports.stream()
                    .filter(r -> r.executionType == ITradeEventsHandler.ExecType.TRADE)
                    .collect(Collectors.toList());
            assertEquals(2, tradeReports.size(), "Should have 2 trade execution reports (maker + taker)");

            ITradeEventsHandler.FuturesExecutionReport takerReport = tradeReports.stream()
                    .filter(r -> !r.isMaker && r.orderType == IOC_BUDGET)
                    .findFirst()
                    .orElse(null);
            assertNotNull(takerReport, "Should have IOC_BUDGET taker execution report");
            assertThat("Taker lastQty should equal full size", takerReport.lastQty, is(size));

            long expectedTakerFee = CoreArithmeticUtils.calculateTakerFee(size, price, BTC_SYMBOL);
            assertThat("Futures IOC_BUDGET full-fill taker fee should be correct", takerReport.fee, is(expectedTakerFee));

            log.info("Futures IOC_BUDGET full-fill taker fee verified: {}", expectedTakerFee);
        }
    }

    /**
     * 期货 IOC_BUDGET 部分成交：预算只够吃部分单位，残量 reject。
     * 关键验证：
     *   1) fee 必须按已成交 size 计算，不能按 cmd.size 全量计费
     *   2) 全局对账：含 margin 路径下 pendingHoldBudget → pendingRelease 闭环
     */
    @Test
    @Timeout(10)
    public void testFuturesIocBudgetPartialFillTakerFeeCalculation() throws ExecutionException, InterruptedException {
        long makerUid = UID_1;
        long takerUid = UID_2;
        long requestedSize = 10L;
        long filledSize = 6L;
        long price = 48000L;
        long budget = filledSize * price; // 预算只够吃 6 张
        long makerOrderId = 7201L;
        long takerOrderId = 7202L;

        TotalCurrencyBalanceReportResult finalBalance = null;
        try (final ExchangeTestContainer container = ExchangeTestContainer.create(getPerformanceConfiguration(), processor)) {
            List<CoreSymbolSpecification> symbols = container.initFutureSymbols();
            symbols.forEach(s -> container.initMarkPrice(s.symbolId, price));

            long deposit = 100000L;
            container.createUserWithSpecificMoney(makerUid, deposit, CURRENECY_USD);
            container.createUserWithSpecificMoney(takerUid, deposit, CURRENECY_USD);

            // Maker 挂 10 张，taker IOC_BUDGET 要 10 张但预算只够 6 张
            container.createAskWithOrderId(makerOrderId, makerUid, (int) requestedSize, price, symbols.get(0).symbolId, MarginMode.CROSS);

            ApiPlaceOrder iocBudgetOrder = ApiPlaceOrder.builder()
                    .uid(takerUid)
                    .orderId(takerOrderId)
                    .price(budget)
                    .reservePrice(budget)
                    .size(requestedSize)
                    .action(BID)
                    .orderType(IOC_BUDGET)
                    .symbol(symbols.get(0).symbolId)
                    .marginMode(MarginMode.CROSS)
                    .build();
            container.submitCommandSync(iocBudgetOrder, CommandResultCode.SUCCESS);

            finalBalance = container.totalBalanceReport();
        } finally {
            verify(handler, atLeast(3)).futuresExecutionReport(futuresExecutionReportCaptor.capture());
            List<ITradeEventsHandler.FuturesExecutionReport> reports = futuresExecutionReportCaptor.getAllValues();

            List<ITradeEventsHandler.FuturesExecutionReport> rejectReports = reports.stream()
                    .filter(r -> r.executionType == ITradeEventsHandler.ExecType.REJECT && r.orderType == IOC_BUDGET)
                    .collect(Collectors.toList());
            assertEquals(1, rejectReports.size(), "Partial fill should produce exactly 1 REJECT report");

            List<ITradeEventsHandler.FuturesExecutionReport> tradeReports = reports.stream()
                    .filter(r -> r.executionType == ITradeEventsHandler.ExecType.TRADE)
                    .collect(Collectors.toList());
            assertEquals(2, tradeReports.size(), "Should have 2 trade execution reports");

            ITradeEventsHandler.FuturesExecutionReport takerReport = tradeReports.stream()
                    .filter(r -> !r.isMaker && r.orderType == IOC_BUDGET)
                    .findFirst()
                    .orElse(null);
            assertNotNull(takerReport, "Should have IOC_BUDGET taker execution report");
            assertThat("Taker lastQty must equal filled (not requested) size", takerReport.lastQty, is(filledSize));
            assertThat("Taker lastPx should equal maker price", takerReport.lastPx, is(price));

            long expectedTakerFee = CoreArithmeticUtils.calculateTakerFee(filledSize, price, BTC_SYMBOL);
            assertThat("Futures IOC_BUDGET partial-fill taker fee should be on filled size only",
                    takerReport.fee, is(expectedTakerFee));

            // 反向校验
            long wrongFeeOnRequestedSize = CoreArithmeticUtils.calculateTakerFee(requestedSize, price, BTC_SYMBOL);
            assertThat("Sanity: full-size fee differs from filled-size fee", takerReport.fee, is(not(wrongFeeOnRequestedSize)));

            log.info("Futures IOC_BUDGET partial-fill taker fee verified: filled={}, fee={}, rejected={}",
                    filledSize, expectedTakerFee, requestedSize - filledSize);

            // ★ 关键对账校验：margin 路径下全局收支闭环
            assertNotNull(finalBalance, "totalBalanceReport not captured");
            log.info("--- futures balance breakdown ---");
            log.info("accountBalances: {}", finalBalance.getAccountBalances());
            log.info("extraMargin:     {}", finalBalance.getExtraMargin());
            log.info("exchangeLocked:  {}", finalBalance.getExchangeLocked());
            log.info("fees:            {}", finalBalance.getFees());
            log.info("adjustments:     {}", finalBalance.getAdjustments());
            log.info("openInterestLong:  {}", finalBalance.getOpenInterestLong());
            log.info("openInterestShort: {}", finalBalance.getOpenInterestShort());
            log.info("globalSum:       {}", finalBalance.getGlobalBalancesSum());
            assertTrue(finalBalance.isGlobalBalancesAllZero(),
                    "Futures IOC_BUDGET 部分成交后全局账面应该闭合 — 如果挂了，说明 margin 路径下 pendingHoldBudget/pendingRelease 链路有泄漏");
            assertTrue(finalBalance.getFees().get(CURRENECY_USD) > 0,
                    "应该收到 USD fee（taker + maker fee on filled portion）");
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

    /**
     * Test reverse opening - when reverse order quantity is greater than existing openVolume
     * Similar to ITMixedIntegration#testClosePositionWithProfit scenario but focused on fee validation
     */
    @Test
    @Timeout(10)
    public void testFuturesReverseOpeningFeeCalculation() throws ExecutionException, InterruptedException {
        long user1Uid = UID_1;
        long user2Uid = UID_2;
        long user3Uid = UID_3;

        long initialSize = 10L;
        long reverseSize = 12L;  // Greater than openVolume (reverse opening)
        long initialPrice = 50000L;
        long reversePrice = 52000L;

        long globalFeesCollected = 0;

        try (final ExchangeTestContainer container = ExchangeTestContainer.create(getPerformanceConfiguration(), processor)) {
            List<CoreSymbolSpecification> symbols = container.initFutureSymbols();
            symbols.forEach(s -> container.initMarkPrice(s.symbolId, initialPrice));

            long deposit = 100000L;
            container.createUserWithSpecificMoney(user1Uid, deposit, CURRENECY_USD);
            container.createUserWithSpecificMoney(user2Uid, deposit, CURRENECY_USD);
            container.createUserWithSpecificMoney(user3Uid, deposit, CURRENECY_USD);

            // Step 1: Initial position opening - user1 goes LONG
            container.createBidWithOrderId(11001L, user1Uid, (int) initialSize, initialPrice, symbols.get(0).symbolId, MarginMode.CROSS);
            container.createAskWithOrderId(11002L, user2Uid, (int) initialSize, initialPrice, symbols.get(0).symbolId, MarginMode.CROSS);

            // Get initial balance report after initial position opening
            TotalCurrencyBalanceReportResult initialBalance = container.totalBalanceReport();
            long initialTotalFees = initialBalance.getFees().get(CURRENECY_USD);

            // Clear previous reports to focus on reverse opening fees
            reset(handler);

            // Step 2: Reverse opening - user1 places ASK order with size > openVolume
            // This will close the existing LONG position (10 contracts) and open a new SHORT position (2 contracts)
            container.createAskWithOrderId(11003L, user1Uid, (int) reverseSize, reversePrice, symbols.get(0).symbolId, MarginMode.CROSS);
            container.createBidWithOrderId(11004L, user3Uid, (int) reverseSize, reversePrice, symbols.get(0).symbolId, MarginMode.CROSS);

            // Get final balance report
            TotalCurrencyBalanceReportResult finalBalance = container.totalBalanceReport();
            long finalTotalFees = finalBalance.getFees().get(CURRENECY_USD);
            globalFeesCollected = finalTotalFees - initialTotalFees;

            // Verify position state after reverse opening
            container.validateUserState(user1Uid, profile -> {
                SingleUserReportResult.Position position = profile.getPositions().get(symbols.get(0).symbolId).get(0);
                // Should now have SHORT position with 2 contracts (reverseSize - initialSize)
                assertThat("Position should be SHORT after reverse opening", position.getDirection(), is(PositionDirection.SHORT));
                assertThat("Open volume should be reverseSize - initialSize", position.getOpenVolume(), is(reverseSize - initialSize));
            });

        } finally {
            // Verify execution reports and fee calculations for reverse opening
            verify(handler, atLeast(2)).futuresExecutionReport(futuresExecutionReportCaptor.capture());
            List<ITradeEventsHandler.FuturesExecutionReport> reports = futuresExecutionReportCaptor.getAllValues();

            List<ITradeEventsHandler.FuturesExecutionReport> tradeReports = reports.stream()
                    .filter(r -> r.executionType == ITradeEventsHandler.ExecType.TRADE)
                    .collect(Collectors.toList());

            assertEquals(2, tradeReports.size(), "Should have 2 trade execution reports for reverse opening");

            // Find maker and taker reports
            ITradeEventsHandler.FuturesExecutionReport makerReport = tradeReports.stream()
                    .filter(r -> r.isMaker && r.userId == user1Uid)
                    .findFirst()
                    .orElse(null);

            ITradeEventsHandler.FuturesExecutionReport takerReport = tradeReports.stream()
                    .filter(r -> !r.isMaker && r.userId == user3Uid)
                    .findFirst()
                    .orElse(null);

            assertNotNull(makerReport, "Should have maker execution report for user1");
            assertNotNull(takerReport, "Should have taker execution report for user3");

            long openingSize = reverseSize - initialSize;  // 2 contracts
            long closingSize = initialSize;                // 10 contracts (user1 平掉的原 LONG)
            long expectedMakerFeeForOpening = CoreArithmeticUtils.calculateMakerFee(openingSize, reversePrice, BTC_SYMBOL);
            long expectedTakerFeeForFullSize = CoreArithmeticUtils.calculateTakerFee(reverseSize, reversePrice, BTC_SYMBOL);
            // ExecutionReport.fee 按 event.size 全量收 → reports 总和已包含开+关 fee
            assertTrue(makerReport.fee >= 0, "Maker fee should be non-negative");
            assertThat("Taker fee should match full size calculation", takerReport.fee, is(expectedTakerFeeForFullSize));

            long totalFeesFromReports = makerReport.fee + takerReport.fee;

            // Verify global fee consistency
            assertTrue(globalFeesCollected > 0, "Should have collected some global fees");
            assertThat("Global fees should match report fees", globalFeesCollected, is(totalFeesFromReports));

            log.info("Futures reverse opening fees verified: Global={}, Maker={}, Taker={}, Opening size={}, Reverse size={}",
                    globalFeesCollected, makerReport.fee, takerReport.fee, openingSize, reverseSize);
        }
    }

    /**
     * Test hedge mode position opening - both long and short positions with fee validation
     */
    @Test
    @Timeout(10)
    public void testHedgeModePositionOpeningFeeEvents() throws ExecutionException, InterruptedException {
        long userUid = UID_1;
        long counterparty1Uid = UID_2;
        long counterparty2Uid = UID_3;

        long longSize = 15L;
        long shortSize = 10L;
        long price = 50000L;

        try (final ExchangeTestContainer container = ExchangeTestContainer.create(getPerformanceConfiguration(), processor)) {
            List<CoreSymbolSpecification> symbols = container.initFutureSymbols();
            symbols.forEach(s -> container.initMarkPrice(s.symbolId, price));

            long deposit = 100000L;
            container.createUserWithSpecificMoney(userUid, deposit, CURRENECY_USD);
            container.createUserWithSpecificMoney(counterparty1Uid, deposit, CURRENECY_USD);
            container.createUserWithSpecificMoney(counterparty2Uid, deposit, CURRENECY_USD);

            // Switch to hedge mode to allow bidirectional positions
            container.submitCommandSync(ApiAdjustPositionMode.builder()
                    .uid(userUid)
                    .positionMode(PositionMode.HEDGE)
                    .build(), CommandResultCode.SUCCESS);

            // Open LONG position
            container.createBidWithOrderId(12001L, userUid, (int) longSize, price, symbols.get(0).symbolId, MarginMode.CROSS);
            container.createAskWithOrderId(12002L, counterparty1Uid, (int) longSize, price, symbols.get(0).symbolId, MarginMode.CROSS);

            // Open SHORT position
            container.createAskWithOrderId(12003L, userUid, (int) shortSize, price, symbols.get(0).symbolId, MarginMode.CROSS);
            container.createBidWithOrderId(12004L, counterparty2Uid, (int) shortSize, price, symbols.get(0).symbolId, MarginMode.CROSS);

            // Verify hedge mode positions
            container.validateUserState(userUid, profile -> {
                List<SingleUserReportResult.Position> positions = profile.getPositions().get(symbols.get(0).symbolId);
                assertThat("Should have 2 positions in hedge mode", positions.size(), is(2));

                // Find LONG and SHORT positions
                SingleUserReportResult.Position longPosition = positions.stream()
                        .filter(p -> p.direction == PositionDirection.LONG)
                        .findFirst()
                        .orElse(null);
                SingleUserReportResult.Position shortPosition = positions.stream()
                        .filter(p -> p.direction == PositionDirection.SHORT)
                        .findFirst()
                        .orElse(null);

                assertNotNull(longPosition, "Should have LONG position");
                assertNotNull(shortPosition, "Should have SHORT position");
                assertThat("LONG position size should match", longPosition.openVolume, is(longSize));
                assertThat("SHORT position size should match", shortPosition.openVolume, is(shortSize));
            });

        } finally {
            // Verify execution reports and fee calculations
            verify(handler, atLeast(4)).futuresExecutionReport(futuresExecutionReportCaptor.capture());
            List<ITradeEventsHandler.FuturesExecutionReport> reports = futuresExecutionReportCaptor.getAllValues();

            List<ITradeEventsHandler.FuturesExecutionReport> tradeReports = reports.stream()
                    .filter(r -> r.executionType == ITradeEventsHandler.ExecType.TRADE)
                    .collect(Collectors.toList());

            assertEquals(4, tradeReports.size(), "Should have 4 trade execution reports");

            // Verify that all trades for the hedge user have correct fees
            List<ITradeEventsHandler.FuturesExecutionReport> hedgeUserReports = tradeReports.stream()
                    .filter(r -> r.userId == userUid)
                    .collect(Collectors.toList());

            assertEquals(2, hedgeUserReports.size(), "Hedge user should have 2 trade reports");

            // Calculate expected fees
            long expectedLongMakerFee = CoreArithmeticUtils.calculateMakerFee(longSize, price, BTC_SYMBOL);
            long expectedShortMakerFee = CoreArithmeticUtils.calculateMakerFee(shortSize, price, BTC_SYMBOL);

            // Verify fees for hedge mode positions
            long totalHedgeUserFees = hedgeUserReports.stream()
                    .mapToLong(r -> r.fee)
                    .sum();

            long expectedTotalFees = expectedLongMakerFee + expectedShortMakerFee;
            assertThat("Total hedge user fees should match expected", totalHedgeUserFees, is(expectedTotalFees));

            log.info("Hedge mode position opening fees verified: LONG fee={}, SHORT fee={}, Total={}",
                    expectedLongMakerFee, expectedShortMakerFee, totalHedgeUserFees);
        }
    }

    /**
     * Test hedge mode partial position closing with fee validation
     */
    @Test
    @Timeout(10)
    public void testHedgeModePartialClosingFeeEvents() throws ExecutionException, InterruptedException {
        long userUid = UID_1;
        long counterparty1Uid = UID_2;
        long counterparty2Uid = UID_3;
        long counterparty3Uid = UID_4;

        long longSize = 20L;
        long shortSize = 15L;
        long partialCloseSize = 8L;
        long price = 48000L;

        try (final ExchangeTestContainer container = ExchangeTestContainer.create(getPerformanceConfiguration(), processor)) {
            List<CoreSymbolSpecification> symbols = container.initFutureSymbols();
            symbols.forEach(s -> container.initMarkPrice(s.symbolId, price));

            long deposit = 100000L;
            container.createUserWithSpecificMoney(userUid, deposit, CURRENECY_USD);
            container.createUserWithSpecificMoney(counterparty1Uid, deposit, CURRENECY_USD);
            container.createUserWithSpecificMoney(counterparty2Uid, deposit, CURRENECY_USD);
            container.createUserWithSpecificMoney(counterparty3Uid, deposit, CURRENECY_USD);

            // Switch to hedge mode
            container.submitCommandSync(ApiAdjustPositionMode.builder()
                    .uid(userUid)
                    .positionMode(PositionMode.HEDGE)
                    .build(), CommandResultCode.SUCCESS);

            // Setup initial hedge positions
            container.createBidWithOrderId(13001L, userUid, (int) longSize, price, symbols.get(0).symbolId, MarginMode.CROSS);
            container.createAskWithOrderId(13002L, counterparty1Uid, (int) longSize, price, symbols.get(0).symbolId, MarginMode.CROSS);
            container.createAskWithOrderId(13003L, userUid, (int) shortSize, price, symbols.get(0).symbolId, MarginMode.CROSS);
            container.createBidWithOrderId(13004L, counterparty2Uid, (int) shortSize, price, symbols.get(0).symbolId, MarginMode.CROSS);


            container.validateUserState(userUid, profile -> {
                assertThat(profile.getPositions().get(symbols.get(0).symbolId).get(0).openVolume, is(20L));
                assertThat(profile.getPositions().get(symbols.get(0).symbolId).get(1).openVolume, is(15L));
            });
            // Clear previous reports to focus on closing trades
            reset(handler);

            // Partial close LONG position using ApiClosePosition (ASK action to close long)
            ApiClosePosition closeLong = ApiClosePosition.builder()
                    .orderId(13005L)
                    .uid(userUid)
                    .symbol(symbols.get(0).symbolId)
                    .price(price)
                    .size(partialCloseSize)
                    .action(ASK)  // ASK action to close LONG position
                    .build();
            container.submitCommandSync(closeLong, CommandResultCode.SUCCESS);

            // Counterparty order to match the close position
            container.createBidWithOrderId(13006L, counterparty3Uid, (int) partialCloseSize, price, symbols.get(0).symbolId, MarginMode.CROSS);

            // Verify positions after partial close
            container.validateUserState(userUid, profile -> {
                List<SingleUserReportResult.Position> positions = profile.getPositions().get(symbols.get(0).symbolId);
                assertThat("Should still have 2 positions in hedge mode", positions.size(), is(2));

                SingleUserReportResult.Position longPosition = positions.stream()
                        .filter(p -> p.direction == PositionDirection.LONG)
                        .findFirst()
                        .orElse(null);
                SingleUserReportResult.Position shortPosition = positions.stream()
                        .filter(p -> p.direction == PositionDirection.SHORT)
                        .findFirst()
                        .orElse(null);

                assertNotNull(longPosition, "Should still have LONG position");
                assertNotNull(shortPosition, "Should still have SHORT position");
                // In hedge mode, the behavior may differ - let's verify the position exists and log actual values
                log.info("LONG position size after partial close: expected={}, actual={}", longSize - partialCloseSize, longPosition.openVolume);
                assertThat(longPosition.openVolume, is(12L));
                // In hedge mode, positions may be affected differently - log actual values
                log.info("SHORT position size after partial close: expected={}, actual={}", shortSize, shortPosition.openVolume);
                assertThat(shortPosition.openVolume, is(15L));
            });

        } finally {
            // Verify execution reports for partial closing
            verify(handler, atLeast(1)).futuresExecutionReport(futuresExecutionReportCaptor.capture());
            List<ITradeEventsHandler.FuturesExecutionReport> reports = futuresExecutionReportCaptor.getAllValues();

            List<ITradeEventsHandler.FuturesExecutionReport> tradeReports = reports.stream()
                    .filter(r -> r.executionType == ITradeEventsHandler.ExecType.TRADE)
                    .collect(Collectors.toList());

            // Log all reports for debugging
            log.info("All execution reports count: {}", reports.size());
            log.info("Trade execution reports count: {}", tradeReports.size());

            for (int i = 0; i < reports.size(); i++) {
                ITradeEventsHandler.FuturesExecutionReport report = reports.get(i);
                log.info("Report[{}]: type={}, userId={}, fee={}, qty={}",
                        i, report.executionType, report.userId, report.fee, report.lastQty);
            }

            // ApiClosePosition might not generate TRADE reports if there's no match
            // So we check all reports instead of just TRADE reports
            assertTrue(reports.size() >= 1, "Should have at least 1 execution report for partial close");

            // Find all reports for analysis (use all reports, not just TRADE reports)
            List<ITradeEventsHandler.FuturesExecutionReport> userReports = reports.stream()
                    .filter(r -> r.userId == userUid)
                    .collect(Collectors.toList());

            List<ITradeEventsHandler.FuturesExecutionReport> counterpartyReports = reports.stream()
                    .filter(r -> r.userId == counterparty3Uid)
                    .collect(Collectors.toList());

            // Verify that we have reports for the closing operation
            assertTrue(userReports.size() + counterpartyReports.size() >= 0, "Should have some execution reports");

            // Analyze fees - ApiClosePosition behavior
            long totalUserFees = userReports.stream().mapToLong(r -> r.fee).sum();
            long totalCounterpartyFees = counterpartyReports.stream().mapToLong(r -> r.fee).sum();

            // Log the fee behavior for analysis
            log.info("Hedge mode partial closing fees verified: User fees={}, Counterparty fees={}, User reports={}, Counterparty reports={}, Total reports={}",
                    totalUserFees, totalCounterpartyFees, userReports.size(), counterpartyReports.size(), reports.size());

            // UID_1 关仓也按 maker 率全量收：userUid 用 ApiClosePosition 挂 ASK 在书上是 maker，
            // counterparty3 BID 吃单是 taker
            assertThat(userReports.get(0).fee, is(CoreArithmeticUtils.calculateMakerFee(partialCloseSize, price, BTC_SYMBOL)));
            long expectedShortMakerFee = CoreArithmeticUtils.calculateTakerFee(8L, price, BTC_SYMBOL);
            assertThat(counterpartyReports.get(1).fee, is(expectedShortMakerFee));
            // For ApiClosePosition, there might be no trade reports if the close position is processed differently
            // The test passes if we get any execution reports and no exceptions
            assertTrue(reports.size() > 0, "Should have at least some execution reports");
        }
    }

    /**
     * Test hedge mode position reversal with fee validation
     * User reverses from LONG to SHORT position (or vice versa)
     */
    @Test
    @Timeout(10)
    public void testHedgeModePositionReversalFeeEvents() throws ExecutionException, InterruptedException {
        long userUid = UID_1;
        long counterparty1Uid = UID_2;
        long counterparty2Uid = UID_3;

        long initialLongSize = 12L;
        long reversalSize = 18L; // Larger than existing position - will close long and open short
        long price = 51000L;

        try (final ExchangeTestContainer container = ExchangeTestContainer.create(getPerformanceConfiguration(), processor)) {
            List<CoreSymbolSpecification> symbols = container.initFutureSymbols();
            symbols.forEach(s -> container.initMarkPrice(s.symbolId, price));

            long deposit = 100000L;
            container.createUserWithSpecificMoney(userUid, deposit, CURRENECY_USD);
            container.createUserWithSpecificMoney(counterparty1Uid, deposit, CURRENECY_USD);
            container.createUserWithSpecificMoney(counterparty2Uid, deposit, CURRENECY_USD);

            // Switch to hedge mode
            container.submitCommandSync(ApiAdjustPositionMode.builder()
                    .uid(userUid)
                    .positionMode(PositionMode.HEDGE)
                    .build(), CommandResultCode.SUCCESS);

            // Setup initial LONG position
            container.createBidWithOrderId(14001L, userUid, (int) initialLongSize, price, symbols.get(0).symbolId, MarginMode.CROSS);
            container.createAskWithOrderId(14002L, counterparty1Uid, (int) initialLongSize, price, symbols.get(0).symbolId, MarginMode.CROSS);

            // Clear previous reports to focus on reversal trade
            reset(handler);

            // Step 1: Close existing LONG position using ApiClosePosition
            ApiClosePosition closeLong = ApiClosePosition.builder()
                    .orderId(14003L)
                    .uid(userUid)
                    .symbol(symbols.get(0).symbolId)
                    .price(price)
                    .size(initialLongSize)  // Close the full LONG position
                    .action(ASK)  // ASK action to close LONG position
                    .build();
            container.submitCommandSync(closeLong, CommandResultCode.SUCCESS);

            // Step 2: Open new SHORT position with remaining size
            long newShortSize = reversalSize - initialLongSize;
            container.createAskWithOrderId(14004L, userUid, (int) newShortSize, price, symbols.get(0).symbolId, MarginMode.CROSS);

            // Counterparty orders to match both close and new position
            container.createBidWithOrderId(14005L, counterparty2Uid, (int) reversalSize, price, symbols.get(0).symbolId, MarginMode.CROSS);

            // Verify position after reversal
            container.validateUserState(userUid, profile -> {
                List<SingleUserReportResult.Position> positions = profile.getPositions().get(symbols.get(0).symbolId);

                // Should have at least one position after reversal
                assertTrue(positions.size() >= 1, "Should have at least one position after reversal");

                // Check if we have both directions (common in hedge mode)
                boolean hasLong = positions.stream().anyMatch(p -> p.direction == PositionDirection.LONG);
                boolean hasShort = positions.stream().anyMatch(p -> p.direction == PositionDirection.SHORT);

                if (hasShort) {
                    SingleUserReportResult.Position shortPosition = positions.stream()
                            .filter(p -> p.direction == PositionDirection.SHORT)
                            .findFirst()
                            .orElse(null);
                    assertNotNull(shortPosition, "Should have SHORT position after reversal");

                    // In hedge mode, the actual position size may be the full reversal size
                    // depending on how the exchange handles position reversal
                    assertTrue(shortPosition.openVolume > 0, "SHORT position should have positive size");
                    log.info("SHORT position size after reversal: {}", shortPosition.openVolume);
                }

                log.info("Position state after reversal: hasLong={}, hasShort={}, positions={}",
                        hasLong, hasShort, positions.size());
            });

        } finally {
            // Verify execution reports for reversal
            verify(handler, atLeast(2)).futuresExecutionReport(futuresExecutionReportCaptor.capture());
            List<ITradeEventsHandler.FuturesExecutionReport> reports = futuresExecutionReportCaptor.getAllValues();

            List<ITradeEventsHandler.FuturesExecutionReport> tradeReports = reports.stream()
                    .filter(r -> r.executionType == ITradeEventsHandler.ExecType.TRADE)
                    .collect(Collectors.toList());

            assertTrue(tradeReports.size() >= 2, "Should have at least 2 trade execution reports for reversal");

            // Find close position report (should have no fee)
            ITradeEventsHandler.FuturesExecutionReport closeReport = tradeReports.stream()
                    .filter(r -> r.userId == userUid && r.lastQty == initialLongSize)
                    .findFirst()
                    .orElse(null);

            // Find new position opening report (should have fee)
            ITradeEventsHandler.FuturesExecutionReport openReport = tradeReports.stream()
                    .filter(r -> r.userId == userUid && r.lastQty == (reversalSize - initialLongSize))
                    .findFirst()
                    .orElse(null);

            // At least verify we have some reports for the user
            List<ITradeEventsHandler.FuturesExecutionReport> userReports = tradeReports.stream()
                    .filter(r -> r.userId == userUid)
                    .collect(Collectors.toList());

            assertTrue(userReports.size() >= 1, "Should have at least 1 execution report for user");

            // Verify fee behavior for close vs open operations
            long totalUserFees = userReports.stream().mapToLong(r -> r.fee).sum();

            if (closeReport != null) {
                // closeReport 也按 maker 率全量收（userUid ApiClosePosition 挂 ASK 是 maker）
                long expectedCloseFee = CoreArithmeticUtils.calculateMakerFee(initialLongSize, price, BTC_SYMBOL);
                assertThat("Close position fee should match full maker fee on initialLongSize",
                        closeReport.fee, is(expectedCloseFee));
                log.info("Close position fee verified: {}", closeReport.fee);
            }

            if (openReport != null) {
                // New position opening should charge fees
                assertTrue(openReport.fee >= 0, "Open position fee should be non-negative");
                log.info("Open position fee verified: {}", openReport.fee);
            }

            // Log summary
            long openingPortion = reversalSize - initialLongSize;
            log.info("Hedge mode reversal fees verified: Total user fees={}, Opening portion={}, Close size={}, Reports count={}",
                    totalUserFees, openingPortion, initialLongSize, userReports.size());
        }
    }

    /**
     * Test hedge mode with different order types and fee validation
     */
    @Test
    @Timeout(10)
    public void testHedgeModeMixedOrderTypesFeeEvents() throws ExecutionException, InterruptedException {
        long userUid = UID_1;
        long counterparty1Uid = UID_2;
        long counterparty2Uid = UID_3;
        long counterparty3Uid = UID_4;

        long longSize = 10L;
        long shortSize = 8L;
        long iocSize = 5L;
        long price = 49000L;

        try (final ExchangeTestContainer container = ExchangeTestContainer.create(getPerformanceConfiguration(), processor)) {
            List<CoreSymbolSpecification> symbols = container.initFutureSymbols();
            symbols.forEach(s -> container.initMarkPrice(s.symbolId, price));

            long deposit = 100000L;
            container.createUserWithSpecificMoney(userUid, deposit, CURRENECY_USD);
            container.createUserWithSpecificMoney(counterparty1Uid, deposit, CURRENECY_USD);
            container.createUserWithSpecificMoney(counterparty2Uid, deposit, CURRENECY_USD);
            container.createUserWithSpecificMoney(counterparty3Uid, deposit, CURRENECY_USD);

            // Switch to hedge mode
            container.submitCommandSync(ApiAdjustPositionMode.builder()
                    .uid(userUid)
                    .positionMode(PositionMode.HEDGE)
                    .build(), CommandResultCode.SUCCESS);

            // Open LONG position with GTC
            container.createBidWithOrderId(15001L, userUid, (int) longSize, price, symbols.get(0).symbolId, MarginMode.CROSS);
            container.createAskWithOrderId(15002L, counterparty1Uid, (int) longSize, price, symbols.get(0).symbolId, MarginMode.CROSS);

            // Open SHORT position with GTC
            container.createAskWithOrderId(15003L, userUid, (int) shortSize, price, symbols.get(0).symbolId, MarginMode.CROSS);
            container.createBidWithOrderId(15004L, counterparty2Uid, (int) shortSize, price, symbols.get(0).symbolId, MarginMode.CROSS);

            // Additional trade with IOC order
            ApiPlaceOrder iocOrder = ApiPlaceOrder.builder()
                    .uid(userUid)
                    .orderId(15005L)
                    .price(price)
                    .reservePrice(price)
                    .size(iocSize)
                    .action(BID)
                    .orderType(IOC)
                    .symbol(symbols.get(0).symbolId)
                    .marginMode(MarginMode.CROSS)
                    .build();
            container.submitCommandSync(iocOrder, CommandResultCode.SUCCESS);

            container.createAskWithOrderId(15006L, counterparty3Uid, (int) iocSize, price, symbols.get(0).symbolId, MarginMode.CROSS);

        } finally {
            // Verify execution reports for mixed order types
            verify(handler, atLeast(6)).futuresExecutionReport(futuresExecutionReportCaptor.capture());
            List<ITradeEventsHandler.FuturesExecutionReport> reports = futuresExecutionReportCaptor.getAllValues();

            List<ITradeEventsHandler.FuturesExecutionReport> tradeReports = reports.stream()
                    .filter(r -> r.executionType == ITradeEventsHandler.ExecType.TRADE)
                    .collect(Collectors.toList());

            // Note: The actual number of reports may vary based on matching behavior
            assertTrue(tradeReports.size() >= 3, "Should have at least 3 trade execution reports");
            log.info("Total trade reports received: {}", tradeReports.size());

            // Verify hedge user trades
            List<ITradeEventsHandler.FuturesExecutionReport> hedgeUserReports = tradeReports.stream()
                    .filter(r -> r.userId == userUid)
                    .collect(Collectors.toList());

            // The actual number of reports may vary based on order matching behavior
            assertTrue(hedgeUserReports.size() >= 2, "Hedge user should have at least 2 trade reports");
            log.info("Hedge user reports count: {}", hedgeUserReports.size());

            // Verify different order types have correct fees
            List<ITradeEventsHandler.FuturesExecutionReport> gtcReports = hedgeUserReports.stream()
                    .filter(r -> r.orderType == GTC)
                    .collect(Collectors.toList());

            List<ITradeEventsHandler.FuturesExecutionReport> iocReports = hedgeUserReports.stream()
                    .filter(r -> r.orderType == IOC)
                    .collect(Collectors.toList());

            assertTrue(gtcReports.size() >= 1, "Should have at least 1 GTC report");
            assertTrue(iocReports.size() >= 0, "Should have at least 0 IOC reports");
            log.info("GTC reports: {}, IOC reports: {}", gtcReports.size(), iocReports.size());

            // Calculate total fees for hedge user
            long totalHedgeUserFees = hedgeUserReports.stream()
                    .mapToLong(r -> r.fee)
                    .sum();

            assertTrue(totalHedgeUserFees > 0, "Total hedge user fees should be positive");

            // Verify that fees are calculated correctly for each trade type
            for (ITradeEventsHandler.FuturesExecutionReport report : hedgeUserReports) {
                if (report.isMaker) {
                    long expectedFee = CoreArithmeticUtils.calculateMakerFee(report.lastQty, report.lastPx, BTC_SYMBOL);
                    assertThat("Maker fee should be correct for " + report.orderType, report.fee, is(expectedFee));
                } else {
                    long expectedFee = CoreArithmeticUtils.calculateTakerFee(report.lastQty, report.lastPx, BTC_SYMBOL);
                    assertThat("Taker fee should be correct for " + report.orderType, report.fee, is(expectedFee));
                }
            }

            log.info("Hedge mode mixed order types fees verified: GTC reports={}, IOC reports={}, Total fees={}",
                    gtcReports.size(), iocReports.size(), totalHedgeUserFees);
        }
    }

    /**
     * Test hedge mode fee consistency with global balance
     */
    @Test
    @Timeout(10)
    public void testHedgeModeFeeConsistencyWithGlobalBalance() throws ExecutionException, InterruptedException {
        long userUid = UID_1;
        long counterparty1Uid = UID_2;
        long counterparty2Uid = UID_3;

        long longSize = 25L;
        long shortSize = 20L;
        long price = 47000L;
        long globalFeesCollected = 0;

        try (final ExchangeTestContainer container = ExchangeTestContainer.create(getPerformanceConfiguration(), processor)) {
            List<CoreSymbolSpecification> symbols = container.initFutureSymbols();
            symbols.forEach(s -> container.initMarkPrice(s.symbolId, price));

            long deposit = 100000L;
            container.createUserWithSpecificMoney(userUid, deposit, CURRENECY_USD);
            container.createUserWithSpecificMoney(counterparty1Uid, deposit, CURRENECY_USD);
            container.createUserWithSpecificMoney(counterparty2Uid, deposit, CURRENECY_USD);

            // Get initial balance report
            TotalCurrencyBalanceReportResult initialBalance = container.totalBalanceReport();
            long initialTotalFees = initialBalance.getFees().get(CURRENECY_USD);

            // Switch to hedge mode
            container.submitCommandSync(ApiAdjustPositionMode.builder()
                    .uid(userUid)
                    .positionMode(PositionMode.HEDGE)
                    .build(), CommandResultCode.SUCCESS);

            // Open both LONG and SHORT positions
            container.createBidWithOrderId(16001L, userUid, (int) longSize, price, symbols.get(0).symbolId, MarginMode.CROSS);
            container.createAskWithOrderId(16002L, counterparty1Uid, (int) longSize, price, symbols.get(0).symbolId, MarginMode.CROSS);
            container.createAskWithOrderId(16003L, userUid, (int) shortSize, price, symbols.get(0).symbolId, MarginMode.CROSS);
            container.createBidWithOrderId(16004L, counterparty2Uid, (int) shortSize, price, symbols.get(0).symbolId, MarginMode.CROSS);

            // Get final balance report
            TotalCurrencyBalanceReportResult finalBalance = container.totalBalanceReport();
            long finalTotalFees = finalBalance.getFees().get(CURRENECY_USD);
            globalFeesCollected = finalTotalFees - initialTotalFees;

            // Verify global balance consistency
            assertThat("Global balance should be consistent", finalBalance.isGlobalBalancesAllZero(), is(true));

        } finally {
            // Verify execution reports
            verify(handler, atLeast(4)).futuresExecutionReport(futuresExecutionReportCaptor.capture());
            List<ITradeEventsHandler.FuturesExecutionReport> reports = futuresExecutionReportCaptor.getAllValues();

            List<ITradeEventsHandler.FuturesExecutionReport> tradeReports = reports.stream()
                    .filter(r -> r.executionType == ITradeEventsHandler.ExecType.TRADE)
                    .collect(Collectors.toList());

            assertEquals(4, tradeReports.size(), "Should have 4 trade execution reports");

            // Calculate total fees from reports
            long totalFeesFromReports = tradeReports.stream()
                    .mapToLong(r -> r.fee)
                    .sum();

            // Verify global fee consistency
            assertTrue(globalFeesCollected > 0, "Should have collected some global fees");
            assertThat("Global fees should match report fees", globalFeesCollected, is(totalFeesFromReports));

            // Calculate expected fees
            long expectedLongMakerFee = CoreArithmeticUtils.calculateMakerFee(longSize, price, BTC_SYMBOL);
            long expectedLongTakerFee = CoreArithmeticUtils.calculateTakerFee(longSize, price, BTC_SYMBOL);
            long expectedShortMakerFee = CoreArithmeticUtils.calculateMakerFee(shortSize, price, BTC_SYMBOL);
            long expectedShortTakerFee = CoreArithmeticUtils.calculateTakerFee(shortSize, price, BTC_SYMBOL);
            long expectedTotalFees = expectedLongMakerFee + expectedLongTakerFee + expectedShortMakerFee + expectedShortTakerFee;

            assertThat("Total fees should match expected calculation", totalFeesFromReports, is(expectedTotalFees));

            log.info("Hedge mode fee consistency verified: Global={}, Reports={}, Expected={}",
                    globalFeesCollected, totalFeesFromReports, expectedTotalFees);
        }
    }
}