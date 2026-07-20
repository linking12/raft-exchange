package exchange.core2.core.common.api.reports;

import exchange.core2.core.common.CoreCurrencySpecification;
import exchange.core2.core.utils.SerializationUtils;
import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;
import net.openhft.chronicle.bytes.BytesIn;
import net.openhft.chronicle.bytes.BytesOut;
import org.eclipse.collections.impl.map.mutable.primitive.IntLongHashMap;
import org.eclipse.collections.impl.map.mutable.primitive.IntObjectHashMap;

import java.util.stream.Stream;

/**
 * 跨所有 shard 聚合后的 fee 报表。
 * fees 与 {@link TotalCurrencyBalanceReportResult#getFees()} 同源同口径
 * （都来自 {@code RiskEngine.fees}），不参与对账方程。
 */
@AllArgsConstructor
@EqualsAndHashCode
@Getter
@ToString
public final class FeeReportResult implements ReportResult {

    /** currency -> 跨 shard 聚合后的 totalFee（currency scale） */
    private final IntLongHashMap fees;

    /** currency id -> spec（含 name）。仅展示用；所有 shard 副本相同。 */
    private final IntObjectHashMap<CoreCurrencySpecification> currencySpecs;

    public static FeeReportResult createEmpty() {
        return new FeeReportResult(null, null);
    }

    private FeeReportResult(final BytesIn bytesIn) {
        this.fees = SerializationUtils.readNullable(bytesIn, SerializationUtils::readIntLongHashMap);
        this.currencySpecs = SerializationUtils.readNullable(bytesIn,
                b -> SerializationUtils.readIntHashMap(b, CoreCurrencySpecification::new));
    }

    @Override
    public void writeMarshallable(final BytesOut bytes) {
        SerializationUtils.marshallNullable(fees, bytes, SerializationUtils::marshallIntLongHashMap);
        SerializationUtils.marshallNullable(currencySpecs, bytes, SerializationUtils::marshallIntHashMap);
    }

    public static FeeReportResult merge(final Stream<BytesIn> pieces) {
        return pieces
                .sequential() // 强制串行流，不用ForkJoinPool
                .map(FeeReportResult::new)
                .reduce(
                        FeeReportResult.createEmpty(),
                        (a, b) -> new FeeReportResult(
                                SerializationUtils.mergeSum(a.fees, b.fees),
                                // 所有 shard 的 currencySpecs 内容相同，任取其一即可
                                SerializationUtils.mergeOverride(a.currencySpecs, b.currencySpecs)));
    }
}
