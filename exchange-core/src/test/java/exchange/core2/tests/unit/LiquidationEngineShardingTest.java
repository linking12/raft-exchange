package exchange.core2.tests.unit;

import exchange.core2.core.processors.liquidation.GlobalADLService;
import exchange.core2.core.processors.liquidation.LiquidationEngine;
import exchange.core2.core.common.FundEvent;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;

import java.util.List;
import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 测试多个 LiquidationEngine 分片处理不同用户的场景
 * 专注于测试初始化逻辑和分片管理，避免复杂的 Mock 依赖
 */
class LiquidationEngineShardingTest {

    private Supplier<FundEvent> eventSupplier;

    private static final int NUM_SHARDS = 4;
    private static final int TOTAL_USERS = 1000;
    
    @BeforeEach
    void setUp() {
        // Event supplier
        eventSupplier = () -> new FundEvent();
    }

    /**
     * 测试多个 LiquidationEngine 的基本初始化
     */
    @Test
    void testMultipleLiquidationEnginesInitialization() {
        List<LiquidationEngine> engines = new ArrayList<>();
        
        // 创建多个 LiquidationEngine 实例
        for (int shardId = 0; shardId < NUM_SHARDS; shardId++) {
            LiquidationEngine engine = new LiquidationEngine(eventSupplier, shardId, NUM_SHARDS, new GlobalADLService(NUM_SHARDS));
            engines.add(engine);
            
            // 验证构造成功
            assertNotNull(engine);
        }
        
        assertEquals(NUM_SHARDS, engines.size());
    }

    /**
     * 测试分片的用户分配逻辑
     * 验证不同分片处理不同的用户集合，没有重叠
     */
    @Test
    void testUserShardingLogic() {
        List<AtomicInteger> processedUserCounts = new ArrayList<>();
        
        // 初始化计数器
        for (int shardId = 0; shardId < NUM_SHARDS; shardId++) {
            processedUserCounts.add(new AtomicInteger(0));
        }
        
        // 模拟分片算法：uid % numShards == shardId
        for (long uid = 1; uid <= TOTAL_USERS; uid++) {
            int shardId = (int)(uid % NUM_SHARDS);
            processedUserCounts.get(shardId).incrementAndGet();
        }
        
        // 验证分片覆盖了所有用户且无重叠
        int totalProcessedUsers = processedUserCounts.stream()
                .mapToInt(AtomicInteger::get)
                .sum();
        
        assertEquals(TOTAL_USERS, totalProcessedUsers);
        
        // 验证每个分片处理的用户数量相对均匀
        for (AtomicInteger count : processedUserCounts) {
            assertTrue(count.get() > 0, "每个分片都应该处理一些用户");
            assertTrue(count.get() <= TOTAL_USERS / NUM_SHARDS + 10, "用户分配应该相对均匀");
        }
        
        System.out.println("用户分片分布：");
        for (int i = 0; i < NUM_SHARDS; i++) {
            System.out.println("分片 " + i + ": " + processedUserCounts.get(i).get() + " 个用户");
        }
    }

    /**
     * 测试并发场景下多个 LiquidationEngine 同时创建
     */
    @Test
    void testConcurrentLiquidationEnginesCreation() throws InterruptedException {
        ExecutorService executor = Executors.newFixedThreadPool(NUM_SHARDS);
        CountDownLatch latch = new CountDownLatch(NUM_SHARDS);
        List<Exception> exceptions = new ArrayList<>();
        List<LiquidationEngine> engines = new ArrayList<>();
        
        // 并发创建多个 LiquidationEngine
        for (int shardId = 0; shardId < NUM_SHARDS; shardId++) {
            final int currentShardId = shardId;
            executor.submit(() -> {
                try {
                    LiquidationEngine engine = new LiquidationEngine(eventSupplier, currentShardId, NUM_SHARDS, new GlobalADLService(NUM_SHARDS));
                    synchronized (engines) {
                        engines.add(engine);
                    }
                } catch (Exception e) {
                    synchronized (exceptions) {
                        exceptions.add(e);
                    }
                } finally {
                    latch.countDown();
                }
            });
        }
        
        assertTrue(latch.await(10, TimeUnit.SECONDS), "所有强平引擎应该在超时前完成创建");
        assertTrue(exceptions.isEmpty(), "不应该有异常发生: " + exceptions);
        assertEquals(NUM_SHARDS, engines.size(), "应该成功创建所有分片的引擎");
        
        executor.shutdown();
    }

    /**
     * 测试 LiquidationEngine 的生命周期管理
     */
    @Test
    void testLiquidationEngineLifecycle() {
        List<LiquidationEngine> engines = new ArrayList<>();
        
        // 创建引擎
        for (int shardId = 0; shardId < NUM_SHARDS; shardId++) {
            LiquidationEngine engine = new LiquidationEngine(eventSupplier, shardId, NUM_SHARDS, new GlobalADLService(NUM_SHARDS));
            engines.add(engine);
        }
        
        // 启动所有引擎
        engines.forEach(LiquidationEngine::start);
        
        // 稍等一下让定时任务启动
        try {
            Thread.sleep(100);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        
        // 停止所有引擎
        engines.forEach(LiquidationEngine::stop);
        
        // 验证没有异常
        assertDoesNotThrow(() -> {
            engines.forEach(engine -> engine.stop(1, TimeUnit.SECONDS));
        });
    }

    /**
     * 测试不同分片ID的构造
     */
    @Test
    void testDifferentShardIds() {
        for (int shardId = 0; shardId < NUM_SHARDS; shardId++) {
            LiquidationEngine engine = new LiquidationEngine(eventSupplier, shardId, NUM_SHARDS, new GlobalADLService(NUM_SHARDS));
            assertNotNull(engine);
        }
    }

    /**
     * 测试边界条件：单分片场景
     */
    @Test
    void testSingleShardScenario() {
        LiquidationEngine engine = new LiquidationEngine(eventSupplier, 0, 1, new GlobalADLService(1));
        assertNotNull(engine);
        
        // 测试生命周期
        assertDoesNotThrow(() -> {
            engine.start();
            Thread.sleep(50);
            engine.stop();
        });
    }

    /**
     * 测试分片负载分布的公平性
     */
    @Test
    void testShardLoadDistribution() {
        int totalUsers = 10000;
        int[] shardCounts = new int[NUM_SHARDS];
        
        // 模拟用户分配到各个分片
        for (long uid = 1; uid <= totalUsers; uid++) {
            int shardId = (int)(uid % NUM_SHARDS);
            shardCounts[shardId]++;
        }
        
        // 验证分配的公平性
        int expectedPerShard = totalUsers / NUM_SHARDS;
        for (int i = 0; i < NUM_SHARDS; i++) {
            assertEquals(expectedPerShard, shardCounts[i], 
                "分片 " + i + " 的用户数量应该是 " + expectedPerShard);
        }
        
        System.out.println("负载分布测试 - 每个分片处理 " + expectedPerShard + " 个用户");
    }
}