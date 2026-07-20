package exchange.core2.tests.integration;//package exchange.core2.tests.integration;

import exchange.core2.core.common.*;
import exchange.core2.core.common.api.ApiPlaceOrder;
import exchange.core2.core.common.api.ApiSettleFundingFees;
import exchange.core2.core.common.api.reports.SingleUserReportResult;
import exchange.core2.core.common.cmd.CommandResultCode;
import exchange.core2.core.common.config.PerformanceConfiguration;
import exchange.core2.core.event.IEventsHandler4Test;
import exchange.core2.core.event.SimpleEventsProcessor4Test;
import exchange.core2.tests.util.ExchangeTestContainer;
import exchange.core2.tests.util.TestConstants;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.collections.impl.map.sorted.mutable.TreeSortedMap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static exchange.core2.tests.util.TestConstants.*;
import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.spy;

/**
 * R2同步优化集成测试
 * <p>
 * 测试重点：
 * 1. SettleFundingFees的两阶段提交正确性
 * 2. 多分片资金费率精度和尾差处理
 * 3. R2同步的细颗粒度优化
 *
 * @author Exchange Core Team
 */
@Slf4j
public class ITR2SyncOptimization {

    private SimpleEventsProcessor4Test processor;

    private IEventsHandler4Test handler = spy(IEventsHandler4Test.handler);

    private static final int SYMBOL_BTC = 10001;
    private static final int SYMBOL_ETH = 10002;
    private static final int CURRENCY_USD = TestConstants.CURRENECY_USD;

    @BeforeEach
    public void before() {
        processor = new SimpleEventsProcessor4Test(handler);
    }

    private CoreSymbolSpecification createBTCSpec() {
        return CoreSymbolSpecification.builder()
                .symbolId(SYMBOL_BTC)
                .type(SymbolType.FUTURES_CONTRACT_PERPETUAL)
                .baseCurrency(TestConstants.CURRENECY_XBT)
                .quoteCurrency(CURRENCY_USD)
                .baseScaleK(100)
                .quoteScaleK(100)
                .takerFee(0)
                .makerFee(0)
                .initMargin(1)
                .initMarginScaleK(100)
                .maintenanceMargin(TreeSortedMap.newMapWith(1000L, 5L))
                .maintenanceMarginScaleK(1000)
                .maxLeverage(TreeSortedMap.newMapWith(1000L, 100L))
                .build();
    }

    private PerformanceConfiguration getPerformanceConfiguration() {
        return PerformanceConfiguration.throughputPerformanceBuilder().build();
    }

    // ============================================
    // Test Suite 1: 资金费率多分片精度测试
    // ============================================

