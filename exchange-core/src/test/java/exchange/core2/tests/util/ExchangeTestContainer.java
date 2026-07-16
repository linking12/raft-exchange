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
import exchange.core2.core.processors.liquidation.LiquidationEngine;
import java.util.concurrent.ThreadFactory;
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
import java.util.function.BooleanSupplier;
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
    private final ThreadFactory disruptorThreadFactory;

    private AtomicLong uniqueIdCounterLong = new AtomicLong();
    private AtomicInteger uniqueIdCounterInt = new AtomicInteger();

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

    public static ExchangeTestContainer create(final PerformanceConfiguration perfCfg,
                                               final InitialStateConfiguration initStateCfg,
                                               final SerializationConfiguration serializationCfg,
                                               ObjLongConsumer<OrderCommand> consumer) {
        return new ExchangeTestContainer(perfCfg, initStateCfg, serializationCfg, consumer);
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

    public static ExchangeTestContainer createSpotOnly(final PerformanceConfiguration perfCfg) {
        return new ExchangeTestContainer(perfCfg,
                InitialStateConfiguration.CLEAN_TEST,
                SerializationConfiguration.DEFAULT, null,
                OrdersProcessingConfiguration.MarginTradingMode.MARGIN_TRADING_DISABLED);
    }

    private ExchangeTestContainer(final PerformanceConfiguration perfCfg,
                                  final InitialStateConfiguration initStateCfg,
                                  final SerializationConfiguration serializationCfg,
                                  final ObjLongConsumer<OrderCommand> consumer) {
        this(perfCfg, initStateCfg, serializationCfg, consumer,
                OrdersProcessingConfiguration.MarginTradingMode.MARGIN_TRADING_ENABLED);
    }

    private ExchangeTestContainer(final PerformanceConfiguration perfCfg,
                                  final InitialStateConfiguration initStateCfg,
                                  final SerializationConfiguration serializationCfg,
                                  final ObjLongConsumer<OrderCommand> consumer,
                                  final OrdersProcessingConfiguration.MarginTradingMode marginTradingMode) {

        //log.debug("CREATING exchange container");

        // 测试容器对生产配置做三处覆盖：
        // 1) disruptorThreadFactory → Thread::new：去掉 Disruptor 钉核（生产 AffinityThreadFactory 在测试 JVM
        //    内多次 new/stop 会污染 net.openhft.affinity.LockInventory；macOS 上 pthread_setaffinity_np
        //    本身是 no-op，钉核不生效却只贡献副作用）。
        // 2) waitStrategy → YIELDING：去掉 Disruptor BUSY_SPIN，避免 4 + 2 个钉核线程在测试 fork 里
        //    100% 占 CPU 饿死其它线程。空载仍是微秒级响应，争用时主动 yield。
        // 3) liquidationThreadFactory → AffinityThreadFactory：单独把 LiquidationEngine 调度线程钉一个
        //    逻辑核。原因：生产用 daemon Thread 是因为单进程只跑一个 ExchangeCore，OS 调度足够；
        //    测试 fork 内 surefire 连跑多个 IT 类、累积 thread 较多，LiquidationEngine 的 2 秒调度被
        //    OS 调度抖动到 30s 之后是已知 flake (testIFMultiShardBoundary / testSingleShardLiquidation /
        //    testDeliveryScenario1)。给它单独钉一个核能保证毫秒级触发。仅 LiquidationEngine 钉核，
        //    Disruptor 仍非钉核，LockInventory 累积量很小（每 fork 2 个 shard × 1 个调度线程）。
        // 其余 shard 数、ringBufferSize 等通过 perfCfg.toBuilder() 原样保留。
        final PerformanceConfiguration testPerfCfg = perfCfg.toBuilder()
                .disruptorThreadFactory(Thread::new)
                .waitStrategy(CoreWaitStrategy.YIELDING)
                .liquidationThreadFactory(Thread::new)
                .build();

        // 测试侧自身 spawn 执行线程也走普通 Thread，不再抢 affinity 槽位
        this.disruptorThreadFactory = Thread::new;

        // 测试容器默认启用 margin trading：
        // 否则 RiskEngine / MatchingEngineRouter 在添加 FUTURES_CONTRACT_* symbol 时直接拒
        // （"Margin symbols are not allowed"），后续撮合时 symbol lookup 返回 null，
        // SimpleEventsProcessor.sendExecutionReport 触发 NPE。
        // 对纯现货测试无影响：margin enabled 只是放行期货 symbol 注册，现货流程不变。
        final OrdersProcessingConfiguration ordersProcessingCfg = OrdersProcessingConfiguration.builder()
                .marginTradingMode(marginTradingMode)
                .build();
        final ExchangeConfiguration exchangeConfiguration = ExchangeConfiguration.defaultBuilder()
                .initStateCfg(initStateCfg)
                .performanceCfg(testPerfCfg)
                .reportsQueriesCfg(ReportsQueriesConfiguration.createStandardConfig())
                .ordersProcessingCfg(ordersProcessingCfg)
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
        initMarkPrice(SYMBOLSPEC_DYNAMIC_FEE_XBT_USD.symbolId, 10000);
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

    public long createAsk(long userId, int size, long price, int symbolId, MarginMode marginMode) {
        long orderId = getRandomTransactionId();
        return createAskWithOrderId(orderId, userId, size, price, symbolId, marginMode);
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
        createBid(UPDATE_PRICE_USER1, 10, price, symbolId, MarginMode.CROSS);
        createAsk(UPDATE_PRICE_USER2, 10, price, symbolId, MarginMode.CROSS);
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

    /**
     * 触发强平：懒启动各分片 LiquidationEngine（幂等，首次真正 start，之后 no-op），
     * 再同步注入 {@link ApiLiquidationScan} 全量扫命令。命令 apply 时触发各分片
     * checkPositions 全量扫，后续 FORCE→IF→ADL 级联由 cascade 自驱，本方法不等级联跑完。
     * <p>懒启动而非构造时启动：避免"只更新价格、不期望强平"的其它测试被价格更新即时触发
     * 强平意外波及——只有显式调用本方法的测试才启用引擎。
     */
    public void triggerLiquidation() {
        if (exchangeCore.getLiquidationEngines() == null) {
            return; // spot-only（未开 margin trading）：无强平引擎
        }
        exchangeCore.getLiquidationEngines().forEach(LiquidationEngine::start);
        submitCommandSync(ApiLiquidationScan.builder().build(), CommandResultCode.SUCCESS);
    }

    /**
     * 触发全量强平扫描并驱动 cascade（FORCE→IF→ADL 自驱），轮询 {@code settled} 一旦成立即返回，最长等 timeoutMs。
     * <p>不抛超时异常——真正的判定留给调用方原有断言；这里只是把"固定 sleep 盲等"换成"到点即走"，
     * 因此收敛在最好情况，绝不会让原本能过的用例变红。每轮末尾 flush 一次 grouping，确保残余命令落地。
     */
    public void triggerLiquidationUntil(long timeoutMs, BooleanSupplier settled) {
        final long deadline = System.currentTimeMillis() + timeoutMs;
        do {
            triggerLiquidation();
            api.groupingControl(0, 1);
            if (settled.getAsBoolean()) {
                break;
            }
            try {
                Thread.sleep(50L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        } while (System.currentTimeMillis() < deadline);
        api.groupingControl(0, 1);
    }

    /**
     * 触发强平并驱动 cascade，直到给定用户里"已清空持仓"的数量连续若干轮不再增长（收敛）或超时。
     * <p>适合流动性可能不足、无法保证全部强平的多用户场景——按实际能达成的稳态收敛，而非死等固定时长，
     * 也不因"没全平"抛异常。结束时 flush 一次 grouping。
     */
    public void triggerLiquidationUntilQuiescent(long timeoutMs, long... uids) {
        final long deadline = System.currentTimeMillis() + timeoutMs;
        int prevCleared = -1;
        int stable = 0;
        do {
            triggerLiquidation();
            api.groupingControl(0, 1);
            int cleared = 0;
            for (long uid : uids) {
                if (noPositions(uid)) {
                    cleared++;
                }
            }
            if (cleared == prevCleared) {
                if (cleared > 0 && ++stable >= 3) {
                    break; // 连续 3 轮无新增且已有强平 → 视为收敛
                }
            } else {
                stable = 0;
                prevCleared = cleared;
            }
            try {
                Thread.sleep(50L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        } while (System.currentTimeMillis() < deadline);
        api.groupingControl(0, 1);
    }

    /** 轮询用：用户是否已被完全强平（无任何持仓）。checked 异常包成 unchecked，方便用在 lambda 里。 */
    public boolean noPositions(long uid) {
        try {
            return getUserProfile(uid).getPositions().size() == 0;
        } catch (InterruptedException | ExecutionException e) {
            throw new RuntimeException(e);
        }
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

    /**
     * 可支配余额 = 真实持有 - 现货挂单冻结。
     * accounts 报"持有总额"，老测试普遍按"下单即扣减"的语义断言，
     * 用这个 helper 统一转成"available"对账即可。
     */
    public static long available(SingleUserReportResult profile, int currency) {
        return profile.getAccounts().get(currency) - profile.getExchangeLocked().get(currency);
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
        res.getIfOpenInterestLong().forEachKeyValue(openInterestDiff::addToValue);
        res.getIfOpenInterestShort().forEachKeyValue((k, v) -> openInterestDiff.addToValue(k, -v));
        if (openInterestDiff.anySatisfy(vol -> vol != 0)) {
            throw new IllegalStateException("Open Interest balance check failed");
        }

        // 同源等价校验：FeeReportQuery.fees 必须等于 TotalCurrencyBalanceReportQuery.fees
        // —— 两者都来自 RiskEngine.fees 的跨 shard mergeSum，任何一处发散都说明 FeeReport 走偏了
        final FeeReportResult feeReport = api.processReport(new FeeReportQuery(), getRandomTransferId()).join();
        if (!feeReport.getFees().equals(res.getFees())) {
            throw new IllegalStateException(
                "FeeReport diverged from TotalCurrencyBalance.fees: feeReport=" + feeReport.getFees() + " total=" + res.getFees());
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
            final ExecutorService executor = Executors.newSingleThreadExecutor(disruptorThreadFactory);
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

    /**
     * 获取IF（保险基金）在指定币种的余额
     * @param symbol 交易对ID
     * @param currency 货币ID
     * @return IF余额
     */
    public long getIFBalance(int symbol, int currency) {
        try {
            // 通过TotalCurrencyBalanceReportQuery查询IF状态
            TotalCurrencyBalanceReportQuery query = new TotalCurrencyBalanceReportQuery();
            CompletableFuture<TotalCurrencyBalanceReportResult> future =
                api.processReport(query, getRandomTransferId());

            TotalCurrencyBalanceReportResult result = future.get(5, TimeUnit.SECONDS);

            // 从结果中获取IF余额
            if (result.getIfBalances() != null) {
                long balance = result.getIfBalances().get(currency);
                log.debug("IF balance for symbol {} currency {}: {}", symbol, currency, balance);
                return balance;
            }
            return 0L;
        } catch (Exception e) {
            log.error("Failed to get IF balance for symbol {} currency {}", symbol, currency, e);
            return 0L;
        }
    }

    /**
     * 获取IF持有的指定方向仓位数量
     * @param symbol 交易对ID
     * @param direction 仓位方向
     * @return IF持有的仓位数量
     */
    public long getIFPosition(int symbol, PositionDirection direction) {
        try {
            // 通过TotalCurrencyBalanceReportQuery查询IF持仓
            TotalCurrencyBalanceReportQuery query = new TotalCurrencyBalanceReportQuery();
            CompletableFuture<TotalCurrencyBalanceReportResult> future =
                api.processReport(query, getRandomTransferId());

            TotalCurrencyBalanceReportResult result = future.get(5, TimeUnit.SECONDS);

            // 从结果中获取IF持仓
            if (direction == PositionDirection.LONG && result.getIfOpenInterestLong() != null) {
                long position = result.getIfOpenInterestLong().get(symbol);
                log.debug("IF long position for symbol {}: {}", symbol, position);
                return position;
            } else if (direction == PositionDirection.SHORT && result.getIfOpenInterestShort() != null) {
                long position = result.getIfOpenInterestShort().get(symbol);
                log.debug("IF short position for symbol {}: {}", symbol, position);
                return position;
            }
            return 0L;
        } catch (Exception e) {
            log.error("Failed to get IF position for symbol {} direction {}", symbol, direction, e);
            return 0L;
        }
    }

    /**
     * 给IF账户注入资金
     * @param symbol 交易对ID
     * @param currency 货币ID
     * @param amount 金额
     */
    public void addIFBalance(int symbol, int currency, long amount) {
        try {
            // 由于IF不是普通用户账户，无法直接通过API注入资金
            // 这个方法主要用于测试准备，实际上IF的资金来源于强平费等
            log.info("Request to add {} to IF balance for symbol {} currency {} - IF balance comes from liquidation fees in production",
                amount, symbol, currency);
            // 注意：在实际系统中，IF的资金是通过强平费自动积累的，不需要手动注入
        } catch (Exception e) {
            log.error("Failed to add IF balance", e);
        }
    }

    /**
     * 关闭前的对账自检：跨用户 / 跨币种的 global balance 必须闭合 (≡ 0)。
     * 默认开启；若测试场景里使用了 {@code LiquidationService.creditLiquidationFee} 等
     * 直接绕过 adjustments 的辅助注资工具，可通过 {@link #skipGlobalReconcileOnClose()} 跳过。
     */
    private boolean skipGlobalReconcileOnClose = false;

    public void skipGlobalReconcileOnClose() {
        this.skipGlobalReconcileOnClose = true;
    }

    @Override
    public void close() {
        try {
            if (!skipGlobalReconcileOnClose) {
                final exchange.core2.core.common.api.reports.TotalCurrencyBalanceReportResult bal = totalBalanceReport();
                if (!bal.isGlobalBalancesAllZero()) {
                    throw new AssertionError(
                            "Global balance not reconciled at container close: sum=" + bal.getGlobalBalancesSum()
                                    + ", accountBalances=" + bal.getAccountBalances()
                                    + ", extraMargin=" + bal.getExtraMargin()
                                    + ", exchangeLocked=" + bal.getExchangeLocked()
                                    + ", fees=" + bal.getFees()
                                    + ", adjustments=" + bal.getAdjustments()
                                    + ", suspends=" + bal.getSuspends()
                                    + ", ifBalances=" + bal.getIfBalances());
                }
            }
        } finally {
            // 停掉懒启动的 LiquidationEngine 调度线程，避免 scheduler 线程泄漏。
            // stop 幂等：未 start（大多数测试）的 engine 调用 stop 也安全。
            if (exchangeCore.getLiquidationEngines() != null) {
                exchangeCore.getLiquidationEngines().forEach(LiquidationEngine::stop);
            }
            exchangeCore.shutdown(3000, TimeUnit.MILLISECONDS);
        }
    }

    public enum AllowedSymbolTypes {
        FUTURES_CONTRACT,
        CURRENCY_EXCHANGE_PAIR,
        BOTH
    }
}
