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
package exchange.core2.tests.util;

import com.google.common.collect.Lists;
import exchange.core2.core.ExchangeApi;
import exchange.core2.core.ExchangeCore;
import exchange.core2.core.common.*;
import exchange.core2.core.common.api.*;
import exchange.core2.core.common.api.binary.BatchAddCurrenciesCommand;
import exchange.core2.core.common.api.binary.BatchAddSymbolsCommand;
import exchange.core2.core.common.api.binary.BinaryDataCommand;
import exchange.core2.core.common.api.reports.*;
import exchange.core2.core.common.cmd.CommandResultCode;
import exchange.core2.core.common.cmd.OrderCommand;
import exchange.core2.core.common.config.*;
import exchange.core2.core.event.IEventsHandler4Test;
import exchange.core2.core.event.SimpleEventsProcessor4Test;
import exchange.core2.core.utils.AffinityThreadFactory;
import lombok.Builder;
import lombok.Data;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.collections.impl.map.mutable.primitive.IntLongHashMap;
import org.eclipse.collections.impl.map.sorted.mutable.TreeSortedMap;
import org.hamcrest.core.Is;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.ObjLongConsumer;
import java.util.function.Supplier;
import java.util.stream.IntStream;
import java.util.stream.LongStream;

import static exchange.core2.tests.util.TestConstants.*;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Slf4j
public final class ExchangeTestContainer implements AutoCloseable {

    @Getter
    private final ExchangeCore exchangeCore;

    @Getter
    private final ExchangeApi api;

    @Getter
    private final AffinityThreadFactory threadFactory;

    private AtomicLong uniqueIdCounterLong = new AtomicLong();
    private AtomicInteger uniqueIdCounterInt = new AtomicInteger();

    @Setter
    private ObjLongConsumer<OrderCommand> consumer = new SimpleEventsProcessor4Test(new IEventsHandler4Test() {
        @Override
        public void orderBook(OrderBook orderBook) {

        }

        @Override
        public void spotExecutionReport(SpotExecutionReport executionReport) {

        }

        @Override
        public void futuresExecutionReport(FuturesExecutionReport executionReport) {

        }

        @Override
        public void fundEventReport(FundEventReport fundEventReport) {

        }
    });

    public static final Consumer<OrderCommand> CHECK_SUCCESS = cmd -> assertEquals(CommandResultCode.SUCCESS, cmd.resultCode);

    public static String timeBasedExchangeId() {
        return String.format("%012X", System.currentTimeMillis());
    }

    public static ExchangeTestContainer create(final PerformanceConfiguration perfCfg) {
        return new ExchangeTestContainer(perfCfg,
                InitialStateConfiguration.CLEAN_TEST,
                SerializationConfiguration.DEFAULT, null);
    }

    public static ExchangeTestContainer create(final PerformanceConfiguration perfCfg, ObjLongConsumer<OrderCommand> consumer) {
        return new ExchangeTestContainer(perfCfg,
                InitialStateConfiguration.CLEAN_TEST,
                SerializationConfiguration.DEFAULT, consumer);
    }

