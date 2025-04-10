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

    /**
     * This is medium load latency test for verifying "triple million" capability:
     * - 1M active users (3M currency accounts)
     * - 1M pending limit-orders
     * - 1M+ messages per second throughput
     * - 10K symbols
     * - less than 1 millisecond 99.99% latency
     * 12-threads CPU and 32GiB RAM is required for running this test in 2+4 configuration.
     */
    @Test
    public void testLatencyMultiSymbolMedium() {
        latencyTestImpl(
                TestDataParameters.mediumBuilder().build(),
                8);
    }

    /**
     * This is high load latency test for verifying exchange core scalability:
     * - 3M active users (10M currency accounts)
     * - 3M pending limit-orders
     * - 1M+ messages per second throughput
     * - 100K symbols
     * - less than 1 millisecond 99.99% latency
     * 12-threads CPU and 32GiB RAM is required for running this test in 2+4 configuration.
     */
    @Test
    public void testLatencyMultiSymbolLarge() {
        latencyTestImpl(
                TestDataParameters.largeBuilder().build(),
                4);
    }

    /**
     * This is high load latency test for verifying exchange core scalability:
     * - 10M active users (33M currency accounts)
     * - 30M pending limit-orders
     * - 200K symbols
     * - 1M+ messages per second throughput
     * 12-threads CPU and 32GiB RAM is required for running this test in 2+4 configuration.
     */
    @Test
    public void testLatencyMultiSymbolHuge() {
        latencyTestImpl(
                TestDataParameters.hugeBuilder().build(),
                2);
    }

}