    /**
     * Test Case 1.1: 两分片对称持仓的资金费率
     * <p>
     * 场景：
     * - Shard0: User100(多10张), User200(空5张)
     * - Shard1: User300(多5张), User400(空10张)
     * - 资金费率: 多头付空头 0.01%
     * <p>
     * 验证：
     * - 每张合约的资金费率一致
     * - 总收入 = 总支出
     * - 无精度丢失
     */
    @Test
    public void testSettleFundingFees_TwoShards_Symmetric() throws Exception {
        PerformanceConfiguration perfCfg = PerformanceConfiguration.baseBuilder()
                .ringBufferSize(16 * 1024)
                .matchingEnginesNum(2)
                .riskEnginesNum(2)
                .build();

        try (ExchangeTestContainer container = ExchangeTestContainer.create(perfCfg, processor)) {
            CoreSymbolSpecification btcSpec = createBTCSpec();
            container.addSymbol(btcSpec);
            container.addCurrency(btcSpec.baseCurrency, 0);
            container.addCurrency(btcSpec.quoteCurrency, 0);

            long markPrice = 100_00;  // 100 USDT
            container.initMarkPrice(SYMBOL_BTC, markPrice);

            // Shard分配：uid % 2
            // Shard0: user100, user200
            // Shard1: user101, user201
            long user100 = UID_2;
            long user200 = UID_4;
            long user101 = UID_1;
            long user201 = UID_3;

            // 创建用户（包括对手方maker）
            container.createUserWithMoney(user100, CURRENCY_USD, 100000);
            container.createUserWithMoney(user200, CURRENCY_USD, 100000);
            container.createUserWithMoney(user101, CURRENCY_USD, 100000);
            container.createUserWithMoney(user201, CURRENCY_USD, 100000);
            container.createUserWithMoney(TAKER_1, CURRENCY_USD, 10000000);
            container.createUserWithMoney(TAKER_2, CURRENCY_USD, 10000000);
            container.createUserWithMoney(TAKER_3, CURRENCY_USD, 10000000);
            container.createUserWithMoney(TAKER_4, CURRENCY_USD, 10000000);

            // Shard0: User100多10张
            container.createBidWithOrderId(1001, user100, 10, markPrice, SYMBOL_BTC, MarginMode.ISOLATED);
            container.submitCommandSync(ApiPlaceOrder.builder()
                    .uid(TAKER_1)
                    .orderId(1002)
                    .symbol(SYMBOL_BTC)
                    .action(OrderAction.ASK)
                    .orderType(OrderType.GTC)
                    .price(markPrice)
                    .size(10)
                    .build(), CommandResultCode.SUCCESS);

            // Shard0: User200空5张
            container.createAskWithOrderId(2001, user200, 5, markPrice, SYMBOL_BTC, MarginMode.ISOLATED);
            container.submitCommandSync(ApiPlaceOrder.builder()
                    .uid(TAKER_2)
                    .orderId(2002)
                    .symbol(SYMBOL_BTC)
                    .action(OrderAction.BID)
                    .orderType(OrderType.GTC)
                    .price(markPrice)
                    .size(5)
                    .build(), CommandResultCode.SUCCESS);

            // Shard1: User101多5张
            container.createBidWithOrderId(3001, user101, 5, markPrice, SYMBOL_BTC, MarginMode.ISOLATED);
            container.submitCommandSync(ApiPlaceOrder.builder()
                    .uid(TAKER_3)
                    .orderId(3002)
                    .symbol(SYMBOL_BTC)
                    .action(OrderAction.ASK)
                    .orderType(OrderType.GTC)
                    .price(markPrice)
                    .size(5)
                    .build(), CommandResultCode.SUCCESS);

            // Shard1: User201空10张
            container.createAskWithOrderId(4001, user201, 10, markPrice, SYMBOL_BTC, MarginMode.ISOLATED);
            container.submitCommandSync(ApiPlaceOrder.builder()
                    .uid(TAKER_4)
                    .orderId(4002)
                    .symbol(SYMBOL_BTC)
                    .action(OrderAction.BID)
                    .orderType(OrderType.GTC)
                    .price(markPrice)
                    .size(10)
                    .build(), CommandResultCode.SUCCESS);

            // 记录初始profit
            long profit100Before = getProfit(container, user100, SYMBOL_BTC);
            long profit200Before = getProfit(container, user200, SYMBOL_BTC);
            long profit101Before = getProfit(container, user101, SYMBOL_BTC);
            long profit201Before = getProfit(container, user201, SYMBOL_BTC);

            log.info("Before funding: user100={}, user200={}, user101={}, user201={}",
                    profit100Before, profit200Before, profit101Before, profit201Before);

            // 执行资金费率: 0.01% = 1 / 10000
            long fundingRate = 1;
            long rateScale = 10000;
            container.submitCommandSync(ApiSettleFundingFees.builder()
                    .symbol(SYMBOL_BTC)
                    .action(OrderAction.BID)
                    .fundingRate(fundingRate)
                    .rateScaleK(rateScale)
                    .transactionId(9999)
                    .build(), CommandResultCode.SUCCESS);

            // 验证结果
            long profit100After = getProfit(container, user100, SYMBOL_BTC);
            long profit200After = getProfit(container, user200, SYMBOL_BTC);
            long profit101After = getProfit(container, user101, SYMBOL_BTC);
            long profit201After = getProfit(container, user201, SYMBOL_BTC);

            log.info("After funding: user100={}, user200={}, user101={}, user201={}",
                    profit100After, profit200After, profit101After, profit201After);

            // 计算变化
            long change100 = profit100After - profit100Before;
            long change200 = profit200After - profit200Before;
            long change101 = profit101After - profit101Before;
            long change201 = profit201After - profit201Before;

            log.info("Changes: user100={}, user200={}, user101={}, user201={}",
                    change100, change200, change101, change201);

            // 预期：
            // User100(多10张): 付 10 * 100 * 0.0001 = 10
            // User101(多5张):  付 5 * 100 * 0.0001 = 5
            // 总支付: 150
            //
            // User200(空5张): 收 150 * (5*100) / (15*100) = 150 * 5/15 = 5
            // User201(空10张): 收 150 * (10*100) / (15*100) = 150 * 10/15 = 10

            assertEquals("User100应支付10", -10, change100);
            assertEquals("User101应支付5", -5, change101);
            assertEquals("User200应收取5", 5, change200);
            assertEquals("User201应收取10", 10, change201);

            // 守恒验证
            long totalChange = change100 + change200 + change101 + change201;
            assertEquals("总变化应为0（资金守恒）", 0, totalChange);

            // 精度验证：每张合约的资金费率一致
            assertEquals("每张空头合约收益应为1", 1, change200 / 5);
            assertEquals("每张空头合约收益应为1", 1, change201 / 10);
        }
    }

