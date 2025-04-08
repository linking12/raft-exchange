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

import static com.binance.raftexchange.client.tests.util.ExchangeTestContainer.newApiStream;

import com.binance.raftexchange.client.Api.ApiStream;
import com.binance.raftexchange.client.Api.ExchangeClient;
import com.binance.raftexchange.stubs.request.ApiCommand;
import com.binance.raftexchange.stubs.response.CommandResult;
import com.google.common.collect.Lists;
import lombok.extern.slf4j.Slf4j;
import org.HdrHistogram.Histogram;
import org.HdrHistogram.SingleWriterRecorder;

import java.util.*;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.function.BiFunction;
import java.util.stream.IntStream;



@Slf4j
public class LatencyTestsModule {

    private static final boolean WRITE_HDR_HISTOGRAMS = false;

    private static final ExchangeClient exchangeClient = new ExchangeClient("127.0.0.1", 5001);

    public static void latencyTestImpl(final TestDataParameters testDataParameters,
                                       final int warmupCycles) {

        final int targetTps = 1000_000; // transactions per second
        final int targetTpsStep = 100_000;

        final int warmupTps = 10_000_000;

        final ExchangeTestContainer.TestDataFutures testDataFutures = ExchangeTestContainer.prepareTestDataAsync(testDataParameters, 1);

        try (final ExchangeTestContainer container = ExchangeTestContainer.create(exchangeClient,  true)) {

            final SingleWriterRecorder hdrRecorder = new SingleWriterRecorder(Integer.MAX_VALUE, 2);

            // TODO - first run should validate the output (orders are accepted and processed properly)

            final BiFunction<Integer, Boolean, Boolean> testIteration = (tps, warmup) -> {
                try {
                    container.loadSymbolsUsersAndPrefillOrdersNoLog(testDataFutures);

                    final TestOrdersGenerator.MultiSymbolGenResult genResult = testDataFutures.genResult.join();


                    final long startTimeMs = System.currentTimeMillis();

                    // run one by one
                    int count = 10000;
                    final CountDownLatch latchBenchmark = new CountDownLatch(count);
                    List<ApiCommand> apiCommandsBenchmark = testDataFutures.getGenResult().join().apiCommandsBenchmark.join();
                    List<List<ApiCommand>> groupCmds = Lists.partition(apiCommandsBenchmark, apiCommandsBenchmark.size() / count);

                    ExchangeClient client = new ExchangeClient("127.0.0.1", 5001);
                    IntStream.range(0, count).parallel().forEach(i -> {
                        BlockingQueue<CommandResult> futures = new LinkedBlockingQueue<>();
                        List<ApiCommand> cmdOnEachClient = groupCmds.get(i);
                        long start = System.nanoTime();
                        try (ApiStream apiStream = newApiStream(client, futures)) {
                            cmdOnEachClient.forEach(apiStream::onNext);
                            waitResult(futures, cmdOnEachClient.size());
                        } catch (Exception e) {
                            throw new RuntimeException(e);
                        } finally {
                            long latency = System.nanoTime() - start;
                            hdrRecorder.recordValue(Math.min(latency, Integer.MAX_VALUE));
                            latchBenchmark.countDown();
                        }
                    });

                    latchBenchmark.await();
                    client.close();

                    final long processingTimeMs = System.currentTimeMillis() - startTimeMs;
                    final float perfMt = (float) genResult.getBenchmarkCommandsSize() / (float) processingTimeMs / 1000.0f;
                    String tag = String.format("%.3f MT/s", perfMt);
                    final Histogram histogram = hdrRecorder.getIntervalHistogram();
                    log.info("{} {}", tag, LatencyTools.createLatencyReportFast(histogram));

                    // compare orderBook final state just to make sure all commands executed same way
//                    testDataFutures.coreSymbolSpecifications.join().forEach(
//                            symbol -> assertEquals(
//                                    testDataFutures.getGenResult().join().getGenResults().get(symbol.getSymbolId()),
//                                    container.requestCurrentOrderBook(symbol.getSymbolId())));

                    // TODO compare events, balances, positions

                    System.gc();
                    Thread.sleep(500);

                    // stop testing if median latency above 1 millisecond
                    return true;
                } catch (Exception ex) {
                    throw new IllegalStateException(ex);
                }
            };

            container.executeTestingThread(() -> {
                return testIteration.apply(warmupTps, true);
//                IntStream.range(0, 1)
//                        .forEach(i -> testIteration.apply(warmupTps, true));
//
//                return IntStream.range(0, 10000)
//                        .map(i -> targetTps + targetTpsStep * i)
//                        .mapToObj(tps -> testIteration.apply(tps, false))
//                        .allMatch(x -> x);
            });
        }
    }

    private static void waitResult(BlockingQueue<CommandResult> futures, int num) throws InterruptedException {
        for (int i = 0; i < num; i++) {
            futures.take();
        }
    }

}


