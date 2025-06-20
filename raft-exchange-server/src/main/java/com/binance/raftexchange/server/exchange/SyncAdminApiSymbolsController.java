package com.binance.raftexchange.server.exchange;

import com.binance.raftexchange.stubs.request.ApiCommand;
import com.binance.raftexchange.stubs.request.ApiSettleFundingFees;
import com.binance.raftexchange.stubs.request.BatchAddSymbolsCommand;
import com.binance.raftexchange.stubs.request.CoreSymbolSpecification;
import exchange.core2.core.common.SymbolType;

import java.util.ArrayList;
import java.util.Collection;
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
                .marginBuy(grpcSymbol.getMarginBuy()).marginSell(grpcSymbol.getMarginSell()).maintenanceMargin(grpcSymbol.getMaintenanceMargin())
                .maxLeverage(grpcSymbol.getMaxLeverage()).build();
            coreSymbols.add(coreSymbol);
        }
        exchange.core2.core.common.api.binary.BatchAddSymbolsCommand batchAddSymbolsCommand =
            new exchange.core2.core.common.api.binary.BatchAddSymbolsCommand(coreSymbols);
        LOG.debug("batchAddSymbolsCommand applied, msg: {}", batchAddSymbolsCommand);

        return callExchange(batchAddSymbolsCommand);
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
}