    public static ExchangeTestContainer create(final PerformanceConfiguration perfCfg,
                                               final InitialStateConfiguration initStateCfg,
                                               final SerializationConfiguration serializationCfg) {
        return new ExchangeTestContainer(perfCfg, initStateCfg, serializationCfg, null);
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

    public void doDeposit(List<Long> userIds, Set<Integer> symbolIds, long deposit) {
        symbolIds.forEach(symbolId -> userIds.forEach(userId -> createUserWithMoney(userId, symbolId, deposit)));
    }

    public void initMarkPrice(int symbol, long price) {
        ApiAdjustMarkPrice cmd = ApiAdjustMarkPrice.builder()
                .transactionId(getRandomTransactionId())
                .markPrice(price)
                .symbol(symbol)
                .build();
        api.submitCommand(cmd);
    }

    @Data
    @Builder
    public static class TestDataFutures {
        final CompletableFuture<List<CoreSymbolSpecification>> coreSymbolSpecifications;
        final CompletableFuture<List<BitSet>> usersAccounts;
        final CompletableFuture<TestOrdersGenerator.MultiSymbolGenResult> genResult;
    }

    private ExchangeTestContainer(final PerformanceConfiguration perfCfg,
                                  final InitialStateConfiguration initStateCfg,
                                  final SerializationConfiguration serializationCfg,
                                  final ObjLongConsumer<OrderCommand> consumer) {

        //log.debug("CREATING exchange container");

        this.threadFactory = new AffinityThreadFactory(AffinityThreadFactory.ThreadAffinityMode.THREAD_AFFINITY_ENABLE_PER_PHYSICAL_CORE, "Exchange-Core");

        final ExchangeConfiguration exchangeConfiguration = ExchangeConfiguration.defaultBuilder()
                .initStateCfg(initStateCfg)
                .performanceCfg(perfCfg)
                .reportsQueriesCfg(ReportsQueriesConfiguration.createStandardConfig())
                .ordersProcessingCfg(OrdersProcessingConfiguration.DEFAULT)
                .loggingCfg(LoggingConfiguration.DEFAULT)
                .serializationCfg(serializationCfg)
                .build();

        this.exchangeCore = ExchangeCore.builder()
                .resultsConsumer(consumer == null ? this.consumer : consumer)
                .exchangeConfiguration(exchangeConfiguration)
                .build();

        //log.debug("STARTING exchange container");
        this.exchangeCore.startup();

        //log.debug("STARTED exchange container");
        this.api = this.exchangeCore.getApi();
    }

    public void initBasicSymbols() {
        addCurrency(SYMBOLSPEC_EUR_USD.baseCurrency, 0);
        addCurrency(SYMBOLSPEC_EUR_USD.quoteCurrency, 1);
        addSymbol(TestConstants.SYMBOLSPEC_EUR_USD);

        addCurrency(SYMBOLSPEC_ETH_XBT.baseCurrency, 0);
        addCurrency(SYMBOLSPEC_ETH_XBT.quoteCurrency, 1);
        addSymbol(TestConstants.SYMBOLSPEC_ETH_XBT);
    }

    public void initBasicSymbolsWithDigit(int digit) {
        addCurrency(SYMBOLSPEC_EUR_USD.baseCurrency, digit);
        addCurrency(SYMBOLSPEC_EUR_USD.quoteCurrency, digit);
        addSymbol(TestConstants.SYMBOLSPEC_EUR_USD);

        addCurrency(SYMBOLSPEC_ETH_XBT.baseCurrency, digit);
        addCurrency(SYMBOLSPEC_ETH_XBT.quoteCurrency, digit);
        addSymbol(TestConstants.SYMBOLSPEC_ETH_XBT);
    }

    public void initMarkPrices() {
        initMarkPrice(SYMBOLSPEC_EUR_USD.symbolId, 1000);
        initMarkPrice(SYMBOLSPEC_ETH_XBT.symbolId, 1000);
    }

    public void initFeeSymbols() {
        addCurrency(SYMBOLSPECFEE_XBT_LTC.baseCurrency, 0);
        addCurrency(SYMBOLSPECFEE_XBT_LTC.quoteCurrency, 0);
        addSymbol(TestConstants.SYMBOLSPECFEE_XBT_LTC);

        addCurrency(SYMBOLSPECFEE_USD_JPY.baseCurrency, 0);
        addCurrency(SYMBOLSPECFEE_USD_JPY.quoteCurrency, 0);
        addSymbol(TestConstants.SYMBOLSPECFEE_USD_JPY);
    }

    public void initFeeSymbol_Xbt_Ltc() {
        addSymbol(TestConstants.SYMBOLSPECFEE_XBT_LTC);
    }

    public void initFeeSymbolsMarkPrice() {
        initMarkPrice(SYMBOLSPECFEE_USD_JPY.symbolId, 10000);
    }

    public void initDynamicFeeSymbols() {
        addCurrency(SYMBOLSPEC_DYNAMIC_FEE_XBT_LTC.baseCurrency, 0);
        addCurrency(SYMBOLSPEC_DYNAMIC_FEE_XBT_LTC.quoteCurrency, 0);
        addSymbol(TestConstants.SYMBOLSPEC_DYNAMIC_FEE_XBT_LTC);

        addCurrency(SYMBOLSPEC_DYNAMIC_FEE_XBT_USD.baseCurrency, 0);
        addCurrency(SYMBOLSPEC_DYNAMIC_FEE_XBT_USD.quoteCurrency, 0);
        addSymbol(TestConstants.SYMBOLSPEC_DYNAMIC_FEE_XBT_USD);
    }

    public void initDynamicFeeSymbolsMarkPrice() {
        initMarkPrice(SYMBOLSPEC_DYNAMIC_FEE_XBT_USD.symbolId, 1000);
    }

    public void initBasicUsers() {
        initBasicUser(TestConstants.UID_1);
        initBasicUser(TestConstants.UID_2);
        initBasicUser(TestConstants.UID_3);
        initBasicUser(TestConstants.UID_4);
    }

    public void initFeeUsers() {
        initFeeUser(TestConstants.UID_1);
        initFeeUser(TestConstants.UID_2);
        initFeeUser(TestConstants.UID_3);
        initFeeUser(TestConstants.UID_4);
    }

    public CoreSymbolSpecification initFutureSymbol(int symbolId, int quoteId) {
        CoreSymbolSpecification futuresSymbol = CoreSymbolSpecification.builder()
                .symbolId(symbolId)
                .type(SymbolType.FUTURES_CONTRACT_PERPETUAL)
                .baseCurrency(BASE_CURRENCY_ID)
                .quoteCurrency(quoteId)
                .baseScaleK(1)
                .quoteScaleK(1)
                .makerFee(10)
                .takerFee(20)
                .maintenanceMargin(TreeSortedMap.newMapWith(1000L, 5L, 100000L, 10L))
                .maintenanceMarginScaleK(1000)
                .maxLeverage(TreeSortedMap.newMapWith(2000L, 5L, 100000L, 10L))
                .initMargin(1)
                .initMarginScaleK(100)
                .build();

        api.submitBinaryDataAsync(new BatchAddSymbolsCommand(futuresSymbol));
        return futuresSymbol;
    }

    private void initCurrencies() {
        CoreCurrencySpecification btc = CoreCurrencySpecification.builder().id(CURRENECY_XBT).digit(0).build();
        CoreCurrencySpecification usdt = CoreCurrencySpecification.builder().id(CURRENECY_USD).digit(0).build();
        CoreCurrencySpecification eth = CoreCurrencySpecification.builder().id(CURRENECY_ETH).digit(0).build();
        CoreCurrencySpecification ltc = CoreCurrencySpecification.builder().id(CURRENECY_LTC).digit(0).build();

        api.submitBinaryDataAsync(new BatchAddCurrenciesCommand(btc));
        api.submitBinaryDataAsync(new BatchAddCurrenciesCommand(usdt));
        api.submitBinaryDataAsync(new BatchAddCurrenciesCommand(eth));
        api.submitBinaryDataAsync(new BatchAddCurrenciesCommand(ltc));
    }

    public List<CoreSymbolSpecification> initFutureSymbols() {
        List<CoreSymbolSpecification> ret = new ArrayList<>();
        // BTC_USDT
        CoreSymbolSpecification futuresSymbol1 = CoreSymbolSpecification.builder()
                .symbolId(10000)
                .type(SymbolType.FUTURES_CONTRACT_PERPETUAL)
                .baseCurrency(CURRENECY_XBT)
                .quoteCurrency(CURRENECY_USD)
                .baseScaleK(1)
                .quoteScaleK(1)
                .makerFee(10)
                .takerFee(20)
                .maintenanceMargin(TreeSortedMap.newMapWith(1000L, 5L, 100000L, 10L))
                .maintenanceMarginScaleK(1000)
                .maxLeverage(TreeSortedMap.newMapWith(2000L, 5L, 100000L, 10L))
                .initMargin(1)
                .initMarginScaleK(100)
//                .marginBuy(100)
//                .marginSell(100)
//                .maxLeverage(10)
//                .maintenanceMargin(50)
                .build();
        // ETH_USDT
        CoreSymbolSpecification futuresSymbol2 = CoreSymbolSpecification.builder()
                .symbolId(10001)
                .type(SymbolType.FUTURES_CONTRACT_PERPETUAL)
                .baseCurrency(CURRENECY_ETH)
                .quoteCurrency(CURRENECY_USD)
                .baseScaleK(1)
                .quoteScaleK(1)
                .makerFee(1)
                .takerFee(2)
                .feeScaleK(100)
                .maintenanceMargin(TreeSortedMap.newMapWith(1000L, 5L, 100000L, 10L))
                .maintenanceMarginScaleK(1000)
                .maxLeverage(TreeSortedMap.newMapWith(2000L, 5L, 100000L, 10L))
                .initMargin(1)
                .initMarginScaleK(100)
//                .marginBuy(100)
//                .marginSell(100)
//                .maxLeverage(20)
//                .maintenanceMargin(100)
                .build();
        // LTC_USDT
        CoreSymbolSpecification futuresSymbol3 = CoreSymbolSpecification.builder()
                .symbolId(10002)
                .type(SymbolType.FUTURES_CONTRACT_PERPETUAL)
                .baseCurrency(CURRENECY_LTC)
                .quoteCurrency(CURRENECY_USD)
                .baseScaleK(1)
                .quoteScaleK(1)
                .makerFee(20)
                .takerFee(30)
                .maintenanceMargin(TreeSortedMap.newMapWith(1000L, 5L, 100000L, 10L))
                .maintenanceMarginScaleK(1000)
                .maxLeverage(TreeSortedMap.newMapWith(2000L, 5L, 100000L, 10L))
                .initMargin(1)
                .initMarginScaleK(100)
//                .marginBuy(100)
//                .marginSell(100)
//                .maxLeverage(50)
//                .maintenanceMargin(200)
                .build();

        api.submitBinaryDataAsync(new BatchAddSymbolsCommand(futuresSymbol1));
        api.submitBinaryDataAsync(new BatchAddSymbolsCommand(futuresSymbol2));
        api.submitBinaryDataAsync(new BatchAddSymbolsCommand(futuresSymbol3));

        ret.add(futuresSymbol1);
        ret.add(futuresSymbol2);
        ret.add(futuresSymbol3);

        initCurrencies();
        return ret;
    }

    public List<CoreSymbolSpecification> initPerpetualSymbols() {
        return this.initFutureSymbols();
    }

    public List<CoreSymbolSpecification> initDeliverySymbols() {
        List<CoreSymbolSpecification> ret = new ArrayList<>();
        // BTC_USDT
        CoreSymbolSpecification futuresSymbol1 = CoreSymbolSpecification.builder()
                .symbolId(10100)
                .type(SymbolType.FUTURES_CONTRACT_DELIVERY)
                .baseCurrency(CURRENECY_XBT)
                .quoteCurrency(CURRENECY_USD)
                .baseScaleK(1)
                .quoteScaleK(1)
                .makerFee(10)
                .takerFee(20)
                .maintenanceMargin(TreeSortedMap.newMapWith(1000L, 5L, 100000L, 10L))
                .maxLeverage(TreeSortedMap.newMapWith(2000L, 5L, 100000L, 10L))
//                .marginBuy(100)
//                .marginSell(100)
//                .maxLeverage(10)
//                .maintenanceMargin(50)
                .build();
        // ETH_USDT
        CoreSymbolSpecification futuresSymbol2 = CoreSymbolSpecification.builder()
                .symbolId(10101)
                .type(SymbolType.FUTURES_CONTRACT_DELIVERY)
                .baseCurrency(CURRENECY_ETH)
                .quoteCurrency(CURRENECY_USD)
                .baseScaleK(1)
                .quoteScaleK(1)
                .makerFee(1)
                .takerFee(2)
                .feeScaleK(100)
                .maintenanceMargin(TreeSortedMap.newMapWith(1000L, 5L, 100000L, 10L))
                .maxLeverage(TreeSortedMap.newMapWith(2000L, 5L, 100000L, 10L))
//                .marginBuy(100)
//                .marginSell(100)
//                .maxLeverage(20)
//                .maintenanceMargin(100)
                .build();
        // LTC_USDT
        CoreSymbolSpecification futuresSymbol3 = CoreSymbolSpecification.builder()
                .symbolId(10102)
                .type(SymbolType.FUTURES_CONTRACT_DELIVERY)
                .baseCurrency(CURRENECY_LTC)
                .quoteCurrency(CURRENECY_USD)
                .baseScaleK(1)
                .quoteScaleK(1)
                .makerFee(20)
                .takerFee(30)
                .maintenanceMargin(TreeSortedMap.newMapWith(1000L, 5L, 100000L, 10L))
                .maxLeverage(TreeSortedMap.newMapWith(2000L, 5L, 100000L, 10L))
//                .marginBuy(100)
//                .marginSell(100)
//                .maxLeverage(50)
//                .maintenanceMargin(200)
                .build();

        api.submitBinaryDataAsync(new BatchAddSymbolsCommand(futuresSymbol1));
        api.submitBinaryDataAsync(new BatchAddSymbolsCommand(futuresSymbol2));
        api.submitBinaryDataAsync(new BatchAddSymbolsCommand(futuresSymbol3));
        ret.add(futuresSymbol1);
        ret.add(futuresSymbol2);
        ret.add(futuresSymbol3);

        initCurrencies();
        return ret;
    }

    public List<CoreSymbolSpecification> initExchangeSymbols() {
        List<CoreSymbolSpecification> ret = new ArrayList<>();
        // BTC_USDT
        CoreSymbolSpecification futuresSymbol1 = CoreSymbolSpecification.builder()
                .symbolId(10003)
                .type(SymbolType.CURRENCY_EXCHANGE_PAIR)
                .baseCurrency(CURRENECY_XBT)
                .quoteCurrency(CURRENECY_USD)
                .baseScaleK(1)
                .quoteScaleK(1)
                .makerFee(10)
                .takerFee(20)
                .maintenanceMargin(TreeSortedMap.newMapWith(1000L, 5L, 100000L, 10L))
                .maxLeverage(TreeSortedMap.newMapWith(2000L, 10L, 100000L, 5L))
//                .marginBuy(100)
//                .marginSell(100)
//                .maxLeverage(10)
//                .maintenanceMargin(50)
                .build();
        // ETH_USDT
        CoreSymbolSpecification futuresSymbol2 = CoreSymbolSpecification.builder()
                .symbolId(10004)
                .type(SymbolType.CURRENCY_EXCHANGE_PAIR)
                .baseCurrency(CURRENECY_ETH)
                .quoteCurrency(CURRENECY_USD)
                .baseScaleK(1)
                .quoteScaleK(1)
                .makerFee(1)
                .takerFee(2)
                .feeScaleK(100)
                .maintenanceMargin(TreeSortedMap.newMapWith(1000L, 5L, 100000L, 10L))
                .maxLeverage(TreeSortedMap.newMapWith(2000L, 5L, 100000L, 10L))
//                .marginBuy(100)
//                .marginSell(100)
//                .maxLeverage(20)
//                .maintenanceMargin(100)
                .build();
        // LTC_USDT
        CoreSymbolSpecification futuresSymbol3 = CoreSymbolSpecification.builder()
                .symbolId(10005)
                .type(SymbolType.CURRENCY_EXCHANGE_PAIR)
                .baseCurrency(CURRENECY_LTC)
                .quoteCurrency(CURRENECY_USD)
                .baseScaleK(1)
                .quoteScaleK(1)
                .makerFee(20)
                .takerFee(30)
                .maintenanceMargin(TreeSortedMap.newMapWith(1000L, 5L, 100000L, 10L))
                .maxLeverage(TreeSortedMap.newMapWith(2000L, 5L, 100000L, 10L))
//                .marginBuy(100)
//                .marginSell(100)
//                .maxLeverage(50)
//                .maintenanceMargin(200)
                .build();

        api.submitBinaryDataAsync(new BatchAddSymbolsCommand(futuresSymbol1));
        api.submitBinaryDataAsync(new BatchAddSymbolsCommand(futuresSymbol2));
        api.submitBinaryDataAsync(new BatchAddSymbolsCommand(futuresSymbol3));
        ret.add(futuresSymbol1);
        ret.add(futuresSymbol2);
        ret.add(futuresSymbol3);

        initCurrencies();
        return ret;
    }

    public void initOneUser(long uid) {
        assertThat(api.submitCommandAsync(ApiAddUser.builder().uid(uid).build()).join(), Is.is(CommandResultCode.SUCCESS));
    }

    public ApiPlaceOrder genOrderWithId(long orderId, long userId, int size, long price, int symbolId, OrderAction action, OrderType orderType) {
        return genOrder(orderId, userId, size, price, symbolId, action, orderType, MarginMode.ISOLATED);
    }

    public ApiPlaceOrder genOrder(long userId, int size, long price, int symbolId, OrderAction action, OrderType orderType) {
        return genOrder(userId, size, price, symbolId, action, orderType, MarginMode.ISOLATED);
    }

    public ApiPlaceOrder genOrder(long userId, int size, long price, int symbolId, OrderAction action, OrderType orderType, MarginMode marginMode) {
        long orderId = getRandomTransactionId();
        return genOrder(orderId, userId, size, price, symbolId, action, orderType, marginMode);
    }

    public ApiPlaceOrder genOrder(long orderId, long userId, int size, long price, int symbolId, OrderAction action, OrderType orderType, MarginMode marginMode) {
        return ApiPlaceOrder.builder()
                .uid(userId)
                .orderId(orderId)
                .action(action)
                .size(size)
                .price(price)
                .reservePrice(price)
                .symbol(symbolId)
                .orderType(orderType)
                .marginMode(marginMode)
                .build();
    }

    public long calculateFee(long price, long size, long step, long sideFee, long scale) {
        return price * size * step * sideFee / scale;
    }

    public long createBid(long userId, int size, long price, int symbolId) {
        long orderId = getRandomTransactionId();
        return createBidWithOrderId(orderId, userId, size, price, symbolId, MarginMode.ISOLATED);
    }

    public long createBid(long userId, int size, long price, int symbolId, MarginMode marginMode) {
        long orderId = getRandomTransactionId();
        return createBidWithOrderId(orderId, userId, size, price, symbolId, marginMode);
    }

    public long createBidWithOrderId(long orderId, long userId, int size, long price, int symbolId) {
        return createBidWithOrderId(orderId, userId, size, price, symbolId, MarginMode.ISOLATED);
    }

    public long createBidWithOrderId(long orderId, long userId, int size, long price, int symbolId, MarginMode marginMode) {
        ApiPlaceOrder order = ApiPlaceOrder.builder()
                .uid(userId)
                .orderId(orderId)
                .action(OrderAction.BID)
                .size(size)
                .price(price)
                .symbol(symbolId)
                .orderType(OrderType.GTC)
                .marginMode(marginMode)
                .build();
        try {
            api.submitCommandAsync(order).get();
        } catch (Exception e) {

        }
        return orderId;
    }

    public void cancelOrder(long userId, long orderId, int symbolId) {
        ApiCancelOrder order = ApiCancelOrder.builder()
                .uid(userId)
                .orderId(orderId)
                .symbol(symbolId)
                .build();
        try {
            api.submitCommandAsync(order).get();
        } catch (Exception e) {

        }
    }

    public void sleepSeconds(int time) {
        try {
            Thread.sleep(time * 1000L);
        } catch (InterruptedException e) {

        }
    }

    public long createAsk(long userId, int size, long price, int symbolId) {
        long orderId = getRandomTransactionId();
        return createAskWithOrderId(orderId, userId, size, price, symbolId, MarginMode.ISOLATED);
    }

    public long createAskWithOrderId(long orderId, long userId, int size, long price, int symbolId) {
        return createAskWithOrderId(orderId, userId, size, price, symbolId, MarginMode.ISOLATED);
    }

    public long createAskWithOrderId(long orderId, long userId, int size, long price, int symbolId, MarginMode mode) {
        ApiPlaceOrder order = ApiPlaceOrder.builder()
                .uid(userId)
                .orderId(orderId)
                .action(OrderAction.ASK)
                .size(size)
                .price(price)
                .symbol(symbolId)
                .orderType(OrderType.GTC)
                .marginMode(mode)
                .build();
        try {
            api.submitCommandAsync(order).get();
        } catch (Exception e) {

        }
        return orderId;
    }

    public long placeExtraMargin(long userId, int quoteId, int symbolId, long amount, MarginMode mode) {
        long txId = getRandomTransactionId();
        ApiAdjustMargin cmd = ApiAdjustMargin.builder().transactionId(txId).symbol(symbolId).uid(userId).amount(amount).currency(quoteId).marginMode(mode).build();
        try {
            api.submitCommandAsync(cmd).get();
        } catch (Exception e) {
        }
        return txId;
    }

    public void updateCurrentPriceTo(int price, int symbolId, int quoteId) {
        // update mark price
        long uid = 100000 + getRandomTransactionId();
        submitCommandSync(ApiAdjustMarkPrice.builder().transactionId(uid).symbol(symbolId).markPrice(price).build(), CommandResultCode.SUCCESS);
        // update bid/ask price
        createUserWithMoney(UPDATE_PRICE_USER1, quoteId, TestConstants.MAX_VALUE);
        createUserWithMoney(UPDATE_PRICE_USER2, quoteId, TestConstants.MAX_VALUE);
        createBid(UPDATE_PRICE_USER1, 10, price, symbolId);
        createAsk(UPDATE_PRICE_USER2, 10, price, symbolId);
        // 触发让R2做完
        api.groupingControl(0, 1);
    }

    public long createRandomUserWithMoney(long amount, int quoteId) {
        long uid = 100000 + getRandomTransactionId();
        final List<ApiCommand> cmds = new ArrayList<>();
        cmds.add(ApiAddUser.builder().uid(uid).build());
        cmds.add(ApiAdjustUserBalance.builder().uid(uid).transactionId(getRandomTransactionId()).amount(amount).currency(quoteId).build());
        api.submitCommandsSync(cmds);
        return uid;
    }

    public long createUserWithSpecificMoney(long userId, long amount, int quoteId) {
        final List<ApiCommand> cmds = new ArrayList<>();
        cmds.add(ApiAddUser.builder().uid(userId).build());
        cmds.add(ApiAdjustUserBalance.builder().uid(userId).transactionId(getRandomTransactionId()).amount(amount).currency(quoteId).build());
        api.submitCommandsSync(cmds);
        return userId;
    }

    public void printUser(long userId) throws ExecutionException, InterruptedException {
        Future<SingleUserReportResult> report = api.processReport(new SingleUserReportQuery(userId), 0);
        System.out.println("------------------");
        System.out.println("userId=" + userId);
        System.out.println("accounts: " + report.get());
    }

    public void initBasicUser(long uid) {
        assertThat(api.submitCommandAsync(ApiAddUser.builder().uid(uid).build()).join(), Is.is(CommandResultCode.SUCCESS));
        assertThat(api.submitCommandAsync(ApiAdjustUserBalance.builder().uid(uid).transactionId(1L).amount(10_000_00L).currency(CURRENECY_USD).build()).join(), Is.is(CommandResultCode.SUCCESS));
        assertThat(api.submitCommandAsync(ApiAdjustUserBalance.builder().uid(uid).transactionId(2L).amount(1_0000_0000L).currency(TestConstants.CURRENECY_XBT).build()).join(), Is.is(CommandResultCode.SUCCESS));
        assertThat(api.submitCommandAsync(ApiAdjustUserBalance.builder().uid(uid).transactionId(3L).amount(1_0000_0000L).currency(CURRENECY_ETH).build()).join(), Is.is(CommandResultCode.SUCCESS));
    }

    public void initFeeUser(long uid) {
        assertThat(api.submitCommandAsync(ApiAddUser.builder().uid(uid).build()).join(), Is.is(CommandResultCode.SUCCESS));
        assertThat(api.submitCommandAsync(ApiAdjustUserBalance.builder().uid(uid).transactionId(1L).amount(10_000_00L).currency(CURRENECY_USD).build()).join(), Is.is(CommandResultCode.SUCCESS));
        assertThat(api.submitCommandAsync(ApiAdjustUserBalance.builder().uid(uid).transactionId(2L).amount(10_000_000L).currency(TestConstants.CURRENECY_JPY).build()).join(), Is.is(CommandResultCode.SUCCESS));
        assertThat(api.submitCommandAsync(ApiAdjustUserBalance.builder().uid(uid).transactionId(3L).amount(1_0000_0000L).currency(TestConstants.CURRENECY_XBT).build()).join(), Is.is(CommandResultCode.SUCCESS));
        assertThat(api.submitCommandAsync(ApiAdjustUserBalance.builder().uid(uid).transactionId(4L).amount(1000_0000_0000L).currency(CURRENECY_LTC).build()).join(), Is.is(CommandResultCode.SUCCESS));
    }

    public void createUserWithMoney(long uid, int currency, long amount) {
        final List<ApiCommand> cmds = new ArrayList<>();
        cmds.add(ApiAddUser.builder().uid(uid).build());
        cmds.add(ApiAdjustUserBalance.builder().uid(uid).transactionId(getRandomTransactionId()).amount(amount).currency(currency).build());
        api.submitCommandsSync(cmds);
    }

    public CoreSymbolSpecification initSymbol() {
        CoreSymbolSpecification spec = CoreSymbolSpecification.builder()
                .symbolId(10001)
                .type(SymbolType.FUTURES_CONTRACT_PERPETUAL)
                .baseCurrency(11)
                .quoteCurrency(12)
//                .marginBuy(1000)
//                .marginSell(1000)
                .baseScaleK(1)
                .quoteScaleK(1)
                .feeScaleK(100)
                .makerFee(1).takerFee(2)
//                .maxLeverage(50)
//                .maintenanceMargin(50)
                .maintenanceMargin(TreeSortedMap.newMapWith(1000L, 5L, 100000L, 10L))
                .maintenanceMarginScaleK(1000)
                .maxLeverage(TreeSortedMap.newMapWith(2000L, 10L, 5000L, 20L, 10000L, 50L))
                .build();

        addSymbol(spec);
        return spec;
    }

    public CoreSymbolSpecification initSymbolExchange() {
        CoreSymbolSpecification spec = CoreSymbolSpecification.builder()
                .symbolId(30001)
                .type(SymbolType.CURRENCY_EXCHANGE_PAIR)
                .baseCurrency(21)
                .quoteCurrency(12)
                .baseScaleK(1)
                .quoteScaleK(1)
                .feeScaleK(100)
                .makerFee(1)
                .takerFee(2)
                .build();

        addSymbol(spec);
        return spec;
    }

    public void addMoneyToUser(long uid, int currency, long amount) {
        long orderId = getRandomTransactionId();
        addMoneyToUserWithOrderId(orderId, uid, currency, amount);
    }

    public void addMoneyToUserWithOrderId(long orderId, long uid, int currency, long amount) {
        final List<ApiCommand> cmds = new ArrayList<>();
        cmds.add(ApiAdjustUserBalance.builder().uid(uid).transactionId(orderId).amount(amount).currency(currency).build());
        api.submitCommandsSync(cmds);
    }

    public void addSymbol(final CoreSymbolSpecification symbol) {
        sendBinaryDataCommandSync(new BatchAddSymbolsCommand(symbol), 5000);
    }

    public CoreCurrencySpecification addCurrency(int id) {
        return addCurrency(id, 0);
    }

    public CoreCurrencySpecification addCurrency(int id, int digit) {
        CoreCurrencySpecification coreCurrencySpecification = CoreCurrencySpecification.builder().id(id).digit(digit).build();
        sendBinaryDataCommandSync(new BatchAddCurrenciesCommand(coreCurrencySpecification), 5000);
        return coreCurrencySpecification;
    }

    public void addCurrency(final CoreCurrencySpecification currency) {
        sendBinaryDataCommandSync(new BatchAddCurrenciesCommand(currency), 5000);
    }

    public void addSymbols(final List<CoreSymbolSpecification> symbols) {
        // split by chunks
        Lists.partition(symbols, 10000).forEach(partition -> sendBinaryDataCommandSync(new BatchAddSymbolsCommand(partition), 5000));
    }

    public void adjustPositionMode(long uid, PositionMode mode) {
        final ApiAdjustPositionMode cmd = ApiAdjustPositionMode.builder().uid(uid).positionMode(mode).build();
        submitCommandSync(cmd, CommandResultCode.SUCCESS);
    }

    public void sendBinaryDataCommandSync(final BinaryDataCommand data, final int timeOutMs) {
        final Future<CommandResultCode> future = api.submitBinaryDataAsync(data);
        try {
            assertThat(future.get(timeOutMs, TimeUnit.MILLISECONDS), Is.is(CommandResultCode.SUCCESS));
        } catch (final InterruptedException | ExecutionException | TimeoutException ex) {
            log.error("Failed sending binary data command", ex);
            throw new RuntimeException(ex);
        }
    }

    private int getRandomTransferId() {
        return uniqueIdCounterInt.incrementAndGet();
    }

    public long getRandomTransactionId() {
        return uniqueIdCounterLong.incrementAndGet();
    }

    public final void userAccountsInit(List<BitSet> userCurrencies) {

        // calculate max amount can transfer to each account so that it is not possible to get long overflow
        final IntLongHashMap accountsNumPerCurrency = new IntLongHashMap();
        userCurrencies.forEach(accounts -> accounts.stream().forEach(currency -> accountsNumPerCurrency.addToValue(currency, 1)));
        final IntLongHashMap amountPerAccount = new IntLongHashMap();
        accountsNumPerCurrency.forEachKeyValue((currency, numAcc) -> amountPerAccount.put(currency, Long.MAX_VALUE / (numAcc + 1)));
        // amountPerAccount.forEachKeyValue((k, v) -> log.debug("{}={}", k, v));

        createUserAccountsRegular(userCurrencies, amountPerAccount);
    }


    private void createUserAccountsRegular(List<BitSet> userCurrencies, IntLongHashMap amountPerAccount) {
        final int numUsers = userCurrencies.size() - 1;

        IntStream.rangeClosed(1, numUsers).forEach(uid -> {
            api.submitCommand(ApiAddUser.builder().uid(uid).build());
            userCurrencies.get(uid).stream().forEach(currency ->
                    api.submitCommand(ApiAdjustUserBalance.builder()
                            .uid(uid)
                            .transactionId(getRandomTransactionId())
                            .amount(amountPerAccount.get(currency))
                            .currency(currency)
                            .build()));
        });

        api.submitCommandAsync(ApiNop.builder().build()).join();
    }

    public void usersInit(int numUsers, Set<Integer> currencies, long amount) {

        LongStream.rangeClosed(1, numUsers)
                .forEach(uid -> {
                    api.submitCommand(ApiAddUser.builder().uid(uid).build());
                    long transactionId = 1L;
                    for (int currency : currencies) {
                        api.submitCommand(ApiAdjustUserBalance.builder()
                                .uid(uid)
                                .transactionId(transactionId++)
                                .amount(amount)
                                .currency(currency).build());
                    }
                });

        api.submitCommandAsync(ApiNop.builder().build()).join();
    }

    public void resetExchangeCore() {
        final CommandResultCode res = api.submitCommandAsync(ApiReset.builder().build()).join();
        assertThat(res, Is.is(CommandResultCode.SUCCESS));
    }

    public void submitCommandSync(ApiCommand apiCommand, CommandResultCode expectedResultCode) {
        assertThat(api.submitCommandAsync(apiCommand).join(), Is.is(expectedResultCode));
    }

    public void submitCommandSync(ApiCommand apiCommand, Consumer<OrderCommand> validator) {
        validator.accept(api.submitCommandAsyncFullResponse(apiCommand).join());
    }

    public L2MarketData requestCurrentOrderBook(final int symbol) {
        return api.requestOrderBookAsync(symbol, -1).join();
    }

    // todo rename
    public void validateUserState(long uid, Consumer<SingleUserReportResult> resultValidator) throws InterruptedException, ExecutionException {
        resultValidator.accept(getUserProfile(uid));
    }

    public SingleUserReportResult getUserProfile(long clientId) throws InterruptedException, ExecutionException {
        return api.processReport(new SingleUserReportQuery(clientId), getRandomTransferId()).get();
    }

    public SymbolCurrencyReportResult getSymbolCurrencyReport() throws InterruptedException, ExecutionException {
        return api.processReport(new SymbolCurrencyReportQuery(), getRandomTransferId()).get();
    }

    public TotalCurrencyBalanceReportResult totalBalanceReport() {
        final TotalCurrencyBalanceReportResult res = api.processReport(new TotalCurrencyBalanceReportQuery(), getRandomTransferId()).join();
        final IntLongHashMap openInterestLong = res.getOpenInterestLong();
        final IntLongHashMap openInterestShort = res.getOpenInterestShort();
        final IntLongHashMap openInterestDiff = new IntLongHashMap(openInterestLong);
        openInterestShort.forEachKeyValue((k, v) -> openInterestDiff.addToValue(k, -v));
        if (openInterestDiff.anySatisfy(vol -> vol != 0)) {
            throw new IllegalStateException("Open Interest balance check failed");
        }

        return res;
    }

    public int requestStateHash() throws InterruptedException, ExecutionException {
        return api.processReport(new StateHashReportQuery(), getRandomTransferId()).get().getStateHash();
    }

    public static List<CoreSymbolSpecification> generateRandomSymbols(final int num,
                                                                      final Collection<Integer> currenciesAllowed,
                                                                      final AllowedSymbolTypes allowedSymbolTypes) {
        final Random random = new Random(1L);

        final Supplier<SymbolType> symbolTypeSupplier;

        switch (allowedSymbolTypes) {
            case FUTURES_CONTRACT:
                symbolTypeSupplier = () -> SymbolType.FUTURES_CONTRACT_PERPETUAL;
                break;

            case CURRENCY_EXCHANGE_PAIR:
                symbolTypeSupplier = () -> SymbolType.CURRENCY_EXCHANGE_PAIR;
                break;

            case BOTH:
            default:
                symbolTypeSupplier = () -> random.nextBoolean() ? SymbolType.FUTURES_CONTRACT_PERPETUAL : SymbolType.CURRENCY_EXCHANGE_PAIR;
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
                final CoreSymbolSpecification symbol = CoreSymbolSpecification.builder()
                        .symbolId(TestConstants.SYMBOL_AUTOGENERATED_RANGE_START + i)
                        .type(type)
                        .baseCurrency(baseCurrency) // TODO for futures can be any value
                        .quoteCurrency(quoteCurrency)
                        .baseScaleK(100)
                        .quoteScaleK(10)
                        .takerFee(takerFee)
                        .makerFee(makerFee) // TODO margins for futures?
                        .build();

                result.add(symbol);

                //log.debug("{}", symbol);
                i++;
            }
        }
        return result;
    }

