package exchange.core2.tests.examples;

import exchange.core2.core.*;
import exchange.core2.core.common.*;
import exchange.core2.core.common.api.*;
import exchange.core2.core.common.api.binary.BatchAddCurrenciesCommand;
import exchange.core2.core.common.api.binary.BatchAddSymbolsCommand;
import exchange.core2.core.common.api.binary.BinaryDataCommand;
import exchange.core2.core.common.api.reports.SingleUserReportQuery;
import exchange.core2.core.common.api.reports.SingleUserReportResult;
import exchange.core2.core.common.cmd.CommandResultCode;
import exchange.core2.core.common.config.ExchangeConfiguration;
import exchange.core2.core.common.config.OrdersProcessingConfiguration;
import exchange.core2.core.event.IEventsHandler4Test;
import exchange.core2.core.event.SimpleEventsProcessor4Test;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.collections.impl.map.sorted.mutable.TreeSortedMap;
import org.hamcrest.core.Is;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

@Slf4j
public class FutureCoreExample {

    private ExchangeCore exchangeCore;
    private ExchangeApi api;

    private int symbolId = 2;
    private int quoteId = 840;
    private long UID_1 = 301L;
    private long UID_2 = 302L;
    private long UID_3 = 303L;
    private long UID_4 = 304L;
    private long UID_5 = 305L;
    private long UID_6 = 306L;

    int MAX_VALUE = 4000000;

    private AtomicLong uniqueIdCounterLong = new AtomicLong();

    private long getRandomTransactionId() {
        return uniqueIdCounterLong.incrementAndGet();
    }

    @Before
    public void setUp() throws Exception {
        SimpleEventsProcessor4Test eventsProcessor = new SimpleEventsProcessor4Test(new IEventsHandler4Test() {

            @Override
            public void spotExecutionReport(SpotExecutionReport executionReport) {
                log.debug("SpotExecutionReport: {}", executionReport);
            }

            @Override
            public void futuresExecutionReport(FuturesExecutionReport executionReport) {
                log.debug("FuturesExecutionReport: {}", executionReport);
            }

            @Override
            public void positionOutReport(PositionOutReport positionOut) {
                log.debug("PositionOutReport: {}", positionOut);
            }

            @Override
            public void orderBook(OrderBook orderBook) {
                log.debug("OrderBook event: {}", orderBook);
            }
        });

        // 配置交易所
        ExchangeConfiguration conf = ExchangeConfiguration.defaultBuilder()
                .ordersProcessingCfg(OrdersProcessingConfiguration.builder()
                        .marginTradingMode(OrdersProcessingConfiguration.MarginTradingMode.MARGIN_TRADING_ENABLED)
                        .build())
                .build();

        // 创建 ExchangeCore
        exchangeCore = ExchangeCore.builder()
                .resultsConsumer(eventsProcessor)
                .exchangeConfiguration(conf)
                .build();

        exchangeCore.startup();
        api = exchangeCore.getApi();

        // 初始化用户和符号
        initCurrencies();
        initializeUserAndSymbols();
        doMarkPrice(symbolId, 10000L);
    }

    @After
    public void tearDown() {
        exchangeCore.shutdown();
    }

    private void doMarkPrice(int symbolId, long markPrice) {
        ApiAdjustMarkPrice cmd = ApiAdjustMarkPrice.builder()
                .transactionId(1334567L)
                .markPrice(markPrice)
                .symbol(symbolId)
                .build();
        api.submitCommandAsync(cmd);
    }

    private void deposit(long userId, long amount) throws ExecutionException, InterruptedException {
        Future<CommandResultCode> future;
        ApiAdjustUserBalance adjustBalance = ApiAdjustUserBalance.builder()
                .uid(userId)
                .currency(quoteId)
                .amount(amount)
                .transactionId(getRandomTransactionId())
                .build();
        future = api.submitCommandAsync(adjustBalance);
        System.out.println("deposit result: " + future.get());
    }

    private void createUser(long userId) throws ExecutionException, InterruptedException {
        Future<CommandResultCode> future;
        future = api.submitCommandAsync(ApiAddUser.builder()
                .uid(userId)
                .build());
        if (future.get().getCode() != 0) {
            System.err.println("ApiAddUser result: " + future.get());
        }
    }

    private long createRandomUserWithMoney() {
        return createRandomUserWithMoney(MAX_VALUE);
    }

    private long createRandomUserWithMoney(long amount) {
        long uid = 100000 + getRandomTransactionId();
        final List<ApiCommand> cmds = new ArrayList<>();
        cmds.add(ApiAddUser.builder().uid(uid).build());
        cmds.add(ApiAdjustUserBalance.builder().uid(uid).transactionId(getRandomTransactionId()).amount(amount).currency(quoteId).build());
        api.submitCommandsSync(cmds);
        return uid;
    }

