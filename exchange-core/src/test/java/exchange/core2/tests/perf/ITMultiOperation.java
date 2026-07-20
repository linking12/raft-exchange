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
package exchange.core2.tests.perf;

import exchange.core2.core.common.config.InitialStateConfiguration;
import exchange.core2.core.common.config.PerformanceConfiguration;
import exchange.core2.core.common.config.SerializationConfiguration;
import exchange.core2.core.event.IEventsHandler4Test;
import exchange.core2.core.event.SimpleEventsProcessor4Test;
import exchange.core2.tests.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
public final class ITMultiOperation {

    SimpleEventsProcessor4Test processor;

    @Mock
    IEventsHandler4Test handler;

    @BeforeEach
    public void before() {
        processor = new SimpleEventsProcessor4Test(handler, true);
    }

    @Test
    @Timeout(60)
    public void shouldPerformMarginOperations() {
        ThroughputTestsModule.throughputTestImpl(
                PerformanceConfiguration.throughputPerformanceBuilder()
                        .matchingEnginesNum(1)
                        .riskEnginesNum(1)
                        .build(),
                TestDataParameters.builder()
                        .totalTransactionsNumber(1_000_000)
                        .targetOrderBookOrdersTotal(1000)
                        .numAccounts(2000)
                        .currenciesAllowed(TestConstants.CURRENCIES_FUTURES)
                        .numSymbols(1)
                        .allowedSymbolTypes(ExchangeTestContainer.AllowedSymbolTypes.FUTURES_CONTRACT)
                        .preFillMode(TestOrdersGeneratorConfig.PreFillMode.ORDERS_NUMBER)
                        .build(),
                InitialStateConfiguration.CLEAN_TEST,
                SerializationConfiguration.DEFAULT,
                2,
                processor
        );
    }

    @Test
    @Timeout(60)
    public void shouldPerformExchangeOperations() {
        ThroughputTestsModule.throughputTestImpl(
                PerformanceConfiguration.throughputPerformanceBuilder()
                        .matchingEnginesNum(1)
                        .riskEnginesNum(1)
                        .build(),
                TestDataParameters.builder()
                        .totalTransactionsNumber(1_000_000)
                        .targetOrderBookOrdersTotal(1000)
                        .numAccounts(2000)
                        .currenciesAllowed(TestConstants.CURRENCIES_EXCHANGE)
                        .numSymbols(1)
                        .allowedSymbolTypes(ExchangeTestContainer.AllowedSymbolTypes.CURRENCY_EXCHANGE_PAIR)
                        .preFillMode(TestOrdersGeneratorConfig.PreFillMode.ORDERS_NUMBER)
                        .build(),
                InitialStateConfiguration.CLEAN_TEST,
                SerializationConfiguration.DEFAULT,
                2,
                processor);
    }

    @Test
    @Timeout(60)
    public void shouldPerformSharded() {
        ThroughputTestsModule.throughputTestImpl(
                PerformanceConfiguration.throughputPerformanceBuilder()
                        .matchingEnginesNum(2)
                        .riskEnginesNum(2)
                        .build(),
                TestDataParameters.builder()
                        .totalTransactionsNumber(1_000_000)
                        .targetOrderBookOrdersTotal(1000)
                        .numAccounts(2000)
                        .currenciesAllowed(TestConstants.CURRENCIES_EXCHANGE)
                        .numSymbols(32)
                        .allowedSymbolTypes(ExchangeTestContainer.AllowedSymbolTypes.BOTH)
                        .preFillMode(TestOrdersGeneratorConfig.PreFillMode.ORDERS_NUMBER)
                        .build(),
                InitialStateConfiguration.CLEAN_TEST,
                SerializationConfiguration.DEFAULT,
                2,
                processor);
    }
}
