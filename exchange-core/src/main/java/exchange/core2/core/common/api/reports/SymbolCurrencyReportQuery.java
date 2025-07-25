package exchange.core2.core.common.api.reports;

import exchange.core2.core.common.CoreCurrencySpecification;
import exchange.core2.core.common.CoreSymbolSpecification;
import exchange.core2.core.processors.MatchingEngineRouter;
import exchange.core2.core.processors.RiskEngine;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.ToString;
import net.openhft.chronicle.bytes.BytesIn;
import net.openhft.chronicle.bytes.BytesOut;
import org.eclipse.collections.impl.map.mutable.primitive.IntObjectHashMap;

import java.util.Optional;
import java.util.stream.Stream;

@NoArgsConstructor
@EqualsAndHashCode
@ToString
public class SymbolCurrencyReportQuery implements ReportQuery<SymbolCurrencyReportResult> {

    public SymbolCurrencyReportQuery(BytesIn bytesIn) {
        // do nothing
    }

    @Override
    public int getReportTypeCode() {
        return ReportType.SYMBOL_CURRENCY_REPORT.getCode();
    }

    @Override
    public SymbolCurrencyReportResult createResult(Stream<BytesIn> sections) {
        return SymbolCurrencyReportResult.merge(sections);
    }

    @Override
    public Optional<SymbolCurrencyReportResult> process(MatchingEngineRouter matchingEngine) {
        return Optional.empty();
    }

    @Override
    public Optional<SymbolCurrencyReportResult> process(RiskEngine riskEngine) {
        final IntObjectHashMap<CoreSymbolSpecification> symbolSpecs = new IntObjectHashMap<>();
        final IntObjectHashMap<CoreCurrencySpecification> currencySpecs = new IntObjectHashMap<>();
        riskEngine.getSymbolSpecificationProvider().getSymbolSpecs().forEachKeyValue(symbolSpecs::put);
        riskEngine.getCurrencySpecificationProvider().getCurrencySpecs().forEachKeyValue(currencySpecs::put);
        return Optional.of(new SymbolCurrencyReportResult(symbolSpecs, currencySpecs));
    }

    @Override
    public void writeMarshallable(BytesOut bytes) {
        // do nothing
    }
}
