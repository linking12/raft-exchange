package exchange.core2.core.common;

import net.openhft.chronicle.bytes.Bytes;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BoundedLongDedupSetTest {

    @Test
    void claimReturnsTrueFirstTimeFalseSecondTime() {
        BoundedLongDedupSet set = new BoundedLongDedupSet(8);
        assertTrue(set.tryClaim(101L));
        assertFalse(set.tryClaim(101L));
        assertTrue(set.tryClaim(102L));
        assertEquals(2, set.size());
    }

    // FIFO 驱逐：第 capacity+1 条挤掉最老的；被驱逐 ID 的"重投"会被当作新事件通过。
    // 用 tryClaim 自身的返回值反推集合状态——纯行为验证，不依赖只读探测 API。
    @Test
    void evictsOldestWhenAtCapacity() {
        BoundedLongDedupSet set = new BoundedLongDedupSet(3);
        set.tryClaim(1L);
        set.tryClaim(2L);
        set.tryClaim(3L);
        assertEquals(3, set.size());

        // 满了 → 加 4 → 挤掉 1
        assertTrue(set.tryClaim(4L));
        assertEquals(3, set.size());
        assertFalse(set.tryClaim(2L), "2 仍在表里");
        assertFalse(set.tryClaim(3L), "3 仍在表里");
        assertFalse(set.tryClaim(4L), "4 已入表");
        // 1 已被挤掉 → 再次 claim 应被当成新事件接受
        assertTrue(set.tryClaim(1L), "1 已驱逐，重投视为新事件");
    }

    // raft 收敛：所有 follower 写入同一序列后 stateHash 必须等价。
    @Test
    void stateHashConvergesAcrossEquivalentInsertionSequences() {
        BoundedLongDedupSet a = new BoundedLongDedupSet(4);
        a.tryClaim(10L); a.tryClaim(20L); a.tryClaim(30L);

        BoundedLongDedupSet b = new BoundedLongDedupSet(4);
        b.tryClaim(10L); b.tryClaim(20L); b.tryClaim(30L);

        assertEquals(a.stateHash(), b.stateHash());
    }

    // 序列化 round-trip：snapshot 读回后行为等价——同样的 tryClaim 序列产生同样的结果。
    @Test
    void serializationRoundTripPreservesBehavior() {
        BoundedLongDedupSet original = new BoundedLongDedupSet(8);
        original.tryClaim(7L);
        original.tryClaim(14L);
        original.tryClaim(21L);

        Bytes<?> buf = Bytes.allocateElasticOnHeap(128);
        original.writeMarshallable(buf);

        BoundedLongDedupSet restored = new BoundedLongDedupSet(buf);
        assertEquals(original.size(), restored.size());
        assertEquals(original.stateHash(), restored.stateHash());

        // 已 claim 的 ID 重投仍被拒，未 claim 的 ID 仍被接受
        assertFalse(restored.tryClaim(7L));
        assertFalse(restored.tryClaim(14L));
        assertFalse(restored.tryClaim(21L));
        assertTrue(restored.tryClaim(99L));
    }

    // 已驱逐的 ID 在序列化后从队列里消失，重投视为新事件。
    @Test
    void serializationAfterEvictionDropsEvictedIds() {
        BoundedLongDedupSet original = new BoundedLongDedupSet(2);
        original.tryClaim(1L);
        original.tryClaim(2L);
        original.tryClaim(3L); // 挤掉 1

        Bytes<?> buf = Bytes.allocateElasticOnHeap(64);
        original.writeMarshallable(buf);

        BoundedLongDedupSet restored = new BoundedLongDedupSet(buf);
        assertEquals(2, restored.size());
        assertFalse(restored.tryClaim(2L), "2 仍在");
        assertFalse(restored.tryClaim(3L), "3 仍在");
        // 注：tryClaim(1L) 会挤掉 2，所以放最后验
        assertTrue(restored.tryClaim(1L), "1 已驱逐，重投视为新事件");
    }

    @Test
    void rejectsInvalidCapacity() {
        assertThrows(IllegalArgumentException.class, () -> new BoundedLongDedupSet(0));
        assertThrows(IllegalArgumentException.class, () -> new BoundedLongDedupSet(-1));
    }

    // 损坏 snapshot 防御：capacity 异常、size 异常（含负值/越界）都应直接抛 IllegalStateException，
    // 不能进入构造完成、留下负 size 让后续 tryClaim 撞 ArrayIndexOutOfBoundsException。
    @Test
    void rejectsCorruptedSnapshot() {
        // capacity = 0
        Bytes<?> buf1 = Bytes.allocateElasticOnHeap(16);
        buf1.writeInt(0); buf1.writeInt(0);
        assertThrows(IllegalStateException.class, () -> new BoundedLongDedupSet(buf1));

        // capacity 负值
        Bytes<?> buf2 = Bytes.allocateElasticOnHeap(16);
        buf2.writeInt(-1); buf2.writeInt(0);
        assertThrows(IllegalStateException.class, () -> new BoundedLongDedupSet(buf2));

        // size 负值
        Bytes<?> buf3 = Bytes.allocateElasticOnHeap(16);
        buf3.writeInt(8); buf3.writeInt(-3);
        assertThrows(IllegalStateException.class, () -> new BoundedLongDedupSet(buf3));

        // size > capacity
        Bytes<?> buf4 = Bytes.allocateElasticOnHeap(16);
        buf4.writeInt(8); buf4.writeInt(99);
        assertThrows(IllegalStateException.class, () -> new BoundedLongDedupSet(buf4));
    }
}
