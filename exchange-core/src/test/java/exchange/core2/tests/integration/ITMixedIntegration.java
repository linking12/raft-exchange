package exchange.core2.tests.integration;

import exchange.core2.core.IFundEventsHandler;
import exchange.core2.core.ITradeEventsHandler;
import exchange.core2.core.common.*;
import exchange.core2.core.processors.liquidation.LiquidationEngine;
import exchange.core2.core.common.api.*;
import exchange.core2.core.common.cmd.CommandResultCode;
import exchange.core2.core.common.config.PerformanceConfiguration;
import exchange.core2.core.event.IEventsHandler4Test;
import exchange.core2.core.event.SimpleEventsProcessor4Test;
import exchange.core2.core.processors.liquidation.LiquidationService;
import exchange.core2.core.utils.ReflectionUtils;
import exchange.core2.tests.util.EventCheck;
import exchange.core2.tests.util.ExchangeTestContainer;
import exchange.core2.tests.util.LatencyTools;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.collections.impl.map.sorted.mutable.TreeSortedMap;
import org.hamcrest.core.Is;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutionException;

import static exchange.core2.core.common.OrderAction.BID;
import static exchange.core2.core.common.OrderType.GTC;
import static exchange.core2.tests.util.TestConstants.*;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.core.Is.is;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

@Slf4j
@ExtendWith(MockitoExtension.class)
class ITMixedIntegration {

    private int symbolId = 2;
    private int quoteId = 840;

    private SimpleEventsProcessor4Test processor;

    private IEventsHandler4Test handler = spy(IEventsHandler4Test.handler);

    @Captor
    ArgumentCaptor<ITradeEventsHandler.SpotExecutionReport> spotEventCaptor;

    @Captor
    ArgumentCaptor<ITradeEventsHandler.FuturesExecutionReport> futuresEventCaptor;

    @Captor
    ArgumentCaptor<IFundEventsHandler.FundEventReport> fundEventCaptor;

    @BeforeEach
    public void before() {
        processor = new SimpleEventsProcessor4Test(handler);
    }

    @AfterEach()
    public void after() {
    }

    private PerformanceConfiguration getPerformanceConfiguration() {
        return PerformanceConfiguration.baseBuilder().build();
    }

    private PerformanceConfiguration getHignPerformanceConfiguration() {
        return PerformanceConfiguration.throughputPerformanceBuilder().build();
    }

    // userId1既有现货又有期货, 资产free, lock check正确 -- 期货没有开仓
    @Test
    public void testMixedExchangeAndMargin() {
        long deposit = 10000L;
        long userId1 = UID_1;
        long fee = 120L;
        int size = 1;
        long price1 = 10000;
        long price2 = 15000;
        long makerOrderId1 = 1005L;
        long makerOrderId2 = 1007L;
        long exchangeOrderId = 112233L;
        try (final ExchangeTestContainer container = ExchangeTestContainer.create(getPerformanceConfiguration(), processor);) {
            List<CoreSymbolSpecification> symbols = container.initFutureSymbols();
            symbols.forEach(s -> container.initMarkPrice(s.symbolId, 10000));
            List<CoreSymbolSpecification> symbolsExchange = container.initExchangeSymbols();

            container.createUserWithSpecificMoney(userId1, deposit, quoteId);

            container.createBidWithOrderId(makerOrderId1, userId1, size, price1, symbols.get(0).symbolId, MarginMode.CROSS);
            container.validateUserState(userId1, profile -> {
                assertThat(profile.getAccounts().get(quoteId), is(deposit));
                assertThat(profile.getOrders().size(), is(1));
                assertThat(profile.getPositions().size(), is(1));
            });

            ApiPlaceOrder order = container.genOrderWithId(exchangeOrderId, userId1, 1, 10000, symbolsExchange.get(0).symbolId, BID, GTC);

            container.submitCommandSync(order, CommandResultCode.RISK_NSF);

            // fee is 20, margin required is 120, total = 140
            container.addMoneyToUser(userId1, quoteId, 140 - 1);
            container.submitCommandSync(order, CommandResultCode.RISK_NSF);

            container.validateUserState(userId1, profile -> {
                assertThat(profile.getPositions().size(), is(1));
                assertThat(profile.getOrders().size(), is(1));
            });

            container.addMoneyToUser(userId1, quoteId, 1);
            container.submitCommandSync(order, CommandResultCode.SUCCESS);

            container.validateUserState(userId1, profile -> {
                assertThat(profile.getPositions().size(), is(1));
                // exchange dedect balance and fee first
                assertThat(profile.getAccounts().get(quoteId), is(120L));
                assertThat(profile.getOrders().size(), is(2));
            });
        } catch (ExecutionException e) {
            throw new RuntimeException(e);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        } finally {
            verify(handler, times(5)).fundEventReport(fundEventCaptor.capture());
            // check margin event
            List<IFundEventsHandler.FundEventReport> fundEvents = fundEventCaptor.getAllValues();
            IFundEventsHandler.FundEventReport marginEvent = fundEvents.get(1);
            assertThat(userId1, Is.is(marginEvent.getAccountId()));
            assertThat(quoteId, Is.is(marginEvent.getBalances().getCurrency()));
            assertThat(10000, Is.is(marginEvent.getPositions().getSymbolId()));
            assertThat(PositionDirection.LONG, Is.is(marginEvent.getPositions().getDirection()));
            assertThat(FundEvent.FundEventType.LOCK_PENDING, Is.is(marginEvent.getEventType()));
            assertThat(deposit - fee, Is.is(marginEvent.getBalances().getFree()));
            assertThat(0L, Is.is(marginEvent.getPositions().getCumRealized()));
            assertThat(fee, Is.is(marginEvent.getBalances().getLocked()));
            assertThat(0L, Is.is(marginEvent.getPositions().getOpenPriceSum()));
            assertThat(0L, Is.is(marginEvent.getPositions().getQuantity()));
            assertThat(0L, Is.is(marginEvent.getPositions().getUnrealizedProfit()));
            assertThat(0L, Is.is(marginEvent.getPositions().getLiquidationPrice()));
            assertThat(0L, Is.is(marginEvent.getPositions().getMarginRatioScaleK()));

            IFundEventsHandler.FundEventReport deposit2 = fundEvents.get(2);
            assertThat(10019L, Is.is(deposit2.getBalances().getFree()));
            assertThat(120L, Is.is(deposit2.getBalances().getLocked()));

            IFundEventsHandler.FundEventReport deposit3 = fundEvents.get(3);
            assertThat(10020L, Is.is(deposit3.getBalances().getFree()));
            assertThat(120L, Is.is(deposit3.getBalances().getLocked()));

            IFundEventsHandler.FundEventReport exchangeLockEvent = fundEvents.get(4);
            assertThat(userId1, Is.is(exchangeLockEvent.getAccountId()));
            assertThat(quoteId, Is.is(exchangeLockEvent.getBalances().getCurrency()));
            assertThat(10003, Is.is(exchangeLockEvent.getPositions().getSymbolId()));
            assertThat(PositionDirection.EMPTY, Is.is(exchangeLockEvent.getPositions().getDirection()));
            assertThat(FundEvent.FundEventType.LOCKED, Is.is(exchangeLockEvent.getEventType()));
            assertThat(0L, Is.is(exchangeLockEvent.getBalances().getFree()));
            assertThat(0L, Is.is(exchangeLockEvent.getPositions().getCumRealized()));
            assertThat(120L, Is.is(exchangeLockEvent.getBalances().getLocked()));
            assertThat(0L, Is.is(exchangeLockEvent.getPositions().getOpenPriceSum()));
            assertThat(0L, Is.is(exchangeLockEvent.getPositions().getQuantity()));
            assertThat(0L, Is.is(exchangeLockEvent.getPositions().getUnrealizedProfit()));
            assertThat(0L, Is.is(exchangeLockEvent.getPositions().getLiquidationPrice()));
            assertThat(0L, Is.is(exchangeLockEvent.getPositions().getMarginRatioScaleK()));
        }
    }