    private void initCurrencies() {
        CoreCurrencySpecification BNB = CoreCurrencySpecification.builder()
                .id(1).name("BNB").digit(8).build();

        CoreCurrencySpecification USDT = CoreCurrencySpecification.builder()
                .id(quoteId).name("USDT").digit(6).build();

        api.submitBinaryDataAsync(new BatchAddCurrenciesCommand(BNB));
        api.submitBinaryDataAsync(new BatchAddCurrenciesCommand(USDT));
    }

    private void initSpotSymbol() {
        CoreSymbolSpecification futuresSymbol = CoreSymbolSpecification.builder()
                .symbolId(symbolId + 1)
                .type(SymbolType.CURRENCY_EXCHANGE_PAIR)
                .baseCurrency(1)
                .quoteCurrency(quoteId)
                .baseScaleK(1)
                .quoteScaleK(1)
                .makerFee(10)
                .takerFee(20)
                .maintenanceMargin(TreeSortedMap.newMapWith(1000L, 5L, 100000L, 10L))
                .maintenanceMarginScaleK(100)
                .maxLeverage(TreeSortedMap.newMapWith(2000L, 5L, 100000L, 10L))
                .build();

        api.submitBinaryDataAsync(new BatchAddSymbolsCommand(futuresSymbol));
    }

    private void initializeUserAndSymbols() throws Exception {
        Future<CommandResultCode> future;

        // 添加用户user
        createUser(UID_1);
        createUser(UID_2);
        createUser(UID_3);
        createUser(UID_4);
        createUser(UID_5);

        // 调整user2余额
        deposit(UID_1, 200);
        deposit(UID_2, 100);
        deposit(UID_3, 1000);
        deposit(UID_4, 1000);
        deposit(UID_5, MAX_VALUE);

        // 添加期货符号
        CoreSymbolSpecification futuresSymbol = CoreSymbolSpecification.builder()
                .symbolId(symbolId)
                .type(SymbolType.FUTURES_CONTRACT_PERPETUAL)
                .baseCurrency(0)
                .quoteCurrency(quoteId)
                .makerFee(0)
                .takerFee(0)
                .baseScaleK(1_000)
                .quoteScaleK(100)
                .initMargin(1)
                .initMarginScaleK(100)
                .maintenanceMargin(TreeSortedMap.newMapWith(1000L, 5L, 100000L, 10L))
                .maxLeverage(TreeSortedMap.newMapWith(2000L, 5L, 100000L, 10L))
                .build();

        future = api.submitBinaryDataAsync(new BatchAddSymbolsCommand(futuresSymbol));
        System.out.println("Add symbol result: " + future.get());
    }

    private void printUser(long userId) throws ExecutionException, InterruptedException {
        Future<SingleUserReportResult> report = api.processReport(new SingleUserReportQuery(userId), 0);
        System.out.println("------------------");
        System.out.println("userId=" + userId);
        System.out.println("accounts: " + report.get());
    }

    private SingleUserReportResult getUserStatus(long userId) throws ExecutionException, InterruptedException {
        Future<SingleUserReportResult> future = api.processReport(new SingleUserReportQuery(userId), 0);
        SingleUserReportResult report = future.get();
        assertNotNull(report.getUserStatus());
        return report;
    }

    private void checkOrder(SingleUserReportResult report, int size) {
        assertEquals(report.getOrders().size(), size);
    }

    private void checkPosition(SingleUserReportResult report, int size) {
        assertEquals(report.getPositions().size(), size);
    }

    // 取消订单, 未开仓之前可以取消订单
    @Test
    public void testCancelSuccess() throws ExecutionException, InterruptedException {
        long userId = createRandomUserWithMoney();
        long orderId = getRandomTransactionId();
        ApiPlaceOrder order1 = ApiPlaceOrder.builder()
                .uid(userId)
                .orderId(orderId)
                .action(OrderAction.BID)
                .size(1L)
                .price(10000L)
                .symbol(symbolId)
                .orderType(OrderType.GTC)
                .marginMode(MarginMode.ISOLATED)
                .build();
        api.submitCommandAsync(order1);

        SingleUserReportResult userStatus1 = getUserStatus(userId);
        checkOrder(userStatus1, 1);
        checkPosition(userStatus1, 1);

        ApiCancelOrder order2 = ApiCancelOrder.builder()
                .uid(userId)
                .orderId(orderId)
                .symbol(symbolId)
                .build();
        api.submitCommandAsync(order2);

        SingleUserReportResult userStatus2 = getUserStatus(userId);
        checkOrder(userStatus2, 0);
        checkPosition(userStatus2, 0);
    }