    public void loadSymbolsUsersAndPrefillOrders(TestDataFutures testDataFutures) {
        // load symbols
        final List<CoreSymbolSpecification> coreSymbolSpecifications = testDataFutures.coreSymbolSpecifications.join();
        initPerfCurrencies(coreSymbolSpecifications);

        log.info("Loading {} symbols...", coreSymbolSpecifications.size());
        try (ExecutionTime ignore = new ExecutionTime(t -> log.debug("Loaded all symbols in {}", t))) {
            addSymbols(coreSymbolSpecifications);
        }

        // init markPrice
        testDataFutures.coreSymbolSpecifications.join().forEach(symbol -> {
            if (symbol.getType() == SymbolType.FUTURES_CONTRACT_PERPETUAL) {
                initMarkPrice(symbol.getSymbolId(), 100);
            }
        });

        // create accounts and deposit initial funds
        final List<BitSet> userAccounts = testDataFutures.usersAccounts.join();
        log.info("Loading {} users having {} accounts...", userAccounts.size(), userAccounts.stream().mapToInt(BitSet::cardinality).sum());
        try (ExecutionTime ignore = new ExecutionTime(t -> log.debug("Loaded all users in {}", t))) {
            userAccountsInit(userAccounts);
        }

        final List<ApiCommand> apiCommandsFill = testDataFutures.genResult.join().getApiCommandsFill().join();
        log.info("Order books pre-fill with {} orders...", apiCommandsFill.size());
        try (ExecutionTime ignore = new ExecutionTime(t -> log.debug("Order books pre-fill completed in {}", t))) {
            getApi().submitCommandsSync(apiCommandsFill);
        }

        assertTrue(totalBalanceReport().isGlobalBalancesAllZero());
    }

