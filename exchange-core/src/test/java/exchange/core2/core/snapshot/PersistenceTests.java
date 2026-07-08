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
package exchange.core2.core.snapshot;

import exchange.core2.core.common.cmd.OrderCommand;
import exchange.core2.core.event.IEventsHandler4Test;
import exchange.core2.core.event.SimpleEventsProcessor4Test;
import exchange.core2.core.processors.liquidation.LiquidationEngine;
import exchange.core2.core.common.*;
import exchange.core2.core.common.api.*;
import exchange.core2.core.common.api.reports.SingleUserReportResult;
import exchange.core2.core.common.api.reports.TotalCurrencyBalanceReportResult;
import exchange.core2.core.common.cmd.CommandResultCode;
import exchange.core2.core.common.config.InitialStateConfiguration;
import exchange.core2.core.common.config.PerformanceConfiguration;
import exchange.core2.core.common.config.SerializationConfiguration;
import exchange.core2.tests.util.*;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.collections.impl.map.sorted.mutable.TreeSortedMap;
import org.hamcrest.core.Is;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.function.ObjLongConsumer;

import static exchange.core2.tests.util.TestConstants.*;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.spy;

@Slf4j
@ExtendWith(MockitoExtension.class)
public class PersistenceTests {

    private static final int SYMBOL_FUTURES = 41;
    private static final int CURRENCY_FUT = 42;

    private SimpleEventsProcessor4Test processor;

    private IEventsHandler4Test handler = spy(IEventsHandler4Test.handler);

    @BeforeEach
    public void before() {
        processor = new SimpleEventsProcessor4Test(handler, true);
    }

    // 创建期货合约规格
    private static CoreSymbolSpecification createFuturesSpec() {
        return CoreSymbolSpecification.builder()
                .symbolId(SYMBOL_FUTURES)
                .type(SymbolType.FUTURES_CONTRACT_PERPETUAL)
                .baseCurrency(100)                // 基础货币: XBT
                .quoteCurrency(CURRENCY_FUT)      // 结算货币: FUT
                .baseScaleK(1)            // 1手 = 1,000,000 satoshi (0.01 BTC)
                .quoteScaleK(1)              // 最小价格变动: 10,000 units
                .takerFee(190)                   // Taker费率: 190 units/手
                .makerFee(70)                    // Maker费率: 70 units/手
                .feeScaleK(1_000_000)             // 费率缩放因子
                .initMargin(1)                    // 初始保证金率 1% (1/100)
                .initMarginScaleK(100)
                .maintenanceMargin(TreeSortedMap.newMapWith(
                        0L, 5_000L,                  // 0-1000名义价值: 维持保证金率0.5%
                        1_000_000L, 7_000L,               // 100万以上: 维持保证金率0.7%
                        10_000_000L, 10_000L))            // 1000万以上: 维持保证金率1%
                .maintenanceMarginScaleK(1_000_000)
                .maxLeverage(TreeSortedMap.newMapWith(
                        0L, 100L,                    // 0-1000名义价值: 最大杠杆100倍
                        1_000_000L, 50L,                  // 100万以上: 最大杠杆50倍
                        10_000_000L, 20L))                // 1000万以上: 最大杠杆20倍
                .build();
    }

    @Test
    public void testPersist() throws ExecutionException, InterruptedException {
        PerformanceConfiguration performanceConfiguration = PerformanceConfiguration.throughputPerformanceBuilder()
                .ringBufferSize(32 * 1024)
                .matchingEnginesNum(1)
                .riskEnginesNum(1)
                .msgsInGroupLimit(512)
                .build();
        TestDataParameters testDataParameters = TestDataParameters.singlePairMarginBuilder()
                .preFillMode(TestOrdersGeneratorConfig.PreFillMode.ORDERS_NUMBER_PLUS_QUARTER)
                .build();
        persistenceTestImpl(performanceConfiguration, testDataParameters, processor);
    }