    // 开单之后订单取消不会影响仓位
    @Test
    public void testCancelFailed() throws ExecutionException, InterruptedException {
        long userId1 = createRandomUserWithMoney();
        long orderId1 = getRandomTransactionId();
        ApiPlaceOrder order1 = ApiPlaceOrder.builder()
                .uid(userId1)
                .orderId(orderId1)
                .action(OrderAction.BID)
                .size(2L)
                .price(10000L)
                .symbol(symbolId)
                .orderType(OrderType.GTC)
                .marginMode(MarginMode.ISOLATED)
                .build();
        api.submitCommandAsync(order1);
        SingleUserReportResult userStatus1 = getUserStatus(userId1);
        checkOrder(userStatus1, 1);
        checkPosition(userStatus1, 1);

        long userId2 = createRandomUserWithMoney();
        long orderId2 = getRandomTransactionId();
        ApiPlaceOrder order2 = ApiPlaceOrder.builder()
                .uid(userId2)
                .orderId(orderId2)
                .action(OrderAction.ASK)
                .size(1L)
                .price(10000L)
                .symbol(symbolId)
                .orderType(OrderType.GTC)
                .marginMode(MarginMode.ISOLATED)
                .build();
        api.submitCommandAsync(order2);

        ApiCancelOrder order3 = ApiCancelOrder.builder()
                .uid(userId1)
                .orderId(orderId1)
                .symbol(symbolId)
                .build();
        api.submitCommandAsync(order3);

        SingleUserReportResult userStatus2 = getUserStatus(userId1);
        checkOrder(userStatus2, 0);
        checkPosition(userStatus2, 1);
    }

    // 刚开始没开出来时direction为empty
    @Test
    public void testPositionInitialStatus() throws ExecutionException, InterruptedException {
        long userId = createRandomUserWithMoney();
        createBid(userId, 1, 10000L);

        SingleUserReportResult userStatus = getUserStatus(userId);
        checkOrder(userStatus, 1);
        checkPosition(userStatus, 1);

        assertEquals(userStatus.getPositions().getFirst().get(0).profit, 0);
        assertEquals(userStatus.getPositions().getFirst().get(0).openPriceSum, 0);
        assertEquals(userStatus.getPositions().getFirst().get(0).openVolume, 0);
        assertEquals(userStatus.getPositions().getFirst().get(0).pendingBuySize, 1);
        assertEquals(userStatus.getPositions().getFirst().get(0).pendingSellSize, 0);
        assertEquals(userStatus.getPositions().getFirst().get(0).direction, PositionDirection.LONG);
    }

    // Long/Short仓位类型正确
    @Test
    public void testOpenLong() throws ExecutionException, InterruptedException {
        long userId1 = createRandomUserWithMoney();
        createBid(userId1, 1, 10000L);

        SingleUserReportResult userStatus = getUserStatus(userId1);
        checkPosition(userStatus, 1);
        assertEquals(userStatus.getPositions().getFirst().get(0).pendingBuySize, 1);

        long userId2 = createRandomUserWithMoney();
        createAsk(userId2, 1, 10000L);

        SingleUserReportResult userStatus1 = getUserStatus(userId1);
        checkPosition(userStatus1, 1);

        assertEquals(userStatus1.getPositions().getFirst().get(0).profit, 0);
        assertEquals(userStatus1.getPositions().getFirst().get(0).openPriceSum, 10000);
        assertEquals(userStatus1.getPositions().getFirst().get(0).openVolume, 1);
        assertEquals(userStatus1.getPositions().getFirst().get(0).pendingBuySize, 0);
        assertEquals(userStatus1.getPositions().getFirst().get(0).pendingSellSize, 0);
        assertEquals(userStatus1.getPositions().getFirst().get(0).direction, PositionDirection.LONG);

        SingleUserReportResult userStatus2 = getUserStatus(userId2);
        checkPosition(userStatus2, 1);

        assertEquals(userStatus2.getPositions().getFirst().get(0).profit, 0);
        assertEquals(userStatus2.getPositions().getFirst().get(0).openPriceSum, 10000);
        assertEquals(userStatus2.getPositions().getFirst().get(0).openVolume, 1);
        assertEquals(userStatus2.getPositions().getFirst().get(0).pendingBuySize, 0);
        assertEquals(userStatus2.getPositions().getFirst().get(0).pendingSellSize, 0);
        assertEquals(userStatus2.getPositions().getFirst().get(0).direction, PositionDirection.SHORT);
    }