    private void initPerfCurrencies(List<CoreSymbolSpecification> coreSymbolSpecifications) {
        // init currencies，保证currency精度比交易对size*price精度高
        coreSymbolSpecifications.forEach(symbol -> {
            int digit = TenPowers.log10(symbol.baseScaleK * symbol.quoteScaleK);
            addCurrency(symbol.baseCurrency, digit);
            addCurrency(symbol.quoteCurrency, digit);
        });
    }

    public void loadSymbolsUsersAndPrefillOrdersNoLog(TestDataFutures testDataFutures) {

        // load symbols
        final List<CoreSymbolSpecification> coreSymbolSpecifications = testDataFutures.coreSymbolSpecifications.join();

        initPerfCurrencies(coreSymbolSpecifications);
        // load symbols
        addSymbols(coreSymbolSpecifications);

        // init markPrice
        testDataFutures.coreSymbolSpecifications.join().forEach(symbol -> {
            if (symbol.getType() == SymbolType.FUTURES_CONTRACT_PERPETUAL) {
                initMarkPrice(symbol.getSymbolId(), 100);
            }
        });

        // create accounts and deposit initial funds
        userAccountsInit(testDataFutures.usersAccounts.join());

        getApi().submitCommandsSync(testDataFutures.genResult.join().getApiCommandsFill().join());
    }


