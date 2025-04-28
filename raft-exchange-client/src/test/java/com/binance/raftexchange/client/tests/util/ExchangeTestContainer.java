package com.binance.raftexchange.client.tests.util;

import com.binance.raftexchange.client.Api.ApiStream;
import com.binance.raftexchange.client.Api.ExchangeClient;
import com.binance.raftexchange.stubs.SymbolType;
import com.binance.raftexchange.stubs.report.SingleUserReportResult;
import com.binance.raftexchange.stubs.report.TotalCurrencyBalanceReportResult;
import com.binance.raftexchange.stubs.request.ApiAddUser;
import com.binance.raftexchange.stubs.request.ApiAdjustUserBalance;
import com.binance.raftexchange.stubs.request.ApiBinaryDataCommand;
import com.binance.raftexchange.stubs.request.ApiCommand;
import com.binance.raftexchange.stubs.request.ApiNop;
import com.binance.raftexchange.stubs.request.BatchAddSymbolsCommand;
import com.binance.raftexchange.stubs.request.BinaryDataCommand;
import com.binance.raftexchange.stubs.request.CoreSymbolSpecification;
import com.binance.raftexchange.stubs.response.CommandResult;
import com.binance.raftexchange.stubs.response.CommandResultCode;
import com.binance.raftexchange.stubs.response.L2MarketData;
import com.binance.raftexchange.stubs.response.OrderCommand;
import com.google.common.collect.Lists;
import io.grpc.stub.StreamObserver;
import lombok.Builder;
import lombok.Data;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.hamcrest.core.Is;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.ObjLongConsumer;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.Assert.assertEquals;

@Slf4j
public final class ExchangeTestContainer implements AutoCloseable {

    private AtomicLong uniqueIdCounterLong = new AtomicLong();
    private AtomicInteger uniqueIdCounterInt = new AtomicInteger();

    private final ExchangeClient exchangeClient;
    private final boolean waitAllResponse; // 是否等待所有rpc响应

    @Setter
    private ObjLongConsumer<OrderCommand> consumer = (cmd, seq) -> {
    };

    public static final Consumer<OrderCommand> CHECK_SUCCESS = cmd -> assertEquals(CommandResultCode.SUCCESS, cmd.getResultCode());

    public static String timeBasedExchangeId() {
        return String.format("%012X", System.currentTimeMillis());
    }

    public static ExchangeTestContainer create(ExchangeClient exchangeClient, boolean waitAllResponse) {
        return new ExchangeTestContainer(exchangeClient, waitAllResponse);
    }


    public static TestDataFutures prepareTestDataAsync(TestDataParameters parameters, int seed) {

        final CompletableFuture<List<CoreSymbolSpecification>> coreSymbolSpecificationsFuture = CompletableFuture.supplyAsync(
                () -> ExchangeTestContainer.generateRandomSymbols(parameters.numSymbols, parameters.currenciesAllowed, parameters.allowedSymbolTypes));

        final CompletableFuture<List<BitSet>> usersAccountsFuture = CompletableFuture.supplyAsync(
                () -> UserCurrencyAccountsGenerator.generateUsers(parameters.numAccounts, parameters.currenciesAllowed));

        final CompletableFuture<TestOrdersGenerator.MultiSymbolGenResult> genResultFuture = coreSymbolSpecificationsFuture.thenCombineAsync(
                usersAccountsFuture,
                (css, ua) -> TestOrdersGenerator.generateMultipleSymbols(
                        TestOrdersGeneratorConfig.builder()
                                .coreSymbolSpecifications(css)
                                .totalTransactionsNumber(parameters.totalTransactionsNumber)
                                .usersAccounts(ua)
                                .targetOrderBookOrdersTotal(parameters.targetOrderBookOrdersTotal)
                                .seed(seed)
                                .preFillMode(parameters.preFillMode)
                                .avalancheIOC(parameters.avalancheIOC)
                                .build()));

        return TestDataFutures.builder()
                .coreSymbolSpecifications(coreSymbolSpecificationsFuture)
                .usersAccounts(usersAccountsFuture)
                .genResult(genResultFuture)
                .build();
    }

