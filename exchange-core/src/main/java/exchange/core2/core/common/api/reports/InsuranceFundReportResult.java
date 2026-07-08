package exchange.core2.core.common.api.reports;

import exchange.core2.core.utils.SerializationUtils;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.ToString;
import net.openhft.chronicle.bytes.BytesIn;
import net.openhft.chronicle.bytes.BytesOut;
import net.openhft.chronicle.bytes.WriteBytesMarshallable;
import org.eclipse.collections.impl.map.mutable.primitive.IntObjectHashMap;

import java.util.stream.Stream;

/**
 * Insurance Fund 池的 per-shard × per-symbol 明细。
 *
 * <p>
 * 数据结构：{@code byShard : IntObjectHashMap<PerShardData>}——shardId → 该 shard 的 per-symbol IF 明细。 process 阶段每个 shard 装 1 个
 * entry；createResult merge 后装全部 N 个 shard。
 *
 * <p>
 * 每个 {@link IFEntry} 含 available（IF 现金）+ positionValue（IF 接管仓位 mark-to-market）， 单位统一在 quote-currency scale，对齐
 * {@link TotalCurrencyBalanceReportResult#ifBalances} 口径。
 *
 * <p>
 * 运营 dashboard 用途：识别哪个 shard 的哪个 symbol IF 快耗尽，决定 IF_DEPOSIT 定向注资目标。
 */
@Getter
@ToString
@EqualsAndHashCode
public final class InsuranceFundReportResult implements ReportResult {

    /** shardId → 该 shard 的 per-symbol IF 明细。process 阶段 size=1，merge 后 size=N */
    private final IntObjectHashMap<PerShardData> byShard;

    private InsuranceFundReportResult(IntObjectHashMap<PerShardData> byShard) {
        this.byShard = byShard;
    }

    /** process 阶段构造：单 shard 视图 */
    public static InsuranceFundReportResult ofShard(int shardId, IntObjectHashMap<IFEntry> perSymbolIF) {
        IntObjectHashMap<PerShardData> map = new IntObjectHashMap<>();
        map.put(shardId, new PerShardData(perSymbolIF));
        return new InsuranceFundReportResult(map);
    }

    /** merge 阶段：把所有 shard 的 section 合到一个 map */
    public static InsuranceFundReportResult merge(Stream<BytesIn> sections) {
        final IntObjectHashMap<PerShardData> merged = new IntObjectHashMap<>();
        sections.forEach(bytes -> {
            final int shardId = bytes.readInt();
            final IntObjectHashMap<IFEntry> perSymbolIF = SerializationUtils.readIntHashMap(bytes, IFEntry::new);
            merged.put(shardId, new PerShardData(perSymbolIF));
        });
        return new InsuranceFundReportResult(merged);
    }

    /** 反序列化：单 shard section 格式（跨 shard 传输） */
    public InsuranceFundReportResult(BytesIn bytes) {
        final int shardId = bytes.readInt();
        final IntObjectHashMap<IFEntry> perSymbolIF = SerializationUtils.readIntHashMap(bytes, IFEntry::new);
        this.byShard = new IntObjectHashMap<>();
        this.byShard.put(shardId, new PerShardData(perSymbolIF));
    }

    /**
     * 序列化：只 marshal 单 shard section 格式。
     * <p>
     * section 由 process() 输出后跨线程/节点传输，byShard 只应有 1 个 entry。
     * <p>
     * 汇总视图（byShard.size() = N）不参与 wire format——它只在 client 侧最终对象里存在。
     */
    @Override
    public void writeMarshallable(BytesOut bytes) {
        if (byShard.size() != 1) {
            throw new IllegalStateException("section marshalling expects exactly 1 shard entry, got " + byShard.size());
        }
        byShard.forEachKeyValue((shardId, data) -> {
            bytes.writeInt(shardId);
            SerializationUtils.marshallIntHashMap(data.perSymbolIF, bytes);
        });
    }

    // ==================================================================
    // Nested
    // ==================================================================

    @Getter
    @ToString
    @EqualsAndHashCode
    @RequiredArgsConstructor
    public static final class PerShardData {
        /** symbol → { available, positionValue } */
        private final IntObjectHashMap<IFEntry> perSymbolIF;
    }

    @Getter
    @ToString
    @EqualsAndHashCode
    @RequiredArgsConstructor
    public static final class IFEntry implements WriteBytesMarshallable {

        /** IF 现金余额（quote-currency scale） */
        private final long available;

        /** IF 接管仓位 mark-to-market 价值（quote-currency scale） */
        private final long positionValue;

        public IFEntry(BytesIn bytes) {
            this.available = bytes.readLong();
            this.positionValue = bytes.readLong();
        }

        @Override
        public void writeMarshallable(BytesOut bytes) {
            bytes.writeLong(available);
            bytes.writeLong(positionValue);
        }

        public long total() {
            return available + positionValue;
        }
    }
}
