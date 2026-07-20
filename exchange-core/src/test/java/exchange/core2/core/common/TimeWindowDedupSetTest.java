package exchange.core2.core.common;

import net.openhft.chronicle.bytes.Bytes;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TimeWindowDedupSetTest {

    private static final long WINDOW = 1_000L; // 测试用 1s 窗口
    private static final int CAP = 8;

    @Test
    void claimReturnsTrueFirstTimeFalseWithinWindow() {
        TimeWindowDedupSet set = new TimeWindowDedupSet(WINDOW, CAP);
        assertTrue(set.tryClaim(101L, 100L));
        assertFalse(set.tryClaim(101L, 200L), "窗口内重投应被拒");
        assertTrue(set.tryClaim(102L, 300L));
        assertEquals(2, set.size());
    }

    // 时间窗淘汰：超过 windowMs 后老 id 过期，同 id 重投视为新事件。
    @Test
    void expiresIdAfterWindow() {
        TimeWindowDedupSet set = new TimeWindowDedupSet(WINDOW, CAP);
        assertTrue(set.tryClaim(1L, 100L));
        // 100 + 1000 = 1100 仍在窗口边界内（cutoff = 1100 - 1000 = 100，time=100 不 < 100）
        assertFalse(set.tryClaim(1L, 1100L), "边界内仍去重");
        // 1101：cutoff = 101，time=100 < 101 → 过期淘汰 → 重投接受
        assertTrue(set.tryClaim(1L, 1101L), "超窗后重投视为新事件");
        assertEquals(1, set.size());
    }

    // 硬上限兜底：window 很大不会按时间淘汰，达到 hardCap 时按最老淘汰。
    @Test
    void evictsOldestWhenAtHardCap() {
        TimeWindowDedupSet set = new TimeWindowDedupSet(Long.MAX_VALUE / 2, 3);
        assertTrue(set.tryClaim(1L, 10L));
        assertTrue(set.tryClaim(2L, 20L));
        assertTrue(set.tryClaim(3L, 30L));
        assertEquals(3, set.size());

        assertTrue(set.tryClaim(4L, 40L), "第 4 条");
        assertEquals(3, set.size(), "达到 hardCap，总量不超");
        assertFalse(set.tryClaim(2L, 41L), "2 仍在");
        assertFalse(set.tryClaim(3L, 42L), "3 仍在");
        assertFalse(set.tryClaim(4L, 43L), "4 仍在");
        assertTrue(set.tryClaim(1L, 44L), "1 已被 hardCap 挤掉，重投视为新事件");
    }

    // 时间戳非单调（backward）也不能破坏容器：内部 clamp 到最新条目时间。
    @Test
    void clampsBackwardTimestamp() {
        TimeWindowDedupSet set = new TimeWindowDedupSet(Long.MAX_VALUE / 2, CAP);
        assertTrue(set.tryClaim(5L, 1_000L));
        assertTrue(set.tryClaim(6L, 500L), "时间回退仍能插入（clamp 到 1000）");
        assertFalse(set.tryClaim(5L, 400L), "5 仍在窗口内");
        assertEquals(2, set.size());
    }

    // raft 收敛：相同 (id, nowMs) 序列在不同实例上产生相同 stateHash。
    @Test
    void stateHashConvergesAcrossEquivalentSequences() {
        TimeWindowDedupSet a = new TimeWindowDedupSet(WINDOW, 4);
        a.tryClaim(10L, 100L); a.tryClaim(20L, 200L); a.tryClaim(30L, 300L);

        TimeWindowDedupSet b = new TimeWindowDedupSet(WINDOW, 4);
        b.tryClaim(10L, 100L); b.tryClaim(20L, 200L); b.tryClaim(30L, 300L);

        assertEquals(a.stateHash(), b.stateHash());
    }

    // 序列化 round-trip：读回后 size / stateHash / 去重行为（含 claimTime）等价。
    @Test
    void serializationRoundTripPreservesBehavior() {
        TimeWindowDedupSet original = new TimeWindowDedupSet(WINDOW, CAP);
        original.tryClaim(7L, 100L);
        original.tryClaim(14L, 150L);
        original.tryClaim(21L, 200L);

        Bytes<?> buf = Bytes.allocateElasticOnHeap(128);
        original.writeMarshallable(buf);

        TimeWindowDedupSet restored = new TimeWindowDedupSet(buf);
        assertEquals(original.size(), restored.size());
        assertEquals(original.stateHash(), restored.stateHash());

        // 窗口内已 claim 的 id 仍被拒，新 id 仍被接受（claimTime 随快照恢复）
        assertFalse(restored.tryClaim(7L, 210L));
        assertFalse(restored.tryClaim(14L, 220L));
        assertTrue(restored.tryClaim(99L, 230L));
    }

    // 触发 grow（size 超初始物理容量）后仍正确去重，且序列化等价。
    @Test
    void growsBeyondInitialCapacity() {
        TimeWindowDedupSet set = new TimeWindowDedupSet(Long.MAX_VALUE / 2, 1024);
        for (int i = 0; i < 100; i++) {
            assertTrue(set.tryClaim(i, 1000L + i), "首次 claim " + i);
        }
        assertEquals(100, set.size());
        for (int i = 0; i < 100; i++) {
            assertFalse(set.tryClaim(i, 2000L + i), "重投 " + i + " 应被拒");
        }
        Bytes<?> buf = Bytes.allocateElasticOnHeap(4096);
        set.writeMarshallable(buf);
        TimeWindowDedupSet restored = new TimeWindowDedupSet(buf);
        assertEquals(set.size(), restored.size());
        assertEquals(set.stateHash(), restored.stateHash());
    }

    @Test
    void rejectsInvalidConstructorArgs() {
        assertThrows(IllegalArgumentException.class, () -> new TimeWindowDedupSet(0, CAP));
        assertThrows(IllegalArgumentException.class, () -> new TimeWindowDedupSet(-1, CAP));
        assertThrows(IllegalArgumentException.class, () -> new TimeWindowDedupSet(WINDOW, 0));
        assertThrows(IllegalArgumentException.class, () -> new TimeWindowDedupSet(WINDOW, -1));
    }

    // 损坏 snapshot 防御：windowMs / hardCap / size 异常均直接抛 IllegalStateException。
    @Test
    void rejectsCorruptedSnapshot() {
        // windowMs = 0
        Bytes<?> b1 = Bytes.allocateElasticOnHeap(32);
        b1.writeLong(0L); b1.writeInt(CAP); b1.writeInt(0);
        assertThrows(IllegalStateException.class, () -> new TimeWindowDedupSet(b1));

        // hardCap = 0
        Bytes<?> b2 = Bytes.allocateElasticOnHeap(32);
        b2.writeLong(WINDOW); b2.writeInt(0); b2.writeInt(0);
        assertThrows(IllegalStateException.class, () -> new TimeWindowDedupSet(b2));

        // size 负值
        Bytes<?> b3 = Bytes.allocateElasticOnHeap(32);
        b3.writeLong(WINDOW); b3.writeInt(CAP); b3.writeInt(-3);
        assertThrows(IllegalStateException.class, () -> new TimeWindowDedupSet(b3));

        // size > hardCap
        Bytes<?> b4 = Bytes.allocateElasticOnHeap(32);
        b4.writeLong(WINDOW); b4.writeInt(CAP); b4.writeInt(CAP + 1);
        assertThrows(IllegalStateException.class, () -> new TimeWindowDedupSet(b4));
    }
}