    public static void persistenceTestImpl(final PerformanceConfiguration performanceConfiguration,
                                           final TestDataParameters testDataParameters, ObjLongConsumer<OrderCommand> consumer) throws InterruptedException, ExecutionException {


        final long stateId;

        final ExchangeTestContainer.TestDataFutures testDataFutures = ExchangeTestContainer.prepareTestDataAsync(testDataParameters, 20250715);

        final String exchangeId = String.format("%012X", System.currentTimeMillis());
        final InitialStateConfiguration firstStartConfig = InitialStateConfiguration.cleanStart(exchangeId);

        final long originalPrefillStateHash;

        try (final ExchangeTestContainer container = ExchangeTestContainer.create(performanceConfiguration, firstStartConfig, SerializationConfiguration.DISK_SNAPSHOT_ONLY, consumer)) {

            container.getExchangeCore().liquidationEngines.forEach(LiquidationEngine::stop);
            container.loadSymbolsUsersAndPrefillOrders(testDataFutures);
            doExtra(container);

            log.info("Creating snapshot...");
            stateId = System.currentTimeMillis() * 1000;
            final ApiPersistState apiPersistState = ApiPersistState.builder().dumpId(stateId).build();
            try (ExecutionTime ignore = new ExecutionTime(t -> log.debug("Snapshot {} created in {}", stateId, t))) {
                final CommandResultCode resultCode = container.getApi().submitCommandAsync(apiPersistState).get();
                assertThat(resultCode, Is.is(CommandResultCode.SUCCESS));
            }

            log.info("Requesting state hash...");
            originalPrefillStateHash = container.requestStateHash();

            System.gc();
            Thread.sleep(200);

            final InitialStateConfiguration fromSnapshotConfig = InitialStateConfiguration.fromSnapshotOnly(exchangeId, stateId, 0);

            log.debug("Creating new exchange from persisted state...");
            final long tLoad = System.currentTimeMillis();
            try (final ExchangeTestContainer recreatedContainer = ExchangeTestContainer.create(performanceConfiguration, fromSnapshotConfig, SerializationConfiguration.DISK_SNAPSHOT_ONLY, consumer)) {

                recreatedContainer.getExchangeCore().liquidationEngines.forEach(LiquidationEngine::stop);

                recreatedContainer.getApi().submitRecoverCommandAsync(ApiRecoverState.builder().snapshotId(fromSnapshotConfig.getSnapshotId()).build()).get();

                // simple sync query in order to wait until core is started to respond
                recreatedContainer.totalBalanceReport();

                float loadTimeSec = (float) (System.currentTimeMillis() - tLoad) / 1000.0f;
                log.debug("Load+start time: {}s", String.format("%.3f", loadTimeSec));

                log.info("Requesting state hash...");
                final long restoredPrefillStateHash = recreatedContainer.requestStateHash();
                assertThat(restoredPrefillStateHash, is(originalPrefillStateHash));

                assertTrue(recreatedContainer.totalBalanceReport().isGlobalBalancesAllZero());
                doCheckExtra(container);
            }
            System.gc();
            Thread.sleep(200);
        }
    }

    private static void doCheckExtra(ExchangeTestContainer container) throws ExecutionException, InterruptedException {
        container.validateUserState(UID_1, profile -> {
            SingleUserReportResult.Position pos = profile.getPositions().get(SYMBOL_FUTURES).get(0);
            assertNotNull(pos);
            assertEquals(40L, pos.openVolume);
            assertEquals(0L, pos.pendingSellSize);
            assertEquals(50L, pos.pendingBuySize);
            assertEquals(50, pos.leverage);
            assertEquals(50000000L, pos.extraMargin);
            assertEquals(850000L, pos.unrealizedProfit);
            assertEquals(-1240085L, pos.liquidationPrice);
            assertEquals(131, pos.marginRatioScaleK);
        });
        container.validateUserState(UID_2, profile -> {
            // 仓位应被部分或全部平仓
            List<SingleUserReportResult.Position> pos = profile.getPositions().get(SYMBOL_FUTURES);
            assertTrue(pos == null);
            // 账户余额应为负或接近零
            long balance = profile.getAccounts().get(CURRENCY_FUT);
            assertTrue(balance != 25000L, "Balance should be negative after liquidation");
        });
        container.validateUserState(UID_4, profile -> {
            assertThat(profile.getPositions().get(SYMBOL_FUTURES).size(), Is.is(2));
            assertThat(profile.getPositions().get(SYMBOL_FUTURES).get(0).direction, Is.is(PositionDirection.LONG));
            assertThat(profile.getPositions().get(SYMBOL_FUTURES).get(0).leverage, Is.is(10));
            assertThat(profile.getPositions().get(SYMBOL_FUTURES).get(1).direction, Is.is(PositionDirection.SHORT));
            assertThat(profile.getPositions().get(SYMBOL_FUTURES).get(1).leverage, Is.is(10));
        });
    }