    @Data
    @Builder
    public static class TestDataFutures {
        final CompletableFuture<List<CoreSymbolSpecification>> coreSymbolSpecifications;
        final CompletableFuture<List<BitSet>> usersAccounts;
        final CompletableFuture<TestOrdersGenerator.MultiSymbolGenResult> genResult;
    }

    private ExchangeTestContainer(ExchangeClient exchangeClient, boolean waitAllResponse) {
        this.exchangeClient = exchangeClient;
        this.waitAllResponse = waitAllResponse;
    }

    public void initBasicSymbols() {
    }

    public void initFeeSymbols() {
    }

    public void initBasicUsers() {
    }

    public void initFeeUsers() {
    }

    public void initBasicUser(long uid) {
    }

    public void initFeeUser(long uid) {
    }

    public void createUserWithMoney(long uid, int currency, long amount) {

    }

    public void addMoneyToUser(long uid, int currency, long amount) {

    }


    public void addSymbol(final CoreSymbolSpecification symbol) {

    }

    public void addSymbols(final List<CoreSymbolSpecification> symbols) {
        // split by chunks
        Lists.partition(symbols, 10000).forEach(partition -> {
            Map<Integer, CoreSymbolSpecification> symbolSpecificationMap = partition.stream().collect(Collectors.toMap(CoreSymbolSpecification::getSymbolId, v -> v));
            BinaryDataCommand batchAddSymbolsCommand = BinaryDataCommand.newBuilder().setAddSymbols(BatchAddSymbolsCommand.newBuilder().putAllSymbols(symbolSpecificationMap)).build();
            sendBinaryDataCommandSync(batchAddSymbolsCommand, 10_000);
        });
    }

    // waitAllResponse=false时用这个，性能好
    public static ApiStream newApiStream(ExchangeClient exchangeClient, CompletableFuture<CommandResult> futures) {
        return exchangeClient.createStream(new StreamObserver<CommandResult>() {

            @Override
            public void onNext(CommandResult commandResult) {
                futures.complete(commandResult);
            }

            @Override
            public void onError(Throwable throwable) {
                futures.completeExceptionally(throwable);
            }

            @Override
            public void onCompleted() {
            }
        });
    }

    public static ApiStream newApiStream(ExchangeClient exchangeClient, BlockingQueue<CommandResult> futures) {
        return exchangeClient.createStream(new StreamObserver<CommandResult>() {

            @Override
            public void onNext(CommandResult commandResult) {
                futures.add(commandResult);
            }

            @Override
            public void onError(Throwable throwable) {
                throw new RuntimeException(throwable);
            }

            @Override
            public void onCompleted() {
            }
        });
    }

    public void sendBinaryDataCommandSync(final BinaryDataCommand data, final int timeOutMs) {
        if (waitAllResponse) {
            BlockingQueue<CommandResult> futures = new LinkedBlockingQueue<>();
            try (ApiStream apiStream = newApiStream(exchangeClient, futures)) {
                apiStream.onNext(ApiCommand.newBuilder().setBinaryData(ApiBinaryDataCommand.newBuilder().setData(data)).build());
                CommandResult result = futures.poll(timeOutMs, TimeUnit.MILLISECONDS);
                assertThat(result.getResultCode(), Is.is(CommandResultCode.SUCCESS));
            } catch (final Exception ex) {
                log.error("Failed sending binary data command", ex);
                throw new RuntimeException(ex);
            }
        } else {
            CompletableFuture<CommandResult> futures = new CompletableFuture<>();
            try (ApiStream apiStream = newApiStream(exchangeClient, futures)) {
                apiStream.onNext(ApiCommand.newBuilder().setBinaryData(ApiBinaryDataCommand.newBuilder().setData(data)).build());
                assertThat(futures.get(timeOutMs, TimeUnit.MILLISECONDS).getResultCode(), Is.is(CommandResultCode.SUCCESS));
            } catch (final Exception ex) {
                log.error("Failed sending binary data command", ex);
                throw new RuntimeException(ex);
            }
        }
    }