    /**
     * Test Case 1.2: 单分片退化场景
     * <p>
     * 验证：
     * - 单分片场景下新逻辑正确
     */
    @Test
    public void testSettleFundingFees_SingleShard() throws Exception {
        PerformanceConfiguration perfCfg = PerformanceConfiguration.baseBuilder()
                .ringBufferSize(16 * 1024)
                .matchingEnginesNum(1)
                .riskEnginesNum(1)
                .build();

        try (ExchangeTestContainer container = ExchangeTestContainer.create(perfCfg, processor)) {
            CoreSymbolSpecification btcSpec = createBTCSpec();
            container.addSymbol(btcSpec);
            container.addCurrency(btcSpec.baseCurrency, 0);
            container.addCurrency(btcSpec.quoteCurrency, 0);

            long markPrice = 100_00;
            container.initMarkPrice(SYMBOL_BTC, markPrice);

            long user100 = 100;
            long user200 = 200;

            container.createUserWithMoney(user100, CURRENCY_USD, 100000);
            container.createUserWithMoney(user200, CURRENCY_USD, 100000);
            container.createUserWithMoney(user100 + 10000, CURRENCY_USD, 10000000);
            container.createUserWithMoney(user200 + 10000, CURRENCY_USD, 10000000);

            // User100多10张
            container.createBidWithOrderId(1001, user100, 10, markPrice, SYMBOL_BTC, MarginMode.ISOLATED);
            container.submitCommandSync(ApiPlaceOrder.builder()
                    .uid(user100 + 10000)
                    .orderId(1002)
                    .symbol(SYMBOL_BTC)
                    .action(OrderAction.ASK)
                    .orderType(OrderType.GTC)
                    .price(markPrice)
                    .size(10)
                    .build(), CommandResultCode.SUCCESS);

            // User200空10张
            container.createAskWithOrderId(2001, user200, 10, markPrice, SYMBOL_BTC, MarginMode.ISOLATED);
            container.submitCommandSync(ApiPlaceOrder.builder()
                    .uid(user200 + 10000)
                    .orderId(2002)
                    .symbol(SYMBOL_BTC)
                    .action(OrderAction.BID)
                    .orderType(OrderType.GTC)
                    .price(markPrice)
                    .size(10)
                    .build(), CommandResultCode.SUCCESS);

            long profitBefore100 = getProfit(container, user100, SYMBOL_BTC);
            long profitBefore200 = getProfit(container, user200, SYMBOL_BTC);

            container.submitCommandSync(ApiSettleFundingFees.builder()
                    .symbol(SYMBOL_BTC)
                    .action(OrderAction.BID)
                    .fundingRate(10000)
                    .rateScaleK(100000000)
                    .transactionId(9999)
                    .build(), CommandResultCode.SUCCESS);

            long profitAfter100 = getProfit(container, user100, SYMBOL_BTC);
            long profitAfter200 = getProfit(container, user200, SYMBOL_BTC);

            long change100 = profitAfter100 - profitBefore100;
            long change200 = profitAfter200 - profitBefore200;

            log.info("Changes: user100={}, user200={}", change100, change200);

            assertEquals("User100应支付10", -10, change100);
            assertEquals("User200应收取10", 10, change200);
            assertEquals("总变化为0", 0, change100 + change200);
        }
    }

