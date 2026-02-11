package com.binance.raftexchange.server.exchange;

import com.binance.raftexchange.stubs.CoreCurrencySpecification;
import com.binance.raftexchange.stubs.CoreSymbolSpecification;
import com.binance.raftexchange.stubs.request.ApiAdjustMarkPrice;
import com.binance.raftexchange.stubs.request.ApiCommand;
import com.binance.raftexchange.stubs.request.ApiSettleFundingFees;
import com.binance.raftexchange.stubs.request.ApiSettlePNL;
import com.binance.raftexchange.stubs.request.BatchAddCurrenciesCommand;
import com.binance.raftexchange.stubs.request.BatchAddSymbolsCommand;
import exchange.core2.core.common.SymbolType;
import org.eclipse.collections.impl.map.sorted.mutable.TreeSortedMap;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

public class SyncAdminApiSymbolsController extends AbstractApiController {

    /**
     * 
     */
    public static CompletableFuture<Supplier<byte[]>> batchAddSymbols(BatchAddSymbolsCommand grpcBatchAddSymbolsCommand) {
        Map<Integer, CoreSymbolSpecification> symbolsMap = grpcBatchAddSymbolsCommand.getSymbolsMap();
        Collection<exchange.core2.core.common.CoreSymbolSpecification> coreSymbols = new ArrayList<>(symbolsMap.size());
        for (CoreSymbolSpecification grpcSymbol : symbolsMap.values()) {
            exchange.core2.core.common.CoreSymbolSpecification coreSymbol = exchange.core2.core.common.CoreSymbolSpecification.builder()
                .symbolId(grpcSymbol.getSymbolId()).type(SymbolType.of(grpcSymbol.getType().getNumber())).baseCurrency(grpcSymbol.getBaseCurrency())
                .quoteCurrency(grpcSymbol.getQuoteCurrency()).baseScaleK(grpcSymbol.getBaseScaleK()).quoteScaleK(grpcSymbol.getQuoteScaleK())
                .takerFee(grpcSymbol.getTakerFee()).makerFee(grpcSymbol.getMakerFee())
                .liquidationFee(grpcSymbol.getLiquidationFee()).feeScaleK(grpcSymbol.getFeeScaleK())
                .initMargin(grpcSymbol.getInitMargin()).initMarginScaleK(grpcSymbol.getInitMarginScaleK())
                .maintenanceMargin(TreeSortedMap.newMap(Comparator.naturalOrder(), grpcSymbol.getMaintenanceMarginMap()))
                .maintenanceMarginScaleK(grpcSymbol.getMaintenanceMarginScaleK())
                .maxLeverage(TreeSortedMap.newMap(Comparator.naturalOrder(), grpcSymbol.getMaxLeverageMap())).build();
            coreSymbols.add(coreSymbol);
        }
        exchange.core2.core.common.api.binary.BatchAddSymbolsCommand batchAddSymbolsCommand =
            new exchange.core2.core.common.api.binary.BatchAddSymbolsCommand(coreSymbols);
        LOG.debug("batchAddSymbolsCommand applied, msg: {}", batchAddSymbolsCommand);

        return callExchange(batchAddSymbolsCommand);
    }

    public static CompletableFuture<Supplier<byte[]>> batchAddCurrencies(BatchAddCurrenciesCommand grpcBatchAddCurrenciesCommand) {
        Map<Integer, CoreCurrencySpecification> currenciesMap = grpcBatchAddCurrenciesCommand.getCurrenciesMap();
        Collection<exchange.core2.core.common.CoreCurrencySpecification> currencies = new ArrayList<>(currenciesMap.size());
        for (CoreCurrencySpecification grpcCurrency : currenciesMap.values()) {
            exchange.core2.core.common.CoreCurrencySpecification currency = exchange.core2.core.common.CoreCurrencySpecification.builder()
                .id(grpcCurrency.getId()).name(grpcCurrency.getName()).digit(grpcCurrency.getDigit()).build();
            currencies.add(currency);
        }
        exchange.core2.core.common.api.binary.BatchAddCurrenciesCommand batchAddCurrenciesCommand =
            new exchange.core2.core.common.api.binary.BatchAddCurrenciesCommand(currencies);
        LOG.debug("batchAddCurrenciesCommand applied, msg: {}", batchAddCurrenciesCommand);

        return callExchange(batchAddCurrenciesCommand);
    }

    public static CompletableFuture<Supplier<byte[]>> adjustMarkPrice(ApiCommand apiCommand) {
        ApiAdjustMarkPrice grpcAdjustPrice = apiCommand.getAdjustMarkprice();
        exchange.core2.core.common.api.ApiAdjustMarkPrice apiAdjustMarkPrice = exchange.core2.core.common.api.ApiAdjustMarkPrice.builder()
            .transactionId(grpcAdjustPrice.getTransactionId()).symbol(grpcAdjustPrice.getSymbol()).markPrice(grpcAdjustPrice.getMarkPrice()).build();
        apiAdjustMarkPrice.updateTimestamp(apiCommand.getTimestamp());
        LOG.debug("apiAdjustPrice applied, msg: {}", apiAdjustMarkPrice);
        return callExchange(apiAdjustMarkPrice);
    }

    public static CompletableFuture<Supplier<byte[]>> settleFundingFees(ApiCommand apiCommand) {
        ApiSettleFundingFees grpcSettleFundingFees = apiCommand.getSettleFundingFees();
        exchange.core2.core.common.api.ApiSettleFundingFees apiSettleFundingFees = exchange.core2.core.common.api.ApiSettleFundingFees.builder()
            .transactionId(grpcSettleFundingFees.getTransactionId()).symbol(grpcSettleFundingFees.getSymbol())
            .action(exchange.core2.core.common.OrderAction.of((byte) grpcSettleFundingFees.getAction().getNumber()))
            .fundingRate(grpcSettleFundingFees.getFundingRate()).rateScaleK(grpcSettleFundingFees.getRateScaleK()).build();
        apiSettleFundingFees.updateTimestamp(apiCommand.getTimestamp());
        LOG.debug("apiSettleFundingFees applied, msg: {}", apiSettleFundingFees);
        return callExchange(apiSettleFundingFees);
    }

    public static CompletableFuture<Supplier<byte[]>> settlePNL(ApiCommand apiCommand) {
        ApiSettlePNL grpcSettlePnl = apiCommand.getSettlePnl();
        exchange.core2.core.common.api.ApiSettlePNL apiSettlePNL = exchange.core2.core.common.api.ApiSettlePNL.builder()
            .transactionId(grpcSettlePnl.getTransactionId()).symbol(grpcSettlePnl.getSymbol()).settlePrice(grpcSettlePnl.getSettlePrice()).build();
        apiSettlePNL.updateTimestamp(apiCommand.getTimestamp());
        LOG.debug("apiSettlePNL applied, msg: {}", apiSettlePNL);
        return callExchange(apiSettlePNL);
    }
}
