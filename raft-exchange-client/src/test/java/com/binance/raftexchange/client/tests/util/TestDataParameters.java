package com.binance.raftexchange.client.tests.util;

import lombok.Builder;
import lombok.Data;

import java.util.Set;

@Data
@Builder
public class TestDataParameters {
    public final int totalTransactionsNumber;
    public final int targetOrderBookOrdersTotal;
    public final int numAccounts;
    public final Set<Integer> currenciesAllowed;
    public final int numSymbols;
    public final ExchangeTestContainer.AllowedSymbolTypes allowedSymbolTypes;
    public final TestOrdersGeneratorConfig.PreFillMode preFillMode;
    public final boolean avalancheIOC;

    public static TestDataParametersBuilder singlePairMarginBuilder() {
        return TestDataParameters.builder()
                .totalTransactionsNumber(3_000_000) // 3w 300w 不影响tps
                .targetOrderBookOrdersTotal(1000)
                .numAccounts(2000)
                .currenciesAllowed(TestConstants.CURRENCIES_FUTURES)
                .numSymbols(1)
                .allowedSymbolTypes(ExchangeTestContainer.AllowedSymbolTypes.FUTURES_CONTRACT)
                .preFillMode(TestOrdersGeneratorConfig.PreFillMode.ORDERS_NUMBER);
    }

    public static TestDataParameters.TestDataParametersBuilder singlePairExchangeBuilder() {
        return TestDataParameters.builder()
                .totalTransactionsNumber(3_000_000)
                .targetOrderBookOrdersTotal(1000)
                .numAccounts(2000)
                .currenciesAllowed(TestConstants.CURRENCIES_EXCHANGE)
                .numSymbols(1)
                .allowedSymbolTypes(ExchangeTestContainer.AllowedSymbolTypes.CURRENCY_EXCHANGE_PAIR)
                .preFillMode(TestOrdersGeneratorConfig.PreFillMode.ORDERS_NUMBER);
    }


}
