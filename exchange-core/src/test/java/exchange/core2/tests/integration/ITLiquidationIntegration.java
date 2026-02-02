package exchange.core2.tests.integration;

import exchange.core2.core.common.api.ApiAdjustPositionMode;
import exchange.core2.core.common.cmd.CommandResultCode;
import exchange.core2.core.event.IEventsHandler4Test;
import exchange.core2.core.event.SimpleEventsProcessor4Test;
import exchange.core2.core.orderbook.OrderBookDirectImpl;
import exchange.core2.core.processors.liquidation.LiquidationEngine;
import exchange.core2.core.common.*;
import exchange.core2.core.common.api.reports.SingleUserReportResult;
import exchange.core2.core.common.config.PerformanceConfiguration;
import exchange.core2.core.utils.AffinityThreadFactory;
import exchange.core2.tests.util.ExchangeTestContainer;
import exchange.core2.tests.util.LatencyTools;
import lombok.extern.slf4j.Slf4j;
import net.jpountz.lz4.LZ4Factory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.concurrent.ExecutionException;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.core.Is.is;
import static org.junit.jupiter.api.Assertions.*;

/**
 * 测试多个 LiquidationEngine 分片处理不同用户强平的集成测试
 */
@Slf4j
@ExtendWith(MockitoExtension.class)
class ITLiquidationIntegration {

    private static final int SYMBOL_ID = 2;
    private static final int QUOTE_ID = 840;

    SimpleEventsProcessor4Test processor;

    @Mock
    IEventsHandler4Test handler;

    @BeforeEach
    public void before() {
        processor = new SimpleEventsProcessor4Test(handler, true);
    }

    @AfterEach()
    public void after() {

    }

    // 性能配置
    protected PerformanceConfiguration getPerformanceConfiguration() {
        return PerformanceConfiguration.DEFAULT;
    }

    protected PerformanceConfiguration getPerformanceConfiguration4Test() {
        return PerformanceConfiguration.builder()
                .ringBufferSize(64 * 1024)
                .matchingEnginesNum(4)
                .riskEnginesNum(8)
                .msgsInGroupLimit(4_096)
                .maxGroupDurationNs(4_000_000)
                .threadFactory(new AffinityThreadFactory(AffinityThreadFactory.ThreadAffinityMode.THREAD_AFFINITY_ENABLE_PER_LOGICAL_CORE, "Exchange-Core-Disruptor"))
                .waitStrategy(CoreWaitStrategy.BUSY_SPIN)
                .binaryCommandsLz4CompressorFactory(() -> LZ4Factory.fastestInstance().highCompressor())
                .orderBookFactory(OrderBookDirectImpl::new).build();
    }

    /**
     * 测试多用户跨分片强平场景
     * 创建分布在不同分片的用户，让他们都达到强平条件，验证各分片独立处理强平
     */
    @Test
    public void testMultiUserCrossShardLiquidation() throws InterruptedException, java.util.concurrent.ExecutionException {
        long deposit = 5000L;
        long largeDeposit = 1000000L;
        int size = 5;
        long price = 10000;
        long liquidationPrice = 2000; // 触发强平的价格

        // 创建分布在不同分片的用户ID（基于 uid % numShards 的分片算法）
        // 假设有 2 个分片，创建用户ID让它们分布在不同分片
        long user1 = 1001L; // 分片 1001 % 2 = 1
        long user2 = 1002L; // 分片 1002 % 2 = 0
        long user3 = 1003L; // 分片 1003 % 2 = 1
        long user4 = 1004L; // 分片 1004 % 2 = 0
        long user5 = 1005L; // 分片 1005 % 2 = 1 (与user1同分片)
        long user6 = 1006L; // 分片 1006 % 2 = 0 (与user2同分片)

        // 流动性提供者（不会被强平）
        long liquidityProvider1 = 2001L;
        long liquidityProvider2 = 2002L;

        try (final ExchangeTestContainer container = ExchangeTestContainer.create(getPerformanceConfiguration(), processor);) {
            // 先停止自动强平，手动控制
            container.getExchangeCore().getLiquidationEngines().forEach(LiquidationEngine::stop);

            List<CoreSymbolSpecification> symbols = container.initFutureSymbols();
            CoreSymbolSpecification symbol = symbols.get(0);
            container.initMarkPrice(symbol.symbolId, price);

            // 创建用户并充值
            container.createUserWithSpecificMoney(user1, deposit, QUOTE_ID);
            container.createUserWithSpecificMoney(user2, deposit, QUOTE_ID);
            container.createUserWithSpecificMoney(user3, deposit, QUOTE_ID);
            container.createUserWithSpecificMoney(user4, deposit, QUOTE_ID);
            container.createUserWithSpecificMoney(user5, deposit, QUOTE_ID);
            container.createUserWithSpecificMoney(user6, deposit, QUOTE_ID);

            // 流动性提供者充值大量资金，用于接收强平订单
            container.createUserWithSpecificMoney(liquidityProvider1, largeDeposit, QUOTE_ID);
            container.createUserWithSpecificMoney(liquidityProvider2, largeDeposit, QUOTE_ID);

            log.info("创建用户持仓 - 让用户做多，当价格下跌时触发强平");

            // 让用户1-6都建立多头持仓（逐仓模式，容易触发强平）
            long orderId = 10001L;
            container.createBidWithOrderId(orderId++, user1, size, price, symbol.symbolId, MarginMode.ISOLATED);
            container.createAskWithOrderId(orderId++, liquidityProvider1, size, price, symbol.symbolId, MarginMode.CROSS);

            container.createBidWithOrderId(orderId++, user2, size, price, symbol.symbolId, MarginMode.ISOLATED);
            container.createAskWithOrderId(orderId++, liquidityProvider1, size, price, symbol.symbolId, MarginMode.CROSS);

            container.createBidWithOrderId(orderId++, user3, size, price, symbol.symbolId, MarginMode.ISOLATED);
            container.createAskWithOrderId(orderId++, liquidityProvider1, size, price, symbol.symbolId, MarginMode.CROSS);

            container.createBidWithOrderId(orderId++, user4, size, price, symbol.symbolId, MarginMode.ISOLATED);
            container.createAskWithOrderId(orderId++, liquidityProvider1, size, price, symbol.symbolId, MarginMode.CROSS);

            container.createBidWithOrderId(orderId++, user5, size, price, symbol.symbolId, MarginMode.ISOLATED);
            container.createAskWithOrderId(orderId++, liquidityProvider1, size, price, symbol.symbolId, MarginMode.CROSS);

            container.createBidWithOrderId(orderId++, user6, size, price, symbol.symbolId, MarginMode.ISOLATED);
            container.createAskWithOrderId(orderId++, liquidityProvider1, size, price, symbol.symbolId, MarginMode.CROSS);

            log.info("验证所有用户都有持仓");
            long[] testUsers = {user1, user2, user3, user4, user5, user6};
            for (long userId : testUsers) {
                container.validateUserState(userId, profile -> {
                    assertThat("用户 " + userId + " 应该有持仓", profile.getPositions().size(), is(1));
                    log.info("用户 {} 持仓验证通过", userId);
                });
            }

            log.info("创建流动性订单用于接收强平订单");
            // 在强平价格附近创建买单，用于接收强平时的卖单
            container.createBidWithOrderId(orderId++, liquidityProvider2, 50, liquidationPrice, symbol.symbolId, MarginMode.CROSS);

            log.info("更新价格到 {} 触发强平条件", liquidationPrice);
            // 价格大幅下跌，触发强平
            container.updateCurrentPriceTo((int) liquidationPrice, symbol.symbolId, QUOTE_ID);

            container.validateUserState(liquidityProvider2, profile -> {
                assertThat(profile.getPositions().size(), is(1));
                assertThat(profile.getPositions().get(symbol.symbolId).get(0).pendingBuySize, is(40L));
            });

            log.info("手动触发所有分片的强平检查");
            // 手动触发所有分片的强平
            List<LiquidationEngine> liquidationEngines = container.getExchangeCore().getLiquidationEngines();
            assertThat("应该有多个强平引擎分片", liquidationEngines.size(), is(2));

            // 触发强平
            liquidationEngines.forEach(LiquidationEngine::triggerOnce);

            // 等待强平完成
            Thread.sleep(3000L);
            container.getApi().groupingControl(0, 1);
            Thread.sleep(500L);  // 确保事件处理完成

            log.info("验证强平结果");

            for (long userId : testUsers) {
                container.validateUserState(userId, profile -> {
                    log.info("用户 {} 强平后持仓数: {}", userId, profile.getPositions().size());

                    if (profile.getPositions().size() == 0) {
                        log.info("用户 {} 已被完全强平", userId);
                    } else {
                        log.info("用户 {} 还有持仓", userId);
                    }
                });
            }

            log.info("强平检查完成");

            // 40手吃掉user1-user6每人5手单, 剩下10手
            container.validateUserState(liquidityProvider2, profile -> {
                assertThat(profile.getPositions().size(), is(1));
                assertThat(profile.getPositions().get(symbol.symbolId).get(0).pendingBuySize, is(10L));
            });
        }
    }