    // userId1既有现货又有期货, 资产free, lock check正确 -- 期货开仓且有profit
    @Test
    public void testMixedExchangeAndMargin2() {
        long deposit = 10000L;
        long userId1 = UID_1;
        long userId2 = UID_2;
        int size = 1;
        long price1 = 10000;
        long price2 = 15000;
        long makerOrderId1 = 1005L;
        long takerOrderId2 = 1006L;
        long exchangeOrderId = 112233L;
        try (final ExchangeTestContainer container = ExchangeTestContainer.create(getPerformanceConfiguration(), processor);) {
            container.getExchangeCore().getLiquidationEngines().forEach(LiquidationEngine::stop);
            List<CoreSymbolSpecification> symbols = container.initFutureSymbols();
            symbols.forEach(s -> container.initMarkPrice(s.symbolId, 10000));
            List<CoreSymbolSpecification> symbolsExchange = container.initExchangeSymbols();

            container.createUserWithSpecificMoney(userId1, deposit, quoteId);
            container.createUserWithSpecificMoney(userId2, MAX_VALUE, quoteId);

            container.createBidWithOrderId(makerOrderId1, userId1, size, price1, symbols.get(0).symbolId, MarginMode.CROSS);
            container.createAskWithOrderId(takerOrderId2, userId2, size, price1, symbols.get(0).symbolId, MarginMode.CROSS);

            container.updateCurrentPriceTo(15000, symbols.get(0).symbolId, quoteId);

            // 开仓成功，uid1作为maker，余额-10元
            container.validateUserState(userId1, profile -> {
                assertThat(profile.getAccounts().get(quoteId), Is.is(deposit - symbols.get(0).makerFee));
                assertThat(profile.getPositions().get(symbols.get(0).symbolId).get(0).getOpenVolume(), is(1L));
            });

            ApiPlaceOrder order = container.genOrderWithId(exchangeOrderId, userId1, 1, 10000, symbolsExchange.get(0).symbolId, BID, GTC);
            // 现货下单10000*1，余额9990，期货占用75，资金不足
            container.submitCommandSync(order, CommandResultCode.RISK_NSF);

            // 需要补充 10 + 75 + 这次下单按taker预收手续费20 = 105
            container.addMoneyToUser(userId1, quoteId, 104);
            container.submitCommandSync(order, CommandResultCode.RISK_NSF);

            container.addMoneyToUser(userId1, quoteId, 1);
            container.submitCommandSync(order, CommandResultCode.SUCCESS);

            container.validateUserState(userId1, profile -> {
                assertThat(profile.getPositions().size(), is(1));
            });
        } catch (ExecutionException e) {
            throw new RuntimeException(e);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        } finally {
            verify(handler, times(19)).fundEventReport(fundEventCaptor.capture());
            // check fund event
            List<IFundEventsHandler.FundEventReport> fundEvents = fundEventCaptor.getAllValues();
            IFundEventsHandler.FundEventReport event = fundEvents.get(18);
            assertThat(userId1, Is.is(event.getAccountId()));
            assertThat(quoteId, Is.is(event.getBalances().getCurrency()));
            assertThat(10003, Is.is(event.getPositions().getSymbolId()));
            assertThat(PositionDirection.EMPTY, Is.is(event.getPositions().getDirection()));
            assertThat(FundEvent.FundEventType.LOCKED, Is.is(event.getEventType()));
            assertThat(-25L, Is.is(event.getBalances().getFree()));
            assertThat(0L, Is.is(event.getPositions().getCumRealized()));
            assertThat(100L, Is.is(event.getBalances().getLocked()));
            assertThat(0L, Is.is(event.getPositions().getOpenPriceSum()));
            assertThat(0L, Is.is(event.getPositions().getQuantity()));
            assertThat(0L, Is.is(event.getPositions().getUnrealizedProfit()));
            assertThat(0L, Is.is(event.getPositions().getLiquidationPrice()));
            assertThat(0L, Is.is(event.getPositions().getMarginRatioScaleK()));
        }
    }

