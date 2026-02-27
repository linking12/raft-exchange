package exchange.core2.tests.integration;

import exchange.core2.core.IFundEventsHandler;
import exchange.core2.core.ITradeEventsHandler;
import exchange.core2.core.common.*;
import exchange.core2.core.common.api.reports.SingleUserReportResult;
import exchange.core2.core.common.config.PerformanceConfiguration;
import exchange.core2.core.event.IEventsHandler4Test;
import exchange.core2.core.event.SimpleEventsProcessor4Test;
import exchange.core2.tests.util.EventCheck;
import exchange.core2.tests.util.ExchangeTestContainer;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.concurrent.ExecutionException;

import static exchange.core2.tests.util.TestConstants.*;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.core.Is.is;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * 测试 commit 03ec8f04 的 calculateLockedMargin 优化
 * 验证在各种场景下locked margin的计算是否正确
 */
@Slf4j
@ExtendWith(MockitoExtension.class)
public class ITRiskEngineLockedMarginOptimization {

    private static final int SYMBOL_BTC = 1001; // BTCUSDT
    private static final int SYMBOL_ETH = 1002; // ETHUSDT
    private static final int SYMBOL_BNB = 1003; // BNBUSDT
    private static final int CURRENCY_USDT = CURRENECY_USD;

    private static final long MAKER_UID = UID_1;
    private static final long TAKER_UID = UID_2;
    private static final long USER_MULTI = UID_3;

    private static final int LEVERAGE_10X = 10;
    private static final int LEVERAGE_20X = 20;

    SimpleEventsProcessor4Test processor;
    IEventsHandler4Test handler = spy(IEventsHandler4Test.handler);

    @Captor
    ArgumentCaptor<ITradeEventsHandler.FuturesExecutionReport> futuresEventCaptor;

    @Captor
    ArgumentCaptor<IFundEventsHandler.FundEventReport> fundEventCaptor;

    @BeforeEach
    public void before() {
        processor = new SimpleEventsProcessor4Test(handler);
    }

    @AfterEach
    public void after() {
        // cleanup
    }

    private PerformanceConfiguration getPerformanceConfiguration() {
        return PerformanceConfiguration.baseBuilder().build();
    }

    /**
     * 测试用例1: 基本正确性 - 验证单个持仓的locked计算
     */
    @Test
    public void testBasicLockedMarginCalculation() throws ExecutionException, InterruptedException {
        int deposit = 10000;
        int size = 1;
        long makerOrderId = 1001L;
        long takerOrderId = 1002L;

        try (final ExchangeTestContainer container = ExchangeTestContainer.create(getPerformanceConfiguration(), processor)) {
            container.initFutureSymbol(SYMBOL_BTC, CURRENCY_USDT);
            container.addCurrency(CURRENCY_USDT);
            container.initMarkPrice(SYMBOL_BTC, 100_000);
            container.createUserWithSpecificMoney(TAKER_UID, deposit, CURRENCY_USDT);
            container.createUserWithSpecificMoney(MAKER_UID, MAX_VALUE, CURRENCY_USDT);

            // Maker挂单, Taker开仓
            container.createAskWithOrderId(makerOrderId, MAKER_UID, size, 100_000, SYMBOL_BTC);
            container.createBidWithOrderId(takerOrderId, TAKER_UID, size, 100_000, SYMBOL_BTC);

            // 验证持仓已创建
            container.validateUserState(TAKER_UID, profile -> {
                List<SingleUserReportResult.Position> positions = profile.getPositions().get(SYMBOL_BTC);
                assertNotNull(positions);
                assertThat(positions.get(0).openVolume, is(1L));
                assertThat(positions.get(0).direction, is(PositionDirection.LONG));

                // 验证locked > 0
                assertTrue(positions.get(0).openInitMarginSum > 0, "Locked margin should be positive");

                log.info("Position openVolume={}, openInitMarginSum={}",
                        positions.get(0).openVolume, positions.get(0).openInitMarginSum);
            });

            verify(handler, never()).spotExecutionReport(any());
            verify(handler, atLeast(2)).futuresExecutionReport(any());
        } finally {
            verify(handler, atLeast(4)).fundEventReport(any());
        }
    }