    // 做空平仓, 仓位开出来以后需要用户主动开一个相反方向的订单
    @Test
    public void testCloseShortPosition() throws ExecutionException, InterruptedException {
        long userId1 = createRandomUserWithMoney();
        createAsk(userId1, 1, 10000L);

        SingleUserReportResult userStatus = getUserStatus(userId1);
        checkPosition(userStatus, 1);
        assertEquals(userStatus.getPositions().getFirst().get(0).pendingSellSize, 1);
        assertEquals(userStatus.getPositions().getFirst().get(0).pendingBuySize, 0);
        assertEquals(userStatus.getPositions().getFirst().get(0).direction, PositionDirection.SHORT);

        long userId2 = createRandomUserWithMoney();
        createBid(userId2, 1, 10000L);

        SingleUserReportResult userStatus1 = getUserStatus(userId1);
        // 确认仓位信息, 保证做空确实开出来了
        checkPosition(userStatus1, 1);
        assertEquals(userStatus.getPositions().getFirst().get(0).pendingSellSize, 1);
        assertEquals(userStatus.getPositions().getFirst().get(0).pendingBuySize, 0);
        assertEquals(userStatus1.getPositions().getFirst().get(0).direction, PositionDirection.SHORT);

        // userId1下反向单, 主动平仓
        createBid(userId1, 1, 10000L);

        // userId4需要吃掉userId1的平仓单后userId1才能平仓成功
        long userId4 = createRandomUserWithMoney();
        createAsk(userId4, 1, 10000L);

        SingleUserReportResult userStatus2 = getUserStatus(userId1);
        checkPosition(userStatus2, 0);
    }

    // 做多平仓, 仓位开出来以后需要用户主动开一个相反方向的订单
    @Test
    public void testCloseLongPosition() throws ExecutionException, InterruptedException {
        long userId1 = createRandomUserWithMoney();
        createBid(userId1, 1, 10000L);

        SingleUserReportResult userStatus = getUserStatus(userId1);
        checkPosition(userStatus, 1);
        assertEquals(userStatus.getPositions().getFirst().get(0).pendingBuySize, 1);
        assertEquals(userStatus.getPositions().getFirst().get(0).pendingSellSize, 0);
        assertEquals(userStatus.getPositions().getFirst().get(0).direction, PositionDirection.LONG);

        long userId2 = createRandomUserWithMoney();
        createAsk(userId2, 1, 10000L);

        SingleUserReportResult userStatus1 = getUserStatus(userId1);
        // 确认仓位信息, 保证做多确实开出来了
        checkPosition(userStatus1, 1);
        assertEquals(userStatus.getPositions().getFirst().get(0).pendingBuySize, 1);
        assertEquals(userStatus.getPositions().getFirst().get(0).pendingSellSize, 0);
        assertEquals(userStatus1.getPositions().getFirst().get(0).direction, PositionDirection.LONG);

        // userId1下反向单, 主动平仓
        createAsk(userId1, 1, 10000L);

        // userId4需要吃掉userId1的平仓单后userId1才能平仓成功
        long userId4 = createRandomUserWithMoney();
        createBid(userId4, 1, 10000L);

        SingleUserReportResult userStatus2 = getUserStatus(userId1);
        checkPosition(userStatus2, 0);
    }

    // 做多后市场升值, 平仓检查收益是否符合预期
    @Test
    public void testLongProfit() throws ExecutionException, InterruptedException {
        long userId1 = createRandomUserWithMoney();
        createBid(userId1, 1, 10000L);

        SingleUserReportResult userStatus = getUserStatus(userId1);
        checkPosition(userStatus, 1);
        assertEquals(userStatus.getPositions().getFirst().get(0).pendingBuySize, 1);
        assertEquals(userStatus.getPositions().getFirst().get(0).pendingSellSize, 0);
        assertEquals(userStatus.getPositions().getFirst().get(0).direction, PositionDirection.LONG);

        long userId2 = createRandomUserWithMoney();
        // orderId2成交后userId1做多仓位才算开出来
        createAsk(userId2, 1, 10000L);

        SingleUserReportResult userStatus1 = getUserStatus(userId1);
        // 确认仓位信息, 保证做多确实开出来了
        checkPosition(userStatus1, 1);
        assertEquals(userStatus.getPositions().getFirst().get(0).pendingBuySize, 1);
        assertEquals(userStatus.getPositions().getFirst().get(0).pendingSellSize, 0);
        assertEquals(userStatus1.getPositions().getFirst().get(0).direction, PositionDirection.LONG);

        // 更新当前市场价格
        updateCurrentPriceTo(10500);

        // userId1下反向单, 主动平仓
        createAsk(userId1, 1, 10500L);

        // userId6需要吃掉userId1的平仓单后userId1才能平仓成功
        long userId6 = createRandomUserWithMoney();
        createBid(userId6, 1, 10500L);

        // check profit, 平仓后应该按照市场价给定收益
        SingleUserReportResult userStatus2 = getUserStatus(userId1);
        checkPosition(userStatus2, 0);
        assertEquals(userStatus2.getAccounts().get(quoteId), MAX_VALUE + 5000);
    }