    private static void doExtra(ExchangeTestContainer container) throws ExecutionException, InterruptedException {

        container.initFeeSymbols();

        // 2. 添加期货合约
        CoreSymbolSpecification spec = createFuturesSpec();
        container.addCurrency(spec.baseCurrency, 0);
        container.addCurrency(spec.quoteCurrency, 0);
        container.addSymbol(spec);

        // 3. 创建用户并充值
        final long UID_ISOLATED = UID_1; // 逐仓用户
        final long UID_CROSS = UID_2;    // 全仓用户
        final long UID_LIQ = UID_3;
        final long UID_HEDGE = UID_4; // 双向持仓用户

        // 逐仓用户充值1,000,000 FUT (足够开仓和追加保证金)
        container.createUserWithMoney(UID_ISOLATED, CURRENCY_FUT, 1_000_000_000L);

        // 全仓用户充值500,000 FUT (临界值，可能触发强平)
        container.createUserWithMoney(UID_CROSS, CURRENCY_FUT, 1000_000L);
        container.createUserWithMoney(UID_LIQ, CURRENCY_FUT, MAX_VALUE);
        container.createUserWithMoney(UID_HEDGE, CURRENCY_FUT, MAX_VALUE);

        // 设置UID_HEDGE为双向持仓
        container.submitCommandSync(ApiAdjustPositionMode.builder()
                .uid(UID_HEDGE)
                .positionMode(PositionMode.HEDGE)
                .build(), CommandResultCode.SUCCESS);

        // 4. 设置初始标记价格 (10,000 FUT per 1 XBT)
        container.initMarkPrice(SYMBOL_FUTURES, 10_000);

        // 5. 逐仓用户下单 (多头)
        final ApiPlaceOrder isolatedOrder = ApiPlaceOrder.builder()
                .uid(UID_ISOLATED)
                .orderId(5001L)
                .price(10_000L)          // 价格: 10,000 FUT
                .size(100L)              // 100手 (100 * 0.01 BTC = 1 BTC)
                .action(OrderAction.BID) // 买入开多
                .orderType(OrderType.GTC)
                .symbol(SYMBOL_FUTURES)
                .leverage(50)            // 50倍杠杆
                .marginMode(MarginMode.ISOLATED) // 逐仓模式
                .build();

        container.submitCommandSync(isolatedOrder, cmd ->
                assertThat(cmd.resultCode, is(CommandResultCode.SUCCESS)));

        // 6. 全仓用户下单 (空头)
        final ApiPlaceOrder crossOrder = ApiPlaceOrder.builder()
                .uid(UID_CROSS)
                .orderId(5002L)
                .price(10_000L)         // 价格: 10,000 FUT
                .size(50L)              // 50手 (0.5 BTC)
                .action(OrderAction.ASK) // 卖出开空
                .orderType(OrderType.GTC)
                .symbol(SYMBOL_FUTURES)
                .marginMode(MarginMode.CROSS) // 全仓模式
                .build();

        container.submitCommandSync(crossOrder, cmd ->
                assertThat(cmd.resultCode, is(CommandResultCode.SUCCESS)));

        // 7. 验证初始持仓状态
        container.validateUserState(UID_ISOLATED, profile -> {
            SingleUserReportResult.Position pos = profile.getPositions().get(SYMBOL_FUTURES).get(0);
            assertNotNull(pos);
            assertEquals(PositionDirection.LONG, pos.direction);
            assertEquals(50L, pos.openVolume); // 100手
            assertEquals(50, pos.leverage);
            assertEquals(MarginMode.ISOLATED, pos.marginMode);
            assertEquals(50L, pos.pendingBuySize);
        });

        final ApiPlaceOrder reduceOnlyOrder = ApiPlaceOrder.builder()
                .uid(UID_ISOLATED)
                .orderId(5011L)
                .price(11_000L)          // 价格: 10,000 FUT
                .size(10L)              // 100手 (100 * 0.01 BTC = 1 BTC)
                .action(OrderAction.ASK) // 买入开多
                .orderType(OrderType.GTC)
                .symbol(SYMBOL_FUTURES)
                .leverage(50)            // 50倍杠杆
                .reduceOnly(true)
                .marginMode(MarginMode.ISOLATED) // 逐仓模式
                .build();

        container.submitCommandSync(reduceOnlyOrder, cmd ->
                assertThat(cmd.resultCode, is(CommandResultCode.SUCCESS)));

        container.validateUserState(UID_ISOLATED, profile -> {
            SingleUserReportResult.Position pos = profile.getPositions().get(SYMBOL_FUTURES).get(0);
            assertNotNull(pos);
            assertEquals(10L, pos.pendingSellSize); // 100手
            assertEquals(50L, pos.pendingBuySize); // 100手
        });

        container.validateUserState(UID_CROSS, profile -> {
            SingleUserReportResult.Position pos = profile.getPositions().get(SYMBOL_FUTURES).get(0);
            assertNotNull(pos);
            assertEquals(PositionDirection.SHORT, pos.direction);
            assertEquals(50L, pos.openVolume); // 50手
            assertEquals(MarginMode.CROSS, pos.marginMode);
            assertEquals(0L, pos.pendingSellSize);
        });

        // 6.1 HEDGE用户开多
        ApiPlaceOrder hedgeLongOrder = ApiPlaceOrder.builder()
                .uid(UID_HEDGE)
                .orderId(5003L)
                .price(9_000L)
                .size(10L)
                .action(OrderAction.BID)
                .orderType(OrderType.GTC)
                .symbol(SYMBOL_FUTURES)
                .marginMode(MarginMode.ISOLATED)
                .leverage(10)
                .build();

        container.submitCommandSync(hedgeLongOrder, cmd ->
                assertThat(cmd.resultCode, is(CommandResultCode.SUCCESS)));

        // 6.2 HEDGE用户开空
        ApiPlaceOrder hedgeShortOrder = ApiPlaceOrder.builder()
                .uid(UID_HEDGE)
                .orderId(5004L)
                .price(11_000L)
                .size(20L)
                .action(OrderAction.ASK)
                .orderType(OrderType.GTC)
                .symbol(SYMBOL_FUTURES)
                .marginMode(MarginMode.ISOLATED)
                .leverage(10)
                .build();

        container.submitCommandSync(hedgeShortOrder, cmd ->
                assertThat(cmd.resultCode, is(CommandResultCode.SUCCESS)));

        // 8. 更新标记价格使逐仓用户盈利，全仓用户亏损
        container.initMarkPrice(SYMBOL_FUTURES, 10_500);

        // 9. 逐仓用户追加保证金
        ApiAdjustMargin adjustMargin = ApiAdjustMargin.builder().transactionId(1345L).symbol(SYMBOL_FUTURES).uid(UID_ISOLATED).amount(50_000_000L).currency(CURRENCY_FUT).marginMode(MarginMode.ISOLATED).build();
        container.submitCommandSync(adjustMargin, CommandResultCode.SUCCESS);

        // 验证追加保证金后状态
        container.validateUserState(UID_ISOLATED, profile -> {
            SingleUserReportResult.Position pos = profile.getPositions().get(SYMBOL_FUTURES).get(0);
            assertEquals(50000000L, pos.extraMargin);
            // 计算未实现盈利: (10,500 - 10,000) * 50 = 2500 FUT
            assertThat(25000L, is(pos.unrealizedProfit));
        });

        // 10. 更新标记价格使全仓用户达到强平条件
        container.updateCurrentPriceTo(31_000, SYMBOL_FUTURES, CURRENCY_FUT);
        container.createAskWithOrderId(MAKER_3, UID_LIQ, 100, 11_000, SYMBOL_FUTURES, MarginMode.CROSS);

        // 11. 手动触发强平扫描
        container.getExchangeCore().liquidationEngines.forEach(LiquidationEngine::triggerOnce);

        // 12. 验证全仓用户被强平
        container.validateUserState(UID_CROSS, profile -> {
            // 仓位应被部分或全部平仓
            List<SingleUserReportResult.Position> pos = profile.getPositions().get(SYMBOL_FUTURES);
            assertTrue(pos == null);
            // 账户余额应为负或接近零
            long balance = profile.getAccounts().get(CURRENCY_FUT);
            assertTrue(balance != 25000L, "Balance should be negative after liquidation");
        });

        // 13. 验证逐仓用户未被强平
        container.validateUserState(UID_ISOLATED, profile -> {
            SingleUserReportResult.Position pos = profile.getPositions().get(SYMBOL_FUTURES).get(0);
            assertNotNull(pos);
            assertEquals(40L, pos.openVolume);
            assertEquals(0L, pos.pendingSellSize);
            assertEquals(50L, pos.pendingBuySize);
            assertEquals(50, pos.leverage);
            assertEquals(50000000L, pos.extraMargin);
            assertEquals(850000L, pos.unrealizedProfit);
            assertEquals(-1240085L, pos.liquidationPrice);
            assertEquals(131, pos.marginRatioScaleK);
        });

        container.validateUserState(UID_HEDGE, profile -> {
            assertThat(profile.getPositions().get(SYMBOL_FUTURES).size(), Is.is(2));
            assertThat(profile.getPositions().get(SYMBOL_FUTURES).get(0).direction, Is.is(PositionDirection.LONG));
            assertThat(profile.getPositions().get(SYMBOL_FUTURES).get(0).leverage, Is.is(10));
            assertThat(profile.getPositions().get(SYMBOL_FUTURES).get(1).direction, Is.is(PositionDirection.SHORT));
            assertThat(profile.getPositions().get(SYMBOL_FUTURES).get(1).leverage, Is.is(10));
        });

        // 14. 验证系统总余额
        TotalCurrencyBalanceReportResult totalBal = container.totalBalanceReport();
        assertTrue(totalBal.isGlobalBalancesAllZero());
    }
}