    /**
     * 测试用例2: 多持仓场景 - 验证takerBaseLocked的正确性
     * 这是优化的核心测试点
     */
    @Test
    public void testMultiPositionLockedMargin() throws ExecutionException, InterruptedException {
        int deposit = 100_000;

        try (final ExchangeTestContainer container = ExchangeTestContainer.create(getPerformanceConfiguration(), processor)) {
            // 初始化3个symbol
            container.initFutureSymbol(SYMBOL_BTC, CURRENCY_USDT);
            container.initFutureSymbol(SYMBOL_ETH, CURRENCY_USDT);
            container.initFutureSymbol(SYMBOL_BNB, CURRENCY_USDT);
            container.addCurrency(CURRENCY_USDT);
            container.initMarkPrice(SYMBOL_BTC, 100_000);
            container.initMarkPrice(SYMBOL_ETH, 3_000);
            container.initMarkPrice(SYMBOL_BNB, 300);

            container.createUserWithSpecificMoney(USER_MULTI, deposit, CURRENCY_USDT);
            container.createUserWithSpecificMoney(MAKER_UID, MAX_VALUE, CURRENCY_USDT);

            // 开3个不同symbol的持仓
            container.createAskWithOrderId(1001, MAKER_UID, 1, 100_000, SYMBOL_BTC);
            container.createBidWithOrderId(2001, USER_MULTI, 1, 100_000, SYMBOL_BTC);

            container.createAskWithOrderId(1002, MAKER_UID, 10, 3_000, SYMBOL_ETH);
            container.createBidWithOrderId(2002, USER_MULTI, 10, 3_000, SYMBOL_ETH);

            container.createAskWithOrderId(1003, MAKER_UID, 100, 300, SYMBOL_BNB);
            container.createBidWithOrderId(2003, USER_MULTI, 100, 300, SYMBOL_BNB);

            // 验证3个持仓都存在
            container.validateUserState(USER_MULTI, profile -> {
                assertThat(profile.getPositions().size(), is(3));
                assertNotNull(profile.getPositions().get(SYMBOL_BTC));
                assertNotNull(profile.getPositions().get(SYMBOL_ETH));
                assertNotNull(profile.getPositions().get(SYMBOL_BNB));

                long totalLocked = profile.getPositions().values().stream()
                        .flatMap(List::stream)
                        .mapToLong(p -> p.openInitMarginSum)
                        .sum();

                log.info("Total locked with 3 positions: {}", totalLocked);
                assertTrue(totalLocked == 1600, "Total locked should be positive");
            });

            long initialLocked = getInitialLockedMargin(container, USER_MULTI);

            // 对SYMBOL_BTC执行交易（增加持仓）
            container.createAskWithOrderId(1004, MAKER_UID, 1, 100_000, SYMBOL_BTC);
            container.createBidWithOrderId(2004, USER_MULTI, 1, 100_000, SYMBOL_BTC);

            // 验证locked增加
            container.validateUserState(USER_MULTI, profile -> {
                long newLocked = profile.getPositions().values().stream()
                        .flatMap(List::stream)
                        .mapToLong(p -> p.openInitMarginSum)
                        .sum();

                log.info("Locked after increasing BTC position: {}", newLocked);
                assertTrue(newLocked > initialLocked, "Locked should increase after opening more position");

                // 验证BTC持仓数量
                List<SingleUserReportResult.Position> btcPos = profile.getPositions().get(SYMBOL_BTC);
                assertThat(btcPos.get(0).openVolume, is(2L));
            });

        } finally {
            verify(handler, never()).spotExecutionReport(any());
        }
    }

