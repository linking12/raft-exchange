package exchange.core2.core.common;

import net.openhft.chronicle.bytes.BytesIn;
import net.openhft.chronicle.bytes.BytesOut;
import net.openhft.chronicle.bytes.WriteBytesMarshallable;
import org.eclipse.collections.impl.set.mutable.primitive.LongHashSet;

import java.util.Objects;

/**
 * 时间窗去重容器：外部触发命令（BALANCE_ADJUSTMENT / MARGIN_ADJUSTMENT / 借贷全家桶 / POOL_* / INTERNAL_TRANSFER 等）的幂等门。
 *
 * <p>语义：{@link #tryClaim(long, long)} 传入 id 与命令时间 {@code nowMs}，首次见到（且未过期）返回 true 并记录；
 * 已在窗口内记录过返回 false（命令应拒）。保留最近 {@code windowMs}（默认 3 天）内的 id；超窗的老 id 被淘汰，
 * 之后同 id 视为新事件——外部 at-least-once 重投只要落在窗口内即可幂等。另设 {@code hardCap} 硬上限兜底，
 * 防单用户高频下内存/快照无界膨胀（触顶时按最老淘汰，应打 metric 告警）。
 *
 * <p><b>确定性（raft 收敛红线）</b>：淘汰只由 {@code nowMs} 驱动，而 {@code nowMs} 必须是随 raft log 复制的
 * <b>确定性命令时间</b>（leader 盖章、各节点同值），<b>严禁</b>用各节点本地 {@code System.currentTimeMillis()}。
 * 容器内部把插入时间 clamp 成 {@code max(nowMs, 最新条目时间)}，使每个容器内时间单调不减——因此即便命令时间在
 * leader 切换处不严格单调，容器仍自洽（head 恒为最老），无需全局引擎时钟。
 *
 * <p>物理布局（可增长 ring、head 位置、容量）不进 hash / 不序列化；{@link #stateHash()} 与
 * {@link #writeMarshallable} 均按 FIFO 逻辑序遍历 (id, time) 对，使 snapshot 恢复后布局不同但逻辑等价的状态
 * 在所有节点产生相同 hash。
 *
 * <p>序列化：windowMs + hardCap + size + size 个 (id, time)（FIFO 顺序）。
 */
public final class TimeWindowDedupSet implements WriteBytesMarshallable, StateHash {

    /** 默认保留窗口：3 天。 */
    public static final long DEFAULT_WINDOW_MS = 3L * 24 * 3600 * 1000;
    /** 默认硬上限：单用户最多保留的 id 条数（安全阀，正常 3 天量远小于此）。 */
    public static final int DEFAULT_HARD_CAP = 1 << 16;

    private static final int INITIAL_CAPACITY = 16;
    /** snapshot 反序列化 size 上限——挡损坏数据导致的超大分配。 */
    private static final int MAX_SNAPSHOT_SIZE = 1 << 20;

    private final long windowMs;
    private final int hardCap;

    private long[] ids;
    private long[] times;
    private final LongHashSet idSet;
    private int head;
    private int size;

    public TimeWindowDedupSet() {
        this(DEFAULT_WINDOW_MS, DEFAULT_HARD_CAP);
    }

    public TimeWindowDedupSet(long windowMs, int hardCap) {
        if (windowMs <= 0) throw new IllegalArgumentException("windowMs must be positive");
        if (hardCap <= 0) throw new IllegalArgumentException("hardCap must be positive");
        this.windowMs = windowMs;
        this.hardCap = hardCap;
        final int cap = Math.min(INITIAL_CAPACITY, hardCap);
        this.ids = new long[cap];
        this.times = new long[cap];
        this.idSet = new LongHashSet(cap);
    }

