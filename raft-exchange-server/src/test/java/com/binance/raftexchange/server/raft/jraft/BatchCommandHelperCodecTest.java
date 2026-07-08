package com.binance.raftexchange.server.raft.jraft;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.ByteBuffer;

import org.junit.jupiter.api.Test;

import com.binance.raftexchange.server.raft.jraft.closure.BatchClosure;
import com.binance.raftexchange.server.raft.jraft.closure.BatchClosure.PendingCmd;

class BatchCommandHelperCodecTest {

    @Test
    void packUnpackRoundTrip() {
        byte[][] raw = {{1, 2, 3}, {10, 20}, {0x42}};
        BatchClosure mc = closureOf(raw);

        byte[][] unpacked = BatchCommandHelper.unpackBatchEntry(ByteBuffer.wrap(BatchCommandHelper.packBatchEntry(mc)));

        assertEquals(raw.length, unpacked.length);
        for (int i = 0; i < raw.length; i++) {
            assertArrayEquals(raw[i], unpacked[i]);
        }
    }

    @Test
    void emptyBatchRoundTrip() {
        BatchClosure mc = new BatchClosure(new PendingCmd[0]);
        byte[][] unpacked = BatchCommandHelper.unpackBatchEntry(ByteBuffer.wrap(BatchCommandHelper.packBatchEntry(mc)));
        assertEquals(0, unpacked.length);
    }

    @Test
    void isBatchTrueForPackedOutput() {
        BatchClosure mc = closureOf(new byte[][] {{0x42}});
        assertTrue(BatchCommandHelper.isBatchEntry(ByteBuffer.wrap(BatchCommandHelper.packBatchEntry(mc))));
    }

    @Test
    void isBatchFalseForProtoTag() {
        assertFalse(BatchCommandHelper.isBatchEntry(ByteBuffer.wrap(new byte[] {0x08, 0x01})));
        assertFalse(BatchCommandHelper.isBatchEntry(ByteBuffer.wrap(new byte[] {0x12, 0x34})));
    }

    @Test
    void isBatchFalseForEmptyBuffer() {
        assertFalse(BatchCommandHelper.isBatchEntry(ByteBuffer.wrap(new byte[0])));
    }

    @Test
    void isBatchDoesNotAdvancePosition() {
        BatchClosure mc = closureOf(new byte[][] {{0x42}});
        ByteBuffer buf = ByteBuffer.wrap(BatchCommandHelper.packBatchEntry(mc));
        int posBefore = buf.position();
        BatchCommandHelper.isBatchEntry(buf);
        assertEquals(posBefore, buf.position(), "isBatchEntry 必须只读，不消费 bytes");
    }

    @Test
    void unpackRejectsNonBatch() {
        assertThrows(IllegalStateException.class,
            () -> BatchCommandHelper.unpackBatchEntry(ByteBuffer.wrap(new byte[] {0x08, 0x01, 0x02})));
    }

    @Test
    void largeCmdRoundTrip() {
        byte[] large = new byte[10_000];
        for (int i = 0; i < large.length; i++) {
            large[i] = (byte)(i & 0xFF);
        }
        BatchClosure mc = closureOf(new byte[][] {large, {1}, large});

        byte[][] unpacked = BatchCommandHelper.unpackBatchEntry(ByteBuffer.wrap(BatchCommandHelper.packBatchEntry(mc)));

        assertArrayEquals(large, unpacked[0]);
        assertArrayEquals(new byte[] {1}, unpacked[1]);
        assertArrayEquals(large, unpacked[2]);
    }

    private static BatchClosure closureOf(byte[][] raw) {
        PendingCmd[] cmds = new PendingCmd[raw.length];
        for (int i = 0; i < raw.length; i++) {
            cmds[i] = new PendingCmd(raw[i], null, (r, e) -> {
            });
        }
        return new BatchClosure(cmds);
    }
}