    /**
     * 测试用例3: 持仓从无到有 - 验证takerSpr==null边界
     */
    @Test
    public void testNewPositionCreation() throws ExecutionException, InterruptedException {
        int deposit = 10000;

        try (final ExchangeTestContainer container = ExchangeTestContainer.create(getPerformanceConfiguration(), processor)) {
            container.initFutureSymbol(SYMBOL_BTC, CURRENCY_USDT);
            container.addCurrency(CURRENCY_USDT);
            container.initMarkPrice(SYMBOL_BTC, 100_000);
            container.createUserWithSpecificMoney(TAKER_UID, deposit, CURRENCY_USDT);
            container.createUserWithSpecificMoney(MAKER_UID, MAX_VALUE, CURRENCY_USDT);

            // 验证初始无持仓
            container.validateUserState(TAKER_UID, profile -> {
                assertTrue(profile.getPositions() == null || profile.getPositions().isEmpty(),
                        "Initial positions should be empty");
            });

            // 开第一个仓位
            container.createAskWithOrderId(1001, MAKER_UID, 1, 100_000, SYMBOL_BTC);
            container.createBidWithOrderId(2001, TAKER_UID, 1, 100_000, SYMBOL_BTC);

            // 验证locked增加
            container.validateUserState(TAKER_UID, profile -> {
                assertNotNull(profile.getPositions());
                List<SingleUserReportResult.Position> positions = profile.getPositions().get(SYMBOL_BTC);
                assertNotNull(positions);
                assertTrue(positions.get(0).openInitMarginSum > 0,
                        "Locked should be positive after opening position");
            });

            long lockedAfterFirst = getInitialLockedMargin(container, TAKER_UID);

            // 继续增加仓位
            container.createAskWithOrderId(1002, MAKER_UID, 1, 100_000, SYMBOL_BTC);
            container.createBidWithOrderId(2002, TAKER_UID, 1, 100_000, SYMBOL_BTC);

            // 验证locked继续增加
            long lockedAfterSecond = getInitialLockedMargin(container, TAKER_UID);
            assertTrue(lockedAfterSecond > lockedAfterFirst,
                    "Locked should increase after adding to position");

        } finally {
            verify(handler, never()).spotExecutionReport(any());
        }
    }

    /**
     * 测试用例4: 持仓清空 - 验证完全平仓时的locked计算
     */
    @Test
    public void testPositionFullyClose() throws ExecutionException, InterruptedException {
        int deposit = 100_000;

        try (final ExchangeTestContainer container = ExchangeTestContainer.create(getPerformanceConfiguration(), processor)) {
            container.initFutureSymbol(SYMBOL_BTC, CURRENCY_USDT);
            container.initFutureSymbol(SYMBOL_ETH, CURRENCY_USDT);
            container.addCurrency(CURRENCY_USDT);
            container.initMarkPrice(SYMBOL_BTC, 100_000);
            container.initMarkPrice(SYMBOL_ETH, 3_000);

            container.createUserWithSpecificMoney(USER_MULTI, deposit, CURRENCY_USDT);
            container.createUserWithSpecificMoney(MAKER_UID, MAX_VALUE, CURRENCY_USDT);

            // 开两个不同symbol的持仓
            // BTC
            container.createAskWithOrderId(1001, MAKER_UID, 2, 100_000, SYMBOL_BTC);
            container.createBidWithOrderId(2001, USER_MULTI, 2, 100_000, SYMBOL_BTC);
            // ETH
            container.createAskWithOrderId(1002, MAKER_UID, 10, 3_000, SYMBOL_ETH);
            container.createBidWithOrderId(2002, USER_MULTI, 10, 3_000, SYMBOL_ETH);

            long lockedWithTwoPositions = getInitialLockedMargin(container, USER_MULTI);
            log.info("Locked with two positions: {}", lockedWithTwoPositions);

            // 完全平掉BTC
            container.createBidWithOrderId(1003, MAKER_UID, 2, 100_000, SYMBOL_BTC);
            container.createAskWithOrderId(2003, USER_MULTI, 2, 100_000, SYMBOL_BTC);

            // 验证BTC持仓已清空
            container.validateUserState(USER_MULTI, profile -> {
                List<SingleUserReportResult.Position> btcPos = profile.getPositions().get(SYMBOL_BTC);
                assertTrue(btcPos == null, "BTC position should be closed");

                // ETH持仓仍然存在
                List<SingleUserReportResult.Position> ethPos = profile.getPositions().get(SYMBOL_ETH);
                assertNotNull(ethPos);
                assertThat(ethPos.get(0).openVolume, is(10L));
            });

            // 验证locked只剩ETH的部分
            long lockedAfterClose = getInitialLockedMargin(container, USER_MULTI);
            log.info("Locked after closing BTC: {}", lockedAfterClose);
            assertTrue(lockedAfterClose < lockedWithTwoPositions,
                    "Locked should decrease after closing one position");
            assertTrue(lockedAfterClose > 0,
                    "Locked should still be positive (ETH remains)");

        } finally {
            verify(handler, never()).spotExecutionReport(any());
        }
    }

