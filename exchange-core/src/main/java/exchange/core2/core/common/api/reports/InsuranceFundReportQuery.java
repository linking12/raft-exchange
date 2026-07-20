package exchange.core2.core.common.api.reports;

import exchange.core2.core.common.CoreCurrencySpecification;
import exchange.core2.core.common.CoreSymbolSpecification;
import exchange.core2.core.processors.CurrencySpecificationProvider;
import exchange.core2.core.processors.MatchingEngineRouter;
import exchange.core2.core.processors.RiskEngine;
import exchange.core2.core.processors.LastPriceCacheRecord;
import exchange.core2.core.processors.SymbolSpecificationProvider;
import exchange.core2.core.processors.liquidation.LiquidationService;
import exchange.core2.core.utils.CoreArithmeticUtils;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.ToString;
import net.openhft.chronicle.bytes.BytesIn;
import net.openhft.chronicle.bytes.BytesOut;
import org.eclipse.collections.impl.map.mutable.primitive.IntLongHashMap;
import org.eclipse.collections.impl.map.mutable.primitive.IntObjectHashMap;

import java.util.Optional;
import java.util.stream.Stream;

/**
 * 一次查询拿到全部 shard 的保险基金明细：期货 IF（per-symbol）+ 借贷 LIF（per-currency）。
 *
 * <p>
 * 无参数——每个 shard 独立跑 {@link #process(RiskEngine)} 生成自己的 section； {@link #createResult} 合并所有 sections 到
 * {@link InsuranceFundReportResult#getByShard()}。
 *
 * <p>
 * 两池口径均对齐 {@link TotalCurrencyBalanceReportQuery}：期货 IF 折算到 quote-currency scale，LIF 直接取
 * {@code LoanService.loanInsuranceFund}（currency scale，可为负）。
 */
@NoArgsConstructor
@EqualsAndHashCode
@ToString
public final class InsuranceFundReportQuery implements ReportQuery<InsuranceFundReportResult> {

    public InsuranceFundReportQuery(BytesIn bytesIn) {
        // 无参数
    }

    @Override
    public int getReportTypeCode() {
        return ReportType.INSURANCE_FUND.getCode();
    }

    @Override
    public InsuranceFundReportResult createResult(Stream<BytesIn> sections) {
        return InsuranceFundReportResult.merge(sections);
    }

    @Override
    public Optional<InsuranceFundReportResult> process(MatchingEngineRouter matchingEngine) {
        // ME 不涉及 IF 数据
        return Optional.empty();
    }

    @Override
    public Optional<InsuranceFundReportResult> process(RiskEngine riskEngine) {
        return Optional.of(InsuranceFundReportResult.ofShard(riskEngine.getShardId(),
            collectFuturesInsuranceFund(riskEngine), collectLoanInsuranceFund(riskEngine),
            collectMarkPriceTs(riskEngine)));
    }

    /** symbolId → markPrice 最后更新时间；从未有过价格的 symbol 不收录（无价与陈旧是两回事，交给外部区分）。 */
    private static IntLongHashMap collectMarkPriceTs(RiskEngine riskEngine) {
        final IntLongHashMap result = new IntLongHashMap();
        riskEngine.getLastPriceCache().forEachKeyValue((symbolId, record) -> {
            if (record.markPriceTs > 0) {
                result.put(symbolId, record.markPriceTs);
            }
        });
        return result;
    }

    /**
     * 期货 IF：available（IFNotional 现金）+ positionValue（接管仓位 MtM），同 symbol 两者合并。
     * positionValue 公式与 {@link TotalCurrencyBalanceReportQuery} 一致：
     * {@code openPriceSum + direction × (openVolume × markPrice − openPriceSum)}。
     */
    private static IntObjectHashMap<InsuranceFundReportResult.FuturesIfEntry> collectFuturesInsuranceFund(
        RiskEngine riskEngine) {
        final SymbolSpecificationProvider specProvider = riskEngine.getSymbolSpecificationProvider();
        final CurrencySpecificationProvider currencySpecProvider = riskEngine.getCurrencySpecificationProvider();
        final LiquidationService liquidationService = riskEngine.getLiquidationService();

        final IntObjectHashMap<LastPriceCacheRecord> dummyLastPriceCache = new IntObjectHashMap<>();
        riskEngine.getLastPriceCache().forEachKeyValue((s, r) -> dummyLastPriceCache.put(s, r.copy()));

        final IntObjectHashMap<InsuranceFundReportResult.FuturesIfEntry> perSymbol = new IntObjectHashMap<>();

        liquidationService.getNotionals().forEachKeyValue((symbol, notional) -> {
            final CoreSymbolSpecification spec = specProvider.getSymbolSpecification(symbol);
            if (spec == null) {
                return;
            }
            final CoreCurrencySpecification currencySpec =
                currencySpecProvider.getCurrencySpecification(spec.quoteCurrency);
            perSymbol.put(symbol, new InsuranceFundReportResult.FuturesIfEntry(
                CoreArithmeticUtils.sizePriceToCurrencyScale(notional.available, spec, currencySpec), 0L));
        });

        liquidationService.getPositions().forEachValue(position -> {
            final CoreSymbolSpecification spec = specProvider.getSymbolSpecification(position.symbol);
            if (spec == null) {
                return;
            }
            final CoreCurrencySpecification currencySpec =
                currencySpecProvider.getCurrencySpecification(spec.quoteCurrency);
            final LastPriceCacheRecord priceRecord =
                dummyLastPriceCache.getIfAbsentPut(position.symbol, LastPriceCacheRecord.dummy);
            final long unrealized = Math.multiplyExact((long)position.direction.getMultiplier(), Math
                .subtractExact(Math.multiplyExact(position.openVolume, priceRecord.markPrice), position.openPriceSum));
            final long positionValueInCurrency = CoreArithmeticUtils
                .sizePriceToCurrencyScale(position.openPriceSum + unrealized, spec, currencySpec);

            // 同 symbol 可能既在 notionals 又在 positions，合并两侧
            final InsuranceFundReportResult.FuturesIfEntry existing = perSymbol.get(position.symbol);
            final long available = existing != null ? existing.getAvailable() : 0L;
            final long prevPositionValue = existing != null ? existing.getPositionValue() : 0L;
            perSymbol.put(position.symbol, new InsuranceFundReportResult.FuturesIfEntry(available,
                prevPositionValue + positionValueInCurrency));
        });

        return perSymbol;
    }

    /** 借贷 LIF：per-currency 单值，copy 出快照避免持有 live map。 */
    private static IntLongHashMap collectLoanInsuranceFund(RiskEngine riskEngine) {
        return new IntLongHashMap(riskEngine.getLoanService().getLoanInsuranceFund());
    }

    @Override
    public void writeMarshallable(BytesOut bytes) {
        // 无参数
    }
}