    /**
     * Run test using threads factory.
     * This is needed for correct cpu pinning.
     *
     * @param test - test lambda
     * @param <V>  return parameter type
     * @return result from test lambda
     */
    public <V> V executeTestingThread(final Callable<V> test) {
        try {
            final ExecutorService executor = Executors.newSingleThreadExecutor(threadFactory);
            final V result = executor.submit(test).get();
            executor.shutdown();
            executor.awaitTermination(3000, TimeUnit.SECONDS);
            return result;
        } catch (ExecutionException | InterruptedException ex) {
            throw new RuntimeException(ex);
        }
    }

    public float executeTestingThreadPerfMtps(final Callable<Integer> test) {
        return executeTestingThread(() -> {
            final long tStart = System.currentTimeMillis();
            final int numMessages = test.call();
            final long tDuration = System.currentTimeMillis() - tStart;
            return numMessages / (float) tDuration / 1000.0f;
        });
    }

    public float benchmarkMtps(final List<ApiCommand> apiCommandsBenchmark) {
        final long tStart = System.currentTimeMillis();
        getApi().submitCommandsSync(apiCommandsBenchmark);
        final long tDuration = System.currentTimeMillis() - tStart;
        return apiCommandsBenchmark.size() / (float) tDuration / 1000.0f;
    }

    @Override
    public void close() {
        exchangeCore.shutdown(3000, TimeUnit.MILLISECONDS);
    }

    public enum AllowedSymbolTypes {
        FUTURES_CONTRACT,
        CURRENCY_EXCHANGE_PAIR,
        BOTH
    }
}