    // 做空后市场升值, 平仓检查收益是否符合预期
    @Test
    public void testShortProfit() throws ExecutionException, InterruptedException {
        long userId1 = createRandomUserWithMoney();
        createAsk(userId1, 1, 10000L);

        SingleUserReportResult userStatus = getUserStatus(userId1);
        checkPosition(userStatus, 1);
        assertEquals(userStatus.getPositions().getFirst().get(0).pendingBuySize, 0);
        assertEquals(userStatus.getPositions().getFirst().get(0).pendingSellSize, 1);
        assertEquals(userStatus.getPositions().getFirst().get(0).direction, PositionDirection.SHORT);

        long userId2 = createRandomUserWithMoney();
        // orderId2成交后userId1做多仓位才算开出来
        createBid(userId2, 1, 10000L);

        SingleUserReportResult userStatus1 = getUserStatus(userId1);
        // 确认仓位信息, 保证做多确实开出来了
        checkPosition(userStatus1, 1);
        assertEquals(userStatus1.getPositions().getFirst().get(0).pendingBuySize, 0);
        assertEquals(userStatus1.getPositions().getFirst().get(0).pendingSellSize, 0);
        assertEquals(userStatus1.getPositions().getFirst().get(0).direction, PositionDirection.SHORT);

        // 更新当前市场价格
        updateCurrentPriceTo(10500);

        // userId1下反向单, 主动平仓
        createBid(userId1, 1, 10500);

        // userId6需要吃掉userId1的平仓单后userId1才能平仓成功
        long userId6 = createRandomUserWithMoney();
        createAsk(userId6, 1, 10500);

        // check profit, 平仓后应该按照市场价给定收益
        SingleUserReportResult userStatus2 = getUserStatus(userId1);
        checkPosition(userStatus2, 0);
        assertEquals(userStatus2.getAccounts().get(quoteId), MAX_VALUE - 5000);
    }

    private void createBid(long userId, int size, long price) {
        ApiPlaceOrder order1 = ApiPlaceOrder.builder()
                .uid(userId)
                .orderId(getRandomTransactionId())
                .action(OrderAction.BID)
                .size(size)
                .price(price)
                .symbol(symbolId)
                .orderType(OrderType.GTC)
                .marginMode(MarginMode.ISOLATED)
                .build();
        api.submitCommandAsync(order1);
    }

    private void createAsk(long userId, int size, long price) {
        ApiPlaceOrder order1 = ApiPlaceOrder.builder()
                .uid(userId)
                .orderId(getRandomTransactionId())
                .action(OrderAction.ASK)
                .size(size)
                .price(price)
                .symbol(symbolId)
                .orderType(OrderType.GTC)
                .marginMode(MarginMode.ISOLATED)
                .build();
        api.submitCommandAsync(order1);
    }

    private void updateCurrentPriceTo(int price) {
        // 模拟两个用户分别下买卖单, 使得成交价为price
        long userId1 = createRandomUserWithMoney();
        long orderId1 = getRandomTransactionId();

        ApiPlaceOrder order1 = ApiPlaceOrder.builder()
                .uid(userId1)
                .orderId(orderId1)
                .action(OrderAction.BID)
                .size(10L)
                .price(price)
                .symbol(symbolId)
                .orderType(OrderType.GTC)
                .marginMode(MarginMode.ISOLATED)
                .build();
        api.submitCommandAsync(order1);

        long userId2 = createRandomUserWithMoney();
        long orderId2 = getRandomTransactionId();
        // orderId2成交后userId1做多仓位才算开出来
        ApiPlaceOrder order2 = ApiPlaceOrder.builder()
                .uid(userId2)
                .orderId(orderId2)
                .action(OrderAction.ASK)
                .size(10L)
                .price(price)
                .symbol(symbolId)
                .orderType(OrderType.GTC)
                .marginMode(MarginMode.ISOLATED)
                .build();
        api.submitCommandAsync(order2);
    }

    // 测试双向持仓, 双向持仓后会先平掉之前相反方向的仓位
    @Test
    public void testOpenTwoWayPosition() throws ExecutionException, InterruptedException {
        long userId1 = createRandomUserWithMoney();
        createBid(userId1, 1, 10000L);

        SingleUserReportResult userStatus = getUserStatus(userId1);
        checkPosition(userStatus, 1);
        assertEquals(userStatus.getPositions().getFirst().get(0).pendingBuySize, 1);

        long userId2 = createRandomUserWithMoney();
        // order2成交后, userId1持有long仓位
        createAsk(userId2, 1, 10000L);
        SingleUserReportResult userStatus2 = getUserStatus(userId1);
        checkPosition(userStatus2, 1);
        assertEquals(userStatus2.getPositions().getFirst().get(0).direction, PositionDirection.LONG);

        long userId3 = createRandomUserWithMoney();
        createBid(userId3, 1, 9000L);

        // 假设userId1想开short
        createAsk(userId1, 1, 9000L);

        SingleUserReportResult userStatus3 = getUserStatus(userId1);
        // 成交后position会被清空
        checkPosition(userStatus3, 0);
        // 成交后用户资产需要更新
        assertEquals(userStatus3.getAccounts().get(quoteId), MAX_VALUE - 10000);
    }