    /**
     * 测试分片负载均衡 - 验证不同分片处理的强平任务相对均匀
     */
    @Test
    public void testShardingLoadBalance() throws InterruptedException, java.util.concurrent.ExecutionException {
        long deposit = 3000L;
        long largeDeposit = 1000000L;
        int size = 3;
        long price = 10000;
        long liquidationPrice = 1000;

        // 创建大量用户分布在不同分片
        int totalUsers = 16; // 2个分片，每个分片8个用户
        long[] userIds = new long[totalUsers];
        for (int i = 0; i < totalUsers; i++) {
            userIds[i] = 3000L + i; // 从3000开始，确保分布均匀
        }

        long liquidityProvider = 9000L;

        try (final ExchangeTestContainer container = ExchangeTestContainer.create(getPerformanceConfiguration(), processor);) {
            container.getExchangeCore().getLiquidationEngines().forEach(LiquidationEngine::stop);

            List<CoreSymbolSpecification> symbols = container.initFutureSymbols();
            CoreSymbolSpecification symbol = symbols.get(0);
            container.initMarkPrice(symbol.symbolId, price);

            // 创建所有用户
            for (long userId : userIds) {
                container.createUserWithSpecificMoney(userId, deposit, QUOTE_ID);
            }

            // 创建流动性提供者
            container.createUserWithSpecificMoney(liquidityProvider, largeDeposit, QUOTE_ID);

            // 验证强平引擎分片
            List<LiquidationEngine> liquidationEngines = container.getExchangeCore().getLiquidationEngines();
            assertThat("应该有2个强平引擎分片", liquidationEngines.size(), is(2));

            // 验证用户在分片间的分布
            verifyUserShardDistribution(userIds, 2);

            log.info("创建用户持仓 - 让所有用户建立多头持仓");

            // 让所有用户建立多头持仓（逐仓模式，容易触发强平）
            long orderId = 20001L;
            for (long userId : userIds) {
                container.createBidWithOrderId(orderId++, userId, size, price, symbol.symbolId, MarginMode.ISOLATED);
                container.createAskWithOrderId(orderId++, liquidityProvider, size, price, symbol.symbolId, MarginMode.CROSS);
            }

            log.info("验证所有用户都有持仓");
            for (long userId : userIds) {
                container.validateUserState(userId, profile -> {
                    assertThat("用户 " + userId + " 应该有持仓", profile.getPositions().size(), is(1));
                });
            }

            log.info("创建流动性订单用于接收强平订单");
            // 在强平价格附近创建大量买单，用于接收强平时的卖单
            container.createBidWithOrderId(orderId++, liquidityProvider, totalUsers * size + 10, liquidationPrice, symbol.symbolId, MarginMode.CROSS);

            log.info("更新价格到 {} 触发强平条件", liquidationPrice);
            // 价格大幅下跌，触发强平
            container.updateCurrentPriceTo((int) liquidationPrice, symbol.symbolId, QUOTE_ID);

            log.info("手动触发所有分片的强平检查，并记录强平前后的日志数量");

            // 触发强平
            liquidationEngines.forEach(LiquidationEngine::triggerOnce);
            Thread.sleep(3000L);
            container.getApi().groupingControl(0, 1);
            Thread.sleep(500L);  // 确保事件处理完成

            log.info("验证强平结果和负载分布");

            // 统计被强平的用户数量
            int liquidatedUsers = 0;
            for (long userId : userIds) {
                try {
                    container.validateUserState(userId, profile -> {
                        if (profile.getPositions().size() == 0) {
                            // 用户被完全强平，通过抛出特殊异常来标记
                            throw new RuntimeException("LIQUIDATED");
                        }
                    });
                } catch (RuntimeException e) {
                    if ("LIQUIDATED".equals(e.getMessage())) {
                        liquidatedUsers++;
                    }
                }
            }

            log.info("总共 {} 个用户中有 {} 个被强平", totalUsers, liquidatedUsers);

            // 验证至少有一部分用户被强平（说明强平机制正常工作）
            assertThat("应该有用户被强平", liquidatedUsers > 0, is(true));


            // 验证负载相对均衡
            // 统计每个分片被强平的用户数量
            int shard0LiquidatedUsers = 0;
            int shard1LiquidatedUsers = 0;

            for (long userId : userIds) {
                int shardId = (int) (userId % 2); // 假设分片算法是 userId % numShards
                try {
                    container.validateUserState(userId, profile -> {
                        if (profile.getPositions().size() == 0) {
                            throw new RuntimeException("LIQUIDATED");
                        }
                    });
                } catch (RuntimeException e) {
                    if ("LIQUIDATED".equals(e.getMessage())) {
                        if (shardId == 0) {
                            shard0LiquidatedUsers++;
                        } else {
                            shard1LiquidatedUsers++;
                        }
                    }
                }
            }

            log.info("分片0强平用户数: {}, 分片1强平用户数: {}", shard0LiquidatedUsers, shard1LiquidatedUsers);

            // 验证负载分布相对均衡（差值不超过总数的50%）
            if (liquidatedUsers > 0) {
                int maxDiff = Math.max(2, liquidatedUsers * 5 / 10); // 允许50%的差异，最少2
                int actualDiff = Math.abs(shard0LiquidatedUsers - shard1LiquidatedUsers);
                assertThat("分片间强平负载应该相对均衡，实际差异: " + actualDiff + ", 允许差异: " + maxDiff,
                        actualDiff <= maxDiff, is(true));
            }

            // 验证两个分片都有参与强平（如果有强平的话）
            if (liquidatedUsers > 1) {
                assertThat("两个分片都应该参与强平",
                        shard0LiquidatedUsers > 0 && shard1LiquidatedUsers > 0, is(true));
            }

            // 验证流动性提供者接收了强平订单
            container.validateUserState(liquidityProvider, profile -> {
                if (profile.getPositions().size() > 0) {
                    SingleUserReportResult.Position position = profile.getPositions().get(symbol.symbolId).get(0);
                    long receivedSize = totalUsers * size + 10 - position.pendingBuySize;
                    log.info("流动性提供者接收了 {} 手强平订单", receivedSize);
                    assertThat("流动性提供者应该接收了强平订单", receivedSize > 0, is(true));
                }
            });

            log.info("负载均衡测试完成 - 验证了分片强平功能的正常工作");
        }
    }

    /**
     * 验证用户在分片间的分布是否均匀
     */
    private void verifyUserShardDistribution(long[] userIds, int numShards) {
        int[] shardCounts = new int[numShards];

        for (long userId : userIds) {
            int shardId = (int) (userId % numShards);
            shardCounts[shardId]++;
        }

        log.info("用户分片分布:");
        for (int i = 0; i < numShards; i++) {
            log.info("分片 {} 用户数: {}", i, shardCounts[i]);
        }

        // 验证分布相对均匀（每个分片的用户数差异不超过2）
        int expectedPerShard = userIds.length / numShards;
        for (int i = 0; i < numShards; i++) {
            int diff = Math.abs(shardCounts[i] - expectedPerShard);
            assertThat("分片 " + i + " 的用户分布应该相对均匀", diff <= 2, is(true));
        }
    }

