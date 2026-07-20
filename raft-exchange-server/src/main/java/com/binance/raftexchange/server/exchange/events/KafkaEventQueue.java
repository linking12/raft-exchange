package com.binance.raftexchange.server.exchange.events;

import java.io.Closeable;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.LongAdder;
import java.util.concurrent.locks.LockSupport;

import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.binance.raftexchange.server.metrics.RaftExchangeMetrics;
import net.openhft.chronicle.bytes.Bytes;
import net.openhft.chronicle.queue.ChronicleQueue;
import net.openhft.chronicle.queue.ExcerptAppender;
import net.openhft.chronicle.queue.ExcerptTailer;
import net.openhft.chronicle.queue.impl.single.SingleChronicleQueueBuilder;
import net.openhft.chronicle.queue.rollcycles.LargeRollCycles;
import net.openhft.chronicle.wire.DocumentContext;

public final class KafkaEventQueue implements KafkaEventSink, Closeable {
    private static final Logger LOG = LoggerFactory.getLogger(KafkaEventQueue.class);

    private final ChronicleQueue queue;
    // StoreAppender is not thread-safe; each Disruptor thread gets its own instance.
    private final ConcurrentLinkedQueue<ExcerptAppender> allAppenders = new ConcurrentLinkedQueue<>();
    private final ThreadLocal<ExcerptAppender> appenderHolder;
    private final Path cursorFile;
    // volatile：producers/topics 可由后台线程异步 bind，sender loop 读到 null 时 park。
    // Kafka 不可达时不阻塞 server 启动，events 先落 Chronicle 排队等 producer 就绪。
    private volatile Map<IEventsHandlerByKafka.TopicGroup, KafkaProducer<Long, byte[]>> producers;
    private volatile Map<IEventsHandlerByKafka.TopicGroup, String> topics;

    private volatile boolean running = true;
    private final Thread senderThread;

    // Chronicle index 编码 cycle+seq 不能直接相减；用两个 adder 算 backlog。
    private final LongAdder enqueuedCount = new LongAdder();
    private final LongAdder sentCount = new LongAdder();

    static {
        RaftExchangeMetrics.Kafka
            .prewarm(Arrays.stream(IEventsHandlerByKafka.TopicGroup.values()).map(Enum::name).toList());
    }

    public KafkaEventQueue(String raftClusterName,
        Map<IEventsHandlerByKafka.TopicGroup, KafkaProducer<Long, byte[]>> producers,
        Map<IEventsHandlerByKafka.TopicGroup, String> topics) throws IOException {
        this(raftClusterName);
        bindProducers(producers, topics);
    }

    /**
     * 不带 producers 的 ctor，让 KafkaProducer 异步初始化。Chronicle/sender 线程立即启动， Kafka 不可达不会阻塞调用方；events 先入本地 Chronicle 排队，等
     * {@link #bindProducers} 注入后再消费。
     */
    public KafkaEventQueue(String raftClusterName) throws IOException {
        this.producers = null;
        this.topics = null;
        Path queueDir = Paths.get(System.getProperty("app.home"), raftClusterName + "-EVENT");
        this.cursorFile = queueDir.resolve("kafka-cursor.dat");

        Files.createDirectories(queueDir);
        this.queue = SingleChronicleQueueBuilder.binary(queueDir).rollCycle(LargeRollCycles.LARGE_DAILY).build();
        this.appenderHolder = ThreadLocal.withInitial(() -> {
            ExcerptAppender a = queue.createAppender();
            allAppenders.add(a);
            return a;
        });

        this.senderThread = new Thread(this::senderLoop, "kafka-event-forwarder");
        this.senderThread.setDaemon(true);
        this.senderThread.start();

        // 重启时 chronicle 残留会被 sender 消费但本进程没记 enqueue → clip 0 避免瞬时为负。
        RaftExchangeMetrics.Kafka.registerBacklogGauge(raftClusterName,
            () -> Math.max(0L, enqueuedCount.sum() - sentCount.sum()));
    }