    // 反向开单, 测试profit加减是否准确
    @Test
    public void testClosePositionWithProfit() {
        long deposit = 10000L;
        int size = 10;
        long price1 = 10000;
        int price2 = 15000;
        try (final ExchangeTestContainer container = ExchangeTestContainer.create(getPerformanceConfiguration());) {
            container.getExchangeCore().getLiquidationEngines().forEach(LiquidationEngine::stop);
            List<CoreSymbolSpecification> symbols = container.initFutureSymbols();
            symbols.forEach(s -> container.initMarkPrice(s.symbolId, 10000));

            container.createUserWithSpecificMoney(UID_1, deposit, quoteId);
            container.createUserWithSpecificMoney(UID_2, MAX_VALUE, quoteId);
            container.createUserWithSpecificMoney(UID_3, MAX_VALUE, quoteId);

            container.createBidWithOrderId(MAKER_1, UID_1, size, price1, symbols.get(0).symbolId, MarginMode.CROSS);
            container.createAskWithOrderId(TAKER_1, UID_2, size, price1, symbols.get(0).symbolId, MarginMode.CROSS);

            container.updateCurrentPriceTo(price2, symbols.get(0).symbolId, quoteId);

            container.validateUserState(UID_1, profile -> {
                assertThat(profile.getAccounts().get(quoteId), Is.is(deposit - symbols.get(0).makerFee * size));
                assertThat(profile.getPositions().get(symbols.get(0).symbolId).get(0).getOpenVolume(), is(10L));
            });

            // 反向开单数量大于openVolume
            container.createAskWithOrderId(MAKER_2, UID_1, 12, price2, symbols.get(0).symbolId, MarginMode.CROSS);
            container.createBidWithOrderId(TAKER_2, UID_3, 11, price2, symbols.get(0).symbolId, MarginMode.CROSS);

            container.validateUserState(UID_1, profile -> {
                assertThat(profile.getPositions().get(symbols.get(0).symbolId).get(0).profit, is((price2 - price1) * size));
                assertThat(profile.getPositions().get(symbols.get(0).symbolId).get(0).openVolume, is(1L));
                assertThat(profile.getPositions().get(symbols.get(0).symbolId).get(0).pendingSellSize, is(1L));
                assertThat(profile.getPositions().get(symbols.get(0).symbolId).get(0).openInitMarginSum, is(150L));
                assertThat(profile.getPositions().get(symbols.get(0).symbolId).get(0).unrealizedProfit, is(0L));
                assertThat(profile.getPositions().get(symbols.get(0).symbolId).get(0).liquidationPrice, is(74517L));
                assertThat(profile.getPositions().get(symbols.get(0).symbolId).get(0).marginRatioScaleK, is(1L));
            });

        } catch (ExecutionException e) {
            throw new RuntimeException(e);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
    }

    // 反向开单, 测试profit加减是否准确
    @Test
    public void testClosePositionWithProfit2() {
        long deposit = 20000L;
        int size = 10;
        long price1 = 10000;
        int price2 = 9000;
        try (final ExchangeTestContainer container = ExchangeTestContainer.create(getPerformanceConfiguration());) {
            container.getExchangeCore().getLiquidationEngines().forEach(LiquidationEngine::stop);
            List<CoreSymbolSpecification> symbols = container.initFutureSymbols();
            symbols.forEach(s -> container.initMarkPrice(s.symbolId, 10000));

            container.createUserWithSpecificMoney(UID_1, deposit, quoteId);
            container.createUserWithSpecificMoney(UID_2, MAX_VALUE, quoteId);
            container.createUserWithSpecificMoney(UID_3, MAX_VALUE, quoteId);
            container.createUserWithSpecificMoney(UID_4, MAX_VALUE, quoteId);
            container.createUserWithSpecificMoney(UID_5, MAX_VALUE, quoteId);

            container.createBidWithOrderId(MAKER_1, UID_1, size, price1, symbols.get(0).symbolId, MarginMode.CROSS);
            container.createAskWithOrderId(TAKER_1, UID_2, size, price1, symbols.get(0).symbolId, MarginMode.CROSS);

            container.updateCurrentPriceTo(price2, symbols.get(0).symbolId, quoteId);

            container.validateUserState(UID_1, profile -> {
                assertThat(profile.getAccounts().get(quoteId), Is.is(deposit - symbols.get(0).makerFee * size));
                assertThat(profile.getPositions().get(symbols.get(0).symbolId).get(0).getOpenVolume(), is(10L));
            });

            // 反向开单数量大于openVolume
            container.createAskWithOrderId(MAKER_2, UID_1, 12, price2, symbols.get(0).symbolId, MarginMode.CROSS);
            container.createBidWithOrderId(TAKER_2, UID_3, 11, price2, symbols.get(0).symbolId, MarginMode.CROSS);

            container.validateUserState(UID_1, profile -> {
                assertThat(profile.getPositions().get(symbols.get(0).symbolId).get(0).profit, is((price2 - price1) * size));
                assertThat(profile.getPositions().get(symbols.get(0).symbolId).get(0).openVolume, is(1L));
                assertThat(profile.getPositions().get(symbols.get(0).symbolId).get(0).pendingSellSize, is(1L));
                assertThat(profile.getPositions().get(symbols.get(0).symbolId).get(0).openInitMarginSum, is(90L));
                assertThat(profile.getPositions().get(symbols.get(0).symbolId).get(0).unrealizedProfit, is(0L));
                assertThat(profile.getPositions().get(symbols.get(0).symbolId).get(0).liquidationPrice, is(18796L));
                assertThat(profile.getPositions().get(symbols.get(0).symbolId).get(0).marginRatioScaleK, is(4L));
            });

            container.createBidWithOrderId(TAKER_3, UID_4, 1, price2, symbols.get(0).symbolId, MarginMode.CROSS);
            container.validateUserState(UID_1, profile -> {
                assertThat(profile.getPositions().get(symbols.get(0).symbolId).get(0).profit, is((price2 - price1) * size));
                assertThat(profile.getPositions().get(symbols.get(0).symbolId).get(0).openVolume, is(2L));
                assertThat(profile.getPositions().get(symbols.get(0).symbolId).get(0).pendingSellSize, is(0L));
                assertThat(profile.getPositions().get(symbols.get(0).symbolId).get(0).openInitMarginSum, is(90L * 2));
                assertThat(profile.getPositions().get(symbols.get(0).symbolId).get(0).unrealizedProfit, is(0L));
                assertThat(profile.getPositions().get(symbols.get(0).symbolId).get(0).liquidationPrice, is(13870L));
                assertThat(profile.getPositions().get(symbols.get(0).symbolId).get(0).marginRatioScaleK, is(9L));
            });

        } catch (ExecutionException e) {
            throw new RuntimeException(e);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * 测试1: 逐仓爆仓后被强平，市场上有单子能全部吃掉强平单
     * 场景：用户逐仓模式下多头爆仓，市场有足够流动性完全吃掉强平单
     * 验证：1) 强平费用正确收取 2) IF收到强平费用 3) 发送强平事件
     */
    @Test
    public void testIsolatedLiquidationFullyMatchedWithFee() {
        long userDeposit = 2000L;
        long makerDeposit = 100000L;
        int userSize = 10;
        int makerFee = userSize * 10;
        int takerFee = userSize * 20;
        int liquidationFee = userSize * 50;
        long openPrice = 10000;
        long liquidationTriggerPrice = 9900; // 触发强平的价格

        try (final ExchangeTestContainer container = ExchangeTestContainer.create(getPerformanceConfiguration(), processor);) {
            // 停止自动强平，手动触发
            container.getExchangeCore().getLiquidationEngines().forEach(LiquidationEngine::stop);

            CoreSymbolSpecification spec = CoreSymbolSpecification.builder()
                    .symbolId(10000)
                    .type(SymbolType.FUTURES_CONTRACT_PERPETUAL)
                    .baseCurrency(CURRENECY_XBT)
                    .quoteCurrency(CURRENECY_USD)
                    .baseScaleK(1)
                    .quoteScaleK(1)
                    .makerFee(10)
                    .takerFee(20)
                    .liquidationFee(50)
                    .feeScaleK(0)
                    .maintenanceMargin(TreeSortedMap.newMapWith(1000L, 5L, 100000L, 10L))
                    .maintenanceMarginScaleK(10)
                    .maxLeverage(TreeSortedMap.newMapWith(2000L, 5L, 100000L, 10L))
                    .initMargin(1)
                    .initMarginScaleK(100)
                    .build();
            container.addSymbol(spec);
            container.addCurrency(spec.baseCurrency, 0);
            container.addCurrency(spec.quoteCurrency, 0);
            container.initMarkPrice(spec.symbolId, (int) openPrice);

            // 创建用户
            long loser = UID_1;  // 将被强平的用户
            long maker = UID_2;  // 提供流动性的用户

            container.createUserWithSpecificMoney(loser, userDeposit, quoteId);
            container.createUserWithSpecificMoney(maker, makerDeposit, quoteId);
            container.createUserWithSpecificMoney(UID_3, makerDeposit, quoteId);

            container.createBidWithOrderId(10001L, loser, userSize, openPrice, spec.symbolId, MarginMode.ISOLATED);
            container.createAskWithOrderId(10002L, maker, userSize, openPrice, spec.symbolId, MarginMode.CROSS);

            // 验证开仓成功
            container.validateUserState(loser, profile -> {
                assertThat(profile.getPositions().size(), is(1));
                assertThat(profile.getPositions().get(spec.symbolId).get(0).getOpenVolume(), is((long) userSize));
                assertThat(profile.getPositions().get(spec.symbolId).get(0).marginMode, is(MarginMode.ISOLATED));
                // 验证maker余额
                assertThat(profile.getAccounts().get(quoteId), is(userDeposit - makerFee));
            });

            container.validateUserState(maker, profile -> {
                assertThat(profile.getPositions().size(), is(1));
                // 验证taker余额
                assertThat(profile.getAccounts().get(quoteId), is(makerDeposit - takerFee));
            });

            container.updateCurrentPriceTo((int) liquidationTriggerPrice, spec.symbolId, quoteId);

            // 强平时会以市价卖出，我们在低价挂买单接住
            container.createBidWithOrderId(10003L, UID_3, userSize, liquidationTriggerPrice, spec.symbolId, MarginMode.CROSS);
            container.getExchangeCore().getLiquidationEngines().forEach(LiquidationEngine::triggerOnce);

            // 等待强平完成
            Thread.sleep(100);

            // 验证用户仓位被清空, 强平fee收取
            long profit = (openPrice - liquidationTriggerPrice) * userSize;
            container.validateUserState(loser, profile -> {
                assertThat("用户应该没有持仓了", profile.getPositions().size(), is(0));
                // 验证用户余额变化
                assertThat(profile.getAccounts().get(quoteId), is(userDeposit - makerFee - profit - liquidationFee));
            });

        } catch (ExecutionException | InterruptedException e) {
            throw new RuntimeException(e);
        } finally {
            // 验证发送了强平事件
            verify(handler, times(23)).fundEventReport(fundEventCaptor.capture());
            List<IFundEventsHandler.FundEventReport> fundEvents = fundEventCaptor.getAllValues();

            IFundEventsHandler.FundEventReport liquidationAlertEvt = null;
            for (int i = 0; i < fundEvents.size(); i++) {
                IFundEventsHandler.FundEventReport report = fundEvents.get(i);
                if (report.getEventType().equals(FundEvent.FundEventType.LIQUIDATION_ALERT) && report.getAccountId() == UID_1) {
                    liquidationAlertEvt = report;
                    break;
                }
            }

            assertThat(UID_1, Is.is(liquidationAlertEvt.getAccountId()));
            assertThat(quoteId, Is.is(liquidationAlertEvt.getBalances().getCurrency()));
            assertThat(10000, Is.is(liquidationAlertEvt.getPositions().getSymbolId()));
            assertThat(PositionDirection.LONG, Is.is(liquidationAlertEvt.getPositions().getDirection()));
            assertThat(FundEvent.FundEventType.LIQUIDATION_ALERT, Is.is(liquidationAlertEvt.getEventType()));
            assertThat(0L, Is.is(liquidationAlertEvt.getBalances().getFree()));
            assertThat(0L, Is.is(liquidationAlertEvt.getPositions().getCumRealized()));
            assertThat(0L, Is.is(liquidationAlertEvt.getBalances().getLocked()));
            assertThat(100000L, Is.is(liquidationAlertEvt.getPositions().getOpenPriceSum()));
            assertThat(10L, Is.is(liquidationAlertEvt.getPositions().getQuantity()));
            // 10000价格跌到100, unrealizedProfit = 100 - 10000 = -9900
            assertThat(-1000L, Is.is(liquidationAlertEvt.getPositions().getUnrealizedProfit()));
            // 逐仓强平价格计算
            assertThat(14850L, Is.is(liquidationAlertEvt.getPositions().getLiquidationPrice()));
            assertThat(-10L, Is.is(liquidationAlertEvt.getPositions().getMarginRatioScaleK()));
            EventCheck.checkEventPending(liquidationAlertEvt);

            IFundEventsHandler.FundEventReport liquidationCloseEvt = null;
            for (int i = 0; i < fundEvents.size(); i++) {
                IFundEventsHandler.FundEventReport report = fundEvents.get(i);
                if (report.getEventType().equals(FundEvent.FundEventType.LIQUIDATION_CLOSE) && report.getAccountId() == UID_1) {
                    liquidationCloseEvt = report;
                    break;
                }
            }

            assertThat(UID_1, Is.is(liquidationCloseEvt.getAccountId()));
            assertThat(quoteId, Is.is(liquidationCloseEvt.getBalances().getCurrency()));
            assertThat(10000, Is.is(liquidationCloseEvt.getPositions().getSymbolId()));
            assertThat(PositionDirection.LONG, Is.is(liquidationCloseEvt.getPositions().getDirection()));
            assertThat(FundEvent.FundEventType.LIQUIDATION_CLOSE, Is.is(liquidationCloseEvt.getEventType()));
            assertThat(1900L, Is.is(liquidationCloseEvt.getBalances().getFree()));
            assertThat(0L, Is.is(liquidationCloseEvt.getBalances().getLocked()));
            assertThat(-1000L, Is.is(liquidationCloseEvt.getPositions().getCumRealized()));
            assertThat(0L, Is.is(liquidationCloseEvt.getPositions().getOpenPriceSum()));
            assertThat(0L, Is.is(liquidationCloseEvt.getPositions().getQuantity()));
            EventCheck.checkEvent(liquidationCloseEvt);
            EventCheck.checkEventPending(liquidationCloseEvt);

            IFundEventsHandler.FundEventReport liquidationFeeEvt = null;
            for (int i = 0; i < fundEvents.size(); i++) {
                IFundEventsHandler.FundEventReport report = fundEvents.get(i);
                if (report.getEventType().equals(FundEvent.FundEventType.LIQUIDATION_FEE) && report.getAccountId() == UID_1) {
                    liquidationFeeEvt = report;
                    break;
                }
            }

            assertThat(UID_1, Is.is(liquidationFeeEvt.getAccountId()));
            assertThat(quoteId, Is.is(liquidationFeeEvt.getBalances().getCurrency()));
            assertThat(10000, Is.is(liquidationFeeEvt.getPositions().getSymbolId()));
            assertThat(PositionDirection.LONG, Is.is(liquidationFeeEvt.getPositions().getDirection()));
            assertThat(FundEvent.FundEventType.LIQUIDATION_FEE, Is.is(liquidationFeeEvt.getEventType()));
            assertThat(400L, Is.is(liquidationFeeEvt.getBalances().getFree()));
            assertThat(0L, Is.is(liquidationFeeEvt.getBalances().getLocked()));
            assertThat(-1000L, Is.is(liquidationFeeEvt.getPositions().getCumRealized()));
            assertThat(0L, Is.is(liquidationFeeEvt.getPositions().getOpenPriceSum()));
            assertThat(0L, Is.is(liquidationFeeEvt.getPositions().getQuantity()));
            EventCheck.checkEvent(liquidationFeeEvt);
            EventCheck.checkEventPending(liquidationFeeEvt);
        }
    }

