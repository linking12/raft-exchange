package com.binance.raftexchange.client.tests.perf;

import com.binance.raftexchange.client.tests.util.TestDataParameters;
import com.binance.raftexchange.client.tests.util.ThroughputTestsModule;
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
                TestDataParameters.singlePairMarginBuilder().build(),
                50);
    }

    @Test
    void testThroughputExchange() {
        ThroughputTestsModule.throughputTestImpl(
                TestDataParameters.singlePairExchangeBuilder().build(),
                50);
    }

}