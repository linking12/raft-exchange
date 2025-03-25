package com.binance.raftexchange.client.tests.perf;

import com.binance.raftexchange.client.tests.util.TestDataParameters;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;

import static com.binance.raftexchange.client.tests.util.LatencyTestsModule.latencyTestImpl;

/**
 * 这里都是client端，记得参考core的PerformanceConfiguration 启动raft-exchange服务端
 * 我们的服务端没有CLEAN_TEST，可以删除snapshot实现
 * 我们的服务端一定会打snapshot，可以调长snapshot的时间实现
 */
@Slf4j
final class PerfLatency {

    /**
     * This is latency test for simplified conditions
     * - one symbol (margin mode)
     * - ~1K active users (2K currency accounts)
     * - 1K pending limit-orders (in one order book)
     * 6-threads CPU can run this test
     */
    @Test
    public void testLatencyMargin() {
        latencyTestImpl(
                TestDataParameters.singlePairMarginBuilder().build(),
                16);
    }

    /**
     * This is latency test for simplified conditions
     * - one symbol (exchange mode)
     * - ~1K active users (2K currency accounts)
     * - 1K pending limit-orders (in one order book)
     * 6-threads CPU can run this test
     */
    @Test
    public void testLatencyExchange() {
        latencyTestImpl(
                TestDataParameters.singlePairExchangeBuilder().build(),
                16);
    }

}