    private int getRandomTransferId() {
        return uniqueIdCounterInt.incrementAndGet();
    }

    private long getRandomTransactionId() {
        return uniqueIdCounterLong.incrementAndGet();
    }

    public final void userAccountsInit(List<BitSet> userCurrencies) {

        // calculate max amount can transfer to each account so that it is not possible to get long overflow
        final Map<Integer, Long> accountsNumPerCurrency = new HashMap<>();
        userCurrencies.forEach(accounts -> accounts.stream().forEach(currency -> accountsNumPerCurrency.merge(currency, 1L, Long::sum)));
        final Map<Integer, Long> amountPerAccount = new HashMap<>();
        accountsNumPerCurrency.forEach((currency, numAcc) -> amountPerAccount.put(currency, Long.MAX_VALUE / (numAcc + 1)));
        // amountPerAccount.forEachKeyValue((k, v) -> log.debug("{}={}", k, v));

        createUserAccountsRegular(userCurrencies, amountPerAccount);
    }


    private void createUserAccountsRegular(List<BitSet> userCurrencies, Map<Integer, Long> amountPerAccount) {
        final int numUsers = userCurrencies.size() - 1;
        if (waitAllResponse) {
            BlockingQueue<CommandResult> futures = new LinkedBlockingQueue<>();
            final int BATCH_SIZE = 2000;
            AtomicInteger reqCount = new AtomicInteger();
            try (ApiStream apiStream = newApiStream(exchangeClient, futures)) {
                IntStream.rangeClosed(1, numUsers).forEach(uid -> {
                    int actualUid = TestConstants.UID_AUTOGENERATED_RANGE_START + uid;
                    apiStream.onNext(ApiCommand.newBuilder().setAddUser(ApiAddUser.newBuilder().setUid(actualUid)).build());
                    reqCount.getAndIncrement();
                    userCurrencies.get(uid).stream().forEach(currency -> {
                        apiStream.onNext(ApiCommand.newBuilder().setAdjustBalance(ApiAdjustUserBalance.newBuilder()
                                .setUid(actualUid)
                                .setTransactionId(getRandomTransactionId())
                                .setAmount(amountPerAccount.get(currency))
                                .setCurrency(currency)
                        ).build());
                        if (reqCount.incrementAndGet() >= BATCH_SIZE) {
                            try {
                                waitResult(futures, reqCount.getAndSet(0));
                            } catch (InterruptedException e) {
                                throw new RuntimeException(e);
                            }
                        }
                    });
                });
                apiStream.onNext(ApiCommand.newBuilder().setNop(ApiNop.newBuilder()).build());
                reqCount.getAndIncrement();
                waitResult(futures, reqCount.get());
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        } else {
            CompletableFuture<CommandResult> futures = new CompletableFuture<>();
            try (ApiStream apiStream = newApiStream(exchangeClient, futures)) {
                IntStream.rangeClosed(1, numUsers).forEach(uid -> {
                    apiStream.onNext(ApiCommand.newBuilder().setAddUser(ApiAddUser.newBuilder().setUid(uid)).build());
                    userCurrencies.get(uid).stream().forEach(currency -> apiStream.onNext(ApiCommand.newBuilder().setAdjustBalance(ApiAdjustUserBalance.newBuilder()
                            .setUid(uid)
                            .setTransactionId(getRandomTransactionId())
                            .setAmount(amountPerAccount.get(currency))
                            .setCurrency(currency)
                    ).build()));
                });
                apiStream.onNext(ApiCommand.newBuilder().setNop(ApiNop.newBuilder()).build());
                futures.get();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
    }

    private void waitResult(BlockingQueue<CommandResult> futures, int num) throws InterruptedException {
        for (int i = 0; i < num; i++) {
            futures.take();
        }
    }

    public void usersInit(int numUsers, Set<Integer> currencies) {

    }


    public void submitCommandSync(ApiCommand apiCommand, CommandResultCode expectedResultCode) {


    }

    public void submitCommandSync(ApiCommand apiCommand, Consumer<OrderCommand> validator) {
    }

    public L2MarketData requestCurrentOrderBook(final int symbol) {
        return null;
    }

    public void validateUserState(long uid, Consumer<SingleUserReportResult> resultValidator) throws InterruptedException, ExecutionException {
    }

    public SingleUserReportResult getUserProfile(long clientId) throws InterruptedException, ExecutionException {
        return null;
    }

    public TotalCurrencyBalanceReportResult totalBalanceReport() {
        CompletableFuture<TotalCurrencyBalanceReportResult> future = exchangeClient.totalCurrencyBalanceReport(getRandomTransferId());
        try {
            TotalCurrencyBalanceReportResult res = future.get();
            Map<Integer, Long> openInterestLong = res.getOpenInterestLongMap();
            Map<Integer, Long> openInterestShort = res.getOpenInterestShortMap();
            Map<Integer, Long> openInterestDiff = new HashMap<>(openInterestLong);
            openInterestShort.forEach((k, v) -> openInterestDiff.merge(k, -v, Long::sum));
            if (openInterestDiff.values().stream().anyMatch(vol -> vol != 0)) {
                throw new IllegalStateException("Open Interest balance check failed");
            }

            return res;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public int requestStateHash() throws InterruptedException, ExecutionException {
        return 0;
    }

    public static List<CoreSymbolSpecification> generateRandomSymbols(final int num,
                                                                      final Collection<Integer> currenciesAllowed,
                                                                      final AllowedSymbolTypes allowedSymbolTypes) {
        final Random random = new Random(1L);

        final Supplier<SymbolType> symbolTypeSupplier;

        switch (allowedSymbolTypes) {
            case FUTURES_CONTRACT:
                symbolTypeSupplier = () -> SymbolType.FUTURES_CONTRACT;
                break;

            case CURRENCY_EXCHANGE_PAIR:
                symbolTypeSupplier = () -> SymbolType.CURRENCY_EXCHANGE_PAIR;
                break;

            case BOTH:
            default:
                symbolTypeSupplier = () -> random.nextBoolean() ? SymbolType.FUTURES_CONTRACT : SymbolType.CURRENCY_EXCHANGE_PAIR;
                break;
        }

        final List<Integer> currencies = new ArrayList<>(currenciesAllowed);
        final List<CoreSymbolSpecification> result = new ArrayList<>();
        for (int i = 0; i < num; ) {
            int baseCurrency = currencies.get(random.nextInt(currencies.size()));
            int quoteCurrency = currencies.get(random.nextInt(currencies.size()));
            if (baseCurrency != quoteCurrency) {
                final SymbolType type = symbolTypeSupplier.get();
                final long makerFee = random.nextInt(1000);
                final long takerFee = makerFee + random.nextInt(500);
                final CoreSymbolSpecification symbol = CoreSymbolSpecification.newBuilder()
                        .setSymbolId(TestConstants.SYMBOL_AUTOGENERATED_RANGE_START + i)
                        .setType(type)
                        .setBaseCurrency(baseCurrency) // TODO for futures can be any value
                        .setQuoteCurrency(quoteCurrency)
                        .setBaseScaleK(100)
                        .setQuoteScaleK(10)
                        .setTakerFee(takerFee)
                        .setMakerFee(makerFee) // TODO margins for futures?
                        .build();

                result.add(symbol);

                //log.debug("{}", symbol);
                i++;
            }
        }
        return result;
    }

    public void loadSymbolsUsersAndPrefillOrders(TestDataFutures testDataFutures) {

    }

    public void loadSymbolsUsersAndPrefillOrdersNoLog(TestDataFutures testDataFutures) {

        // load symbols
        addSymbols(testDataFutures.coreSymbolSpecifications.join());

        // create accounts and deposit initial funds
        userAccountsInit(testDataFutures.usersAccounts.join());

        if (waitAllResponse) {
            BlockingQueue<CommandResult> futures = new LinkedBlockingQueue<>();
            try (ApiStream apiStream = newApiStream(exchangeClient, futures)) {
                List<ApiCommand> apiCommands = testDataFutures.genResult.join().getApiCommandsFill().join();
                apiCommands.forEach(apiStream::onNext);
                waitResult(futures, apiCommands.size());
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        } else {
            CompletableFuture<CommandResult> futures = new CompletableFuture<>();
            try (ApiStream apiStream = newApiStream(exchangeClient, futures)) {
                List<ApiCommand> apiCommands = testDataFutures.genResult.join().getApiCommandsFill().join();
                apiCommands.forEach(apiStream::onNext);
                futures.get();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }

    }

    public <V> V executeTestingThread(final Callable<V> test) {
        try {
            final ExecutorService executor = Executors.newSingleThreadExecutor();
            final V result = executor.submit(test).get();
            executor.shutdown();
            executor.awaitTermination(3000, TimeUnit.SECONDS);
            return result;
        } catch (ExecutionException | InterruptedException ex) {
            throw new RuntimeException(ex);
        }
    }

    public float benchmarkMtps(final TestDataFutures testDataFutures) {
        List<ApiCommand> apiCommandsBenchmark = testDataFutures.getGenResult().join().apiCommandsBenchmark.join();

        long tStart = System.currentTimeMillis();
        final int BATCH_SIZE = 20000;
        if (waitAllResponse) {
//            int userCount = testDataFutures.getUsersAccounts().join().size();
            int userCount = 50; //创建太多也不好
            List<ExchangeClient> clients = IntStream.range(0, userCount).mapToObj(i -> new ExchangeClient("localhost", 5001)).collect(Collectors.toList());
            List<List<ApiCommand>> groupCmds = Lists.partition(apiCommandsBenchmark, apiCommandsBenchmark.size() / userCount);
            CountDownLatch countDownLatch = new CountDownLatch(userCount);

            tStart = System.currentTimeMillis();

            ExecutorService executor = Executors.newFixedThreadPool(userCount);

            IntStream.range(0, userCount).forEach(i -> {
                executor.submit(() -> {
                    BlockingQueue<CommandResult> futures = new LinkedBlockingQueue<>();
                    ExchangeClient client = clients.get(i);
                    List<ApiCommand> cmdOnEachClient = groupCmds.get(i);
                    try (ApiStream apiStream = newApiStream(client, futures)) {
                        AtomicInteger count = new AtomicInteger();
                        for (ApiCommand cmd : cmdOnEachClient) {
                            apiStream.onNext(cmd);
                            if (count.incrementAndGet() >= BATCH_SIZE) {
                                waitResult(futures, count.getAndSet(0));
                            }
                        }
                        waitResult(futures, count.get());
                        countDownLatch.countDown();
                        client.close();
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                });
            });
            try {
                countDownLatch.await();
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
        } else {
            // 只等一个的版本
            CompletableFuture<CommandResult> futures = new CompletableFuture<>();
            try (ApiStream apiStream = newApiStream(exchangeClient, futures)) {
                apiCommandsBenchmark.forEach(apiStream::onNext);
                futures.get();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }

        final long tDuration = System.currentTimeMillis() - tStart;
        return apiCommandsBenchmark.size() / (float) tDuration / 1000.0f;
    }

    @Override
    public void close() {
        try {
            exchangeClient.close();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public enum AllowedSymbolTypes {
        FUTURES_CONTRACT,
        CURRENCY_EXCHANGE_PAIR,
        BOTH
    }
}