    public TimeWindowDedupSet(BytesIn bytesIn) {
        this.windowMs = bytesIn.readLong();
        this.hardCap = bytesIn.readInt();
        if (windowMs <= 0) throw new IllegalStateException("invalid windowMs in snapshot: " + windowMs);
        if (hardCap <= 0 || hardCap > MAX_SNAPSHOT_SIZE) {
            throw new IllegalStateException("invalid hardCap in snapshot: " + hardCap);
        }
        final int storedSize = bytesIn.readInt();
        if (storedSize < 0 || storedSize > hardCap || storedSize > MAX_SNAPSHOT_SIZE) {
            throw new IllegalStateException("invalid size in snapshot: " + storedSize + " (hardCap=" + hardCap + ")");
        }
        int cap = INITIAL_CAPACITY;
        while (cap < storedSize) cap <<= 1;
        cap = Math.min(cap, hardCap);
        if (cap < storedSize) cap = storedSize; // hardCap 恰等于 size 的边界
        this.ids = new long[Math.max(cap, 1)];
        this.times = new long[Math.max(cap, 1)];
        this.idSet = new LongHashSet(Math.max(cap, 1));
        for (int i = 0; i < storedSize; i++) {
            final long id = bytesIn.readLong();
            final long t = bytesIn.readLong();
            ids[i] = id;
            times[i] = t;
            idSet.add(id);
        }
        this.head = 0;
        this.size = storedSize;
    }

    /**
     * 原子 check-and-claim：以命令时间 {@code nowMs} 清除超窗老条目后，首次见到 id 返回 true 并入表；已存在返回 false。
     *
     * <p>{@code nowMs} 必须是确定性命令时间（leader 盖章、随 raft 复制）；调用方应在所有业务校验通过后再调用（提交语义），
     * 校验失败路径不得调用，否则 id 误入表会让后续合法重试被误判为重投。
     */
    public boolean tryClaim(final long id, final long nowMs) {
        final int cap = ids.length;
        // clamp 到最新条目时间，保证容器内时间单调不减
        final long eff = (size == 0) ? nowMs : Math.max(nowMs, times[(head + size - 1) % cap]);
        final long cutoff = eff - windowMs;
        // 1) 清除超窗老条目（head 恒最老）
        while (size > 0 && times[head] < cutoff) {
            idSet.remove(ids[head]);
            head = (head + 1) % ids.length;
            size--;
        }
        // 2) 窗口内已见过 → 拒
        if (idSet.contains(id)) {
            return false;
        }
        // 3) 硬上限兜底：触顶按最老淘汰（应由上层对该事件打 metric）
        while (size >= hardCap) {
            idSet.remove(ids[head]);
            head = (head + 1) % ids.length;
            size--;
        }
        // 4) 需要则增长（上限 hardCap）
        if (size == ids.length && ids.length < hardCap) {
            grow();
        }
        final int c = ids.length;
        final int tail = (head + size) % c;
        ids[tail] = id;
        times[tail] = eff;
        idSet.add(id);
        size++;
        return true;
    }

    private void grow() {
        final int oldCap = ids.length;
        int newCap = Math.min(oldCap << 1, hardCap);
        if (newCap <= oldCap) return;
        final long[] newIds = new long[newCap];
        final long[] newTimes = new long[newCap];
        for (int i = 0; i < size; i++) {
            final int j = (head + i) % oldCap;
            newIds[i] = ids[j];
            newTimes[i] = times[j];
        }
        this.ids = newIds;
        this.times = newTimes;
        this.head = 0;
    }

    public int size() {
        return size;
    }

    @Override
    public void writeMarshallable(BytesOut bytes) {
        bytes.writeLong(windowMs);
        bytes.writeInt(hardCap);
        bytes.writeInt(size);
        final int cap = ids.length;
        for (int i = 0; i < size; i++) {
            final int j = (head + i) % cap;
            bytes.writeLong(ids[j]);
            bytes.writeLong(times[j]);
        }
    }

    /** 按 FIFO 逻辑序哈希 (id, time)，物理布局差异不影响 hash。 */
    @Override
    public int stateHash() {
        int h = Objects.hash(windowMs, hardCap, size);
        final int cap = ids.length;
        for (int i = 0; i < size; i++) {
            final int j = (head + i) % cap;
            h = h * 31 + Long.hashCode(ids[j]);
            h = h * 31 + Long.hashCode(times[j]);
        }
        return h;
    }
}