    // 持仓到警戒线, 发预警通知 - 做多
//    @Test
    public void testLongPositionWarning() throws ExecutionException, InterruptedException {
        long userId1 = createRandomUserWithMoney(1000);
        createBid(userId1, 1, 10000L);

        SingleUserReportResult userStatus = getUserStatus(userId1);
        checkPosition(userStatus, 1);
        assertEquals(userStatus.getPositions().getFirst().get(0).pendingBuySize, 1);

        long userId2 = createRandomUserWithMoney(1000);
        // order2成交后, userId1持有long仓位
        createAsk(userId2, 1, 10000L);
        SingleUserReportResult userStatus2 = getUserStatus(userId1);
        checkPosition(userStatus2, 1);
        assertEquals(userStatus2.getPositions().getFirst().get(0).direction, PositionDirection.LONG);

        // 模拟市价波动
        updateCurrentPriceTo(8000);

        // trigger强平逻辑
        exchangeCore.liquidationScanner.triggerOnce();
        // should raise Margin call warning
    }

    // 持仓到警戒线, 发预警通知 - 做空
//    @Test
    public void testShortPositionWarning() throws ExecutionException, InterruptedException {
        long userId1 = createRandomUserWithMoney(1000);
        createAsk(userId1, 1, 10000L);

        SingleUserReportResult userStatus = getUserStatus(userId1);
        checkPosition(userStatus, 1);
        assertEquals(userStatus.getPositions().getFirst().get(0).pendingSellSize, 1);

        long userId2 = createRandomUserWithMoney(1000);
        // order2成交后, userId1持有long仓位
        createBid(userId2, 1, 10000L);
        SingleUserReportResult userStatus2 = getUserStatus(userId1);
        checkPosition(userStatus2, 1);
        assertEquals(userStatus2.getPositions().getFirst().get(0).direction, PositionDirection.SHORT);

        // 模拟市价波动
        updateCurrentPriceTo(11000);

        // trigger强平逻辑
        exchangeCore.liquidationScanner.triggerOnce();
        // should raise Margin call warning
    }

    // 做多被强制平仓
//    @Test
    public void testLongPostionForcedLiquadate() throws ExecutionException, InterruptedException {
        exchangeCore.liquidationScanner.stop(1, TimeUnit.MINUTES);
        long userId1 = createRandomUserWithMoney(1000);
        createBid(userId1, 1, 10000L);

        SingleUserReportResult userStatus = getUserStatus(userId1);
        checkPosition(userStatus, 1);
        assertEquals(userStatus.getPositions().getFirst().get(0).pendingBuySize, 1);

        long userId2 = createRandomUserWithMoney(1000);
        // order2成交后, userId1持有long仓位
        createAsk(userId2, 1, 10000L);
        SingleUserReportResult userStatus2 = getUserStatus(userId1);
        checkPosition(userStatus2, 1);
        assertEquals(userStatus2.getPositions().getFirst().get(0).direction, PositionDirection.LONG);

        // 模拟市价波动
        createBiasPrice(9000);
        sleep(500);
        int cnt = 500;
        for (int i = 0; i < cnt; i++) {
            updateCurrentPriceTo(9000);
        }

        // trigger强平逻辑
        exchangeCore.liquidationScanner.triggerOnce();
        // should trigger liquidate
        // search log Liquidated: uid=
//        SingleUserReportResult userStatus3 = getUserStatus(userId1);
//        checkPosition(userStatus3, 0);
    }