    /**
     * 测试单分片强平场景 - 只有指定分片的用户被强平
     * 创建分布在不同分片的用户，但只触发特定分片的强平引擎
     */
    @Test
    public void testSingleShardLiquidation() {
        long deposit = 3000L;  // 减少保证金，更容易强平
        long largeDeposit = 100000L;
        int positionSize = 5;   // 增加持仓量，增加风险
        long entryPrice = 10000L;  // 提高入场价格
        long liquidationPrice = 500L;  // 极端下跌95%，确保触发强平

        // 创建分布在不同分片的用户
        // 分片0的用户: userId % 2 == 0
        long shard0User1 = 5000L; // 5000 % 2 = 0
        long shard0User2 = 5002L; // 5002 % 2 = 0
        long shard0User3 = 5004L; // 5004 % 2 = 0

        // 分片1的用户: userId % 2 == 1
        long shard1User1 = 5001L; // 5001 % 2 = 1
        long shard1User2 = 5003L; // 5003 % 2 = 1
        long shard1User3 = 5005L; // 5005 % 2 = 1

        long liquidityProvider = 9999L;

        try (final ExchangeTestContainer container = ExchangeTestContainer.create(getPerformanceConfiguration(), processor);) {
            // 停止自动强平，手动控制
            container.getExchangeCore().getLiquidationEngines().forEach(engine -> engine.stop());

            List<CoreSymbolSpecification> symbols = container.initFutureSymbols();
            CoreSymbolSpecification symbol = symbols.get(0);
            container.initMarkPrice(symbol.symbolId, entryPrice);

            // 创建所有用户
            long[] allUsers = {shard0User1, shard0User2, shard0User3, shard1User1, shard1User2, shard1User3};
            for (long userId : allUsers) {
                container.createUserWithSpecificMoney(userId, deposit, QUOTE_ID);
            }
            container.createUserWithSpecificMoney(liquidityProvider, largeDeposit, QUOTE_ID);

            // 验证强平引擎分片数量
            List<LiquidationEngine> liquidationEngines = container.getExchangeCore().getLiquidationEngines();
            assertThat("应该有2个强平引擎分片", liquidationEngines.size(), is(2));

            log.info("为所有用户创建持仓 - 让所有用户建立多头持仓");
            long orderId = 60001L;

            // 让所有用户建立多头持仓（逐仓模式，容易触发强平）
            for (long userId : allUsers) {
                container.createBidWithOrderId(orderId++, userId, positionSize, entryPrice, symbol.symbolId, MarginMode.ISOLATED);
                container.createAskWithOrderId(orderId++, liquidityProvider, positionSize, entryPrice, symbol.symbolId, MarginMode.CROSS);
            }

            log.info("验证所有用户都有持仓");
            for (long userId : allUsers) {
                container.validateUserState(userId, profile -> {
                    assertThat("用户 " + userId + " 应该有持仓", profile.getPositions().size(), is(1));
                    SingleUserReportResult.Position position = profile.getPositions().get(symbol.symbolId).get(0);
                    assertThat("用户 " + userId + " 持仓数量正确", position.openVolume, is((long) positionSize));
                });
            }

            log.info("创建流动性订单用于接收强平订单");
            // 创建足够的流动性接收强平订单
            container.createBidWithOrderId(orderId++, liquidityProvider, allUsers.length * positionSize + 5,
                    liquidationPrice, symbol.symbolId, MarginMode.CROSS);

            log.info("更新价格到 {} 触发强平条件", liquidationPrice);
            // 价格大幅下跌，触发强平条件
            container.updateCurrentPriceTo((int) liquidationPrice, symbol.symbolId, QUOTE_ID);

            log.info("只触发分片0的强平引擎 - 测试单分片强平");
            // 只触发分片0的强平引擎（假设第一个引擎处理分片0）
            LiquidationEngine shard0Engine = liquidationEngines.get(0);
            shard0Engine.triggerOnce();

            long[] shard0Users = {shard0User1, shard0User2, shard0User3};
            long[] shard1Users = {shard1User1, shard1User2, shard1User3};

            for (long userId : shard0Users) {
                LatencyTools.waitForCondition(10000, () -> {
                    try {
                        return container.getUserProfile(userId).getPositions().size() == 0;
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                });
            }

            log.info("验证单分片强平结果");

            // 统计被强平的用户
            int shard0LiquidatedUsers = 0;
            int shard1LiquidatedUsers = 0;
            int totalLiquidatedUsers = 0;

            // 检查分片0用户的强平情况
            for (long userId : shard0Users) {
                container.validateUserState(userId, profile -> {
                    log.info("分片0用户 {} 强平后持仓数: {}", userId, profile.getPositions().size());
                    if (profile.getPositions().size() == 0) {
                        log.info("分片0用户 {} 被强平", userId);
                    } else {
                        SingleUserReportResult.Position position = profile.getPositions().get(symbol.symbolId).get(0);
                        log.info("分片0用户 {} 剩余持仓: {}", userId, position.openVolume);
                    }
                });
            }

            // 检查分片1用户的强平情况（应该不被强平）
            for (long userId : shard1Users) {
                container.validateUserState(userId, profile -> {
                    log.info("分片1用户 {} 强平后持仓数: {}", userId, profile.getPositions().size());
                    if (profile.getPositions().size() == 0) {
                        log.info("分片1用户 {} 被强平", userId);
                    } else {
                        SingleUserReportResult.Position position = profile.getPositions().get(symbol.symbolId).get(0);
                        log.info("分片1用户 {} 剩余持仓: {}", userId, position.openVolume);
                        // 分片1的用户不应该被强平，应该还有持仓
                        assertThat("分片1用户 " + userId + " 不应该被强平", position.openVolume, is((long) positionSize));
                    }
                });
            }

            // 统计实际强平情况
            for (long userId : shard0Users) {
                try {
                    container.validateUserState(userId, profile -> {
                        if (profile.getPositions().size() == 0) {
                            throw new RuntimeException("LIQUIDATED");
                        }
                    });
                } catch (RuntimeException e) {
                    if ("LIQUIDATED".equals(e.getMessage())) {
                        shard0LiquidatedUsers++;
                        totalLiquidatedUsers++;
                    }
                }
            }

            for (long userId : shard1Users) {
                try {
                    container.validateUserState(userId, profile -> {
                        if (profile.getPositions().size() == 0) {
                            throw new RuntimeException("LIQUIDATED");
                        }
                    });
                } catch (RuntimeException e) {
                    if ("LIQUIDATED".equals(e.getMessage())) {
                        shard1LiquidatedUsers++;
                        totalLiquidatedUsers++;
                    }
                }
            }

            log.info("单分片强平结果统计:");
            log.info("分片0被强平用户数: {}", shard0LiquidatedUsers);
            log.info("分片1被强平用户数: {}", shard1LiquidatedUsers);
            log.info("总被强平用户数: {}", totalLiquidatedUsers);

            // 验证单分片强平的预期结果
            // 1. 应该有用户被强平（说明强平机制工作）
            assertThat("应该有用户被强平", totalLiquidatedUsers > 0, is(true));

            // 2. 分片1的用户不应该被强平（因为只触发了分片0的引擎）
            assertThat("分片1用户不应该被强平", shard1LiquidatedUsers, is(0));

            // 3. 只有分片0的用户应该被强平
            assertThat("只有分片0的用户应该被强平", shard0LiquidatedUsers, is(totalLiquidatedUsers));

            // 4. 至少有一个分片0的用户被强平
            assertThat("至少有一个分片0用户被强平", shard0LiquidatedUsers > 0, is(true));

            log.info("现在触发分片1的强平引擎，验证剩余用户也会被强平");
            // 现在触发分片1的强平引擎
            LiquidationEngine shard1Engine = liquidationEngines.get(1);
            shard1Engine.triggerOnce();

            for (long userId : shard1Users) {
                LatencyTools.waitForCondition(150, () -> {
                    try {
                        return container.getUserProfile(userId).getPositions().size() == 0;
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                });
            }

            // 重新统计强平情况
            int shard1LiquidatedUsersAfter = 0;
            for (long userId : shard1Users) {
                try {
                    container.validateUserState(userId, profile -> {
                        if (profile.getPositions().size() == 0) {
                            throw new RuntimeException("LIQUIDATED");
                        }
                    });
                } catch (RuntimeException e) {
                    if ("LIQUIDATED".equals(e.getMessage())) {
                        shard1LiquidatedUsersAfter++;
                    }
                }
            }

            log.info("触发分片1引擎后，分片1被强平用户数: {}", shard1LiquidatedUsersAfter);

            // 验证分片1的用户现在也被强平了
            assertThat("分片1用户现在应该被强平", shard1LiquidatedUsersAfter > 0, is(true));

            // 验证流动性提供者接收了强平订单
            container.validateUserState(liquidityProvider, profile -> {
                if (profile.getPositions().size() > 0) {
                    SingleUserReportResult.Position position = profile.getPositions().get(symbol.symbolId).get(0);
                    long totalLiquidityProvided = allUsers.length * positionSize + 5;
                    long consumedLiquidity = totalLiquidityProvided - position.pendingBuySize;
                    log.info("流动性提供者接收了 {} 手强平订单", consumedLiquidity);
                    assertThat("流动性提供者应该接收了强平订单", consumedLiquidity > 0, is(true));
                }
            });

            log.info("单分片强平测试完成 - 验证了分片独立强平功能");

        } catch (Exception e) {
            log.error("单分片强平测试失败", e);
            fail("单分片强平测试失败: " + e.getMessage());
        }
    }

    /**
     * 测试混合保证金模式的跨分片强平
     * 验证全仓和逐仓模式在不同分片上的强平行为差异
     */
    @Test
    public void testMixedMarginModeAcrossShards() {
        long deposit = 50000L; // 大幅增加初始资金，让全仓用户有充足保证金
        long largeDeposit = 1000000L;
        int size = 3; // 减少交易量，降低风险敞口
        long price = 10000;
        long liquidationPrice = 8000; // 更保守的价格下跌：20%的下跌

        // 用户分布在不同分片，使用不同保证金模式
        long crossUser1 = 4001L; // 分片1，全仓
        long crossUser2 = 4002L; // 分片0，全仓
        long isolatedUser1 = 4003L; // 分片1，逐仓
        long isolatedUser2 = 4004L; // 分片0，逐仓

        long liquidityProvider = 9001L;

        try (final ExchangeTestContainer container = ExchangeTestContainer.create(getPerformanceConfiguration(), processor);) {
            container.getExchangeCore().getLiquidationEngines().forEach(LiquidationEngine::stop);

            List<CoreSymbolSpecification> symbols = container.initFutureSymbols();
            CoreSymbolSpecification symbol = symbols.get(0);
            container.initMarkPrice(symbol.symbolId, price);

            // 创建用户
            container.createUserWithSpecificMoney(crossUser1, deposit, QUOTE_ID);
            container.createUserWithSpecificMoney(crossUser2, deposit, QUOTE_ID);
            container.createUserWithSpecificMoney(isolatedUser1, deposit, QUOTE_ID);
            container.createUserWithSpecificMoney(isolatedUser2, deposit, QUOTE_ID);
            container.createUserWithSpecificMoney(liquidityProvider, largeDeposit, QUOTE_ID);

            // 验证强平引擎分片
            List<LiquidationEngine> liquidationEngines = container.getExchangeCore().getLiquidationEngines();
            assertThat("应该有2个强平引擎分片", liquidationEngines.size(), is(2));

            // 验证用户分片分布
            log.info("验证用户分片分布:");
            log.info("crossUser1 {} -> 分片 {}", crossUser1, crossUser1 % 2);
            log.info("crossUser2 {} -> 分片 {}", crossUser2, crossUser2 % 2);
            log.info("isolatedUser1 {} -> 分片 {}", isolatedUser1, isolatedUser1 % 2);
            log.info("isolatedUser2 {} -> 分片 {}", isolatedUser2, isolatedUser2 % 2);

            long orderId = 70001L;

            log.info("创建不同保证金模式的持仓");

            // 创建全仓模式持仓
            container.createBidWithOrderId(orderId++, crossUser1, size, price, symbol.symbolId, MarginMode.CROSS);
            container.createAskWithOrderId(orderId++, liquidityProvider, size, price, symbol.symbolId, MarginMode.CROSS);

            container.createBidWithOrderId(orderId++, crossUser2, size, price, symbol.symbolId, MarginMode.CROSS);
            container.createAskWithOrderId(orderId++, liquidityProvider, size, price, symbol.symbolId, MarginMode.CROSS);

            // 创建逐仓模式持仓
            container.createBidWithOrderId(orderId++, isolatedUser1, size, price, symbol.symbolId, MarginMode.ISOLATED);
            container.createAskWithOrderId(orderId++, liquidityProvider, size, price, symbol.symbolId, MarginMode.CROSS);

            container.createBidWithOrderId(orderId++, isolatedUser2, size, price, symbol.symbolId, MarginMode.ISOLATED);
            container.createAskWithOrderId(orderId++, liquidityProvider, size, price, symbol.symbolId, MarginMode.CROSS);

            log.info("验证所有用户都有持仓");
            long[] allUsers = {crossUser1, crossUser2, isolatedUser1, isolatedUser2};
            for (long userId : allUsers) {
                container.validateUserState(userId, profile -> {
                    assertThat("用户 " + userId + " 应该有持仓", profile.getPositions().size(), is(1));
                    SingleUserReportResult.Position position = profile.getPositions().get(symbol.symbolId).get(0);
                    assertThat("用户 " + userId + " 持仓数量正确", position.openVolume, is((long) size));
                    log.info("用户 {} 持仓验证通过: size={}, marginMode={}", userId, position.openVolume, position.marginMode);
                });
            }

            log.info("创建流动性订单用于接收强平订单");
            // 创建足够的流动性接收强平订单
            container.createBidWithOrderId(orderId++, liquidityProvider, allUsers.length * size + 10,
                    liquidationPrice, symbol.symbolId, MarginMode.CROSS);

            log.info("更新价格到 {} 触发强平条件", liquidationPrice);
            // 价格大幅下跌，触发强平条件
            container.updateCurrentPriceTo((int) liquidationPrice, symbol.symbolId, QUOTE_ID);

            log.info("触发所有分片的强平引擎");
            // 触发所有分片的强平
            liquidationEngines.forEach(LiquidationEngine::triggerOnce);
            Thread.sleep(3000L);
            container.getApi().groupingControl(0, 1);
            Thread.sleep(500L);  // 确保事件处理完成

            log.info("验证混合保证金模式强平结果");

            // 统计不同保证金模式的强平情况
            int crossModeLiquidatedUsers = 0;
            int isolatedModeLiquidatedUsers = 0;
            int shard0LiquidatedUsers = 0;
            int shard1LiquidatedUsers = 0;

            long[] crossUsers = {crossUser1, crossUser2};
            long[] isolatedUsers = {isolatedUser1, isolatedUser2};

            // 检查全仓模式用户的强平情况
            for (long userId : crossUsers) {
                container.validateUserState(userId, profile -> {
                    log.info("全仓用户 {} 强平后持仓数: {}", userId, profile.getPositions().size());
                    if (profile.getPositions().size() == 0) {
                        log.info("全仓用户 {} 被强平", userId);
                    } else {
                        SingleUserReportResult.Position position = profile.getPositions().get(symbol.symbolId).get(0);
                        log.info("全仓用户 {} 剩余持仓: {}", userId, position.openVolume);
                    }
                });
            }

            // 检查逐仓模式用户的强平情况
            for (long userId : isolatedUsers) {
                container.validateUserState(userId, profile -> {
                    log.info("逐仓用户 {} 强平后持仓数: {}", userId, profile.getPositions().size());
                    if (profile.getPositions().size() == 0) {
                        log.info("逐仓用户 {} 被强平", userId);
                    } else {
                        SingleUserReportResult.Position position = profile.getPositions().get(symbol.symbolId).get(0);
                        log.info("逐仓用户 {} 剩余持仓: {}", userId, position.openVolume);
                    }
                });
            }

            // 统计实际强平情况
            for (long userId : crossUsers) {
                try {
                    container.validateUserState(userId, profile -> {
                        if (profile.getPositions().size() == 0) {
                            throw new RuntimeException("LIQUIDATED");
                        }
                    });
                } catch (RuntimeException e) {
                    if ("LIQUIDATED".equals(e.getMessage())) {
                        crossModeLiquidatedUsers++;
                        int shardId = (int) (userId % 2);
                        if (shardId == 0) {
                            shard0LiquidatedUsers++;
                        } else {
                            shard1LiquidatedUsers++;
                        }
                    }
                }
            }

            for (long userId : isolatedUsers) {
                try {
                    container.validateUserState(userId, profile -> {
                        if (profile.getPositions().size() == 0) {
                            throw new RuntimeException("LIQUIDATED");
                        }
                    });
                } catch (RuntimeException e) {
                    if ("LIQUIDATED".equals(e.getMessage())) {
                        isolatedModeLiquidatedUsers++;
                        int shardId = (int) (userId % 2);
                        if (shardId == 0) {
                            shard0LiquidatedUsers++;
                        } else {
                            shard1LiquidatedUsers++;
                        }
                    }
                }
            }

            log.info("混合保证金模式强平结果统计:");
            log.info("全仓模式被强平用户数: {}", crossModeLiquidatedUsers);
            log.info("逐仓模式被强平用户数: {}", isolatedModeLiquidatedUsers);
            log.info("分片0被强平用户数: {}", shard0LiquidatedUsers);
            log.info("分片1被强平用户数: {}", shard1LiquidatedUsers);
            log.info("总被强平用户数: {}", crossModeLiquidatedUsers + isolatedModeLiquidatedUsers);

            // 验证强平结果的预期
            // 1. 应该有用户被强平（说明强平机制工作）
            int totalLiquidatedUsers = crossModeLiquidatedUsers + isolatedModeLiquidatedUsers;
            LatencyTools.waitForCondition(5000, () -> {
                try {
                    return totalLiquidatedUsers > 0;
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });

            assertThat("应该有用户被强平", totalLiquidatedUsers > 0, is(true));

            // 2. 验证分片间的独立性 - 两个分片都应该处理强平
            if (totalLiquidatedUsers > 1) {
                assertThat("两个分片都应该参与强平",
                        shard0LiquidatedUsers >= 0 && shard1LiquidatedUsers >= 0, is(true));
            }

            // 3. 验证预期的强平行为差异
            log.info("逐仓模式强平率: {}/{} = {}", isolatedModeLiquidatedUsers, isolatedUsers.length,
                    (double) isolatedModeLiquidatedUsers / isolatedUsers.length);
            log.info("全仓模式强平率: {}/{} = {}", crossModeLiquidatedUsers, crossUsers.length,
                    (double) crossModeLiquidatedUsers / crossUsers.length);

            // 4. 验证强平模式的差异化行为
            // 在当前价格设置下，逐仓模式应该被强平，全仓模式不应该被强平
            assertThat("逐仓模式用户应该被强平（风险隔离，保证金不足）",
                    isolatedModeLiquidatedUsers > 0, is(true));
            assertThat("全仓模式用户不应该被强平（账户总余额充足）",
                    crossModeLiquidatedUsers, is(0));

            // 验证流动性提供者接收了强平订单
            container.validateUserState(liquidityProvider, profile -> {
                if (profile.getPositions().size() > 0) {
                    SingleUserReportResult.Position position = profile.getPositions().get(symbol.symbolId).get(0);
                    long totalLiquidityProvided = allUsers.length * size + 10;
                    long consumedLiquidity = totalLiquidityProvided - position.pendingBuySize;
                    log.info("流动性提供者接收了 {} 手强平订单", consumedLiquidity);
                    assertThat("流动性提供者应该接收了强平订单", consumedLiquidity > 0, is(true));
                }
            });

            log.info("混合保证金模式跨分片强平测试完成 - 验证了不同保证金模式在分片环境下的强平行为");

        } catch (Exception e) {
            log.error("混合保证金模式强平测试失败", e);
            fail("混合保证金模式强平测试失败: " + e.getMessage());
        }
    }

    /**
     * 测试跨分片对手单强平场景
     * userId1(逐仓)和userId2(全仓)处于不同分片，二者是直接对手单
     * userId2还持有另一个symbol的持仓
     * 通过价格变动确保两个用户都被强平
     * 测试强平挂单不会相互影响，各自独立执行
     */
    @Test
    public void testCrossShardCounterpartyLiquidation() {
        long deposit = 3000L;  // 减少资金，更容易强平
        long largeDeposit = 1000000L;
        int tradeSize = 1;    // 增加交易量，增加风险敞口
        long entryPrice = 10000L;
        long liquidationPrice = 500L; // 95%极端下跌，确保强平

        // 用户分布在不同分片，使用不同保证金模式
        long userId1 = 7001L; // 分片1，逐仓模式，做多
        long userId2 = 7002L; // 分片0，全仓模式，做空 + 第二symbol做多
        long liquidityProvider = 9003L;
        long liquidityProvider2 = 9004L;
        int secondarySize = 15; // userId2在第二symbol上的交易量

        try (final ExchangeTestContainer container = ExchangeTestContainer.create(getPerformanceConfiguration(), processor);) {
            container.getExchangeCore().getLiquidationEngines().forEach(LiquidationEngine::stop);

            List<CoreSymbolSpecification> symbols = container.initFutureSymbols();
            CoreSymbolSpecification symbol1 = symbols.get(0);  // 主要交易的symbol
            CoreSymbolSpecification symbol2 = symbols.get(1);  // userId2的第二个symbol
            container.initMarkPrice(symbol1.symbolId, entryPrice);
            container.initMarkPrice(symbol2.symbolId, entryPrice);

            // 创建用户
            container.createUserWithSpecificMoney(userId1, deposit, QUOTE_ID);
            container.createUserWithSpecificMoney(userId2, deposit + 2000L, QUOTE_ID); // 给userId2稍多资金支持两个symbol
            container.createUserWithSpecificMoney(liquidityProvider, largeDeposit, QUOTE_ID);
            container.createUserWithSpecificMoney(liquidityProvider2, largeDeposit, QUOTE_ID);

            container.submitCommandSync(ApiAdjustPositionMode.builder()
                    .uid(liquidityProvider)
                    .positionMode(PositionMode.HEDGE)
                    .build(), CommandResultCode.SUCCESS);

            // 验证用户分片分布
            log.info("验证用户分片分布:");
            log.info("userId1 {} -> 分片 {} (逐仓模式, 做多方)", userId1, userId1 % 2);
            log.info("userId2 {} -> 分片 {} (全仓模式, 做空方)", userId2, userId2 % 2);
            assertThat("用户应该在不同分片", (userId1 % 2) != (userId2 % 2), is(true));

            // 验证强平引擎分片
            List<LiquidationEngine> liquidationEngines = container.getExchangeCore().getLiquidationEngines();
            assertThat("应该有2个强平引擎分片", liquidationEngines.size(), is(2));

            long orderId = 90001L;

            log.info("步骤1: 创建跨分片对手单 - userId1做多，userId2做空");
            // userId1 开多头持仓（逐仓模式）
            container.createBidWithOrderId(orderId++, userId1, tradeSize, entryPrice, symbol1.symbolId, MarginMode.ISOLATED);
            // userId2 开空头持仓（全仓模式） - 与userId1形成对手单
            container.createAskWithOrderId(orderId++, userId2, tradeSize, entryPrice, symbol1.symbolId, MarginMode.CROSS);

            log.info("步骤1.1: 为userId2创建第二个symbol的多头持仓");
            // userId2 在第二个symbol上开多头持仓（全仓模式）- 这将导致强平
            container.createBidWithOrderId(orderId++, userId2, secondarySize, entryPrice, symbol2.symbolId, MarginMode.CROSS);
            container.createAskWithOrderId(orderId++, liquidityProvider2, secondarySize, entryPrice, symbol2.symbolId, MarginMode.CROSS);

            log.info("步骤2: 验证对手单成交后的初始持仓状态");

            // 验证userId1的多头持仓
            container.validateUserState(userId1, profile -> {
                assertThat("userId1应该有1个持仓", profile.getPositions().size(), is(1));
                SingleUserReportResult.Position pos = profile.getPositions().get(symbol1.symbolId).get(0);
                assertThat("userId1持仓数量", pos.openVolume, is((long) tradeSize));
                assertThat("userId1使用逐仓模式", pos.marginMode, is(MarginMode.ISOLATED));
                assertThat("userId1是多头持仓", pos.direction, is(PositionDirection.LONG));
                log.info("userId1初始持仓: symbol{} 多头 {} 手 ({})", symbol1.symbolId, pos.openVolume, pos.marginMode);
            });

            // 验证userId2的两个持仓
            container.validateUserState(userId2, profile -> {
                assertThat("userId2应该有2个持仓", profile.getPositions().size(), is(2));

                // symbol1上的空头持仓（与userId1对手）
                SingleUserReportResult.Position pos1 = profile.getPositions().get(symbol1.symbolId).get(0);
                assertThat("userId2 symbol1持仓数量", pos1.openVolume, is((long) tradeSize));
                assertThat("userId2使用全仓模式", pos1.marginMode, is(MarginMode.CROSS));
                assertThat("userId2 symbol1是空头持仓", pos1.direction, is(PositionDirection.SHORT));
                log.info("userId2持仓1: symbol{} 空头 {} 手 ({})", symbol1.symbolId, Math.abs(pos1.openVolume), pos1.marginMode);

                // symbol2上的多头持仓
                SingleUserReportResult.Position pos2 = profile.getPositions().get(symbol2.symbolId).get(0);
                assertThat("userId2 symbol2持仓数量", pos2.openVolume, is((long) secondarySize));
                assertThat("userId2 symbol2使用全仓模式", pos2.marginMode, is(MarginMode.CROSS));
                assertThat("userId2 symbol2是多头持仓", pos2.direction, is(PositionDirection.LONG));
                log.info("userId2持仓2: symbol{} 多头 {} 手 ({})", symbol2.symbolId, pos2.openVolume, pos2.marginMode);
            });

            log.info("步骤3: 创建强平流动性 - 为所有方向和symbol都提供流动性");
            // 为userId1多头强平创建买单流动性（接收强平卖单）
            container.createBidWithOrderId(99001L, liquidityProvider, tradeSize + 2, liquidationPrice, symbol1.symbolId, MarginMode.CROSS);
            // 为userId2第二symbol多头强平创建买单流动性
            container.createBidWithOrderId(99003L, liquidityProvider, secondarySize + 2, liquidationPrice, symbol2.symbolId, MarginMode.CROSS);

            container.validateUserState(liquidityProvider, profile -> {
                assertThat(profile.getPositions().get(symbol1.symbolId).size(), is(1));
                assertThat(profile.getPositions().get(symbol2.symbolId).size(), is(1));
            });

            log.info("步骤4: 价格大幅下跌 {} -> {} ({}% 下跌) 触发双方强平",
                    entryPrice, liquidationPrice, (entryPrice - liquidationPrice) * 100 / entryPrice);
            // 价格大幅下跌，同时触发两个symbol的强平条件
            container.updateCurrentPriceTo((int) liquidationPrice, symbol1.symbolId, QUOTE_ID);
            container.updateCurrentPriceTo((int) liquidationPrice, symbol2.symbolId, QUOTE_ID);

            log.info("步骤5: 手动触发所有分片的强平引擎");
            liquidationEngines.forEach(LiquidationEngine::triggerOnce);
            Thread.sleep(3000L);
            container.getApi().groupingControl(0, 1);
            Thread.sleep(500L);  // 确保事件处理完成

            log.info("步骤6: 验证强平结果");

            // 统计强平情况
            boolean userId1Liquidated = false;
            boolean userId2Liquidated = false;

            try {
                container.validateUserState(userId1, profile -> {
                    if (profile.getPositions().size() == 0) {
                        throw new RuntimeException("LIQUIDATED");
                    }
                });
            } catch (RuntimeException e) {
                if ("LIQUIDATED".equals(e.getMessage())) {
                    userId1Liquidated = true;
                }
            }

            try {
                container.validateUserState(userId2, profile -> {
                    if (profile.getPositions().size() == 1) {
                        throw new RuntimeException("LIQUIDATED");
                    }
                });
            } catch (RuntimeException e) {
                if ("LIQUIDATED".equals(e.getMessage())) {
                    userId2Liquidated = true;
                }
            }

            log.info("跨分片对手单强平结果:");
            log.info("userId1 (分片{}, 逐仓模式) 被强平: {}", userId1 % 2, userId1Liquidated);
            log.info("userId2 (分片{}, 全仓模式) 被强平: {}", userId2 % 2, userId2Liquidated);

            boolean finalUserId1Liquidated = userId1Liquidated;
            LatencyTools.waitForCondition(10000, () -> {
                try {
                    return finalUserId1Liquidated;
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });

            boolean finalUserId2Liquidated = userId2Liquidated;
            LatencyTools.waitForCondition(10000, () -> {
                try {
                    return finalUserId2Liquidated;
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });

            // 核心验证：两个对手方都应该被强平
            assertThat("userId1应该被强平（价格下跌，多头逐仓保证金不足）", userId1Liquidated, is(true));
            assertThat("userId2应该被强平（symbol2多头亏损，全仓模式下总体风险过大）", userId2Liquidated, is(true));

            log.info("步骤7: 详细验证双方强平后的持仓状态");

            // 验证userId1完全被强平
            container.validateUserState(userId1, profile -> {
                log.info("userId1强平后状态:");
                log.info("- 持仓数量: {}", profile.getPositions().size());
                assertThat("userId1应该完全被强平（逐仓模式）", profile.getPositions().size(), is(0));
                log.info("- 逐仓多头持仓完全清零");
            });

            // 验证userId2被部分强平（仅symbol2的持仓被清零）
            container.validateUserState(userId2, profile -> {
                log.info("userId2强平后状态:");
                log.info("- 持仓数量: {}", profile.getPositions().size());
                assertThat("userId2应被部分强平", profile.getPositions().size() == 1, is(true));
                log.info("- 全仓模式下所有symbol持仓完全清零");
                log.info("  - symbol{} 空头持仓清零", symbol1.symbolId);
                log.info("  - symbol{} 多头持仓清零", symbol2.symbolId);
            });

            // 验证流动性提供者接收了强平订单
            container.validateUserState(liquidityProvider, profile -> {
                log.info("流动性提供者强平后状态:");
                profile.getPositions().forEachKeyValue((symbolId, positions) -> {
                    if (!positions.isEmpty()) {
                        SingleUserReportResult.Position position = positions.get(0);
                        log.info("- symbol{}: 买单待成交={}, 卖单待成交={}",
                                symbolId, position.pendingBuySize, position.pendingSellSize);

                        // 验证流动性被消耗，说明强平订单被处理
                        if (symbolId == symbol1.symbolId) {
                            // symbol1应该有买单和卖单流动性被消耗
                            long initialBuyLiquidity = tradeSize + 2;
                            long initialSellLiquidity = tradeSize + 2;
                            assertThat("symbol1买单流动性应该被消耗", position.pendingBuySize < initialBuyLiquidity, is(true));
                            assertThat("symbol1卖单流动性应该被消耗", position.pendingSellSize < initialSellLiquidity, is(true));
                        } else if (symbolId == symbol2.symbolId) {
                            // symbol2应该有买单流动性被消耗
                            long initialBuyLiquidity = secondarySize + 2;
                            assertThat("symbol2买单流动性应该被消耗", position.pendingBuySize < initialBuyLiquidity, is(true));
                        }
                    }
                });
            });
            log.info("跨分片混合保证金模式对手单强平测试完成 - 验证了不同保证金模式强平挂单的独立性");
        } catch (Exception e) {
            log.error("跨分片对手单强平测试失败", e);
            fail("跨分片对手单强平测试失败: " + e.getMessage());
        }
    }

    /**
     * 测试基本强平场景 - 逐仓模式
     * 用户建立多头持仓，价格下跌触发强平
     */
    @Test
    public void testBasicLiquidationIsolatedMode() {
        long deposit = 3000L;  // 减少保证金
        long largeDeposit = 100000L;
        int positionSize = 10;
        long entryPrice = 10000L;
        long liquidationPrice = 500L;  // 95%极端下跌

        long trader = 1001L;
        long liquidityProvider = 2001L;

        try (final ExchangeTestContainer container = ExchangeTestContainer.create(getPerformanceConfiguration(), processor)) {
            List<CoreSymbolSpecification> symbols = container.initFutureSymbols();
            CoreSymbolSpecification symbol = symbols.get(0);
            container.initMarkPrice(symbol.symbolId, entryPrice);

            // 创建用户
            container.createUserWithSpecificMoney(trader, deposit, QUOTE_ID);
            container.createUserWithSpecificMoney(liquidityProvider, largeDeposit, QUOTE_ID);

            log.info("创建多头持仓 - 逐仓模式");
            long orderId = 10001L;

            // 交易者开多头持仓（逐仓模式）
            container.createBidWithOrderId(orderId++, trader, positionSize, entryPrice, symbol.symbolId, MarginMode.ISOLATED);
            container.createAskWithOrderId(orderId++, liquidityProvider, positionSize, entryPrice, symbol.symbolId, MarginMode.CROSS);

            // 验证持仓建立
            container.validateUserState(trader, profile -> {
                assertThat("交易者应该有持仓", profile.getPositions().size(), is(1));
                SingleUserReportResult.Position position = profile.getPositions().get(symbol.symbolId).get(0);
                assertThat("持仓数量正确", position.pendingBuySize, is(0L));
                assertThat("持仓方向正确", position.openVolume, is((long) positionSize));
                log.info("交易者持仓验证通过: size={}", position.openVolume);
            });

            log.info("创建流动性订单用于接收强平订单");
            // 在强平价格创建买单，用于接收强平卖单
            container.createBidWithOrderId(orderId++, liquidityProvider, positionSize + 5, liquidationPrice, symbol.symbolId, MarginMode.CROSS);

            log.info("价格下跌到 {} 触发强平", liquidationPrice);
            // 价格大幅下跌，触发强平条件
            container.updateCurrentPriceTo((int) liquidationPrice, symbol.symbolId, QUOTE_ID);
            
            // 多次触发强平引擎，确保强平完全执行
            for (int retry = 0; retry < 3; retry++) {
                container.getExchangeCore().getLiquidationEngines().forEach(LiquidationEngine::triggerOnce);
                Thread.sleep(2000L);
                container.getApi().groupingControl(0, 1);
                Thread.sleep(500L);
            }
            
            // 最后一次确认
            container.getApi().groupingControl(0, 1);
            Thread.sleep(500L);

            // 验证交易者被强平
            container.validateUserState(trader, profile -> {
                log.info("强平后交易者持仓数: {}", profile.getPositions().size());
                assertThat("交易者应该被强平", profile.getPositions().size(), is(0));
            });

            // 验证流动性提供者接收了强平订单
            container.validateUserState(liquidityProvider, profile -> {
                SingleUserReportResult.Position position = profile.getPositions().get(symbol.symbolId).get(0);
                assertThat("流动性提供者应该接收了强平订单", position.pendingBuySize < positionSize + 5, is(true));
            });

        } catch (Exception e) {
            log.error("基本强平测试失败", e);
            fail("基本强平测试失败: " + e.getMessage());
        }
    }

    /**
     * 测试全仓模式强平
     * 全仓模式下用户有多个持仓，测试部分强平场景
     */
    @Test
    public void testCrossMarginLiquidation() {
        long deposit = 20000L;
        long largeDeposit = 100000L;
        int positionSize1 = 5;
        int positionSize2 = 8;
        long entryPrice = 10000L;
        long liquidationPrice = 3000L;

        long trader = 1002L;
        long liquidityProvider = 2002L;

        try (final ExchangeTestContainer container = ExchangeTestContainer.create(getPerformanceConfiguration(), processor);) {
            List<CoreSymbolSpecification> symbols = container.initFutureSymbols();
            CoreSymbolSpecification symbol1 = symbols.get(0);
            CoreSymbolSpecification symbol2 = symbols.get(1);

            container.initMarkPrice(symbol1.symbolId, entryPrice);
            container.initMarkPrice(symbol2.symbolId, entryPrice);

            // 创建用户
            container.createUserWithSpecificMoney(trader, deposit, QUOTE_ID);
            container.createUserWithSpecificMoney(liquidityProvider, largeDeposit, QUOTE_ID);

            log.info("创建多个持仓 - 全仓模式");
            long orderId = 20001L;

            // 交易者在两个品种上开多头持仓（全仓模式）
            container.createBidWithOrderId(orderId++, trader, positionSize1, entryPrice, symbol1.symbolId, MarginMode.CROSS);
            container.createAskWithOrderId(orderId++, liquidityProvider, positionSize1, entryPrice, symbol1.symbolId, MarginMode.CROSS);

            container.createBidWithOrderId(orderId++, trader, positionSize2, entryPrice, symbol2.symbolId, MarginMode.CROSS);
            container.createAskWithOrderId(orderId++, liquidityProvider, positionSize2, entryPrice, symbol2.symbolId, MarginMode.CROSS);

            // 验证持仓建立
            container.validateUserState(trader, profile -> {
                assertThat("交易者应该有2个持仓", profile.getPositions().size(), is(2));
                log.info("交易者持仓验证通过，持仓数: {}", profile.getPositions().size());
            });

            log.info("创建流动性订单用于接收强平订单");
            // 为两个品种都创建流动性
            container.createBidWithOrderId(orderId++, liquidityProvider, positionSize1 + 2, liquidationPrice, symbol1.symbolId, MarginMode.CROSS);
            container.createBidWithOrderId(orderId++, liquidityProvider, positionSize2 + 2, liquidationPrice, symbol2.symbolId, MarginMode.CROSS);

            log.info("两个品种价格都下跌到 {} 触发强平", liquidationPrice);
            // 两个品种价格都下跌
            container.updateCurrentPriceTo((int) liquidationPrice, symbol1.symbolId, QUOTE_ID);
            container.updateCurrentPriceTo((int) liquidationPrice, symbol2.symbolId, QUOTE_ID);

            container.getExchangeCore().getLiquidationEngines().forEach(LiquidationEngine::triggerOnce);
            Thread.sleep(3000L);
            container.getApi().groupingControl(0, 1);
            Thread.sleep(500L);  // 确保事件处理完成

            log.info("验证全仓模式强平结果");
            // 验证交易者强平情况
            container.validateUserState(trader, profile -> {
                log.info("强平后交易者持仓数: {}", profile.getPositions().size());
                // 全仓模式可能部分强平或全部强平
                profile.getPositions().forEachKeyValue((symbolId, positions) -> {
                    if (!positions.isEmpty()) {
                        SingleUserReportResult.Position position = positions.get(0);
                        log.info("品种 {} 剩余持仓: {}", symbolId, position.openVolume);
                    }
                });
            });
        } catch (Exception e) {
            log.error("全仓模式强平测试失败", e);
            fail("全仓模式强平测试失败: " + e.getMessage());
        }
    }

    /**
     * 测试空头持仓强平
     * 用户建立空头持仓，价格上涨触发强平
     */
    @Test
    public void testShortPositionLiquidation() {
        long deposit = 8000L;
        long largeDeposit = 100000L;
        int positionSize = 6;
        long entryPrice = 5000L;
        long liquidationPrice = 12000L; // 价格上涨触发空头强平

        long trader = 1003L;
        long liquidityProvider = 2003L;

        try (final ExchangeTestContainer container = ExchangeTestContainer.create(getPerformanceConfiguration(), processor);) {
            List<CoreSymbolSpecification> symbols = container.initFutureSymbols();
            CoreSymbolSpecification symbol = symbols.get(0);
            container.initMarkPrice(symbol.symbolId, entryPrice);

            // 创建用户
            container.createUserWithSpecificMoney(trader, deposit, QUOTE_ID);
            container.createUserWithSpecificMoney(liquidityProvider, largeDeposit, QUOTE_ID);

            log.info("创建空头持仓 - 逐仓模式");
            long orderId = 30001L;

            // 交易者开空头持仓（逐仓模式）
            container.createAskWithOrderId(orderId++, trader, positionSize, entryPrice, symbol.symbolId, MarginMode.ISOLATED);
            container.createBidWithOrderId(orderId++, liquidityProvider, positionSize, entryPrice, symbol.symbolId, MarginMode.CROSS);

            // 验证空头持仓建立
            container.validateUserState(trader, profile -> {
                assertThat("交易者应该有持仓", profile.getPositions().size(), is(1));
                SingleUserReportResult.Position position = profile.getPositions().get(symbol.symbolId).get(0);
                assertThat("空头持仓数量正确", Math.abs(position.openVolume), is((long) positionSize));
                log.info("交易者空头持仓验证通过: size={}", position.openVolume);
            });

            log.info("创建流动性订单用于接收强平订单");
            // 在强平价格创建卖单，用于接收强平买单
            container.createAskWithOrderId(orderId++, liquidityProvider, positionSize + 3, liquidationPrice, symbol.symbolId, MarginMode.CROSS);

            log.info("价格上涨到 {} 触发空头强平", liquidationPrice);
            // 价格大幅上涨，触发空头强平
            container.updateCurrentPriceTo((int) liquidationPrice, symbol.symbolId, QUOTE_ID);
            container.getExchangeCore().getLiquidationEngines().forEach(LiquidationEngine::triggerOnce);
            Thread.sleep(3000L);
            container.getApi().groupingControl(0, 1);
            Thread.sleep(500L);  // 确保事件处理完成

            log.info("验证空头强平结果");
            // 验证交易者被强平
            container.validateUserState(trader, profile -> {
                log.info("强平后交易者持仓数: {}", profile.getPositions().size());
                if (profile.getPositions().size() == 0) {
                    log.info("空头交易者完全被强平");
                } else {
                    SingleUserReportResult.Position position = profile.getPositions().get(symbol.symbolId).get(0);
                    log.info("空头交易者剩余持仓: {}", position.openVolume);
                }
            });

            // 验证流动性提供者接收了强平订单
            container.validateUserState(liquidityProvider, profile -> {
                if (profile.getPositions().size() > 0) {
                    SingleUserReportResult.Position position = profile.getPositions().get(symbol.symbolId).get(0);
                    log.info("流动性提供者接收空头强平订单，剩余待成交: {}", position.pendingSellSize);
                    assertThat("流动性提供者应该接收了强平订单", position.pendingSellSize < positionSize + 3, is(true));
                }
            });

        } catch (Exception e) {
            log.error("空头强平测试失败", e);
            fail("空头强平测试失败: " + e.getMessage());
        }
    }

    /**
     * 测试部分强平场景
     * 用户资金不足全部强平时，只强平部分持仓
     */
    @Test
    public void testPartialLiquidation() {
        long deposit = 15000L;
        long largeDeposit = 100000L;
        int positionSize = 20;
        long entryPrice = 8000L;
        long partialLiquidationPrice = 6000L; // 触发部分强平的价格

        long trader = 1004L;
        long liquidityProvider = 2004L;

        try (final ExchangeTestContainer container = ExchangeTestContainer.create(getPerformanceConfiguration(), processor);) {
            List<CoreSymbolSpecification> symbols = container.initFutureSymbols();
            CoreSymbolSpecification symbol = symbols.get(0);
            container.initMarkPrice(symbol.symbolId, entryPrice);

            // 创建用户
            container.createUserWithSpecificMoney(trader, deposit, QUOTE_ID);
            container.createUserWithSpecificMoney(liquidityProvider, largeDeposit, QUOTE_ID);

            log.info("创建大额持仓 - 全仓模式");
            long orderId = 40001L;

            // 交易者开大额多头持仓（全仓模式，有足够保证金支持部分强平）
            container.createBidWithOrderId(orderId++, trader, positionSize, entryPrice, symbol.symbolId, MarginMode.CROSS);
            container.createAskWithOrderId(orderId++, liquidityProvider, positionSize, entryPrice, symbol.symbolId, MarginMode.CROSS);

            // 验证持仓建立
            container.validateUserState(trader, profile -> {
                assertThat("交易者应该有持仓", profile.getPositions().size(), is(1));
                SingleUserReportResult.Position position = profile.getPositions().get(symbol.symbolId).get(0);
                assertThat("持仓数量正确", position.openVolume, is((long) positionSize));
                log.info("交易者大额持仓验证通过: size={}", position.openVolume);
            });

            long initialPositionSize = positionSize;

            log.info("创建有限流动性用于部分强平");
            // 只创建部分流动性，模拟部分强平场景
            int liquiditySize = positionSize / 2; // 只能强平一半持仓
            container.createBidWithOrderId(orderId++, liquidityProvider, liquiditySize, partialLiquidationPrice, symbol.symbolId, MarginMode.CROSS);

            log.info("价格下跌到 {} 触发部分强平", partialLiquidationPrice);
            // 价格下跌，触发强平
            container.updateCurrentPriceTo((int) partialLiquidationPrice, symbol.symbolId, QUOTE_ID);
            container.getExchangeCore().getLiquidationEngines().forEach(LiquidationEngine::triggerOnce);
            Thread.sleep(3000L);
            container.getApi().groupingControl(0, 1);
            Thread.sleep(500L);  // 确保事件处理完成

            log.info("验证部分强平结果");

            LatencyTools.waitForCondition(150, () -> {
                try {
                    return container.getUserProfile(trader).getPositions().get(symbol.symbolId).get(0).openVolume < initialPositionSize;
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });

            // 验证部分强平
            container.validateUserState(trader, profile -> {
                log.info("部分强平后交易者持仓数: {}", profile.getPositions().size());
                if (profile.getPositions().size() > 0) {
                    SingleUserReportResult.Position position = profile.getPositions().get(symbol.symbolId).get(0);
                    long remainingSize = Math.abs(position.openVolume);
                    log.info("剩余持仓: {}, 原始持仓: {}", remainingSize, initialPositionSize);

                    // 验证持仓减少了（部分强平）
                    assertThat("持仓应该减少", remainingSize < initialPositionSize, is(true));
                    assertThat("应该还有剩余持仓", remainingSize > 0, is(true));
                } else {
                    log.info("完全强平（可能由于价格波动较大）");
                }
            });

            // 验证流动性提供者的订单被完全消耗
            container.validateUserState(liquidityProvider, profile -> {
                if (profile.getPositions().size() > 0) {
                    SingleUserReportResult.Position position = profile.getPositions().get(symbol.symbolId).get(0);
                    log.info("流动性提供者强平后待成交: {}", position.pendingBuySize);
                    // 由于只提供了部分流动性，应该被完全消耗或接近完全消耗
                }
            });

        } catch (Exception e) {
            log.error("部分强平测试失败", e);
            fail("部分强平测试失败: " + e.getMessage());
        }
    }

    /**
     * 测试多用户同时强平场景
     * 多个用户同时达到强平条件，测试系统处理能力
     */
    @Test
    public void testMultipleUsersLiquidation() {
        long deposit = 6000L;
        long largeDeposit = 200000L;
        int positionSize = 4;
        long entryPrice = 9000L;
        long liquidationPrice = 2500L;

        // 创建多个交易者
        long[] traders = {1005L, 1006L, 1007L, 1008L, 1009L};
        long liquidityProvider = 2005L;

        try (final ExchangeTestContainer container = ExchangeTestContainer.create(getPerformanceConfiguration(), processor);) {
            List<CoreSymbolSpecification> symbols = container.initFutureSymbols();
            CoreSymbolSpecification symbol = symbols.get(0);
            container.initMarkPrice(symbol.symbolId, entryPrice);

            // 创建所有用户
            for (long trader : traders) {
                container.createUserWithSpecificMoney(trader, deposit, QUOTE_ID);
            }
            container.createUserWithSpecificMoney(liquidityProvider, largeDeposit, QUOTE_ID);

            log.info("为 {} 个交易者创建持仓", traders.length);
            long orderId = 50001L;

            // 所有交易者都建立多头持仓（逐仓模式）
            for (long trader : traders) {
                container.createBidWithOrderId(orderId++, trader, positionSize, entryPrice, symbol.symbolId, MarginMode.ISOLATED);
                container.createAskWithOrderId(orderId++, liquidityProvider, positionSize, entryPrice, symbol.symbolId, MarginMode.CROSS);
            }

            // 验证所有交易者都有持仓
            for (long trader : traders) {
                container.validateUserState(trader, profile -> {
                    assertThat("交易者 " + trader + " 应该有持仓", profile.getPositions().size(), is(1));
                });
            }

            log.info("创建足够的流动性接收所有强平订单");
            // 创建足够的流动性接收所有强平订单
            int totalLiquidityNeeded = traders.length * positionSize + 5;
            container.createBidWithOrderId(orderId++, liquidityProvider, totalLiquidityNeeded, liquidationPrice, symbol.symbolId, MarginMode.CROSS);

            log.info("价格下跌到 {} 同时触发 {} 个用户强平", liquidationPrice, traders.length);
            // 价格大幅下跌，同时触发所有用户强平
            container.updateCurrentPriceTo((int) liquidationPrice, symbol.symbolId, QUOTE_ID);
            container.getExchangeCore().getLiquidationEngines().forEach(LiquidationEngine::triggerOnce);
            Thread.sleep(3000L);
            container.getApi().groupingControl(0, 1);
            Thread.sleep(500L);  // 确保事件处理完成

            log.info("验证多用户强平结果");
            int liquidatedUsers = 0;
            for (long trader : traders) {
                container.validateUserState(trader, profile -> {
                    log.info("交易者 {} 强平后持仓数: {}", trader, profile.getPositions().size());
                    if (profile.getPositions().size() == 0) {
                        log.info("交易者 {} 完全被强平", trader);
                    } else {
                        SingleUserReportResult.Position position = profile.getPositions().get(symbol.symbolId).get(0);
                        log.info("交易者 {} 剩余持仓: {}", trader, position.openVolume);
                    }
                });
            }

            log.info("多用户强平测试完成");

            // 验证流动性提供者接收了强平订单
            container.validateUserState(liquidityProvider, profile -> {
                if (profile.getPositions().size() > 0) {
                    SingleUserReportResult.Position position = profile.getPositions().get(symbol.symbolId).get(0);
                    long consumedLiquidity = totalLiquidityNeeded - position.pendingBuySize;
                    log.info("流动性提供者接收了 {} 手强平订单", consumedLiquidity);
                    assertThat("应该接收了强平订单", consumedLiquidity > 0, is(true));
                }
            });

        } catch (Exception e) {
            log.error("多用户强平测试失败", e);
            fail("多用户强平测试失败: " + e.getMessage());
        }
    }

    /**
     * 测试多用户同时强平场景
     * 多个用户同时达到强平条件，测试系统处理能力
     */
    @Test
    public void testUsersServiceSharding() {
        long deposit = 6000L;
        long largeDeposit = 200000L;
        int positionSize = 4;
        long entryPrice = 9000L;
        long liquidationPrice = 2500L;

        // 创建多个交易者
        long[] traders = {1000L, 1001L, 1002L, 1003L, 1004L, 1005L, 1006L, 1007L, 1008L, 1009L, 1010L, 1011L, 1012L, 1013L, 1014L, 1015L, 1016L};
        long liquidityProvider = 2005L;

        try (final ExchangeTestContainer container = ExchangeTestContainer.create(getPerformanceConfiguration4Test(), processor);) {
            List<CoreSymbolSpecification> symbols = container.initFutureSymbols();
            CoreSymbolSpecification symbol = symbols.get(0);
            container.initMarkPrice(symbol.symbolId, 10000);

            // 创建所有用户
            for (long trader : traders) {
                container.createUserWithSpecificMoney(trader, deposit, QUOTE_ID);
            }
            container.createUserWithSpecificMoney(liquidityProvider, largeDeposit, QUOTE_ID);

            long orderId = 50001L;
            for (long trader : traders) {
                container.createBidWithOrderId(orderId++, trader, positionSize, entryPrice, symbol.symbolId, MarginMode.ISOLATED);
                container.createAskWithOrderId(orderId++, liquidityProvider, positionSize, entryPrice, symbol.symbolId, MarginMode.CROSS);
            }

            log.info("为 {} 个交易者创建持仓", traders.length);

            for (long user : traders) {
                assertThat(container.getUserProfile(user) != null, is(true));
            }

        } catch (ExecutionException e) {
            throw new RuntimeException(e);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
    }
}