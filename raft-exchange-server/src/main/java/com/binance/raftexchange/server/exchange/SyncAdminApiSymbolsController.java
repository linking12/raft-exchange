package com.binance.raftexchange.server.exchange;

import com.binance.raftexchange.stubs.request.ApiAdjustPrice;
import com.binance.raftexchange.stubs.request.ApiCommand;
import com.binance.raftexchange.stubs.request.ApiSettleFundingFees;
import com.binance.raftexchange.stubs.request.ApiSettlePNL;
import com.binance.raftexchange.stubs.request.BatchAddSymbolsCommand;
import com.binance.raftexchange.stubs.request.CoreSymbolSpecification;
import exchange.core2.core.common.SymbolType;
import exchange.core2.core.common.api.ApiAdjustMarkPrice;
import org.eclipse.collections.impl.map.sorted.mutable.TreeSortedMap;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

public class SyncAdminApiSymbolsController extends AbstractApiController {

    /**
     * 
     */
    public static CompletableFuture<byte[]> batchAddSymbols(BatchAddSymbolsCommand grpcBatchAddSymbolsCommand) {
        Map<Integer, CoreSymbolSpecification> symbolsMap = grpcBatchAddSymbolsCommand.getSymbolsMap();
        Collection<exchange.core2.core.common.CoreSymbolSpecification> coreSymbols = new ArrayList<>(symbolsMap.size());
        for (CoreSymbolSpecification grpcSymbol : symbolsMap.values()) {
            exchange.core2.core.common.CoreSymbolSpecification coreSymbol = exchange.core2.core.common.CoreSymbolSpecification.builder()
                .symbolId(grpcSymbol.getSymbolId()).type(SymbolType.of(grpcSymbol.getType().getNumber())).baseCurrency(grpcSymbol.getBaseCurrency())
                .quoteCurrency(grpcSymbol.getQuoteCurrency()).baseScaleK(grpcSymbol.getBaseScaleK()).quoteScaleK(grpcSymbol.getQuoteScaleK())
                .takerFee(grpcSymbol.getTakerFee()).makerFee(grpcSymbol.getMakerFee()).feeScaleK(grpcSymbol.getFeeScaleK())
                .initMargin(grpcSymbol.getInitMargin()).initMarginScaleK(grpcSymbol.getInitMarginScaleK())
                .maintenanceMargin(TreeSortedMap.newMap(Comparator.naturalOrder(), grpcSymbol.getMaintenanceMarginMap())).maintenanceMarginScaleK(grpcSymbol.getMaintenanceMarginScaleK())
                .maxLeverage(TreeSortedMap.newMap(Comparator.naturalOrder(), grpcSymbol.getMaxLeverageMap())).build();
            coreSymbols.add(coreSymbol);
        }
        exchange.core2.core.common.api.binary.BatchAddSymbolsCommand batchAddSymbolsCommand =
            new exchange.core2.core.common.api.binary.BatchAddSymbolsCommand(coreSymbols);
        LOG.debug("batchAddSymbolsCommand applied, msg: {}", batchAddSymbolsCommand);

        return callExchange(batchAddSymbolsCommand);
    }

    public static CompletableFuture<byte[]> adjustPrice(ApiCommand apiCommand) {
        ApiAdjustPrice grpcAdjustPrice = apiCommand.getAdjustPrice();
        ApiAdjustMarkPrice apiAdjustMarkPrice = ApiAdjustMarkPrice.builder()
            .transactionId(grpcAdjustPrice.getTransactionId()).symbol(grpcAdjustPrice.getSymbol())
            .markPrice(grpcAdjustPrice.getMarkPrice()).build();
        apiAdjustMarkPrice.updateTimestamp(apiCommand.getTimestamp());
        LOG.debug("apiAdjustPrice applied, msg: {}", apiAdjustMarkPrice);
        return callExchange(apiAdjustMarkPrice);
    }

    public static CompletableFuture<byte[]> settleFundingFees(ApiCommand apiCommand) {
        ApiSettleFundingFees grpcSettleFundingFees = apiCommand.getSettleFundingFees();
        exchange.core2.core.common.api.ApiSettleFundingFees apiSettleFundingFees = exchange.core2.core.common.api.ApiSettleFundingFees.builder()
            .transactionId(grpcSettleFundingFees.getTransactionId()).symbol(grpcSettleFundingFees.getSymbol())
            .fundingRate(grpcSettleFundingFees.getFundingRate()).rateScaleK(grpcSettleFundingFees.getRateScaleK()).build();
        apiSettleFundingFees.updateTimestamp(apiCommand.getTimestamp());
        LOG.debug("apiSettleFundingFees applied, msg: {}", apiSettleFundingFees);
        return callExchange(apiSettleFundingFees);
    }

    public static CompletableFuture<byte[]> settlePNL(ApiCommand apiCommand) {
        ApiSettlePNL grpcSettlePnl = apiCommand.getSettlePnl();
        exchange.core2.core.common.api.ApiSettlePNL apiSettlePNL = exchange.core2.core.common.api.ApiSettlePNL.builder()
            .transactionId(grpcSettlePnl.getTransactionId()).symbol(grpcSettlePnl.getSymbol())
            .settlePrice(grpcSettlePnl.getSettlePrice()).build();
        apiSettlePNL.updateTimestamp(apiCommand.getTimestamp());
        LOG.debug("apiSettlePNL applied, msg: {}", apiSettlePNL);
        return callExchange(apiSettlePNL);
    }
}