    /**
     * 测试2: 全仓爆仓后被强平，市场上有单子能全部吃掉强平单
     * 场景：用户全仓模式下多头爆仓，市场有足够流动性完全吃掉强平单
     * 验证：1) 强平费用正确收取 2) IF收到强平费用 3) 发送强平事件
     */
    @Test
    public void testCrossLiquidationFullyMatchedWithFee() {
        long userDeposit = 2000L;
        long makerDeposit = 100000L;
        int userSize = 10;
        int makerFee = userSize * 10;
        int takerFee = userSize * 20;
        int liquidationFee = userSize * 50;
        long openPrice = 10000;
        long liquidationTriggerPrice = 9900; // 触发强平的价格

        try (final ExchangeTestContainer container = ExchangeTestContainer.create(getPerformanceConfiguration(), processor);) {
            // 停止自动强平，手动触发
            container.getExchangeCore().getLiquidationEngines().forEach(LiquidationEngine::stop);

            CoreSymbolSpecification spec = CoreSymbolSpecification.builder()
                    .symbolId(10000)
                    .type(SymbolType.FUTURES_CONTRACT_PERPETUAL)
                    .baseCurrency(CURRENECY_XBT)
                    .quoteCurrency(CURRENECY_USD)
                    .baseScaleK(1)
                    .quoteScaleK(1)
                    .makerFee(10)
                    .takerFee(20)
                    .liquidationFee(50)
                    .feeScaleK(0)
                    .maintenanceMargin(TreeSortedMap.newMapWith(1000L, 5L, 100000L, 10L))
                    .maintenanceMarginScaleK(10)
                    .maxLeverage(TreeSortedMap.newMapWith(2000L, 5L, 100000L, 10L))
                    .initMargin(1)
                    .initMarginScaleK(100)
                    .build();
            container.addSymbol(spec);
            container.addCurrency(spec.baseCurrency, 0);
            container.addCurrency(spec.quoteCurrency, 0);
            container.initMarkPrice(spec.symbolId, (int) openPrice);

            // 创建用户
            long loser = UID_1;  // 将被强平的用户
            long maker = UID_2;  // 提供流动性的用户

            container.createUserWithSpecificMoney(loser, userDeposit, quoteId);
            container.createUserWithSpecificMoney(maker, makerDeposit, quoteId);
            container.createUserWithSpecificMoney(UID_3, makerDeposit, quoteId);

            container.createBidWithOrderId(10001L, loser, userSize, openPrice, spec.symbolId, MarginMode.CROSS);
            container.createAskWithOrderId(10002L, maker, userSize, openPrice, spec.symbolId, MarginMode.CROSS);

            // 验证开仓成功
            container.validateUserState(loser, profile -> {
                assertThat(profile.getPositions().size(), is(1));
                assertThat(profile.getPositions().get(spec.symbolId).get(0).getOpenVolume(), is((long) userSize));
                assertThat(profile.getPositions().get(spec.symbolId).get(0).marginMode, is(MarginMode.CROSS));
                // 验证maker余额
                assertThat(profile.getAccounts().get(quoteId), is(userDeposit - makerFee));
            });

            container.validateUserState(maker, profile -> {
                assertThat(profile.getPositions().size(), is(1));
                // 验证taker余额
                assertThat(profile.getAccounts().get(quoteId), is(makerDeposit - takerFee));
            });

            container.updateCurrentPriceTo((int) liquidationTriggerPrice, spec.symbolId, quoteId);

            // 强平时会以市价卖出，我们在低价挂买单接住
            container.createBidWithOrderId(10003L, UID_3, userSize, liquidationTriggerPrice, spec.symbolId, MarginMode.CROSS);

            container.getExchangeCore().getLiquidationEngines().forEach(LiquidationEngine::triggerOnce);

            // 等待强平完成
            Thread.sleep(100);

            // 验证用户仓位被清空, 强平fee收取
            long profit = (openPrice - liquidationTriggerPrice) * userSize;
            container.validateUserState(loser, profile -> {
                assertThat("用户应该没有持仓了", profile.getPositions().size(), is(0));
                // 验证用户余额变化
                assertThat(profile.getAccounts().get(quoteId), is(userDeposit - makerFee - profit - liquidationFee));
            });

        } catch (ExecutionException | InterruptedException e) {
            throw new RuntimeException(e);
        } finally {
            // 验证发送了强平事件
            verify(handler, times(23)).fundEventReport(fundEventCaptor.capture());
            List<IFundEventsHandler.FundEventReport> fundEvents = fundEventCaptor.getAllValues();

            IFundEventsHandler.FundEventReport liquidationAlertEvt = null;
            for (int i = 0; i < fundEvents.size(); i++) {
                IFundEventsHandler.FundEventReport report = fundEvents.get(i);
                if (report.getEventType().equals(FundEvent.FundEventType.LIQUIDATION_ALERT) && report.getAccountId() == UID_1) {
                    liquidationAlertEvt = report;
                    break;
                }
            }

            assertThat(UID_1, Is.is(liquidationAlertEvt.getAccountId()));
            assertThat(quoteId, Is.is(liquidationAlertEvt.getBalances().getCurrency()));
            assertThat(10000, Is.is(liquidationAlertEvt.getPositions().getSymbolId()));
            assertThat(PositionDirection.LONG, Is.is(liquidationAlertEvt.getPositions().getDirection()));
            assertThat(FundEvent.FundEventType.LIQUIDATION_ALERT, Is.is(liquidationAlertEvt.getEventType()));
            assertThat(0L, Is.is(liquidationAlertEvt.getBalances().getFree()));
            assertThat(0L, Is.is(liquidationAlertEvt.getPositions().getCumRealized()));
            assertThat(0L, Is.is(liquidationAlertEvt.getBalances().getLocked()));
            assertThat(100000L, Is.is(liquidationAlertEvt.getPositions().getOpenPriceSum()));
            assertThat(10L, Is.is(liquidationAlertEvt.getPositions().getQuantity()));
            assertThat(-1000L, Is.is(liquidationAlertEvt.getPositions().getUnrealizedProfit()));
            assertThat(-1L, Is.is(liquidationAlertEvt.getPositions().getLiquidationPrice()));
            assertThat(550L, Is.is(liquidationAlertEvt.getPositions().getMarginRatioScaleK()));
            EventCheck.checkEventPending(liquidationAlertEvt);

            IFundEventsHandler.FundEventReport liquidationCloseEvt = null;
            for (int i = 0; i < fundEvents.size(); i++) {
                IFundEventsHandler.FundEventReport report = fundEvents.get(i);
                if (report.getEventType().equals(FundEvent.FundEventType.LIQUIDATION_CLOSE) && report.getAccountId() == UID_1) {
                    liquidationCloseEvt = report;
                    break;
                }
            }

            assertThat(UID_1, Is.is(liquidationCloseEvt.getAccountId()));
            assertThat(quoteId, Is.is(liquidationCloseEvt.getBalances().getCurrency()));
            assertThat(10000, Is.is(liquidationCloseEvt.getPositions().getSymbolId()));
            assertThat(PositionDirection.LONG, Is.is(liquidationCloseEvt.getPositions().getDirection()));
            assertThat(FundEvent.FundEventType.LIQUIDATION_CLOSE, Is.is(liquidationCloseEvt.getEventType()));
            assertThat(1900L, Is.is(liquidationCloseEvt.getBalances().getFree()));
            assertThat(0L, Is.is(liquidationCloseEvt.getBalances().getLocked()));
            assertThat(-1000L, Is.is(liquidationCloseEvt.getPositions().getCumRealized()));
            assertThat(0L, Is.is(liquidationCloseEvt.getPositions().getOpenPriceSum()));
            assertThat(0L, Is.is(liquidationCloseEvt.getPositions().getQuantity()));
            EventCheck.checkEvent(liquidationCloseEvt);
            EventCheck.checkEventPending(liquidationCloseEvt);

            IFundEventsHandler.FundEventReport liquidationFeeEvt = null;
            for (int i = 0; i < fundEvents.size(); i++) {
                IFundEventsHandler.FundEventReport report = fundEvents.get(i);
                if (report.getEventType().equals(FundEvent.FundEventType.LIQUIDATION_FEE) && report.getAccountId() == UID_1) {
                    liquidationFeeEvt = report;
                    break;
                }
            }

            assertThat(UID_1, Is.is(liquidationFeeEvt.getAccountId()));
            assertThat(quoteId, Is.is(liquidationFeeEvt.getBalances().getCurrency()));
            assertThat(10000, Is.is(liquidationFeeEvt.getPositions().getSymbolId()));
            assertThat(PositionDirection.LONG, Is.is(liquidationFeeEvt.getPositions().getDirection()));
            assertThat(FundEvent.FundEventType.LIQUIDATION_FEE, Is.is(liquidationFeeEvt.getEventType()));
            assertThat(400L, Is.is(liquidationFeeEvt.getBalances().getFree()));
            assertThat(0L, Is.is(liquidationFeeEvt.getBalances().getLocked()));
            assertThat(-1000L, Is.is(liquidationFeeEvt.getPositions().getCumRealized()));
            assertThat(0L, Is.is(liquidationFeeEvt.getPositions().getOpenPriceSum()));
            assertThat(0L, Is.is(liquidationFeeEvt.getPositions().getQuantity()));
            EventCheck.checkEvent(liquidationFeeEvt);
            EventCheck.checkEventPending(liquidationFeeEvt);
        }
    }

