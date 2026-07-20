package com.binance.raftexchange.server.raft.aeron;

import org.agrona.DirectBuffer;
import org.agrona.concurrent.UnsafeBuffer;

final class AeronFrame {

    private AeronFrame() {}

    /** client → leader: {@code [8B cid][payload]}. */
    static final class Ingress {
        static final int HEADER_LENGTH = Long.BYTES;
        private static final int CID_OFFSET = 0;
        private static final int PAYLOAD_OFFSET = Long.BYTES;

        private Ingress() {}

        static UnsafeBuffer encode(long correlationId, byte[] payload) {
            UnsafeBuffer buffer = new UnsafeBuffer(new byte[HEADER_LENGTH + payload.length]);
            buffer.putLong(CID_OFFSET, correlationId);
            buffer.putBytes(PAYLOAD_OFFSET, payload);
            return buffer;
        }

        static long correlationId(DirectBuffer buffer, int offset) {
            return buffer.getLong(offset + CID_OFFSET);
        }

        static byte[] payload(DirectBuffer buffer, int offset, int length) {
            byte[] payload = new byte[length - HEADER_LENGTH];
            buffer.getBytes(offset + PAYLOAD_OFFSET, payload);
            return payload;
        }
    }

    /** leader → client: {@code [8B cid][8B leaderLogPos][8B engineNanos][payload]}. */
    static final class Egress {
        static final int HEADER_LENGTH = Long.BYTES * 3;
        private static final int CID_OFFSET = 0;
        private static final int LEADER_LOG_POS_OFFSET = Long.BYTES;
        private static final int ENGINE_NANOS_OFFSET = Long.BYTES * 2;
        private static final int PAYLOAD_OFFSET = Long.BYTES * 3;

        private Egress() {}

        static UnsafeBuffer encode(long correlationId, long leaderLogPosition, long engineNanosTaken, byte[] payload) {
            UnsafeBuffer buffer = new UnsafeBuffer(new byte[HEADER_LENGTH + payload.length]);
            buffer.putLong(CID_OFFSET, correlationId);
            buffer.putLong(LEADER_LOG_POS_OFFSET, leaderLogPosition);
            buffer.putLong(ENGINE_NANOS_OFFSET, engineNanosTaken);
            buffer.putBytes(PAYLOAD_OFFSET, payload);
            return buffer;
        }

        static long correlationId(DirectBuffer buffer, int offset) {
            return buffer.getLong(offset + CID_OFFSET);
        }

        static long leaderLogPosition(DirectBuffer buffer, int offset) {
            return buffer.getLong(offset + LEADER_LOG_POS_OFFSET);
        }

        static long engineNanosTaken(DirectBuffer buffer, int offset) {
            return buffer.getLong(offset + ENGINE_NANOS_OFFSET);
        }

        static byte[] payload(DirectBuffer buffer, int offset, int length) {
            byte[] payload = new byte[length - HEADER_LENGTH];
            buffer.getBytes(offset + PAYLOAD_OFFSET, payload);
            return payload;
        }
    }
}