    /**
     * 测试用例5: 带extraMargin的持仓清空 - 验证refundExtraMargin场景
     */
    @Test
    public void testPositionCloseWithExtraMargin() throws ExecutionException, InterruptedException {
        int deposit = 10000;
        long extraMargin = 1000L;

        try (final ExchangeTestContainer container = ExchangeTestContainer.create(getPerformanceConfiguration(), processor)) {
            container.initFutureSymbol(SYMBOL_BTC, CURRENCY_USDT);
            container.addCurrency(CURRENCY_USDT);
            container.initMarkPrice(SYMBOL_BTC, 100_000);
            container.createUserWithSpecificMoney(TAKER_UID, deposit, CURRENCY_USDT);
            container.createUserWithSpecificMoney(MAKER_UID, MAX_VALUE, CURRENCY_USDT);

            // 开仓
            container.createAskWithOrderId(1001, MAKER_UID, 1, 100_000, SYMBOL_BTC);
            container.createBidWithOrderId(2001, TAKER_UID, 1, 100_000, SYMBOL_BTC);

            // 添加额外保证金
            container.placeExtraMargin(TAKER_UID, CURRENCY_USDT, SYMBOL_BTC, extraMargin, MarginMode.ISOLATED);

            // 验证extraMargin已添加
            container.validateUserState(TAKER_UID, profile -> {
                List<SingleUserReportResult.Position> positions = profile.getPositions().get(SYMBOL_BTC);
                assertTrue(positions.get(0).extraMargin > 0, "Extra margin should be added");
            });

            long balanceBeforeClose = getBalance(container, TAKER_UID);
            log.info("Balance before close: {}", balanceBeforeClose);

            // 完全平仓
            container.createBidWithOrderId(1002, MAKER_UID, 1, 100_000, SYMBOL_BTC);
            container.createAskWithOrderId(2002, TAKER_UID, 1, 100_000, SYMBOL_BTC);

            // 验证持仓已清空
            container.validateUserState(TAKER_UID, profile -> {
                List<SingleUserReportResult.Position> positions = profile.getPositions().get(SYMBOL_BTC);
                assertTrue(positions == null);
            });

            // 验证extraMargin已退还（balance应该增加）
            long balanceAfterClose = getBalance(container, TAKER_UID);
            log.info("Balance after close: {}", balanceAfterClose);
            // balance变化 = 平仓盈亏 + extraMargin退还 - 手续费
            // 这里简单验证balance增加了
            assertTrue(balanceAfterClose - balanceBeforeClose == extraMargin);
        } finally {
            verify(handler, never()).spotExecutionReport(any());
        }
    }