    /**
     * 测试3: 逐仓爆仓后被强平，市场部分吃掉，IF接管剩余
     * 场景：用户逐仓模式下爆仓，市场只能部分吃掉强平单，IF接管剩余部分
     * 验证：1) 市场部分成交 2) IF接管剩余部分 3) 强平费用正确
     */
    @Test
    public void testIsolatedLiquidationPartialMatchedWithIFTakeover() {
        long userDeposit = 2000L;
        long makerDeposit = 100000L;
        int userSize = 10;
        int makerFee = userSize * 10;
        int takerFee = userSize * 20;
        int liquidationFee = userSize * 50;
        long openPrice = 10000;
        long liquidationTriggerPrice = 9900;
        long amountPerShard = 500000L;

        try (final ExchangeTestContainer container = ExchangeTestContainer.create(getPerformanceConfiguration(), processor);) {
            // 停止自动强平，手动触发
            container.getExchangeCore().getLiquidationEngines().forEach(LiquidationEngine::stop);

            CoreSymbolSpecification spec = CoreSymbolSpecification.builder()
                    .symbolId(10000)
                    .type(SymbolType.FUTURES_CONTRACT_PERPETUAL)
                    .baseCurrency(CURRENECY_XBT)
                    .quoteCurrency(CURRENECY_USD)
                    .baseScaleK(1)
                    .quoteScaleK(1)
                    .makerFee(10)
                    .takerFee(20)
                    .liquidationFee(50)
                    .feeScaleK(0)
                    .maintenanceMargin(TreeSortedMap.newMapWith(1000L, 5L, 100000L, 10L))
                    .maintenanceMarginScaleK(10)
                    .maxLeverage(TreeSortedMap.newMapWith(2000L, 5L, 100000L, 10L))
                    .initMargin(1)
                    .initMarginScaleK(100)
                    .build();
            container.addSymbol(spec);
            container.addCurrency(spec.baseCurrency, 0);
            container.addCurrency(spec.quoteCurrency, 0);
            container.initMarkPrice(spec.symbolId, (int) openPrice);

            // 创建用户
            long loser = UID_1;  // 将被强平的用户
            long maker = UID_2;  // 提供流动性的用户

            container.createUserWithSpecificMoney(loser, userDeposit, quoteId);
            container.createUserWithSpecificMoney(maker, makerDeposit, quoteId);
            container.createUserWithSpecificMoney(UID_3, makerDeposit, quoteId);

            // 给IF注入初始资金，用于接管
            // 给每个分片的IF注入资金
            List<LiquidationEngine> engines = container.getExchangeCore().getLiquidationEngines();
            for (LiquidationEngine engine : engines) {
                LiquidationService service = ReflectionUtils.extractField(
                        LiquidationEngine.class,
                        engine,
                        "liquidationService"
                );
                // 平均分配资金到各分片
                service.creditLiquidationFee(spec.symbolId, amountPerShard);
                log.debug("Added {} to IF balance for symbol {} in shard", amountPerShard, quoteId);
            }

            container.createBidWithOrderId(30001L, loser, userSize, openPrice, spec.symbolId, MarginMode.ISOLATED);
            container.createAskWithOrderId(30002L, maker, userSize, openPrice, spec.symbolId, MarginMode.CROSS);

            // 验证开仓成功
            container.validateUserState(loser, profile -> {
                assertThat(profile.getPositions().size(), is(1));
                assertThat(profile.getPositions().get(spec.symbolId).get(0).getOpenVolume(), is((long) userSize));
            });

            long loserBalanceBeforeLiquidation = container.getUserProfile(loser).getAccounts().get(quoteId);
            log.info("强平前用户余额: {}", loserBalanceBeforeLiquidation);

            // 获取IF强平前余额
            long ifBalanceBeforeLiquidation = container.getIFBalance(spec.symbolId, quoteId);
            assertThat(ifBalanceBeforeLiquidation, is(amountPerShard));
            log.info("强平前IF余额: {}", ifBalanceBeforeLiquidation);

            log.info("步骤2: 价格下跌到触发强平");
            container.updateCurrentPriceTo((int) liquidationTriggerPrice, spec.symbolId, quoteId);

            int marketCanTake = 5;
            log.info("步骤3: Maker 只挂部分买单 (只能吃掉{}张)", marketCanTake);
            container.createBidWithOrderId(30003L, UID_3, marketCanTake, liquidationTriggerPrice, spec.symbolId, MarginMode.CROSS);

            log.info("步骤4: 手动触发强平");
            container.getExchangeCore().getLiquidationEngines().forEach(LiquidationEngine::triggerOnce);

            // 等待强平和IF接管完成 - 需要更长时间
            Thread.sleep(1000);

            log.info("步骤5: 验证强平结果");
            container.validateUserState(loser, profile -> {
                assertThat(profile.getPositions().size(), is(0));
                // 10手强平单, 5个被市场UID_3吃掉了, 剩余走了IF。被市场吃掉的部分要收强平fee
                long liqFee = liquidationFee / 2;
                //  市场价格从10000 -> 9900 10手, profit = -100 * 10 = -10000
                long profit = -1000L;
                assertThat(profile.getAccounts().get(quoteId), is(userDeposit - makerFee - liqFee + profit ));
            });

            // 验证用户余额变化
            long loserBalanceAfterLiquidation = container.getUserProfile(loser).getAccounts().get(quoteId);
            log.info("强平后用户余额: {}", loserBalanceAfterLiquidation);

            // 验证IF余额增加（收到强平费）
            long ifBalanceAfterLiquidation = container.getIFBalance(spec.symbolId, quoteId);
            log.info("强平后IF余额: {}", ifBalanceAfterLiquidation);
            // IF吃掉了5手UID_1的强平(5 * 9900), 又收取了5手强平手续费(5 * 50)
            assertThat(ifBalanceAfterLiquidation, is(amountPerShard - 5 * liquidationTriggerPrice + 5 * 50L));

        } catch (ExecutionException | InterruptedException e) {
            throw new RuntimeException(e);
        } finally {
            verify(handler, times(25)).fundEventReport(fundEventCaptor.capture());
            List<IFundEventsHandler.FundEventReport> fundEvents = fundEventCaptor.getAllValues();

            IFundEventsHandler.FundEventReport ifCloseEvt = null;
            for (int i = 0; i < fundEvents.size(); i++) {
                IFundEventsHandler.FundEventReport report = fundEvents.get(i);
                if (report.getEventType().equals(FundEvent.FundEventType.IF_POSITION_CLOSE) && report.getAccountId() == UID_1) {
                    ifCloseEvt = report;
                    break;
                }
            }

            assertThat(UID_1, Is.is(ifCloseEvt.getAccountId()));
            assertThat(quoteId, Is.is(ifCloseEvt.getBalances().getCurrency()));
            assertThat(10000, Is.is(ifCloseEvt.getPositions().getSymbolId()));
            assertThat(PositionDirection.LONG, Is.is(ifCloseEvt.getPositions().getDirection()));
            assertThat(FundEvent.FundEventType.IF_POSITION_CLOSE, Is.is(ifCloseEvt.getEventType()));
            assertThat(1650L, Is.is(ifCloseEvt.getBalances().getFree()));
            assertThat(0L, Is.is(ifCloseEvt.getBalances().getLocked()));
            assertThat(-1000L, Is.is(ifCloseEvt.getPositions().getCumRealized()));
            assertThat(0L, Is.is(ifCloseEvt.getPositions().getOpenPriceSum()));
            assertThat(0L, Is.is(ifCloseEvt.getPositions().getQuantity()));
            EventCheck.checkEvent(ifCloseEvt);
            EventCheck.checkEventPending(ifCloseEvt);
        }
    }

