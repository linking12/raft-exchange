package exchange.core2.core.common.config;


import exchange.core2.core.common.CoreWaitStrategy;
import exchange.core2.core.orderbook.IOrderBook;
import exchange.core2.core.orderbook.OrderBookDirectImpl;
import exchange.core2.core.orderbook.OrderBookNaiveImpl;
import exchange.core2.core.utils.AffinityThreadFactory;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import net.jpountz.lz4.LZ4Compressor;
import net.jpountz.lz4.LZ4Factory;

import java.util.concurrent.ThreadFactory;
import java.util.function.Supplier;

/**
 * Exchange performance configuration
 */
@AllArgsConstructor
@Getter
@Builder(toBuilder = true)
public final class PerformanceConfiguration {

    public static final PerformanceConfiguration DEFAULT = PerformanceConfiguration.throughputPerformanceBuilder().build();

    /*
     * Disruptor ring buffer size (number of commands). Must be power of 2.
     */
    private final int ringBufferSize;

    /*
     * Number of matching engines. Each instance requires extra CPU core.
     */
    private final int matchingEnginesNum;

    /*
     * Number of risk engines. Each instance requires extra CPU core.
     */
    private final int riskEnginesNum;

    /*
     * max number of messages not processed by R2 stage. Must be less than quarter of ringBufferSize.
     * Lower values, like 100, provide better mean latency.
     * Higher values, like 2000 provide better throughput and tail latency.
     */
    private final int msgsInGroupLimit;


    /*
     * max interval when messages not processed by R2 stage.
     * Interfere with msgsInGroupLimit parameter.
     * Lower values, like 1000 (1us), provide better mean latency.
     * Higher values, like 2000 provide better throughput and tail latency.
     */
    private final int maxGroupDurationNs;

    /*
     * Disruptor threads factory (also reused for snapshot loader executor)
     */
    private final ThreadFactory disruptorThreadFactory;

    /*
     * Disruptor wait strategy
     */
    private final CoreWaitStrategy waitStrategy;

    /*
     * LiquidationEngine 的调度线程工厂（每 shard 一个调度线程）。
     * null = LiquidationEngine 用内部默认 daemon Thread::new（生产路径，2 秒间隔扫描不需要钉核）。
     * 非 null = 用调用方传的工厂；测试容器在这里塞 AffinityThreadFactory，避免 LiquidationEngine
     * 调度线程被 Disruptor / 其它测试 fork 线程抢占造成 Awaitility 超时。
     */
    private final ThreadFactory liquidationThreadFactory;

    /*
     * Order books factory
     */
    private final IOrderBook.OrderBookFactory orderBookFactory;

    /*
     * LZ4 compressor factory for binary commands and reports
     */
    private final Supplier<LZ4Compressor> binaryCommandsLz4CompressorFactory;

    @Override
    public String toString() {
        return "PerformanceConfiguration{" +
                "ringBufferSize=" + ringBufferSize +
                ", matchingEnginesNum=" + matchingEnginesNum +
                ", riskEnginesNum=" + riskEnginesNum +
                ", msgsInGroupLimit=" + msgsInGroupLimit +
                ", maxGroupDurationNs=" + maxGroupDurationNs +
                ", disruptorThreadFactory=" + (disruptorThreadFactory == null ? null : disruptorThreadFactory.getClass().getSimpleName()) +
                ", waitStrategy=" + waitStrategy +
                ", liquidationThreadFactory=" + (liquidationThreadFactory == null ? null : liquidationThreadFactory.getClass().getSimpleName()) +
                ", orderBookFactory=" + (orderBookFactory == null ? null : orderBookFactory.getClass().getSimpleName()) +
                ", binaryCommandsLz4CompressorFactory=" + (binaryCommandsLz4CompressorFactory == null ? null : binaryCommandsLz4CompressorFactory.getClass().getSimpleName()) +
                '}';
    }

    // TODO add expected number of users and symbols

    public static PerformanceConfiguration.PerformanceConfigurationBuilder baseBuilder() {

        return builder()
                .ringBufferSize(16 * 1024)
                .matchingEnginesNum(1)
                .riskEnginesNum(1)
                .msgsInGroupLimit(256)
                .maxGroupDurationNs(10_000)
                .disruptorThreadFactory(Thread::new)
                .liquidationThreadFactory(Thread::new)
                .waitStrategy(CoreWaitStrategy.BLOCKING)
                .binaryCommandsLz4CompressorFactory(() -> LZ4Factory.fastestInstance().highCompressor())
                .orderBookFactory(OrderBookNaiveImpl::new);
    }

    public static PerformanceConfiguration.PerformanceConfigurationBuilder latencyPerformanceBuilder() {

        return builder()
                .ringBufferSize(2 * 1024)
                .matchingEnginesNum(1)
                .riskEnginesNum(1)
                .msgsInGroupLimit(256)
                .maxGroupDurationNs(10_000)
                .disruptorThreadFactory(new AffinityThreadFactory(AffinityThreadFactory.ThreadAffinityMode.THREAD_AFFINITY_ENABLE_PER_LOGICAL_CORE, "Exchange-Core-Disruptor"))
                .liquidationThreadFactory(new AffinityThreadFactory(AffinityThreadFactory.ThreadAffinityMode.THREAD_AFFINITY_DISABLE, "LiquidationEngine-"))
                .waitStrategy(CoreWaitStrategy.BUSY_SPIN)
                .binaryCommandsLz4CompressorFactory(() -> LZ4Factory.fastestInstance().highCompressor())
                .orderBookFactory(OrderBookDirectImpl::new);
    }

    public static PerformanceConfiguration.PerformanceConfigurationBuilder throughputPerformanceBuilder() {

        return builder()
                .ringBufferSize(64 * 1024)
                .matchingEnginesNum(4)
                .riskEnginesNum(2)
                .msgsInGroupLimit(4_096)
                .maxGroupDurationNs(4_000_000)
                .disruptorThreadFactory(new AffinityThreadFactory(AffinityThreadFactory.ThreadAffinityMode.THREAD_AFFINITY_ENABLE_PER_LOGICAL_CORE, "Exchange-Core-Disruptor"))
                .liquidationThreadFactory(new AffinityThreadFactory(AffinityThreadFactory.ThreadAffinityMode.THREAD_AFFINITY_DISABLE, "LiquidationEngine-"))
                .waitStrategy(CoreWaitStrategy.BUSY_SPIN)
                .binaryCommandsLz4CompressorFactory(() -> LZ4Factory.fastestInstance().highCompressor())
                .orderBookFactory(OrderBookDirectImpl::new);
    }
}
