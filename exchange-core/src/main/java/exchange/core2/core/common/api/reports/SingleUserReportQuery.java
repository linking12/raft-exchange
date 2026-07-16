/*
 * Copyright 2019 Maksim Zheravin
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package exchange.core2.core.common.api.reports;

import exchange.core2.core.common.CoreCurrencySpecification;
import exchange.core2.core.common.CoreSymbolSpecification;
import exchange.core2.core.common.MarginMode;
import exchange.core2.core.common.Order;
import exchange.core2.core.common.PositionDirection;
import exchange.core2.core.common.SymbolPositionRecord;
import exchange.core2.core.common.UserProfile;
import exchange.core2.core.processors.CurrencySpecificationProvider;
import exchange.core2.core.processors.MatchingEngineRouter;
import exchange.core2.core.processors.RiskEngine;
import exchange.core2.core.processors.RiskEngine.LastPriceCacheRecord;
import exchange.core2.core.processors.SymbolSpecificationProvider;
import exchange.core2.core.processors.loan.LoanService;
import exchange.core2.core.utils.CoreArithmeticUtils;
import lombok.EqualsAndHashCode;
import lombok.ToString;
import lombok.extern.slf4j.Slf4j;
import net.openhft.chronicle.bytes.BytesIn;
import net.openhft.chronicle.bytes.BytesOut;
import org.eclipse.collections.impl.list.mutable.FastList;
import org.eclipse.collections.impl.map.mutable.primitive.IntLongHashMap;
import org.eclipse.collections.impl.map.mutable.primitive.IntObjectHashMap;

import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

@EqualsAndHashCode
@ToString
@Slf4j
public final class SingleUserReportQuery implements ReportQuery<SingleUserReportResult> {

    private final long uid;

    public SingleUserReportQuery(long uid) {
        this.uid = uid;
    }

    public SingleUserReportQuery(final BytesIn bytesIn) {
        this.uid = bytesIn.readLong();
    }

    public long getUid() {
        return uid;
    }

    @Override
    public int getReportTypeCode() {
        return ReportType.SINGLE_USER_REPORT.getCode();
    }

    @Override
    public SingleUserReportResult createResult(final Stream<BytesIn> sections) {
        return SingleUserReportResult.merge(sections);
    }

    @Override
    public Optional<SingleUserReportResult> process(final MatchingEngineRouter matchingEngine) {
        final IntObjectHashMap<List<Order>> orders = new IntObjectHashMap<>();

        matchingEngine.getOrderBooks().forEach(ob -> {
            final List<Order> userOrders = ob.findUserOrders(this.uid);
            // dont put empty results, so that the report result merge procedure would be simple
            if (!userOrders.isEmpty()) {
                orders.put(ob.getSymbolSpec().symbolId, userOrders);
            }
        });

        //log.debug("ME{}: orders: {}", matchingEngine.getShardId(), orders);
        return Optional.of(SingleUserReportResult.createFromMatchingEngine(uid, orders));
    }

    @Override
    public Optional<SingleUserReportResult> process(final RiskEngine riskEngine) {

        if (!riskEngine.uidForThisHandler(this.uid)) {
            return Optional.empty();
        }
        final UserProfile userProfile = riskEngine.getUserProfileService().getUserProfile(this.uid);
        SymbolSpecificationProvider symbolSpecProvider = riskEngine.getSymbolSpecificationProvider();
        CurrencySpecificationProvider currencySpecProvider = riskEngine.getCurrencySpecificationProvider();

        if (userProfile != null) {
            final IntObjectHashMap<List<SingleUserReportResult.Position>> positions = new IntObjectHashMap<>(userProfile.positions.size());
            IntObjectHashMap<List<SymbolPositionRecord>> crossPositionsByCurrency = IntObjectHashMap.newMap();
            userProfile.positions.forEachValue(pos -> {
                if (pos.marginMode == MarginMode.CROSS) {
                    crossPositionsByCurrency.getIfAbsentPut(pos.currency, FastList.newList()).add(pos);
                } else {
                    CoreSymbolSpecification spec = symbolSpecProvider.getSymbolSpecification(pos.symbol);
                    LastPriceCacheRecord priceRecord = riskEngine.getLastPriceCache().get(pos.symbol);
                    long totalMargin = pos.openInitMarginSum + pos.estimateUnrealizedProfit(priceRecord) + pos.extraMargin;
                    long unrealizedPnl = pos.estimateUnrealizedProfit(priceRecord);
                    long liquidationPrice = pos.estimateLiquidationPrice(spec, priceRecord, 0, 0, 0);
                    long marginRatioScaleK = pos.estimateMarginRatioScaleK(spec, priceRecord, totalMargin);
                    List<SingleUserReportResult.Position> list = positions.getIfAbsentPut(pos.symbol, FastList.newList());
                    list.add(pos.direction == PositionDirection.LONG ? 0 : list.size(), buildPositionReport(pos, unrealizedPnl, liquidationPrice, marginRatioScaleK, priceRecord.markPrice));
                }
            });

            crossPositionsByCurrency.forEachKeyValue((currency, records) -> {
                CoreCurrencySpecification quoteCurrency = currencySpecProvider.getCurrencySpecification(currency);
                long totalPnl = 0L;
                long totalMM = 0L;
                for (SymbolPositionRecord pos : records) {
                    CoreSymbolSpecification spec = symbolSpecProvider.getSymbolSpecification(pos.symbol);
                    LastPriceCacheRecord priceRecord = riskEngine.getLastPriceCache().get(pos.symbol);
                    long pnl = pos.estimatePnl(priceRecord);
                    totalPnl += CoreArithmeticUtils.sizePriceToCurrencyScale(pnl, spec, quoteCurrency);
                    long mm = pos.calculateMaintenanceMargin(spec, priceRecord);
                    totalMM += CoreArithmeticUtils.sizePriceToCurrencyScale(mm, spec, quoteCurrency);
                }
                // cross 真实可用 = accounts − exchangeLocked − Σ 逐仓虚拟锁定，用 CoreArithmeticUtils 共用 helper
                // 与 LiquidationEngine#checkCross 和 FundEventsHelper#calc 口径完全对齐，
                // 否则 user 查询看到的 liquidationPrice / marginRatioScaleK 偏乐观，实际强平会"突然"打掉。
                long balance = userProfile.calculateCrossAvailable(currency, quoteCurrency,
                    symbolSpecProvider::getSymbolSpecification);

                for (SymbolPositionRecord pos : records) {
                    CoreSymbolSpecification spec = symbolSpecProvider.getSymbolSpecification(pos.symbol);
                    LastPriceCacheRecord priceRecord = riskEngine.getLastPriceCache().get(pos.symbol);
                    long totalPnlSymbolScale = CoreArithmeticUtils.currencyToSizePriceScale(totalPnl, spec, quoteCurrency);
                    long totalMMSymbolScale = CoreArithmeticUtils.currencyToSizePriceScale(totalMM, spec, quoteCurrency);
                    long balanceSymbolScale = CoreArithmeticUtils.currencyToSizePriceScale(balance, spec, quoteCurrency);
                    long unrealizedPnl = pos.estimateUnrealizedProfit(priceRecord);
                    long liquidationPrice = pos.estimateLiquidationPrice(spec, priceRecord, balanceSymbolScale, totalPnlSymbolScale, totalMMSymbolScale);
                    long marginRatioScaleK = pos.estimateMarginRatioScaleK(spec, priceRecord, balanceSymbolScale + totalPnlSymbolScale);
                    List<SingleUserReportResult.Position> list = positions.getIfAbsentPut(pos.symbol, FastList.newList());
                    list.add(pos.direction == PositionDirection.LONG ? 0 : list.size(), buildPositionReport(pos, unrealizedPnl, liquidationPrice, marginRatioScaleK, priceRecord.markPrice));
                }
            });

            // ---- 现货借贷 loan 持仓 + 实时健康度（口径对齐 scanner LoanLiquidationEngine，避免查询看到的 LTV 比强平乐观）----
            final LoanService loanService = riskEngine.getLoanService();
            final long nowMs = System.currentTimeMillis();

            final List<SingleUserReportResult.IsolatedLoan> isolatedLoans = FastList.newList();
            userProfile.isolatedLoans.forEachValue(loan -> {
                if (loan.isEmpty()) {
                    return;
                }
                final CoreSymbolSpecification spec = symbolSpecProvider.getSymbolSpecification(loan.symbolId);
                final LastPriceCacheRecord priceRecord = riskEngine.getLastPriceCache().get(loan.symbolId);
                final long markPrice = priceRecord != null ? priceRecord.markPrice : 0L;
                final long displayInterest = loanService.calculateDisplayInterest(loan, nowMs);
                long ltvBps = 0L;
                if (spec != null && markPrice > 0) {
                    final CoreCurrencySpecification baseSpec = currencySpecProvider.getCurrencySpecification(loan.collateralCurrency);
                    final CoreCurrencySpecification quoteSpec = currencySpecProvider.getCurrencySpecification(loan.loanCurrency);
                    final long collateralValue = LoanService.collateralValueInQuoteCurrency(
                            loan.collateralAmount, spec, markPrice, baseSpec, quoteSpec);
                    if (collateralValue > 0) {
                        final long realDebt = loan.outstandingPrincipal + displayInterest;
                        ltvBps = Math.multiplyExact(realDebt, LoanService.BPS_SCALE) / collateralValue;
                    }
                }
                isolatedLoans.add(new SingleUserReportResult.IsolatedLoan(loan.loanId, loan.symbolId,
                        loan.collateralCurrency, loan.loanCurrency, loan.rateBps, loan.openedAtTs, loan.collateralAmount,
                        loan.outstandingPrincipal, loan.accumulatedInterest, displayInterest, ltvBps, markPrice));
            });

            final List<SingleUserReportResult.CrossLoan> crossLoans = FastList.newList();
            userProfile.crossLoans.forEachValue(loan -> {
                if (loan.isEmpty()) {
                    return;
                }
                final long displayInterest = loanService.calculateDisplayInterest(loan, nowMs);
                crossLoans.add(new SingleUserReportResult.CrossLoan(loan.loanId, loan.symbolId, loan.loanCurrency,
                        loan.rateBps, loan.openedAtTs, loan.outstandingPrincipal, loan.accumulatedInterest, displayInterest));
            });

            final IntLongHashMap crossLoanCollateral = new IntLongHashMap(userProfile.crossLoanCollateral);
            final long crossAccountLtvBps = loanService.calculateCrossAccountLtvBps(userProfile, nowMs,
                    symbolSpecProvider, currencySpecProvider, riskEngine.getLastPriceCache(), loanService.getGlobalConfig().numeraireCurrency);

            // 返回 raw 字段，free 由客户端按需自己算：
            //   - accounts: 真实持有总额（currencyScale），含现货挂单冻结（exchangeLocked 未扣减）
            //   - exchangeLocked: 现货挂单冻结（currencyScale），客户端需自行从 accounts 中减去
            //   - positions: 每个仓位的 openInitMarginSum / pendingSize / pendingAvgPrice / extraMargin / profit
            //                 等原始字段（sizePriceScale = baseScaleK × quoteScaleK）
            //
            // 典型计算（同 currency，按需聚合）：
            //   严格冻结 free   = accounts - exchangeLocked
            //                    - sizePriceToCurrencyScale(Σ_position (openInitMarginSum + pendingMargin + 潜在 fee))
            //     pendingMargin = pendingSize × pendingAvgPrice × initMarginRate / leverage
            //     潜在 fee     = takerFee (按 pending size × pendingAvgPrice 估)
            //   可调度 free（含未实现 PnL）= 严格冻结 free + sizePriceToCurrencyScale(Σ_position unrealizedPnl)
            //     unrealizedPnl 已在 Position.unrealizedProfit 字段里给出（sizePriceScale）
            //
            // 注意：isolated 仓位的 extraMargin 已经从 accounts 扣到 position 内，不重复算入 free。
            return Optional.of(SingleUserReportResult.createFromRiskEngineFound(
                    uid,
                    userProfile.userStatus,
                    userProfile.accounts,
                    userProfile.exchangeLocked,
                    positions,
                    isolatedLoans,
                    crossLoans,
                    crossLoanCollateral,
                    crossAccountLtvBps));
        } else {
            // not found
            return Optional.of(SingleUserReportResult.createFromRiskEngineNotFound(uid));
        }
    }

    private SingleUserReportResult.Position buildPositionReport(SymbolPositionRecord pos, long unrealizedPnl,
                                                                long liquidationPrice, long marginRatioScaleK, long markPrice) {

        return new SingleUserReportResult.Position(
                pos.currency,
                pos.direction,
                pos.openVolume,
                pos.openInitMarginSum,
                pos.openPriceSum,
                pos.profit,
                pos.pendingSellSize,
                pos.pendingBuySize,
                pos.pendingSellAvgPrice,
                pos.pendingBuyAvgPrice,
                pos.getLeverage(),
                pos.marginMode,
                pos.extraMargin,
                unrealizedPnl,
                liquidationPrice,
                marginRatioScaleK,
                markPrice);
    }

    @Override
    public void writeMarshallable(final BytesOut bytes) {
        bytes.writeLong(uid);
    }
}
