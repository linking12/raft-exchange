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
package com.binance.raftexchange.client.tests.util;

import com.binance.raftexchange.client.grpc.ExchangeClient;
import com.binance.raftexchange.stubs.report.TotalCurrencyBalanceReportResult;
import lombok.extern.slf4j.Slf4j;

import java.util.HashMap;
import java.util.Map;
import java.util.stream.IntStream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;


@Slf4j
public class ThroughputTestsModule {

    private static final ExchangeClient exchangeClient = new ExchangeClient("localhost", 5001);


    public static void throughputTestImpl(final TestDataParameters testDataParameters,
                                          final int iterations) {

        final ExchangeTestContainer.TestDataFutures testDataFutures = ExchangeTestContainer.prepareTestDataAsync(testDataParameters, 1);

        try (final ExchangeTestContainer container = ExchangeTestContainer.create(exchangeClient, true)) {

            final float avgMt = container.executeTestingThread(
                    () -> (float) IntStream.range(0, 1)
                            .mapToObj(j -> {
                                container.loadSymbolsUsersAndPrefillOrdersNoLog(testDataFutures);


                                final float perfMt = container.benchmarkMtps(testDataFutures);
                                log.info("{}. {} MT/s", j, String.format("%.3f", perfMt));

                                final long tStart = System.currentTimeMillis();
                                assertTrue(isGlobalBalancesAllZero(container.totalBalanceReport()));
                                final long tDuration = System.currentTimeMillis() - tStart;
                                log.info("read index time: {} ms", tDuration);

                                // compare orderBook final state just to make sure all commands executed same way
//                                testDataFutures.coreSymbolSpecifications.join().forEach(
//                                        symbol -> assertEquals(
//                                                testDataFutures.getGenResult().join().getGenResults().get(symbol.getSymbolId()).getFinalOrderBookSnapshot(),
//                                                container.requestCurrentOrderBook(symbol.getSymbolId())));


//                                container.resetExchangeCore();

                                System.gc();

                                return perfMt;
                            })
                            .mapToDouble(x -> x)
                            .average().orElse(0));

            log.info("Average: {} MT/s", avgMt);
        }
    }

    public static boolean isGlobalBalancesAllZero(TotalCurrencyBalanceReportResult totalCurrencyBalance) {
        return mergeSum(totalCurrencyBalance.getAccountBalancesMap(),
                totalCurrencyBalance.getOrdersBalancesMap(),
                totalCurrencyBalance.getFeesMap(),
                totalCurrencyBalance.getAdjustmentsMap(),
                totalCurrencyBalance.getSuspendsMap())
                .values().stream().allMatch(amount -> amount == 0L);
    }

    public static Map<Integer, Long> mergeSum(final Map<Integer, Long>... maps) {
        Map<Integer, Long> res = new HashMap<>();
        for (Map<Integer, Long> map : maps) {
            if (map != null) {
                map.forEach((key, value) -> res.merge(key, value, Long::sum));
            }
        }
        return res;
    }
}
