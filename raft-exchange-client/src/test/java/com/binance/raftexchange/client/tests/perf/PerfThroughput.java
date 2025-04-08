package com.binance.raftexchange.client.tests.perf;

import com.binance.raftexchange.client.tests.util.*;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;

/**
 * 这里都是client端，记得参考core的PerformanceConfiguration 启动raft-exchange服务端
 * 我们的服务端没有CLEAN_TEST，可以删除snapshot实现
 * 我们的服务端一定会打snapshot，可以调长snapshot的时间实现
 */
@Slf4j
final class PerfThroughput {

    @Test
    void testThroughputMargin() {
        ThroughputTestsModule.throughputTestImpl(
                TestDataParameters.singlePairMarginBuilder()
                        .numSymbols(1)
                        .build(),
                50);
    }

    @Test
    void testThroughputExchange() {
        ThroughputTestsModule.throughputTestImpl(
                TestDataParameters.singlePairExchangeBuilder()
                        .numSymbols(1)
                        .build(),
                50);
    }

    /**
     * This is medium load throughput test for verifying "triple million" capability:
     * * - 1M active users (3M currency accounts)
     * * - 1M pending limit-orders
     * * - 10K symbols
     * * - 1M+ messages per second target throughput
     * 12-threads CPU and 32GiB RAM is required for running this test in 4+4 configuration.
     */
    @Test
    public void testThroughputMultiSymbolMedium() {
        ThroughputTestsModule.throughputTestImpl(
                TestDataParameters.mediumBuilder()
                        .build(),
                50);
    }

    /**
     * This is high load throughput test for verifying exchange core scalability:
     * - 3M active users (10M currency accounts)
     * - 3M pending limit-orders
     * - 1M+ messages per second throughput
     * - 50K symbols
     * - less than 1 millisecond 99.99% latency
     * 12-threads CPU and 32GiB RAM is required for running this test in 2+4 configuration.
     */
    @Test
    public void testThroughputMultiSymbolLarge() {
        ThroughputTestsModule.throughputTestImpl(
                TestDataParameters.largeBuilder().build(),
                25);
    }

    /**
     * This is high load throughput test for verifying exchange core scalability:
     * - 10M active users (33M currency accounts)
     * - 30M pending limit-orders
     * - 1M+ messages per second throughput
     * - 100K symbols
     * - less than 1 millisecond 99.99% latency
     * 12-threads CPU and 32GiB RAM is required for running this test in 2+4 configuration.
     */
    @Test
    public void testThroughputMultiSymbolHuge() {
        ThroughputTestsModule.throughputTestImpl(
                TestDataParameters.hugeBuilder().build(),
                25);
    }

}