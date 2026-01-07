package exchange.core2.tests.integration;

import exchange.core2.core.IFundEventsHandler;
import exchange.core2.core.ITradeEventsHandler;
import exchange.core2.core.processors.liquidation.LiquidationEngine;
import exchange.core2.core.common.CoreSymbolSpecification;
import exchange.core2.core.common.FundEvent;
import exchange.core2.core.common.MarginMode;
import exchange.core2.core.common.PositionDirection;
import exchange.core2.core.common.api.*;
import exchange.core2.core.common.cmd.CommandResultCode;
import exchange.core2.core.common.config.PerformanceConfiguration;
import exchange.core2.core.event.IEventsHandler4Test;
import exchange.core2.core.event.SimpleEventsProcessor4Test;
import exchange.core2.tests.util.ExchangeTestContainer;
import lombok.extern.slf4j.Slf4j;
import org.hamcrest.core.Is;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.concurrent.ExecutionException;

import static exchange.core2.core.common.OrderAction.BID;
import static exchange.core2.core.common.OrderType.GTC;
import static exchange.core2.tests.util.TestConstants.*;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.core.Is.is;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

@Slf4j
@ExtendWith(MockitoExtension.class)
class ITMixedIntegration {

    private int symbolId = 2;
    private int quoteId = 840;

    private SimpleEventsProcessor4Test processor;

    @Mock
    private IEventsHandler4Test handler;

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

    // 反向开单, 测试profit加减是否准确
    @Test
    public void testBankruptcyPrice() {
        long deposit = 10000L;
        int size = 10;
        long price1 = 10000;
        int price2 = 15000;
        try (final ExchangeTestContainer container = ExchangeTestContainer.create(getPerformanceConfiguration(), processor);) {
            container.getExchangeCore().getLiquidationEngines().forEach(LiquidationEngine::stop);
            List<CoreSymbolSpecification> symbols = container.initFutureSymbols();
            symbols.forEach(s -> container.initMarkPrice(s.symbolId, 10000));

            container.createUserWithSpecificMoney(UID_1, deposit, quoteId);
            container.createUserWithSpecificMoney(UID_2, MAX_VALUE, quoteId);
            container.createUserWithSpecificMoney(UID_3, MAX_VALUE, quoteId);

            container.createBidWithOrderId(MAKER_1, UID_1, size, price1, symbols.get(0).symbolId, MarginMode.ISOLATED);
            container.createAskWithOrderId(TAKER_1, UID_2, size, price1, symbols.get(0).symbolId, MarginMode.ISOLATED);

            container.updateCurrentPriceTo(price2, symbols.get(0).symbolId, quoteId);

            container.validateUserState(UID_1, profile -> {
                assertThat(profile.getAccounts().get(quoteId), Is.is(deposit - symbols.get(0).makerFee * size));
                assertThat(profile.getPositions().get(symbols.get(0).symbolId).get(0).getOpenVolume(), is(10L));
            });

            container.validateUserState(UID_3, profile -> {
                assertThat(profile.getPositions().size(), is(0));
            });

            container.updateCurrentPriceTo(9930, symbols.get(0).symbolId, quoteId);
            // 强平价格为9930, 破产价为9920, 挂一个9920单子准备成交
            container.createBidWithOrderId(MAKER_2, UID_3, size, 9920, symbols.get(0).symbolId, MarginMode.ISOLATED);
            container.getExchangeCore().getLiquidationEngines().forEach(LiquidationEngine::triggerOnce);

            // 有可能强平还没触发, 加个sleep保证强平触发吃掉UID_3的订单
            Thread.sleep(1000);
            container.validateUserState(UID_1, profile -> {
                assertThat(profile.getPositions().size(), is(0));
                assertThat(profile.getAccounts().get(quoteId), Is.is(9100L));
            });
            container.validateUserState(UID_3, profile -> {
                assertThat(profile.getPositions().size(), is(1));
            });
        } catch (ExecutionException e) {
            throw new RuntimeException(e);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        } finally {
            verify(handler, times(36)).fundEventReport(fundEventCaptor.capture());
            // check fund event
            List<IFundEventsHandler.FundEventReport> fundEvents = fundEventCaptor.getAllValues();
            IFundEventsHandler.FundEventReport event = null;

            for (int i = 0; i < fundEvents.size(); i++) {
                IFundEventsHandler.FundEventReport evt = fundEvents.get(i);
                if (evt.getEventType().equals(FundEvent.FundEventType.LIQUIDATION_CLOSE)) {
                    event = evt;
                    break;
                }
            }

            assertThat(UID_1, Is.is(event.getAccountId()));
            assertThat(quoteId, Is.is(event.getBalances().getCurrency()));
            assertThat(10000, Is.is(event.getPositions().getSymbolId()));
            assertThat(PositionDirection.LONG, Is.is(event.getPositions().getDirection()));
            assertThat(FundEvent.FundEventType.LIQUIDATION_CLOSE, Is.is(event.getEventType()));
            assertThat(9900L, Is.is(event.getBalances().getFree()));
            assertThat(-800L, Is.is(event.getPositions().getCumRealized()));
            assertThat(0L, Is.is(event.getBalances().getLocked()));
            assertThat(0L, Is.is(event.getPositions().getOpenPriceSum()));
            assertThat(0L, Is.is(event.getPositions().getQuantity()));
            // 10000价格跌到100, unrealizedProfit = 100 - 10000 = -9900
            assertThat(0L, Is.is(event.getPositions().getUnrealizedProfit()));
            // 逐仓强平价格计算
            assertThat(0L, Is.is(event.getPositions().getLiquidationPrice()));
            assertThat(0L, Is.is(event.getPositions().getMarginRatioScaleK()));
        }
    }
}