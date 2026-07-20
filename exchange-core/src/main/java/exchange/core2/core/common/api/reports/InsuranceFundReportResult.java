package exchange.core2.core.common.api.reports;

import exchange.core2.core.utils.SerializationUtils;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.ToString;
import net.openhft.chronicle.bytes.BytesIn;
import net.openhft.chronicle.bytes.BytesOut;
import net.openhft.chronicle.bytes.WriteBytesMarshallable;
import org.eclipse.collections.impl.map.mutable.primitive.IntLongHashMap;
import org.eclipse.collections.impl.map.mutable.primitive.IntObjectHashMap;

import java.util.stream.Stream;

/**
 * 两个保险基金池的 per-shard 明细，一次查询覆盖：
 * <ul>
 *   <li><b>期货 IF</b>（{@code futuresInsuranceFund}）：per-symbol，值含 available（现金）+ positionValue（接管仓位 MtM），
 *       口径对齐 {@link TotalCurrencyBalanceReportResult#getIfBalances()}；</li>
 *   <li><b>借贷 LIF</b>（{@code loanInsuranceFund}）：per-currency 单值，<b>可为负</b>——负值即平台已垫资额，非损失
 *       （loan.md §18.5）。</li>
 * </ul>
 *
 * <p>两池键空间不同（symbolId vs currencyId）、值形状不同，故各自成字段不合并。LIF 同一份数据也在
 * {@link LoanPlatformReportResult} 里：那边是 loan 域账本视图，这边是跨域"所有保险基金"视图。
 *
 * <p>{@code byShard} 在 process 阶段每 shard 装 1 个 entry，merge 后装全部 N 个。运营用途：定位哪个 shard 的哪个
 * symbol IF 将耗尽（{@code IF_DEPOSIT} 注资目标），以及哪个 currency 的 LIF 垫资过深（{@code LOAN_IF_DEPOSIT} 目标）。
 */
@Getter
@ToString
@EqualsAndHashCode
public final class InsuranceFundReportResult implements ReportResult {

    /** shardId → 该 shard 的两池明细。process 阶段 size=1，merge 后 size=N */
    private final IntObjectHashMap<PerShardData> byShard;

    private InsuranceFundReportResult(IntObjectHashMap<PerShardData> byShard) {
        this.byShard = byShard;
    }

    /** process 阶段构造：单 shard 视图。 */
    public static InsuranceFundReportResult ofShard(int shardId, IntObjectHashMap<FuturesIfEntry> futuresInsuranceFund,
        IntLongHashMap loanInsuranceFund, IntLongHashMap markPriceTs) {
        final IntObjectHashMap<PerShardData> map = new IntObjectHashMap<>();
        map.put(shardId, new PerShardData(futuresInsuranceFund, loanInsuranceFund, markPriceTs));
        return new InsuranceFundReportResult(map);
    }

    /** merge 阶段：把所有 shard 的 section 合到一个 map。 */
    public static InsuranceFundReportResult merge(Stream<BytesIn> sections) {
        final IntObjectHashMap<PerShardData> merged = new IntObjectHashMap<>();
        sections.forEach(bytes -> merged.put(bytes.readInt(), readPerShardData(bytes)));
        return new InsuranceFundReportResult(merged);
    }

    /** 反序列化：单 shard section 格式（跨 shard 传输）。 */
    public InsuranceFundReportResult(BytesIn bytes) {
        final int shardId = bytes.readInt();
        this.byShard = new IntObjectHashMap<>();
        this.byShard.put(shardId, readPerShardData(bytes));
    }

    /** 读顺序须与 {@link #writeMarshallable} 一致。 */
    private static PerShardData readPerShardData(BytesIn bytes) {
        final IntObjectHashMap<FuturesIfEntry> futuresIf = SerializationUtils.readIntHashMap(bytes, FuturesIfEntry::new);
        final IntLongHashMap loanIf = SerializationUtils.readIntLongHashMap(bytes);
        final IntLongHashMap markPriceTs = SerializationUtils.readIntLongHashMap(bytes);
        return new PerShardData(futuresIf, loanIf, markPriceTs);
    }

    /**
     * 序列化：只 marshal 单 shard section 格式——section 由 process() 输出后跨线程/节点传输，byShard 只应有 1 个 entry。
     * 汇总视图（byShard.size() = N）不参与 wire format，只在 client 侧最终对象里存在。
     */
    @Override
    public void writeMarshallable(BytesOut bytes) {
        if (byShard.size() != 1) {
            throw new IllegalStateException("section marshalling expects exactly 1 shard entry, got " + byShard.size());
        }
        byShard.forEachKeyValue((shardId, data) -> {
            bytes.writeInt(shardId);
            SerializationUtils.marshallIntHashMap(data.futuresInsuranceFund, bytes);
            SerializationUtils.marshallIntLongHashMap(data.loanInsuranceFund, bytes);
            SerializationUtils.marshallIntLongHashMap(data.markPriceTs, bytes);
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

        /** 期货 IF：symbolId → 现金 + 接管仓位 MtM。 */
        private final IntObjectHashMap<FuturesIfEntry> futuresInsuranceFund;

        /** 借贷 LIF：currency → 余额（currency scale，可为负）。 */
        private final IntLongHashMap loanInsuranceFund;

        /**
         * symbolId → markPrice 最后更新时间（{@code cmd.timestamp}）：期货是喂价时间，现货是最后成交时间。
         *
         * <p>价格陈旧的唯一可观测信号——引擎不据此改变行为，由外部监控比对当前时间判定并告警。
         * 从未有过价格的 symbol 不在此 map 中。
         */
        private final IntLongHashMap markPriceTs;

    }

    @Getter
    @ToString
    @EqualsAndHashCode
    @RequiredArgsConstructor
    public static final class FuturesIfEntry implements WriteBytesMarshallable {

        /** IF 现金余额（quote-currency scale）。 */
        private final long available;

        /** IF 接管仓位 mark-to-market 价值（quote-currency scale）。 */
        private final long positionValue;

        public FuturesIfEntry(BytesIn bytes) {
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