    private void sleep(int sleepMil) {
        try {
            Thread.sleep(sleepMil);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
    }

    private void createBiasPrice(int price) {
        int time = 100;
        int cnt = 10;
        for (int i = 0; i < cnt; i++) {
            long userId = createRandomUserWithMoney();
            createBid(userId, 100, price - i * 10);
            sleep(time);
        }
        for (int i = 0; i < cnt; i++) {
            long userId = createRandomUserWithMoney();
            createAsk(userId, 100, price + i * 10);
            sleep(time);
        }

        long userId1 = createRandomUserWithMoney();
        createBid(userId1, 5, price - 5);
        sleep(time);
        long userId2 = createRandomUserWithMoney();
        createAsk(userId2, 5, price + 5);
        sleep(time);
    }

    // 做空被强制平仓
//    @Test
    public void testShortPositionForcedLiquidate() throws ExecutionException, InterruptedException {
        exchangeCore.liquidationScanner.stop(1, TimeUnit.MINUTES);
        long userId1 = createRandomUserWithMoney(1000);
        createAsk(userId1, 1, 10000L);

        SingleUserReportResult userStatus = getUserStatus(userId1);
        checkPosition(userStatus, 1);
        assertEquals(userStatus.getPositions().getFirst().get(0).pendingSellSize, 1);

        long userId2 = createRandomUserWithMoney(1000);
        // order2成交后, userId1持有long仓位
        createBid(userId2, 1, 10000L);
        SingleUserReportResult userStatus2 = getUserStatus(userId1);
        checkPosition(userStatus2, 1);
        assertEquals(userStatus2.getPositions().getFirst().get(0).direction, PositionDirection.SHORT);

        // 模拟市价波动
        createBiasPrice(11001);
        sleep(500);
        int cnt = 500;
        for (int i = 0; i < cnt; i++) {
            updateCurrentPriceTo(11001);
        }

        // trigger强平逻辑
        exchangeCore.liquidationScanner.triggerOnce();
        // should trigger liquidate
        // search log Liquidated: uid=
        // SingleUserReportResult userStatus3 = getUserStatus(userId1);
        // checkPosition(userStatus3, 0);
    }

    // 模拟初始保证金不够的场景
    @Test
    public void testBalanceNotEnough() throws Exception {
        // suppose current price is 10000
        // userId1下2个期货买单, 价格为10000
        long userId = createRandomUserWithMoney(50);
        long orderId = getRandomTransactionId();
        ApiPlaceOrder order = ApiPlaceOrder.builder()
                .uid(userId)
                .orderId(orderId)
                .action(OrderAction.ASK)
                .size(10L)
                .price(10000)
                .symbol(symbolId)
                .orderType(OrderType.GTC)
                .marginMode(MarginMode.ISOLATED)
                .build();
        api.submitCommandAsync(order);

        SingleUserReportResult userStatus = getUserStatus(userId);
        // 成交后position会被清空
        checkPosition(userStatus, 0);
        checkOrder(userStatus, 0);
    }

    private void doTriggerPlaceOrders() {
        int ordersCount = 1000;
        List<ApiPlaceOrder> orders = new ArrayList<>();

        // 下 10 个期货买单
        for (int i = 0; i < ordersCount; i++) {
            ApiPlaceOrder order = ApiPlaceOrder.builder()
                    .uid(UID_5)
                    .orderId(getRandomTransactionId())
                    .action(OrderAction.BID)
                    .size(1L)
                    .price(10000L)
                    .symbol(symbolId)
                    .orderType(OrderType.GTC)
                    .build();
            orders.add(order);
            api.submitCommandAsync(order);
        }
    }

    @Test
    public void testFuturesTrading() throws Exception {
        int size = 10;
        long price = 10000L;
        int ordersCount = 10;
        List<ApiCommand> cmds = new ArrayList<>();

        long userId1 = createRandomUserWithMoney();
        long userId2 = createRandomUserWithMoney();
        long userId3 = createRandomUserWithMoney();
        long userId4 = createRandomUserWithMoney();

        ApiPlaceOrder order;
        // 模拟撮合：5 单成交，5 单拒绝
        for (int i = 0; i < ordersCount; i++) {
            if (i < ordersCount / 2) {
                order = ApiPlaceOrder.builder()
                        .uid(userId1)
                        .orderId(getRandomTransactionId())
                        .action(OrderAction.BID)
                        .size(size)
                        .price(price)
                        .symbol(symbolId)
                        .orderType(OrderType.GTC)
                        .marginMode(MarginMode.ISOLATED)
                        .build();
            } else {
                order = ApiPlaceOrder.builder()
                        .uid(userId2)
                        .orderId(getRandomTransactionId())
                        .action(OrderAction.ASK)
                        .size(size)
                        .price(price)
                        .symbol(symbolId)
                        .orderType(OrderType.IOC)
                        .marginMode(MarginMode.ISOLATED)
                        .build();
            }
            cmds.add(order);
        }

        createBid(userId3, 10, 11000L);
        createBid(userId3, 10, 12000L);
        createBid(userId3, 10, 13000L);
        createBid(userId3, 10, 14000L);
        createAsk(userId4, 10, 9900L);
        createAsk(userId4, 10, 9800L);
        createAsk(userId4, 10, 9900L);
        createAsk(userId4, 10, 9600L);

        api.submitCommandsSync(cmds);

        SingleUserReportResult userStatus1 = getUserStatus(userId1);
        // 成交后postion会被清空
        checkPosition(userStatus1, 1);
        assertEquals(userStatus1.getPositions().getFirst().get(0).direction, PositionDirection.LONG);
        assertEquals(userStatus1.getPositions().getFirst().get(0).pendingSellSize, 0);
        assertEquals(userStatus1.getPositions().getFirst().get(0).pendingBuySize, 0);
        assertEquals(userStatus1.getPositions().getFirst().get(0).profit, 0);
        assertEquals(userStatus1.getPositions().getFirst().get(0).openVolume, ordersCount * size / 2);
        assertEquals(userStatus1.getPositions().getFirst().get(0).openPriceSum, ordersCount * size / 2 * price);

        SingleUserReportResult userStatus2 = getUserStatus(userId2);
        // 成交后postion会被清空
        checkPosition(userStatus2, 1);
        assertEquals(userStatus2.getPositions().getFirst().get(0).direction, PositionDirection.SHORT);
        assertEquals(userStatus2.getPositions().getFirst().get(0).pendingSellSize, 0);
        assertEquals(userStatus2.getPositions().getFirst().get(0).pendingBuySize, 0);
        assertEquals(userStatus2.getPositions().getFirst().get(0).profit, 0);
        assertEquals(userStatus2.getPositions().getFirst().get(0).openVolume, ordersCount * size / 2);
        assertEquals(userStatus2.getPositions().getFirst().get(0).openPriceSum, ordersCount * size / 2 * price);
    }

    // withdraw时应考虑用户当前持仓
    @Test
    public void testWithdrawConstrain() throws Exception {
        // suppose current price is 10000
        // userId1下2个期货买单, 价格为10000
        int balance = 1000;
        int delta = -1000;
        long userId1 = createRandomUserWithMoney(balance);
        createBid(userId1, 1, 10000L);

        SingleUserReportResult userStatus = getUserStatus(userId1);
        // 成交后postion会被清空
        checkPosition(userStatus, 1);
        checkOrder(userStatus, 1);
        // 账面资金和初始资金一致
        assertEquals(userStatus.getAccounts().get(quoteId), balance);
        assertEquals(userStatus.getPositions().getFirst().get(0).direction, PositionDirection.LONG);
        assertEquals(userStatus.getPositions().getFirst().get(0).openVolume, 0);
        assertEquals(userStatus.getPositions().getFirst().get(0).pendingBuySize, 1);
        assertEquals(userStatus.getPositions().getFirst().get(0).pendingSellSize, 0);

        // do withdraw -> 此时期待结果为提现失败
        ApiAdjustUserBalance order2 = ApiAdjustUserBalance.builder()
                .uid(userId1)
                .currency(quoteId)
                .amount(delta)
                .transactionId(getRandomTransactionId())
                .build();
        CompletableFuture<CommandResultCode> result = api.submitCommandAsync(order2);
        CommandResultCode code = result.get();
        assertEquals(code, CommandResultCode.RISK_NSF);

        SingleUserReportResult userStatus1 = getUserStatus(userId1);
        assertEquals(userStatus1.getAccounts().get(quoteId), balance);
    }

    // 先下future单, 再下现货单。现货下单时需要考虑期货持仓
    @Test
    public void testFutureThenSpot() throws Exception {
        // suppose current price is 10000
        // userId1下2个期货买单, 价格为10000
        int balance = 1000;
        long userId1 = createRandomUserWithMoney(balance);
        createBid(userId1, 1, 10000L);

        SingleUserReportResult userStatus = getUserStatus(userId1);
        // 成交后position会被清空
        checkPosition(userStatus, 1);
        checkOrder(userStatus, 1);
        // 账面资金和初始资金一致
        assertEquals(userStatus.getAccounts().get(quoteId), balance);
        assertEquals(userStatus.getPositions().getFirst().get(0).direction, PositionDirection.LONG);
        assertEquals(userStatus.getPositions().getFirst().get(0).openVolume, 0);
        assertEquals(userStatus.getPositions().getFirst().get(0).pendingBuySize, 1);
        assertEquals(userStatus.getPositions().getFirst().get(0).pendingSellSize, 0);

        // 下现货单
        // 1. 生产现货交易对
        initCurrencies();
        initSpotSymbol();

        // 2. 下单
        ApiPlaceOrder order2 = ApiPlaceOrder.builder()
                .uid(userId1)
                .orderId(getRandomTransactionId())
                .action(OrderAction.BID)
                .size(1L)
                .price(1L)
                .symbol(symbolId + 1)
                .reservePrice(1L)
                .orderType(OrderType.GTC)
                .marginMode(MarginMode.ISOLATED)
                .build();
        CompletableFuture<CommandResultCode> result = api.submitCommandAsync(order2);
        CommandResultCode code = result.get();
        assertEquals(code, CommandResultCode.RISK_NSF);

        SingleUserReportResult userStatus1 = getUserStatus(userId1);
        assertEquals(userStatus1.getAccounts().get(quoteId), balance);
    }

}