    /**
     * 测试用例6: 多个MatcherEvent链 - 验证循环处理的正确性
     */
    @Test
    public void testMultipleMatcherEvents() throws ExecutionException, InterruptedException {
        int deposit = 100_000;

        try (final ExchangeTestContainer container = ExchangeTestContainer.create(getPerformanceConfiguration(), processor)) {
            container.initFutureSymbol(SYMBOL_BTC, CURRENCY_USDT);
            container.initFutureSymbol(SYMBOL_ETH, CURRENCY_USDT);
            container.addCurrency(CURRENCY_USDT);
            container.initMarkPrice(SYMBOL_BTC, 100_000);
            container.initMarkPrice(SYMBOL_ETH, 3_000);

            container.createUserWithSpecificMoney(TAKER_UID, deposit, CURRENCY_USDT);
            container.createUserWithSpecificMoney(MAKER_UID, MAX_VALUE, CURRENCY_USDT);

            // Maker挂3个订单，创建订单簿深度
            container.createAskWithOrderId(1001, MAKER_UID, 1, 100_000, SYMBOL_BTC);
            container.createAskWithOrderId(1002, MAKER_UID, 1, 100_001, SYMBOL_BTC);
            container.createAskWithOrderId(1003, MAKER_UID, 1, 100_002, SYMBOL_BTC);

            // 用户已有其他持仓（ETH）
            container.createAskWithOrderId(1004, MAKER_UID, 10, 3_000, SYMBOL_ETH);
            container.createBidWithOrderId(2001, TAKER_UID, 10, 3_000, SYMBOL_ETH);

            long lockedBefore = getInitialLockedMargin(container, TAKER_UID);

            // Taker大单，会匹配多个Maker订单（产生多个MatcherEvent）
            container.createBidWithOrderId(2002, TAKER_UID, 3, 200_000, SYMBOL_BTC);

            // 验证所有MatcherEvent处理后，持仓正确
            container.validateUserState(TAKER_UID, profile -> {
                List<SingleUserReportResult.Position> btcPositions = profile.getPositions().get(SYMBOL_BTC);
                assertNotNull(btcPositions);
                assertThat(btcPositions.get(0).openVolume, is(3L));
            });

            // 验证locked正确更新
            long lockedAfter = getInitialLockedMargin(container, TAKER_UID);
            assertTrue(lockedAfter > lockedBefore, "Locked should increase after opening position");

        } finally {
            verify(handler, never()).spotExecutionReport(any());
        }
    }

    /**
     * 测试用例7: 挂单pending的影响 - 验证pending部分通过position记录
     */
    @Test
    public void testPendingOrdersLockedMargin() throws ExecutionException, InterruptedException {
        int deposit = 10000;

        try (final ExchangeTestContainer container = ExchangeTestContainer.create(getPerformanceConfiguration(), processor)) {
            container.initFutureSymbol(SYMBOL_BTC, CURRENCY_USDT);
            container.addCurrency(CURRENCY_USDT);
            container.initMarkPrice(SYMBOL_BTC, 100_000);
            container.createUserWithSpecificMoney(TAKER_UID, deposit, CURRENCY_USDT);
            container.createUserWithSpecificMoney(MAKER_UID, MAX_VALUE, CURRENCY_USDT);

            // 开仓
            container.createAskWithOrderId(1001, MAKER_UID, 1, 100_000, SYMBOL_BTC);
            container.createBidWithOrderId(2001, TAKER_UID, 1, 100_000, SYMBOL_BTC);

            // 验证持仓存在
            container.validateUserState(TAKER_UID, profile -> {
                List<SingleUserReportResult.Position> positions = profile.getPositions().get(SYMBOL_BTC);
                assertNotNull(positions);
                assertThat(positions.get(0).openVolume, is(1L));
                // 没有pending订单
                assertThat(positions.get(0).pendingBuySize, is(0L));
                assertThat(positions.get(0).pendingSellSize, is(0L));
            });

            // 挂一个增仓的限价单（不会立即成交）
            container.createBidWithOrderId(2002, TAKER_UID, 1, 90_000, SYMBOL_BTC);

            // 验证pending订单存在
            container.validateUserState(TAKER_UID, profile -> {
                List<SingleUserReportResult.Position> positions = profile.getPositions().get(SYMBOL_BTC);
                assertNotNull(positions);
                // 有pending买单
                assertThat(positions.get(0).pendingBuySize, is(1L));
                assertThat(positions.get(0).pendingBuyAvgPrice, is(90_000L));
                log.info("Position with pending: openVolume={}, pendingBuySize={}",
                        positions.get(0).openVolume, positions.get(0).pendingBuySize);
            });

            // 取消挂单
            container.cancelOrder(TAKER_UID, 2002, SYMBOL_BTC);

            // 验证pending订单已取消
            container.validateUserState(TAKER_UID, profile -> {
                List<SingleUserReportResult.Position> positions = profile.getPositions().get(SYMBOL_BTC);
                assertNotNull(positions);
                assertThat(positions.get(0).pendingBuySize, is(0L));
            });

        } finally {
            verify(handler, never()).spotExecutionReport(any());
        }
    }

