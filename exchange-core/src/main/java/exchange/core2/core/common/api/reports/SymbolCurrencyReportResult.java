package exchange.core2.core.common.api.reports;

import exchange.core2.core.common.CoreCurrencySpecification;
import exchange.core2.core.common.CoreSymbolSpecification;
import exchange.core2.core.utils.SerializationUtils;
import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;
import net.openhft.chronicle.bytes.BytesIn;
import net.openhft.chronicle.bytes.BytesOut;
import org.eclipse.collections.impl.map.mutable.primitive.IntObjectHashMap;

import java.util.stream.Stream;

@AllArgsConstructor
@EqualsAndHashCode
@Getter
@ToString
public class SymbolCurrencyReportResult implements ReportResult {

    private final IntObjectHashMap<CoreSymbolSpecification> symbolSpecs;
    private final IntObjectHashMap<CoreCurrencySpecification> currencySpecs;

    public static SymbolCurrencyReportResult createEmpty() {
        return new SymbolCurrencyReportResult(null, null);
    }

    private SymbolCurrencyReportResult(final BytesIn bytesIn) {
        this.symbolSpecs = SerializationUtils.readNullable(bytesIn, bytes -> SerializationUtils.readIntHashMap(bytes, CoreSymbolSpecification::new));
        this.currencySpecs = SerializationUtils.readNullable(bytesIn, bytes -> SerializationUtils.readIntHashMap(bytes, CoreCurrencySpecification::new));
    }

    @Override
    public void writeMarshallable(BytesOut bytes) {
        SerializationUtils.marshallNullable(symbolSpecs, bytes, SerializationUtils::marshallIntHashMap);
        SerializationUtils.marshallNullable(currencySpecs, bytes, SerializationUtils::marshallIntHashMap);
    }

    public static SymbolCurrencyReportResult merge(final Stream<BytesIn> pieces) {
        return pieces
                .sequential() // 强制串行流，不用ForkJoinPool
                .map(SymbolCurrencyReportResult::new)
                .reduce(
                        SymbolCurrencyReportResult.createEmpty(),
                        (a, b) -> new SymbolCurrencyReportResult(
                                SerializationUtils.mergeOverride(a.symbolSpecs, b.symbolSpecs),
                                SerializationUtils.mergeOverride(a.currencySpecs, b.currencySpecs)));
    }

}