    /**
     * 测试4: 逐仓爆仓后被强平，市场部分吃掉，IF资金不够, 走到ADL
     * 场景：用户逐仓模式下爆仓，市场只能部分吃掉强平单，IF资金不够接管管剩余部分, 最终由ADL兜底
     * 验证：1) 市场部分成交 2) ADL接管剩余部分 3) 强平费用正确
     */
    @Test
    public void testIsolatedLiquidationPartialMatchedWithAdlTakeover() {
        long userDeposit = 2000L;
        long makerDeposit = 100000L;
        int userSize = 10;
        int makerFee = userSize * 10;
        int takerFee = userSize * 20;
        int liquidationFee = userSize * 50;
        long openPrice = 10000;
        long liquidationTriggerPrice = 9900;
        long amountPerShard = 45000L; // IF资金不够UID_1剩余的5手强平

        try (final ExchangeTestContainer container = ExchangeTestContainer.create(getPerformanceConfiguration(), processor);) {
            // 停止自动强平，手动触发
            container.getExchangeCore().getLiquidationEngines().forEach(LiquidationEngine::stop);

            CoreSymbolSpecification spec = CoreSymbolSpecification.builder()
                    .symbolId(10000)
                    .type(SymbolType.FUTURES_CONTRACT_PERPETUAL)
                    .baseCurrency(CURRENECY_XBT)
                    .quoteCurrency(CURRENECY_USD)
                    .baseScaleK(1)
                    .quoteScaleK(1)
                    .makerFee(10)
                    .takerFee(20)
                    .liquidationFee(50)
                    .feeScaleK(0)
                    .maintenanceMargin(TreeSortedMap.newMapWith(1000L, 5L, 100000L, 10L))
                    .maintenanceMarginScaleK(10)
                    .maxLeverage(TreeSortedMap.newMapWith(2000L, 5L, 100000L, 10L))
                    .initMargin(1)
                    .initMarginScaleK(100)
                    .build();
            container.addSymbol(spec);
            container.addCurrency(spec.baseCurrency, 0);
            container.addCurrency(spec.quoteCurrency, 0);
            container.initMarkPrice(spec.symbolId, (int) openPrice);

            // 创建用户
            long loser = UID_1;  // 将被强平的用户
            long maker = UID_2;  // 提供流动性的用户

            container.createUserWithSpecificMoney(loser, userDeposit, quoteId);
            container.createUserWithSpecificMoney(maker, makerDeposit, quoteId);
            container.createUserWithSpecificMoney(UID_3, makerDeposit, quoteId);
            // 模拟市场上其他订单准备ADL
            container.createUserWithSpecificMoney(UID_5, makerDeposit, quoteId);
            container.createUserWithSpecificMoney(UID_6, makerDeposit, quoteId);
            container.createUserWithSpecificMoney(UID_7, makerDeposit, quoteId);
            container.createUserWithSpecificMoney(UID_8, makerDeposit, quoteId);

            // 给IF注入初始资金，用于接管
            // 给每个分片的IF注入资金
            List<LiquidationEngine> engines = container.getExchangeCore().getLiquidationEngines();
            for (LiquidationEngine engine : engines) {
                LiquidationService service = ReflectionUtils.extractField(
                        LiquidationEngine.class,
                        engine,
                        "liquidationService"
                );
                // 平均分配资金到各分片
                service.creditLiquidationFee(spec.symbolId, amountPerShard);
                log.debug("Added {} to IF balance for symbol {} in shard", amountPerShard, quoteId);
            }

            container.createBidWithOrderId(30001L, loser, userSize, openPrice, spec.symbolId, MarginMode.ISOLATED);
            container.createAskWithOrderId(30002L, maker, userSize, openPrice, spec.symbolId, MarginMode.CROSS);

//            // UID_5和UID_6开仓成功
//            container.createBidWithOrderId(50001L, UID_5, userSize, 9000L, spec.symbolId, MarginMode.ISOLATED);
//            container.createAskWithOrderId(60002L, UID_6, userSize, 9000L, spec.symbolId, MarginMode.CROSS);
//
//            // UID_7和UID_8开仓成功, UID_7因为市场波动赚的最多, 为candidate ADL对象
//            container.createBidWithOrderId(70001L, UID_7, userSize, 8000L, spec.symbolId, MarginMode.ISOLATED);
//            container.createAskWithOrderId(80002L, UID_8, userSize, 8000L, spec.symbolId, MarginMode.CROSS);

            // 验证开仓成功
            container.validateUserState(loser, profile -> {
                assertThat(profile.getPositions().size(), is(1));
                assertThat(profile.getPositions().get(spec.symbolId).get(0).getOpenVolume(), is((long) userSize));
            });

            long loserBalanceBeforeLiquidation = container.getUserProfile(loser).getAccounts().get(quoteId);
            log.info("强平前用户余额: {}", loserBalanceBeforeLiquidation);

            // 获取IF强平前余额
            long ifBalanceBeforeLiquidation = container.getIFBalance(spec.symbolId, quoteId);
            assertThat(ifBalanceBeforeLiquidation, is(amountPerShard));
            log.info("强平前IF余额: {}", ifBalanceBeforeLiquidation);

            log.info("步骤2: 价格下跌到触发强平");
            container.updateCurrentPriceTo((int) liquidationTriggerPrice, spec.symbolId, quoteId);

            int marketCanTake = 4;
            log.info("步骤3: Maker 只挂部分买单 (只能吃掉{}张)", marketCanTake);
            container.createBidWithOrderId(30003L, UID_3, marketCanTake, liquidationTriggerPrice, spec.symbolId, MarginMode.CROSS);

            // 验证ADL候选人仓位在强平前的数值
            container.validateUserState(maker, profile -> {
                assertThat(profile.getPositions().size(), is(1));
                assertThat(profile.getAccounts().get(quoteId), is(makerDeposit - takerFee));
                assertThat(profile.getPositions().get(spec.symbolId).get(0).getOpenVolume(), is((long) userSize));
                assertThat(profile.getPositions().get(spec.symbolId).get(0).getOpenInitMarginSum(), is(1000L));
                assertThat(profile.getPositions().get(spec.symbolId).get(0).getOpenPriceSum(), is(100000L));
                assertThat(profile.getPositions().get(spec.symbolId).get(0).getUnrealizedProfit(), is(userSize * (openPrice - liquidationTriggerPrice)));
            });

            log.info("步骤4: 手动触发强平");
            container.getExchangeCore().getLiquidationEngines().forEach(LiquidationEngine::triggerOnce);

            // 等待强平和IF接管完成 - 需要更长时间
            LatencyTools.waitForCondition(200, () -> {
                try {
                    return container.getUserProfile(loser).getPositions().isEmpty();
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });

            log.info("步骤5: 验证强平结果");
            container.validateUserState(loser, profile -> {
                assertThat(profile.getPositions().size(), is(0));
                // 10手强平单, 4手被市场UID_3吃掉了, 剩余走了ADL。被市场吃掉的部分(4手)要收强平fee
                long liqFee = liquidationFee * 4 / 10;
                //  市场价格从10000 -> 9900 10手, profit = -100 * 10 = -10000
                long profit = -1000L;
                assertThat(profile.getAccounts().get(quoteId), is(userDeposit - makerFee - liqFee + profit ));
            });

            // 验证ADL候选人仓位在强平后的数值
            container.validateUserState(maker, profile -> {
                assertThat(profile.getPositions().size(), is(1));
                assertThat(profile.getAccounts().get(quoteId), is(makerDeposit - takerFee));
                // 10手强平单, UID_3吃掉4手, 剩余6手走了ADL, 被UID_2吃掉了
                assertThat(profile.getPositions().get(spec.symbolId).get(0).getOpenVolume(), is(userSize - 6L));
                assertThat(profile.getPositions().get(spec.symbolId).get(0).getOpenInitMarginSum(), is(400L));
                assertThat(profile.getPositions().get(spec.symbolId).get(0).getOpenPriceSum(), is(40600L));
                assertThat(profile.getPositions().get(spec.symbolId).get(0).getUnrealizedProfit(), is(userSize * (openPrice - liquidationTriggerPrice)));
            });

            container.validateUserState(loser, profile -> {
                assertThat(profile.getPositions().size(), is(0));
                // 10手强平单, 4手被市场UID_3吃掉了, 剩余走了ADL。被市场吃掉的部分(4手)要收强平fee
                long liqFee = liquidationFee * 4 / 10;
                //  市场价格从10000 -> 9900 10手, profit = -100 * 10 = -10000
                long profit = -1000L;
                assertThat(profile.getAccounts().get(quoteId), is(userDeposit - makerFee - liqFee + profit ));
            });

            // 验证用户余额变化
            long loserBalanceAfterLiquidation = container.getUserProfile(loser).getAccounts().get(quoteId);
            log.info("强平后用户余额: {}", loserBalanceAfterLiquidation);

            // 验证IF余额会增加, 因为IF金额不够覆盖剩余的强平后订单(6手), 又收取了4手强平手续费(4 * 50)
            long ifBalanceAfterLiquidation = container.getIFBalance(spec.symbolId, quoteId);
            log.info("强平后IF余额: {}", ifBalanceAfterLiquidation);
            assertThat(ifBalanceAfterLiquidation, is(amountPerShard + 4 * 50L));

        } catch (ExecutionException | InterruptedException e) {
            throw new RuntimeException(e);
        } finally {
            verify(handler, times(30)).fundEventReport(fundEventCaptor.capture());
            List<IFundEventsHandler.FundEventReport> fundEvents = fundEventCaptor.getAllValues();

            IFundEventsHandler.FundEventReport adlCloseEvt = null;
            for (int i = 0; i < fundEvents.size(); i++) {
                IFundEventsHandler.FundEventReport report = fundEvents.get(i);
                if (report.getEventType().equals(FundEvent.FundEventType.ADL_POSITION_CLOSE) && report.getAccountId() == UID_2) {
                    adlCloseEvt = report;
                    break;
                }
            }

            assertThat(UID_2, Is.is(adlCloseEvt.getAccountId()));
            assertThat(quoteId, Is.is(adlCloseEvt.getBalances().getCurrency()));
            assertThat(10000, Is.is(adlCloseEvt.getPositions().getSymbolId()));
            assertThat(PositionDirection.SHORT, Is.is(adlCloseEvt.getPositions().getDirection()));
            assertThat(FundEvent.FundEventType.ADL_POSITION_CLOSE, Is.is(adlCloseEvt.getEventType()));
            assertThat(99400L, Is.is(adlCloseEvt.getBalances().getFree()));
            assertThat(400L, Is.is(adlCloseEvt.getBalances().getLocked()));
            assertThat(0L, Is.is(adlCloseEvt.getPositions().getCumRealized()));
            assertThat(40600L, Is.is(adlCloseEvt.getPositions().getOpenPriceSum()));
            assertThat(4L, Is.is(adlCloseEvt.getPositions().getQuantity()));
            assertThat(1000L, Is.is(adlCloseEvt.getPositions().getUnrealizedProfit()));
            assertThat(23400L, Is.is(adlCloseEvt.getPositions().getLiquidationPrice()));
            assertThat(1L, Is.is(adlCloseEvt.getPositions().getMarginRatioScaleK()));
            EventCheck.checkEventPending(adlCloseEvt);

            IFundEventsHandler.FundEventReport adlOriginCloseEvt = null;
            for (int i = 0; i < fundEvents.size(); i++) {
                IFundEventsHandler.FundEventReport report = fundEvents.get(i);
                if (report.getEventType().equals(FundEvent.FundEventType.ADL_ORIGIN_CLOSE) && report.getAccountId() == UID_1) {
                    adlOriginCloseEvt = report;
                    break;
                }
            }

            assertThat(UID_1, Is.is(adlOriginCloseEvt.getAccountId()));
            assertThat(quoteId, Is.is(adlOriginCloseEvt.getBalances().getCurrency()));
            assertThat(10000, Is.is(adlOriginCloseEvt.getPositions().getSymbolId()));
            assertThat(PositionDirection.LONG, Is.is(adlOriginCloseEvt.getPositions().getDirection()));
            assertThat(FundEvent.FundEventType.ADL_ORIGIN_CLOSE, Is.is(adlOriginCloseEvt.getEventType()));
            assertThat(1700L, Is.is(adlOriginCloseEvt.getBalances().getFree()));
            assertThat(0L, Is.is(adlOriginCloseEvt.getBalances().getLocked()));
            assertThat(-1000L, Is.is(adlOriginCloseEvt.getPositions().getCumRealized()));
            assertThat(0L, Is.is(adlOriginCloseEvt.getPositions().getOpenPriceSum()));
            assertThat(0L, Is.is(adlOriginCloseEvt.getPositions().getQuantity()));
            EventCheck.checkEvent(adlOriginCloseEvt);
            EventCheck.checkEventPending(adlOriginCloseEvt);
        }
    }


}