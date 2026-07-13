package exchange.core2.core.common.api.reports;

import exchange.core2.core.utils.SerializationUtils;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.ToString;
import net.openhft.chronicle.bytes.BytesIn;
import net.openhft.chronicle.bytes.BytesOut;
import org.eclipse.collections.impl.map.mutable.primitive.IntLongHashMap;

import java.util.stream.Stream;

/**
 * 现货借贷平台侧账本报表（loan.md §9）——per-shard × per-currency 明细，供运营 / 风控台查询（平台侧数据「拉」不「推」，不发事件）。
 *
 * <p>每个 shard 装 1 个 {@link PerShardData}（5 个 per-currency 桶）；{@link #merge} 后 byShard 装全部 N 个 shard。
 * 5 桶口径完全对齐 {@link LoanService} 的 raft snapshot 字段：
 * <ul>
 *   <li>{@code interestRevenue} —— 累计利息收入（loanCurrency scale）</li>
 *   <li>{@code loanLiqFees} —— 累计强平专项费</li>
 *   <li>{@code badDebt} —— 累计坏账（underwater 核销）</li>
 *   <li>{@code poolAvailable} —— 借贷池可借余额</li>
 *   <li>{@code poolBorrowed} —— 借贷池已借出（= Σ outstandingPrincipal）</li>
 * </ul>
 * 池子利用率下游按 {@code poolBorrowed / (poolAvailable + poolBorrowed)} 自算。
 */
@Getter
@ToString
@EqualsAndHashCode
public final class LoanPlatformReportResult implements ReportResult {

    /** shardId → 该 shard 的 loan 平台桶。process 阶段 size=1，merge 后 size=N */
    private final org.eclipse.collections.impl.map.mutable.primitive.IntObjectHashMap<PerShardData> byShard;

    private LoanPlatformReportResult(
        org.eclipse.collections.impl.map.mutable.primitive.IntObjectHashMap<PerShardData> byShard) {
        this.byShard = byShard;
    }

    /** process 阶段构造：单 shard 视图 */
    public static LoanPlatformReportResult ofShard(int shardId, PerShardData data) {
        final org.eclipse.collections.impl.map.mutable.primitive.IntObjectHashMap<PerShardData> map =
            new org.eclipse.collections.impl.map.mutable.primitive.IntObjectHashMap<>();
        map.put(shardId, data);
        return new LoanPlatformReportResult(map);
    }

    /** merge 阶段：把所有 shard 的 section 合到一个 map */
    public static LoanPlatformReportResult merge(Stream<BytesIn> sections) {
        final org.eclipse.collections.impl.map.mutable.primitive.IntObjectHashMap<PerShardData> merged =
            new org.eclipse.collections.impl.map.mutable.primitive.IntObjectHashMap<>();
        sections.forEach(bytes -> {
            final int shardId = bytes.readInt();
            merged.put(shardId, new PerShardData(bytes));
        });
        return new LoanPlatformReportResult(merged);
    }

    /** 反序列化：单 shard section 格式（跨 shard 传输） */
    public LoanPlatformReportResult(BytesIn bytes) {
        final int shardId = bytes.readInt();
        this.byShard = new org.eclipse.collections.impl.map.mutable.primitive.IntObjectHashMap<>();
        this.byShard.put(shardId, new PerShardData(bytes));
    }

    /**
     * 序列化：只 marshal 单 shard section 格式（process() 输出后跨线程/节点传输，byShard 只应有 1 个 entry）。
     * 汇总视图（byShard.size()=N）只在 client 侧最终对象里存在，不参与 wire format。
     */
    @Override
    public void writeMarshallable(BytesOut bytes) {
        if (byShard.size() != 1) {
            throw new IllegalStateException("section marshalling expects exactly 1 shard entry, got " + byShard.size());
        }
        byShard.forEachKeyValue((shardId, data) -> {
            bytes.writeInt(shardId);
            data.writeMarshallable(bytes);
        });
    }

    // ==================================================================
    // Nested
    // ==================================================================

    @Getter
    @ToString
    @EqualsAndHashCode
    @RequiredArgsConstructor
    public static final class PerShardData implements net.openhft.chronicle.bytes.WriteBytesMarshallable {
        private final IntLongHashMap interestRevenue;
        private final IntLongHashMap loanLiqFees;
        private final IntLongHashMap badDebt;
        private final IntLongHashMap poolAvailable;
        private final IntLongHashMap poolBorrowed;

        public PerShardData(BytesIn bytes) {
            this.interestRevenue = SerializationUtils.readIntLongHashMap(bytes);
            this.loanLiqFees = SerializationUtils.readIntLongHashMap(bytes);
            this.badDebt = SerializationUtils.readIntLongHashMap(bytes);
            this.poolAvailable = SerializationUtils.readIntLongHashMap(bytes);
            this.poolBorrowed = SerializationUtils.readIntLongHashMap(bytes);
        }

        @Override
        public void writeMarshallable(BytesOut bytes) {
            SerializationUtils.marshallIntLongHashMap(interestRevenue, bytes);
            SerializationUtils.marshallIntLongHashMap(loanLiqFees, bytes);
            SerializationUtils.marshallIntLongHashMap(badDebt, bytes);
            SerializationUtils.marshallIntLongHashMap(poolAvailable, bytes);
            SerializationUtils.marshallIntLongHashMap(poolBorrowed, bytes);
        }
    }
}
