package com.binance.raftexchange.server.exchange.snapshot;

import exchange.core2.core.processors.journaling.ISerializationProcessor.SerializedModuleType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.io.PipedInputStream;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class StreamManagerTest {

    private static final SerializedModuleType ME = SerializedModuleType.MATCHING_ENGINE_ROUTER;
    private static final SerializedModuleType RE = SerializedModuleType.RISK_ENGINE;

    // Use unique IDs per test so tests do not share state in the static map
    private static final long HAPPY_ID = 9_000_001L;
    private static final long INTERRUPT_ID = 9_000_002L;
    private static final long DUPLICATE_ID = 9_000_003L;

    @AfterEach
    void cleanup() {
        StreamManager.close(HAPPY_ID, ME, 0);
        StreamManager.close(INTERRUPT_ID, RE, 0);
        StreamManager.close(DUPLICATE_ID, ME, 0);
    }

    // --- happy path: build → get → read → close ---

    @Test
    void buildThenGet_pipedStreamsAreConnected() throws Exception {
        OutputStream out = StreamManager.build(HAPPY_ID, ME, 0);
        PipedInputStream in = StreamManager.get(HAPPY_ID, ME, 0);

        assertNotNull(out);
        assertNotNull(in);

        // Write one byte through the pipe and read it back
        out.write(0xAB);
        out.close();
        assertEquals(0xAB, in.read());
    }

    // --- build twice with same key throws ---

    @Test
    void build_duplicateId_throwsIllegalStateException() throws Exception {
        StreamManager.build(DUPLICATE_ID, ME, 0);
        assertThrows(IllegalStateException.class, () -> StreamManager.build(DUPLICATE_ID, ME, 0));
    }

    // --- get() on a blocked stream that gets interrupted restores interrupt flag ---

    @Test
    void get_interrupted_throwsAndRestoresInterruptFlag() throws InterruptedException {
        AtomicReference<Throwable> caught = new AtomicReference<>();
        AtomicBoolean interruptFlagRestored = new AtomicBoolean(false);

        // Unique ID with no corresponding build() so poll() blocks
        long uniqueId = System.nanoTime();

        Thread t = new Thread(() -> {
            try {
                StreamManager.get(uniqueId, RE, 0);
            } catch (IllegalStateException e) {
                caught.set(e);
                interruptFlagRestored.set(Thread.currentThread().isInterrupted());
            } finally {
                StreamManager.close(uniqueId, RE, 0); // clean up map entry
            }
        });

        t.start();
        Thread.sleep(80); // let the thread enter poll()
        t.interrupt();
        t.join(5_000);

        assertNotNull(caught.get(), "get() should have thrown");
        assertInstanceOf(IllegalStateException.class, caught.get());
        assertTrue(caught.get().getMessage().contains("Interrupted"),
            "message should say 'Interrupted', got: " + caught.get().getMessage());
        assertTrue(interruptFlagRestored.get(), "interrupt flag must be restored on the calling thread");
    }

    // --- close on unknown id is a no-op ---

    @Test
    void close_unknownId_doesNotThrow() {
        assertDoesNotThrow(() -> StreamManager.close(Long.MAX_VALUE, ME, 99));
    }
}