    /**
     * 测试用例8: 对手方Maker的locked计算
     */
    @Test
    public void testMakerLockedMarginCalculation() throws ExecutionException, InterruptedException {
        long deposit = MAX_VALUE;

        try (final ExchangeTestContainer container = ExchangeTestContainer.create(getPerformanceConfiguration(), processor)) {
            container.initFutureSymbol(SYMBOL_BTC, CURRENCY_USDT);
            container.initFutureSymbol(SYMBOL_ETH, CURRENCY_USDT);
            container.addCurrency(CURRENCY_USDT);
            container.initMarkPrice(SYMBOL_BTC, 100_000);
            container.initMarkPrice(SYMBOL_ETH, 3_000);

            container.createUserWithSpecificMoney(MAKER_UID, deposit, CURRENCY_USDT);
            container.createUserWithSpecificMoney(TAKER_UID, deposit, CURRENCY_USDT);

            // Maker开仓BTC和ETH
            container.createAskWithOrderId(1001, TAKER_UID, 2, 100_000, SYMBOL_BTC);
            container.createBidWithOrderId(2001, MAKER_UID, 2, 100_000, SYMBOL_BTC);

            container.createAskWithOrderId(1002, TAKER_UID, 20, 3_000, SYMBOL_ETH);
            container.createBidWithOrderId(2002, MAKER_UID, 20, 3_000, SYMBOL_ETH);

            long makerLockedBefore = getInitialLockedMargin(container, MAKER_UID);
            log.info("Maker locked before match: {}", makerLockedBefore);

            // Maker挂限价单（会被Taker吃掉）
            container.createAskWithOrderId(2003, MAKER_UID, 1, 100_000, SYMBOL_BTC);
            // Taker吃单，Maker部分平仓
            container.createBidWithOrderId(1003, TAKER_UID, 1, 100_000, SYMBOL_BTC);

            // 验证Maker的locked减少（BTC仓位减少）
            long makerLockedAfter = getInitialLockedMargin(container, MAKER_UID);
            log.info("Maker locked after match: {}", makerLockedAfter);
            assertTrue(makerLockedAfter < makerLockedBefore,
                    "Maker locked should decrease after reducing position");

            // 验证Maker的ETH仓位未受影响
            container.validateUserState(MAKER_UID, profile -> {
                List<SingleUserReportResult.Position> ethPos = profile.getPositions().get(SYMBOL_ETH);
                assertThat(ethPos.get(0).openVolume, is(20L));
            });

        } finally {
            verify(handler, never()).spotExecutionReport(any());
        }
    }

    /**
     * 测试用例9: 全仓模式下的locked计算
     */
    @Test
    public void testCrossMarginLockedCalculation() throws ExecutionException, InterruptedException {
        int deposit = 100_000;

        try (final ExchangeTestContainer container = ExchangeTestContainer.create(getPerformanceConfiguration(), processor)) {
            container.initFutureSymbol(SYMBOL_BTC, CURRENCY_USDT);
            container.initFutureSymbol(SYMBOL_ETH, CURRENCY_USDT);
            container.addCurrency(CURRENCY_USDT);
            container.initMarkPrice(SYMBOL_BTC, 100_000);
            container.initMarkPrice(SYMBOL_ETH, 3_000);

            container.createUserWithSpecificMoney(USER_MULTI, deposit, CURRENCY_USDT);
            container.createUserWithSpecificMoney(MAKER_UID, MAX_VALUE, CURRENCY_USDT);

            // 开仓，使用全仓模式
            container.createAskWithOrderId(1001, MAKER_UID, 1, 100_000, SYMBOL_BTC);
            container.createBidWithOrderId(2001, USER_MULTI, 1, 100_000, SYMBOL_BTC, MarginMode.CROSS);

            container.createAskWithOrderId(1002, MAKER_UID, 10, 3_000, SYMBOL_ETH);
            container.createBidWithOrderId(2002, USER_MULTI, 10, 3_000, SYMBOL_ETH, MarginMode.CROSS);

            long locked = getInitialLockedMargin(container, USER_MULTI);
            log.info("Cross margin user locked: {}", locked);
            assertTrue(locked > 0, "Cross margin user should have locked margin");

            // 平掉一个仓位
            container.createBidWithOrderId(1003, MAKER_UID, 1, 100_000, SYMBOL_BTC);
            container.createAskWithOrderId(2003, USER_MULTI, 1, 100_000, SYMBOL_BTC, MarginMode.CROSS);

            long lockedAfter = getInitialLockedMargin(container, USER_MULTI);
            assertTrue(lockedAfter < locked, "Locked should decrease after closing position");

        } finally {
            verify(handler, never()).spotExecutionReport(any());
        }
    }

