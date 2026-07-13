package exchange.core2.core.common.api.reports;

import exchange.core2.core.processors.MatchingEngineRouter;
import exchange.core2.core.processors.RiskEngine;
import exchange.core2.core.processors.loan.LoanService;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.ToString;
import net.openhft.chronicle.bytes.BytesIn;
import net.openhft.chronicle.bytes.BytesOut;
import org.eclipse.collections.impl.map.mutable.primitive.IntLongHashMap;

import java.util.Optional;
import java.util.stream.Stream;

/**
 * 一次查询拿到全部 shard 的 loan 平台侧账本明细（interestRevenue / loanLiqFees / badDebt / poolAvailable / poolBorrowed）。
 *
 * <p>无参数——每个 shard 独立跑 {@link #process(RiskEngine)} 生成自己的 section；{@link #createResult} merge 所有 sections。
 * 平台侧数据（利息收入 / 坏账 / 强平费 / 池子）不走事件，改由本报表「拉取」，运营 / 风控台随时可查最新全局总账。
 */
@NoArgsConstructor
@EqualsAndHashCode
@ToString
public final class LoanPlatformReportQuery implements ReportQuery<LoanPlatformReportResult> {

    public LoanPlatformReportQuery(BytesIn bytesIn) {
        // 无参数
    }

    @Override
    public int getReportTypeCode() {
        return ReportType.LOAN_PLATFORM.getCode();
    }

    @Override
    public LoanPlatformReportResult createResult(Stream<BytesIn> sections) {
        return LoanPlatformReportResult.merge(sections);
    }

    @Override
    public Optional<LoanPlatformReportResult> process(MatchingEngineRouter matchingEngine) {
        // ME 不涉及 loan 平台账本
        return Optional.empty();
    }

    @Override
    public Optional<LoanPlatformReportResult> process(RiskEngine riskEngine) {
        final LoanService loanService = riskEngine.getLoanService();
        // copy 出快照（避免持有 live map 引用）
        final LoanPlatformReportResult.PerShardData data = new LoanPlatformReportResult.PerShardData(
            new IntLongHashMap(loanService.getInterestRevenue()),
            new IntLongHashMap(loanService.getLoanLiqFees()),
            new IntLongHashMap(loanService.getBadDebt()),
            new IntLongHashMap(loanService.getLoanPoolAvailable()),
            new IntLongHashMap(loanService.getLoanPoolBorrowed()));
        return Optional.of(LoanPlatformReportResult.ofShard(riskEngine.getShardId(), data));
    }

    @Override
    public void writeMarshallable(BytesOut bytes) {
        // 无参数
    }
}