    /**
     * Test Case 1.1: 两分片对称持仓的资金费率
     * <p>
     * 场景：
     * - Shard0: User100(多10张), User200(空5张)
     * - Shard1: User300(多5张), User400(空10张)
     * - 资金费率: 多头付空头 0.01%
     * <p>
     * 验证：
     * - 每张合约的资金费率一致
     * - 总收入 = 总支出
     * - 无精度丢失
     */
    @Test
    public void testSettleFundingFees_TwoShards_Symmetric_nonFair() throws Exception {
        PerformanceConfiguration perfCfg = PerformanceConfiguration.baseBuilder()
                .ringBufferSize(16 * 1024)
                .matchingEnginesNum(2)
                .riskEnginesNum(2)
                .build();

        try (ExchangeTestContainer container = ExchangeTestContainer.create(perfCfg, processor)) {
            CoreSymbolSpecification btcSpec = createBTCSpec();
            container.addSymbol(btcSpec);
            container.addCurrency(btcSpec.baseCurrency, 0);
            container.addCurrency(btcSpec.quoteCurrency, 0);

            long markPrice = 100_00;  // 100 USDT
            container.initMarkPrice(SYMBOL_BTC, markPrice);

            // Shard分配：uid % 2
            // Shard0: user100, user200
            // Shard1: user101, user201
            long user100 = UID_2;
            long user200 = UID_4;
            long user101 = UID_1;
            long user201 = UID_3;

            // 创建用户（包括对手方maker）
            container.createUserWithMoney(user100, CURRENCY_USD, 100000);
            container.createUserWithMoney(user200, CURRENCY_USD, 100000);
            container.createUserWithMoney(user101, CURRENCY_USD, 100000);
            container.createUserWithMoney(user201, CURRENCY_USD, 100000);
            container.createUserWithMoney(TAKER_1, CURRENCY_USD, 10000000);
            container.createUserWithMoney(TAKER_2, CURRENCY_USD, 10000000);
            container.createUserWithMoney(TAKER_3, CURRENCY_USD, 10000000);
            container.createUserWithMoney(TAKER_4, CURRENCY_USD, 10000000);

            // Shard0: User100多10张
            container.createBidWithOrderId(1001, user100, 10, markPrice, SYMBOL_BTC, MarginMode.ISOLATED);
            container.submitCommandSync(ApiPlaceOrder.builder()
                    .uid(TAKER_1)
                    .orderId(1002)
                    .symbol(SYMBOL_BTC)
                    .action(OrderAction.ASK)
                    .orderType(OrderType.GTC)
                    .price(markPrice)
                    .size(10)
                    .build(), CommandResultCode.SUCCESS);

            // Shard0: User200空5张
            container.createAskWithOrderId(2001, user200, 5, markPrice, SYMBOL_BTC, MarginMode.ISOLATED);
            container.submitCommandSync(ApiPlaceOrder.builder()
                    .uid(TAKER_2)
                    .orderId(2002)
                    .symbol(SYMBOL_BTC)
                    .action(OrderAction.BID)
                    .orderType(OrderType.GTC)
                    .price(markPrice)
                    .size(5)
                    .build(), CommandResultCode.SUCCESS);

            // Shard1: User101多5张
            container.createBidWithOrderId(3001, user101, 5, markPrice, SYMBOL_BTC, MarginMode.ISOLATED);
            container.submitCommandSync(ApiPlaceOrder.builder()
                    .uid(TAKER_3)
                    .orderId(3002)
                    .symbol(SYMBOL_BTC)
                    .action(OrderAction.ASK)
                    .orderType(OrderType.GTC)
                    .price(markPrice)
                    .size(5)
                    .build(), CommandResultCode.SUCCESS);

            // Shard1: User201空10张
            container.createAskWithOrderId(4001, user201, 10, markPrice, SYMBOL_BTC, MarginMode.ISOLATED);
            container.submitCommandSync(ApiPlaceOrder.builder()
                    .uid(TAKER_4)
                    .orderId(4002)
                    .symbol(SYMBOL_BTC)
                    .action(OrderAction.BID)
                    .orderType(OrderType.GTC)
                    .price(markPrice)
                    .size(10)
                    .build(), CommandResultCode.SUCCESS);

            // 记录初始profit
            long profit100Before = getProfit(container, user100, SYMBOL_BTC);
            long profit200Before = getProfit(container, user200, SYMBOL_BTC);
            long profit101Before = getProfit(container, user101, SYMBOL_BTC);
            long profit201Before = getProfit(container, user201, SYMBOL_BTC);

            log.info("Before funding: user100={}, user200={}, user101={}, user201={}",
                    profit100Before, profit200Before, profit101Before, profit201Before);

            // 执行资金费率: 0.01% = 1 / 10000
            long fundingRate = 1;
            long rateScale = 10000;
            container.submitCommandSync(ApiSettleFundingFees.builder()
                    .symbol(SYMBOL_BTC)
                    .action(OrderAction.BID)
                    .fundingRate(fundingRate)
                    .rateScaleK(rateScale)
                    .transactionId(9999)
                    .build(), CommandResultCode.SUCCESS);

            // 验证结果
            long profit100After = getProfit(container, user100, SYMBOL_BTC);
            long profit200After = getProfit(container, user200, SYMBOL_BTC);
            long profit101After = getProfit(container, user101, SYMBOL_BTC);
            long profit201After = getProfit(container, user201, SYMBOL_BTC);

            log.info("After funding: user100={}, user200={}, user101={}, user201={}",
                    profit100After, profit200After, profit101After, profit201After);

            // 计算变化
            long change100 = profit100After - profit100Before;
            long change200 = profit200After - profit200Before;
            long change101 = profit101After - profit101Before;
            long change201 = profit201After - profit201Before;

            log.info("Changes: user100={}, user200={}, user101={}, user201={}",
                    change100, change200, change101, change201);

            // 预期：
            // User100(多10张): 付 10 * 100 * 0.0001 = 10
            // User101(多5张):  付 5 * 100 * 0.0001 = 5
            // 总支付: 150
            //
            // User200(空5张): 收 150 * (5*100) / (15*100) = 150 * 5/15 = 5
            // User201(空10张): 收 150 * (10*100) / (15*100) = 150 * 10/15 = 10

            assertEquals("User100应支付10", -10, change100);
            assertEquals("User101应支付5", -5, change101);
            assertEquals("User200应收取5", 5, change200);
            assertEquals("User201应收取10", 10, change201);

            // 守恒验证
            long totalChange = change100 + change200 + change101 + change201;
            assertEquals("总变化应为0（资金守恒）", 0, totalChange);

            // 精度验证：每张合约的资金费率一致
            assertEquals("每张空头合约收益应为1", 1, change200 / 5);
            assertEquals("每张空头合约收益应为1", 1, change201 / 10);
        }
    }

    // ============================================
    // Helper Methods
    // ============================================

    private long getProfit(ExchangeTestContainer container, long uid, int symbol) throws Exception {
        SingleUserReportResult userProfile = container.getUserProfile(uid);
        if (userProfile.getPositions() == null || userProfile.getPositions().get(symbol) == null) {
            return 0;
        }
        SingleUserReportResult.Position position = userProfile.getPositions().get(symbol).get(0);
        return position == null ? 0 : position.profit;
    }
}
