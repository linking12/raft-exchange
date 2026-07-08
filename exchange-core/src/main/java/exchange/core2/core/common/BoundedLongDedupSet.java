package exchange.core2.core.common;

import net.openhft.chronicle.bytes.BytesIn;
import net.openhft.chronicle.bytes.BytesOut;
import net.openhft.chronicle.bytes.WriteBytesMarshallable;
import org.eclipse.collections.impl.set.mutable.primitive.LongHashSet;

import java.util.Objects;

/**
 * 限长 FIFO 去重容器：外部触发命令（BALANCE_ADJUSTMENT、MARGIN_ADJUSTMENT 等）的幂等门。
 *
 * <p>语义：{@link #tryClaim(long)} 首次见到 id 返回 true 并记录；已存在返回 false（命令应拒）。
 * 满 capacity 时按插入顺序淘汰最老条目——只能拦截最近 capacity 条历史内的重投。窗口之外的
 * "老重投"会被当成新事件再次落账，调用方需保证外部 at-least-once 重投发生在窗口内。
 *
 * <p>raft 收敛：tryClaim 是命令序列的确定性函数，所有节点 (head, size, ring) 物理状态一致。
 * 即使有节点从 snapshot 恢复——反序列化把 head 归零、ring 紧凑回填——物理布局会异于原节点，
 * 但 {@link #stateHash()} 按 FIFO 顺序遍历，逻辑等价的状态产生相同 hash。
 *
 * <p>序列化：capacity + size + size 个 long（FIFO 顺序）。
 */
public final class BoundedLongDedupSet implements WriteBytesMarshallable, StateHash {

    public static final int DEFAULT_CAPACITY = 4096;

    private final int capacity;
    private final long[] ring;
    private final LongHashSet ids;
    private int head;
    private int size;

    public BoundedLongDedupSet() {
        this(DEFAULT_CAPACITY);
    }

    public BoundedLongDedupSet(int capacity) {
        if (capacity <= 0) throw new IllegalArgumentException("capacity must be positive");
        this.capacity = capacity;
        this.ring = new long[capacity];
        this.ids = new LongHashSet(capacity);
    }

    public BoundedLongDedupSet(BytesIn bytesIn) {
        this.capacity = bytesIn.readInt();
        if (capacity <= 0 || capacity > MAX_SNAPSHOT_CAPACITY) {
            throw new IllegalStateException("invalid capacity in snapshot: " + capacity);
        }
        this.ring = new long[capacity];
        this.ids = new LongHashSet(capacity);
        final int storedSize = bytesIn.readInt();
        if (storedSize < 0 || storedSize > capacity) {
            throw new IllegalStateException(
                    "invalid size in snapshot: " + storedSize + " (capacity=" + capacity + ")");
        }
        for (int i = 0; i < storedSize; i++) {
            long id = bytesIn.readLong();
            ring[i] = id;
            ids.add(id);
        }
        this.head = 0;
        this.size = storedSize;
    }

    /** snapshot 反序列化容量上限——挡住损坏数据导致的超大数组分配。1M 已远超合理 per-user 重投窗口。 */
    private static final int MAX_SNAPSHOT_CAPACITY = 1 << 20;

    /**
     * 原子 check-and-claim：首次见到 id 返回 true 并入表；已存在返回 false。
     *
     * <p>调用方应在所有业务校验通过后再调用，把 claim 当作"提交"语义。
     * 失败校验路径上不能调用此方法，否则 id 误入表会让后续合法重试被误判为重投。
     */
    public boolean tryClaim(long id) {
        if (ids.contains(id)) return false;
        if (size == capacity) {
            ids.remove(ring[head]);
            head = (head + 1) % capacity;
            size--;
        }
        int tail = (head + size) % capacity;
        ring[tail] = id;
        ids.add(id);
        size++;
        return true;
    }

    public int size() {
        return size;
    }

    @Override
    public void writeMarshallable(BytesOut bytes) {
        bytes.writeInt(capacity);
        bytes.writeInt(size);
        for (int i = 0; i < size; i++) {
            bytes.writeLong(ring[(head + i) % capacity]);
        }
    }

    /**
     * 按 FIFO 序而非物理 ring 序哈希——使物理布局差异（snapshot 恢复后 head=0 vs 原地 head=X）
     * 不影响 hash，逻辑等价状态在所有节点上产生相同 stateHash。
     */
    @Override
    public int stateHash() {
        int h = Objects.hash(capacity, size);
        for (int i = 0; i < size; i++) {
            h = h * 31 + Long.hashCode(ring[(head + i) % capacity]);
        }
        return h;
    }
}
