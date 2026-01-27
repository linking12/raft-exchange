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
import exchange.core2.core.utils.CoreArithmeticUtils;
import lombok.EqualsAndHashCode;
import lombok.ToString;
import lombok.extern.slf4j.Slf4j;
import net.openhft.chronicle.bytes.BytesIn;
import net.openhft.chronicle.bytes.BytesOut;
import org.eclipse.collections.impl.list.mutable.FastList;
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
                long balance = userProfile.accounts.get(currency);

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

            return Optional.of(SingleUserReportResult.createFromRiskEngineFound(
                    uid,
                    userProfile.userStatus,
                    userProfile.accounts,
                    positions));
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
