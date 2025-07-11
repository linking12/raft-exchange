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

import exchange.core2.core.common.CoreSymbolSpecification;
import exchange.core2.core.common.MarginMode;
import exchange.core2.core.common.Order;
import exchange.core2.core.common.SymbolPositionRecord;
import exchange.core2.core.common.UserProfile;
import exchange.core2.core.processors.MatchingEngineRouter;
import exchange.core2.core.processors.RiskEngine;
import exchange.core2.core.processors.RiskEngine.LastPriceCacheRecord;
import exchange.core2.core.processors.SymbolSpecificationProvider;
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

        if (userProfile != null) {
            final IntObjectHashMap<SingleUserReportResult.Position> positions = new IntObjectHashMap<>(userProfile.positions.size());
            IntObjectHashMap<List<SymbolPositionRecord>> crossPositionsByCurrency = IntObjectHashMap.newMap();
            userProfile.positions.forEachKeyValue((symbol, pos) -> {
                if (pos.marginMode == MarginMode.CROSS) {
                    crossPositionsByCurrency.getIfAbsentPut(pos.currency, FastList.newList()).add(pos);
                } else {
                    CoreSymbolSpecification spec = symbolSpecProvider.getSymbolSpecification(symbol);
                    LastPriceCacheRecord priceRecord = riskEngine.getLastPriceCache().get(symbol);
                    long totalMargin = pos.openInitMarginSum + pos.estimateUnrealizedProfit(priceRecord) + pos.extraMargin;
                    positions.put(symbol, buildPositionReport(pos, spec, priceRecord, totalMargin));
                }
            });

            crossPositionsByCurrency.forEachKeyValue((currency, records) -> {
                long totalPnl = records.stream().mapToLong(pos -> {
                    LastPriceCacheRecord priceRecord = riskEngine.getLastPriceCache().get(pos.symbol);
                    return pos.estimateProfit(priceRecord);
                }).sum();
                long balance = userProfile.accounts.get(currency);
                long totalMargin = balance + totalPnl;

                for (SymbolPositionRecord pos : records) {
                    CoreSymbolSpecification spec = symbolSpecProvider.getSymbolSpecification(pos.symbol);
                    LastPriceCacheRecord priceRecord = riskEngine.getLastPriceCache().get(pos.symbol);
                    positions.put(pos.symbol, buildPositionReport(pos, spec, priceRecord, totalMargin));
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

    private SingleUserReportResult.Position buildPositionReport(SymbolPositionRecord pos, CoreSymbolSpecification spec,
                                                                LastPriceCacheRecord priceRecord, long totalMargin) {
        long unrealizedPnl = pos.estimateUnrealizedProfit(priceRecord);
        long liquidationPrice = pos.estimateLiquidationPrice(spec, priceRecord);
        long marginRatioScaleK = pos.estimateMarginRatioScaleK(spec, priceRecord, totalMargin);

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
                marginRatioScaleK);
    }

    @Override
    public void writeMarshallable(final BytesOut bytes) {
        bytes.writeLong(uid);
    }
}