    /** 后台线程完成 KafkaProducer 创建后调用，唤醒 sender 线程开始消费 Chronicle 队列。 */
    public void bindProducers(Map<IEventsHandlerByKafka.TopicGroup, KafkaProducer<Long, byte[]>> producers,
        Map<IEventsHandlerByKafka.TopicGroup, String> topics) {
        this.topics = topics;
        this.producers = producers; // last write — sender loop 读 producers != null 即认为 bind 完成
        LockSupport.unpark(senderThread);
    }

    @Override
    public void enqueue(IEventsHandlerByKafka.TopicGroup group, long key, byte[] payload) {
        try (DocumentContext dc = appenderHolder.get().writingDocument()) {
            Bytes<?> b = dc.wire().bytes();
            b.writeByte((byte)group.ordinal());
            b.writeLong(key);
            b.writeInt(payload.length);
            b.write(payload);
        }
        enqueuedCount.increment();
    }

    private void senderLoop() {
        IEventsHandlerByKafka.TopicGroup[] groups = IEventsHandlerByKafka.TopicGroup.values();
        ExcerptTailer tailer = queue.createTailer();
        long savedCursor = readCursor();
        if (savedCursor >= 0) {
            if (tailer.moveToIndex(savedCursor)) {
                try (DocumentContext skip = tailer.readingDocument()) {
                    /* discard */ }
            } else {
                LOG.warn("kafka-sender: cursor index {} not found in queue; replaying from queue head", savedCursor);
                tailer.toStart();
            }
        }

        while (running) {
            // Kafka producer 尚未 bind（Kafka init 还在后台）→ park 等待 bindProducers 唤醒。
            // 期间 enqueue 走 Chronicle 累积，不会丢。
            if (producers == null) {
                LockSupport.parkNanos(TimeUnit.SECONDS.toNanos(1));
                continue;
            }
            try (DocumentContext dc = tailer.readingDocument()) {
                if (!dc.isPresent()) {
                    LockSupport.parkNanos(100_000L); // 100µs idle poll
                    continue;
                }

                long entryIndex = dc.index();

                Bytes<?> b = dc.wire().bytes();
                IEventsHandlerByKafka.TopicGroup group = groups[b.readUnsignedByte()];
                long key = b.readLong();
                int len = b.readInt();
                byte[] payload = new byte[len];
                b.read(payload);
                sendWithRetry(group, key, payload, entryIndex);
            }
        }
        tailer.close();
    }

    private void sendWithRetry(IEventsHandlerByKafka.TopicGroup group, long key, byte[] payload, long entryIndex) {
        ProducerRecord<Long, byte[]> record = new ProducerRecord<>(topics.get(group), key, payload);
        long sendStart = System.nanoTime();
        while (running) {
            try {
                producers.get(group).send(record).get();
                RaftExchangeMetrics.Kafka.recordSendSuccess(group.name(), System.nanoTime() - sendStart);
                sentCount.increment();
                saveCursor(entryIndex);
                return;
            } catch (Exception e) {
                RaftExchangeMetrics.Kafka.recordSendFailure(group.name());
                LOG.error("Kafka send failed, retrying in 1s, group={} key={}", group, key, e);
                LockSupport.parkNanos(TimeUnit.SECONDS.toNanos(1));
                sendStart = System.nanoTime();
            }
        }
    }

    private long readCursor() {
        if (!Files.exists(cursorFile))
            return -1L;
        try {
            return Long.parseLong(Files.readString(cursorFile).trim());
        } catch (Exception e) {
            LOG.warn("Failed to read kafka-cursor — replaying from queue head", e);
            return -1L;
        }
    }

    private void saveCursor(long index) {
        try {
            Files.writeString(cursorFile, Long.toString(index));
        } catch (IOException e) {
            LOG.warn("Failed to persist kafka-cursor at {} — may re-send on restart", index, e);
        }
    }

    @Override
    public void close() {
        running = false;
        LockSupport.unpark(senderThread);
        try {
            senderThread.join(10_000);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
        allAppenders.forEach(ExcerptAppender::close);
        queue.close();
    }
}