    /**
     * 测试用例10: 性能对比 - 验证优化效果
     */
    @Test
    public void testPerformanceImprovement() throws ExecutionException, InterruptedException {
        int deposit = 10_000_000;

        try (final ExchangeTestContainer container = ExchangeTestContainer.create(getPerformanceConfiguration(), processor)) {
            container.initFutureSymbol(SYMBOL_BTC, CURRENCY_USDT);
            container.initFutureSymbol(SYMBOL_ETH, CURRENCY_USDT);
            container.initFutureSymbol(SYMBOL_BNB, CURRENCY_USDT);
            container.addCurrency(CURRENCY_USDT);
            container.initMarkPrice(SYMBOL_BTC, 100_000);
            container.initMarkPrice(SYMBOL_ETH, 3_000);
            container.initMarkPrice(SYMBOL_BNB, 300);

            container.createUserWithSpecificMoney(USER_MULTI, deposit, CURRENCY_USDT);
            container.createUserWithSpecificMoney(MAKER_UID, MAX_VALUE, CURRENCY_USDT);

            // 开多个持仓
            container.createAskWithOrderId(1001, MAKER_UID, 10, 100_000, SYMBOL_BTC);
            container.createBidWithOrderId(2001, USER_MULTI, 10, 100_000, SYMBOL_BTC);

            container.createAskWithOrderId(1002, MAKER_UID, 100, 3_000, SYMBOL_ETH);
            container.createBidWithOrderId(2002, USER_MULTI, 100, 3_000, SYMBOL_ETH);

            container.createAskWithOrderId(1003, MAKER_UID, 1000, 300, SYMBOL_BNB);
            container.createBidWithOrderId(2003, USER_MULTI, 1000, 300, SYMBOL_BNB);

            // 准备Maker订单簿
            for (int i = 0; i < 10; i++) {
                long orderId = 1010L + i;
                container.createAskWithOrderId(orderId, MAKER_UID, 1, 100_000 + i, SYMBOL_BTC);
            }

            // 测试：大单匹配多个Maker（会产生多个MatcherEvent）
            long startTime = System.nanoTime();
            container.createBidWithOrderId(2010, USER_MULTI, 10, 200_000, SYMBOL_BTC);
            long endTime = System.nanoTime();

            long durationMs = (endTime - startTime) / 1_000_000;
            log.info("Trade with multiple positions took: {} ms", durationMs);

            // 这里只是记录时间，不做严格断言（因为性能依赖环境）
            assertTrue(durationMs < 1000, "Trade should complete within 1 second");

        } finally {
            verify(handler, never()).spotExecutionReport(any());
        }
    }

    // ==================== Helper Methods ====================

    private long getInitialLockedMargin(ExchangeTestContainer container, long uid) throws ExecutionException, InterruptedException {
        SingleUserReportResult result = container.getUserProfile(uid);
        if (result == null || result.getPositions() == null) {
            return 0;
        }
        return result.getPositions().values().stream()
                .flatMap(List::stream)
                .mapToLong(p -> p.openInitMarginSum)
                .sum();
    }

    private long getBalance(ExchangeTestContainer container, long uid) throws ExecutionException, InterruptedException {
        SingleUserReportResult result = container.getUserProfile(uid);
        if (result == null || result.getAccounts() == null) {
            return 0;
        }
        return result.getAccounts().get(CURRENCY_USDT);
    }
}
