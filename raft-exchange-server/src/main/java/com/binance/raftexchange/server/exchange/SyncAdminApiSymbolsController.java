package com.binance.raftexchange.server.exchange;

import com.binance.raftexchange.stubs.request.BatchAddSymbolsCommand;
import com.binance.raftexchange.stubs.request.CoreSymbolSpecification;
import exchange.core2.core.common.SymbolType;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Map;

public class SyncAdminApiSymbolsController extends AbstractApiController {

    public static byte[] batchAddSymbols(BatchAddSymbolsCommand grpcBatchAddSymbolsCommand) throws Exception {
        Map<Integer, CoreSymbolSpecification> symbolsMap = grpcBatchAddSymbolsCommand.getSymbolsMap();
        Collection<exchange.core2.core.common.CoreSymbolSpecification> coreSymbols = new ArrayList<>(symbolsMap.size());
        for (CoreSymbolSpecification grpcSymbol : symbolsMap.values()) {
            exchange.core2.core.common.CoreSymbolSpecification coreSymbol = exchange.core2.core.common.CoreSymbolSpecification.builder()
                    .symbolId(grpcSymbol.getSymbolId())
                    .type(SymbolType.of(grpcSymbol.getType().getNumber()))
                    .baseCurrency(grpcSymbol.getBaseCurrency())
                    .quoteCurrency(grpcSymbol.getQuoteCurrency())
                    .baseScaleK(grpcSymbol.getBaseScaleK())
                    .quoteScaleK(grpcSymbol.getQuoteScaleK())
                    .takerFee(grpcSymbol.getTakerFee())
                    .makerFee(grpcSymbol.getMakerFee())
                    .marginBuy(grpcSymbol.getMarginBuy())
                    .marginSell(grpcSymbol.getMarginSell())
                    .build();
            coreSymbols.add(coreSymbol);
        }
        exchange.core2.core.common.api.binary.BatchAddSymbolsCommand batchAddSymbolsCommand = new exchange.core2.core.common.api.binary.BatchAddSymbolsCommand(coreSymbols);
        LOG.info("batchAddSymbolsCommand applied, msg: {}", batchAddSymbolsCommand);

        return callExchange(batchAddSymbolsCommand);
    }
}
