package exchange.core2.core.common.api.reports;

import exchange.core2.core.common.CoreCurrencySpecification;
import exchange.core2.core.processors.MatchingEngineRouter;
import exchange.core2.core.processors.RiskEngine;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.ToString;
import net.openhft.chronicle.bytes.BytesIn;
import net.openhft.chronicle.bytes.BytesOut;
import org.eclipse.collections.impl.map.mutable.primitive.IntLongHashMap;
import org.eclipse.collections.impl.map.mutable.primitive.IntObjectHashMap;

import java.util.Optional;
import java.util.stream.Stream;

@NoArgsConstructor
@EqualsAndHashCode
@ToString
public final class FeeReportQuery implements ReportQuery<FeeReportResult> {

    public FeeReportQuery(BytesIn bytesIn) {
        // do nothing
    }

    @Override
    public int getReportTypeCode() {
        return ReportType.FEE_REPORT.getCode();
    }

    @Override
    public FeeReportResult createResult(Stream<BytesIn> sections) {
        return FeeReportResult.merge(sections);
    }

    @Override
    public Optional<FeeReportResult> process(MatchingEngineRouter matchingEngine) {
        return Optional.empty();
    }

    @Override
    public Optional<FeeReportResult> process(RiskEngine riskEngine) {
        // copy 是必须的：原 fees 在 RiskEngine 线程持续 addToValue，
        // 直接共享会让序列化看到中途态。
        final IntLongHashMap fees = new IntLongHashMap(riskEngine.getFees());
        final IntObjectHashMap<CoreCurrencySpecification> currencySpecs = new IntObjectHashMap<>();
        riskEngine.getCurrencySpecificationProvider().getCurrencySpecs().forEachKeyValue(currencySpecs::put);
        return Optional.of(new FeeReportResult(fees, currencySpecs));
    }

    @Override
    public void writeMarshallable(BytesOut bytes) {
        // do nothing
    }
}
