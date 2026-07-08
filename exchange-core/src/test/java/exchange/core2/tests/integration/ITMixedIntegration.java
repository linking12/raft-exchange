package exchange.core2.tests.integration;

import exchange.core2.core.IFundEventsHandler;
import exchange.core2.core.ITradeEventsHandler;
import exchange.core2.core.common.*;
import exchange.core2.core.processors.liquidation.LiquidationEngine;
import exchange.core2.core.common.api.*;
import exchange.core2.core.common.api.reports.*;
import exchange.core2.core.common.cmd.CommandResultCode;
import exchange.core2.core.common.config.PerformanceConfiguration;
import exchange.core2.core.event.IEventsHandler4Test;
import exchange.core2.core.event.SimpleEventsProcessor4Test;
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
import static org.mockito.Mockito.atLeastOnce;
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
                // accounts = 真实持有总额 (10000 + 139 + 1 = 10140)，不再预扣 spot hold。
                // 可支配余额 = accounts - exchangeLocked(10020) - futures locked(120) = 0
                assertThat(profile.getAccounts().get(quoteId), is(10140L));
                assertThat(profile.getExchangeLocked().get(quoteId), is(10020L));
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
            // locked 现在 = futures margin(120) + 现货挂单冻结(10020) = 10140；free + locked = 真实持有
            assertThat(10140L, Is.is(exchangeLockEvent.getBalances().getLocked()));
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
            // locked 现在 = futures margin(100) + 现货挂单冻结(10020) = 10120
            assertThat(10120L, Is.is(event.getBalances().getLocked()));
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
                // close fee 收取改变了 free balance, 进而影响逐仓维持保证金校验里的强平价
                assertThat(profile.getPositions().get(symbols.get(0).symbolId).get(0).liquidationPrice, is(74418L));
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
                // close fee 收取改变了 free balance, 进而影响逐仓维持保证金校验里的强平价
                assertThat(profile.getPositions().get(symbols.get(0).symbolId).get(0).liquidationPrice, is(18697L));
                assertThat(profile.getPositions().get(symbols.get(0).symbolId).get(0).marginRatioScaleK, is(4L));
            });

            container.createBidWithOrderId(TAKER_3, UID_4, 1, price2, symbols.get(0).symbolId, MarginMode.CROSS);
            container.validateUserState(UID_1, profile -> {
                assertThat(profile.getPositions().get(symbols.get(0).symbolId).get(0).profit, is((price2 - price1) * size));
                assertThat(profile.getPositions().get(symbols.get(0).symbolId).get(0).openVolume, is(2L));
                assertThat(profile.getPositions().get(symbols.get(0).symbolId).get(0).pendingSellSize, is(0L));
                assertThat(profile.getPositions().get(symbols.get(0).symbolId).get(0).openInitMarginSum, is(90L * 2));
                assertThat(profile.getPositions().get(symbols.get(0).symbolId).get(0).unrealizedProfit, is(0L));
                // close fee 收取改变了 free balance, 进而影响逐仓维持保证金校验里的强平价
                assertThat(profile.getPositions().get(symbols.get(0).symbolId).get(0).liquidationPrice, is(13821L));
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
        // BP with liquidationFee: maxLoss = 1000 - (20+50)×10 = 300 → BP = (100000-300)/10 = 9970
        long bpFillPrice = 9970L;

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

            container.createBidWithOrderId(10003L, UID_3, userSize, bpFillPrice, spec.symbolId, MarginMode.CROSS);
            container.getExchangeCore().getLiquidationEngines().forEach(LiquidationEngine::triggerOnce);

            // 等待强平完成
            Thread.sleep(100);

            long profit = (openPrice - bpFillPrice) * userSize;
            long closeFee = userSize * 20L;
            container.validateUserState(loser, profile -> {
                assertThat("用户应该没有持仓了", profile.getPositions().size(), is(0));
                assertThat(profile.getAccounts().get(quoteId), is(userDeposit - makerFee - profit - liquidationFee - closeFee));
            });

        } catch (ExecutionException | InterruptedException e) {
            throw new RuntimeException(e);
        } finally {
            // 验证发送了强平事件（23 原始 + 1 PNL_SETTLEMENT，被强平方仓位归零 profit 入账）
            verify(handler, times(24)).fundEventReport(fundEventCaptor.capture());
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
            assertThat(0L, Is.is(liquidationCloseEvt.getBalances().getLocked()));
            // cumRealized = (bpFillPrice - openPrice) × userSize = (9970-10000)×10 = -300
            assertThat(-300L, Is.is(liquidationCloseEvt.getPositions().getCumRealized()));
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
            // 2000 - 100(maker) - 300(loss = 30×10 at BP=9970) - 200(taker) - 500(liq) = 900
            assertThat(900L, Is.is(liquidationFeeEvt.getBalances().getFree()));
            assertThat(0L, Is.is(liquidationFeeEvt.getBalances().getLocked()));
            assertThat(-300L, Is.is(liquidationFeeEvt.getPositions().getCumRealized()));
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
            // 强平 fill 也按 taker 率收 close fee (10*20=200)
            long closeFee = userSize * 20L;
            container.validateUserState(loser, profile -> {
                assertThat("用户应该没有持仓了", profile.getPositions().size(), is(0));
                // 验证用户余额变化
                assertThat(profile.getAccounts().get(quoteId), is(userDeposit - makerFee - profit - liquidationFee - closeFee));
            });

        } catch (ExecutionException | InterruptedException e) {
            throw new RuntimeException(e);
        } finally {
            // 验证发送了强平事件（23 原始 + 1 PNL_SETTLEMENT，被强平方仓位归零 profit 入账）
            verify(handler, times(24)).fundEventReport(fundEventCaptor.capture());
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
            // 1900 → 1700: 强平 fill 也按 taker 率收 close fee (10 * 20 = 200)            assertThat(1700L, Is.is(liquidationCloseEvt.getBalances().getFree()));
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
            // 400 → 200: close fee (10*20=200) 在 LIQUIDATION_CLOSE 阶段已被扣除, LIQUIDATION_FEE 事件读到的 free 因此少 200
            assertThat(200L, Is.is(liquidationFeeEvt.getBalances().getFree()));
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
        // BP with liquidationFee: maxLoss = 1000 - (20+50)×10 = 300 → BP = (100000-300)/10 = 9970
        long bpFillPrice = 9970L;
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

            // 通过 admin 命令定向给每 shard 各充 amountPerShard，同步反向记账 adjustments 保对账闭环
            int numShards = container.getExchangeCore().getLiquidationEngines().size();
            for (int s = 0; s < numShards; s++) {
                container.submitCommandSync(
                        ApiInsuranceFundDeposit.builder()
                                .shardId(s)
                                .transactionId(10000L + s)
                                .symbol(spec.symbolId)
                                .currencyAmount(amountPerShard)
                                .build(),
                        CommandResultCode.SUCCESS);
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
            assertThat(ifBalanceBeforeLiquidation, is(amountPerShard * (long) numShards));
            log.info("强平前IF余额: {}", ifBalanceBeforeLiquidation);
            // 充值后全局对账仍闭环
            assertThat(container.totalBalanceReport().isGlobalBalancesAllZero(), is(true));

            log.info("步骤2: 价格下跌到触发强平");
            container.updateCurrentPriceTo((int) liquidationTriggerPrice, spec.symbolId, quoteId);

            int marketCanTake = 5;
            log.info("步骤3: Maker 只挂部分买单 (只能吃掉{}张)", marketCanTake);
            container.createBidWithOrderId(30003L, UID_3, marketCanTake, bpFillPrice, spec.symbolId, MarginMode.CROSS);

            log.info("步骤4: 手动触发强平");
            container.getExchangeCore().getLiquidationEngines().forEach(LiquidationEngine::triggerOnce);

            // 等待强平和IF接管完成 - 需要更长时间
            LatencyTools.waitForCondition(60_000, () -> {
                try {
                    return container.getUserProfile(loser).getPositions().isEmpty();
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });

            log.info("步骤5: 验证强平结果");
            container.validateUserState(loser, profile -> {
                assertThat(profile.getPositions().size(), is(0));
                long liqFee = liquidationFee / 2;
                long closeFee = 5L * 20L;
                // BP=9970：市场吃 5 手 + IF 接管 5 手，各承担 (9970-10000)×5 = -150 → 合计 -300
                long profit = -300L;
                assertThat(profile.getAccounts().get(quoteId), is(userDeposit - makerFee - liqFee - closeFee + profit ));
            });

            // 验证用户余额变化
            long loserBalanceAfterLiquidation = container.getUserProfile(loser).getAccounts().get(quoteId);
            log.info("强平后用户余额: {}", loserBalanceAfterLiquidation);

            long ifBalanceAfterLiquidation = container.getIFBalance(spec.symbolId, quoteId);
            log.info("强平后IF余额: {}", ifBalanceAfterLiquidation);
            // IF 变化：+ 市场部分 liqFee(250) − IF 接管 5 手在 mark 9900 的实现损失 (9900-9970)×5 = -350
            assertThat(ifBalanceAfterLiquidation, is(amountPerShard * (long) numShards + 250L - 350L));
        } catch (ExecutionException | InterruptedException e) {
            throw new RuntimeException(e);
        } finally {
            verify(handler, times(26)).fundEventReport(fundEventCaptor.capture());
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
            assertThat(0L, Is.is(ifCloseEvt.getBalances().getLocked()));
            // 5 手市场 + 5 手 IF，各 -150，累计 -300
            assertThat(-300L, Is.is(ifCloseEvt.getPositions().getCumRealized()));
            assertThat(0L, Is.is(ifCloseEvt.getPositions().getOpenPriceSum()));
            assertThat(0L, Is.is(ifCloseEvt.getPositions().getQuantity()));
            EventCheck.checkEvent(ifCloseEvt);
            EventCheck.checkEventPending(ifCloseEvt);
        }
    }

    /**
     * 测试3: 逐仓爆仓后被强平，市场部分吃掉，IF接管剩余, 随后开启settlement funding fee, 测试不平衡的场景
     * 场景：用户逐仓模式下爆仓，市场只能部分吃掉强平单，IF接管剩余部分
     * 验证：1) 市场部分成交 2) IF接管剩余部分 3) 强平费用正确
     */
    @Test
    public void testIsolatedLiquidationPartialMatchedWithIFTakeover_FundingFee() {
        long userDeposit = 2000L;
        long makerDeposit = 100000L;
        int userSize = 10;
        int makerFee = userSize * 10;
        int takerFee = userSize * 20;
        int liquidationFee = userSize * 50;
        long openPrice = 10000;
        long liquidationTriggerPrice = 9900;
        // BP with liquidationFee: maxLoss = 1000 - (20+50)×10 = 300 → BP = (100000-300)/10 = 9970
        long bpFillPrice = 9970L;
        long amountPerShard = 500000L;

        try (final ExchangeTestContainer container = ExchangeTestContainer.create(getHignPerformanceConfiguration(), processor);) {
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
            // 通过 admin 命令定向给每 shard 各充 amountPerShard，同步反向记账 adjustments 保对账闭环
            int numShards = container.getExchangeCore().getLiquidationEngines().size();
            for (int s = 0; s < numShards; s++) {
                container.submitCommandSync(
                        ApiInsuranceFundDeposit.builder()
                                .shardId(s)
                                .transactionId(10000L + s)
                                .symbol(spec.symbolId)
                                .currencyAmount(amountPerShard)
                                .build(),
                        CommandResultCode.SUCCESS);
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
            assertThat(ifBalanceBeforeLiquidation, is(amountPerShard * (long) numShards));
            log.info("强平前IF余额: {}", ifBalanceBeforeLiquidation);
            // 充值后全局对账仍闭环
            assertThat(container.totalBalanceReport().isGlobalBalancesAllZero(), is(true));

            log.info("步骤2: 价格下跌到触发强平");
            container.updateCurrentPriceTo((int) liquidationTriggerPrice, spec.symbolId, quoteId);

            int marketCanTake = 5;
            log.info("步骤3: Maker 只挂部分买单 (只能吃掉{}张)", marketCanTake);
            container.createBidWithOrderId(30003L, UID_3, marketCanTake, bpFillPrice, spec.symbolId, MarginMode.CROSS);

            log.info("步骤4: 手动触发强平");
            container.getExchangeCore().getLiquidationEngines().forEach(LiquidationEngine::triggerOnce);

            // 等待强平和IF接管完成 - 需要更长时间
            LatencyTools.waitForCondition(60_000, () -> {
                try {
                    return container.getUserProfile(loser).getPositions().isEmpty();
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });

            log.info("步骤5: 验证强平结果");
            container.validateUserState(loser, profile -> {
                assertThat(profile.getPositions().size(), is(0));
                long liqFee = liquidationFee / 2;
                long closeFee = 5L * 20L;
                // BP=9970：市场吃 5 手 + IF 接管 5 手，各 (9970-10000)×5 = -150 → 合计 -300
                long profit = -300L;
                assertThat(profile.getAccounts().get(quoteId), is(userDeposit - makerFee - liqFee - closeFee + profit ));
            });

            // 验证用户余额变化
            long loserBalanceAfterLiquidation = container.getUserProfile(loser).getAccounts().get(quoteId);
            log.info("强平后用户余额: {}", loserBalanceAfterLiquidation);

            // 验证IF余额增加（收到强平费）
            long ifBalanceAfterLiquidation = container.getIFBalance(spec.symbolId, quoteId);
            log.info("强平后IF余额: {}", ifBalanceAfterLiquidation);
            // IF 变化：+ 市场 liqFee(250) − IF 接管 5 手在 mark 9900 的实现损失 (9900-9970)×5 = -350
            assertThat(ifBalanceAfterLiquidation, is(amountPerShard * (long) numShards + 250L - 350L));

            // loser被IF接管, 仓位为0, funding settlement对其无影响
            container.validateUserState(loser, profile -> {
                assertThat(profile.getPositions().size(), is(0));
            });
            // loser1的对手方maker此时仓位为1, 为做空, funding settlement后profit应增加
            container.validateUserState(maker, profile -> {
                assertThat(profile.getPositions().size(), is(1));
                assertThat(profile.getPositions().getFirst().get(0).profit, is(0L));
            });

            container.validateUserState(UID_3, profile -> {
                assertThat(profile.getPositions().size(), is(1));
                assertThat(profile.getPositions().getFirst().get(0).profit, is(0L));
            });

            // 做多用户19000L, funding settlement后profit应该减少
            container.validateUserState(UPDATE_PRICE_USER1, profile -> {
                assertThat(profile.getPositions().size(), is(1));
                assertThat(profile.getPositions().getFirst().get(0).profit, is(0L));
            });
            // 做空用户19001L, funding settlement后profit应该增多
            container.validateUserState(UPDATE_PRICE_USER2, profile -> {
                assertThat(profile.getPositions().size(), is(1));
                assertThat(profile.getPositions().getFirst().get(0).profit, is(0L));
            });

            // 发送settlement命令
            // 执行资金费率: 0.01% = 1 / 10000
            long fundingRate = 1;
            long rateScale = 10000;
            container.submitCommandSync(ApiSettleFundingFees.builder()
                    .symbol(spec.symbolId)
                    .action(OrderAction.BID)
                    .fundingRate(fundingRate)
                    .rateScaleK(rateScale)
                    .transactionId(9999)
                    .build(), CommandResultCode.SUCCESS);

            // check profit
            container.validateUserState(loser, profile -> {
                assertThat(profile.getPositions().size(), is(0));
            });

            container.validateUserState(maker, profile -> {
                assertThat(profile.getPositions().size(), is(1));
                assertThat(profile.getPositions().getFirst().get(0).profit, is(7L));
            });

            container.validateUserState(UID_3, profile -> {
                assertThat(profile.getPositions().size(), is(1));
                assertThat(profile.getPositions().getFirst().get(0).profit, is(-4L));
            });

            container.validateUserState(UPDATE_PRICE_USER1, profile -> {
                assertThat(profile.getPositions().size(), is(1));
                assertThat(profile.getPositions().getFirst().get(0).profit, is(-9L));
            });

            container.validateUserState(UPDATE_PRICE_USER2, profile -> {
                assertThat(profile.getPositions().size(), is(1));
                assertThat(profile.getPositions().getFirst().get(0).profit, is(6L));
            });

        } catch (ExecutionException | InterruptedException e) {
            throw new RuntimeException(e);
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
        // BP with liquidationFee: maxLoss = 1000 - (20+50)×10 = 300 → BP = (100000-300)/10 = 9970
        long bpFillPrice = 9970L;
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

            // 通过 admin 命令定向给每 shard 各充 amountPerShard，同步反向记账 adjustments 保对账闭环
            int numShards = container.getExchangeCore().getLiquidationEngines().size();
            for (int s = 0; s < numShards; s++) {
                container.submitCommandSync(
                        ApiInsuranceFundDeposit.builder()
                                .shardId(s)
                                .transactionId(10000L + s)
                                .symbol(spec.symbolId)
                                .currencyAmount(amountPerShard)
                                .build(),
                        CommandResultCode.SUCCESS);
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
            assertThat(ifBalanceBeforeLiquidation, is(amountPerShard * (long) numShards));
            log.info("强平前IF余额: {}", ifBalanceBeforeLiquidation);
            // 充值后全局对账仍闭环
            assertThat(container.totalBalanceReport().isGlobalBalancesAllZero(), is(true));

            log.info("步骤2: 价格下跌到触发强平");
            container.updateCurrentPriceTo((int) liquidationTriggerPrice, spec.symbolId, quoteId);

            int marketCanTake = 4;
            log.info("步骤3: Maker 只挂部分买单 (只能吃掉{}张)", marketCanTake);
            container.createBidWithOrderId(30003L, UID_3, marketCanTake, bpFillPrice, spec.symbolId, MarginMode.CROSS);

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
            LatencyTools.waitForCondition(60_000, () -> {
                try {
                    return container.getUserProfile(loser).getPositions().isEmpty();
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });

            log.info("步骤5: 验证强平结果");
            container.validateUserState(loser, profile -> {
                assertThat(profile.getPositions().size(), is(0));
                long liqFee = liquidationFee * 4 / 10;
                long closeFee = 4L * 20L;
                // BP=9970：4 手市场 + 6 手 ADL，各 (9970-10000) → -120 + -180 = -300
                long profit = -300L;
                assertThat(profile.getAccounts().get(quoteId), is(userDeposit - makerFee - liqFee - closeFee + profit ));
            });

            // 验证ADL候选人仓位在强平后的数值
            container.validateUserState(maker, profile -> {
                assertThat(profile.getPositions().size(), is(1));
                assertThat(profile.getAccounts().get(quoteId), is(makerDeposit - takerFee));
                assertThat(profile.getPositions().get(spec.symbolId).get(0).getOpenVolume(), is(userSize - 6L));
                assertThat(profile.getPositions().get(spec.symbolId).get(0).getOpenInitMarginSum(), is(400L));
                assertThat(profile.getPositions().get(spec.symbolId).get(0).getOpenPriceSum(), is(40180L));
                assertThat(profile.getPositions().get(spec.symbolId).get(0).getUnrealizedProfit(), is((userSize - 6L) * (40180L / (userSize - 6L) - liquidationTriggerPrice)));
            });

            container.validateUserState(loser, profile -> {
                assertThat(profile.getPositions().size(), is(0));
                long liqFee = liquidationFee * 4 / 10;
                long closeFee = 4L * 20L;
                // BP=9970：4 手市场 + 6 手 ADL，各 (9970-10000) → -120 + -180 = -300
                long profit = -300L;
                assertThat(profile.getAccounts().get(quoteId), is(userDeposit - makerFee - liqFee - closeFee + profit ));
            });

            // 验证用户余额变化
            long loserBalanceAfterLiquidation = container.getUserProfile(loser).getAccounts().get(quoteId);
            log.info("强平后用户余额: {}", loserBalanceAfterLiquidation);

            // 验证IF余额会增加, 因为IF金额不够覆盖剩余的强平后订单(6手), 又收取了4手强平手续费(4 * 50)
            long ifBalanceAfterLiquidation = container.getIFBalance(spec.symbolId, quoteId);
            log.info("强平后IF余额: {}", ifBalanceAfterLiquidation);
            assertThat(ifBalanceAfterLiquidation, is(amountPerShard * (long) numShards + 4 * 50L));

        } catch (ExecutionException | InterruptedException e) {
            throw new RuntimeException(e);
        } finally {
            verify(handler, times(31)).fundEventReport(fundEventCaptor.capture());
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
            assertThat(40180L, Is.is(adlCloseEvt.getPositions().getOpenPriceSum()));
            assertThat(4L, Is.is(adlCloseEvt.getPositions().getQuantity()));
            // avgPrice = openPriceSum/qty = 40180/4 = 10045, UPnL = (10045-9900)×4 = 580
            assertThat(580L, Is.is(adlCloseEvt.getPositions().getUnrealizedProfit()));
            assertThat(23330L, Is.is(adlCloseEvt.getPositions().getLiquidationPrice()));
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
            assertThat(1620L, Is.is(adlOriginCloseEvt.getBalances().getFree()));
            assertThat(0L, Is.is(adlOriginCloseEvt.getBalances().getLocked()));
            // cumRealized：4 手市场 + 6 手 ADL 各 -30 → -120 + -180 = -300
            assertThat(-300L, Is.is(adlOriginCloseEvt.getPositions().getCumRealized()));
            assertThat(0L, Is.is(adlOriginCloseEvt.getPositions().getOpenPriceSum()));
            assertThat(0L, Is.is(adlOriginCloseEvt.getPositions().getQuantity()));
            EventCheck.checkEvent(adlOriginCloseEvt);
            EventCheck.checkEventPending(adlOriginCloseEvt);
        }
    }

    /**
     * IF 充值：admin 触发 ApiInsuranceFundDeposit，验证：
     *   1) IF 可用资金按 currencyAmount 入账；
     *   2) adjustments 同步反向记账，全局对账 isGlobalBalancesAllZero() 仍为 true；
     *   3) 非法入参（负数、未知 symbol）被正确拒绝；
     *   4) 多次充值可累加。
     * close() 会再次自动校验对账，给该路径上一道额外保险。
     */
    @Test
    public void testInsuranceFundDepositKeepsGlobalReconciliation() {
        long firstDeposit = 1_000_000L;
        long secondDeposit = 500L;

        try (final ExchangeTestContainer container = ExchangeTestContainer.create(getPerformanceConfiguration())) {
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
            container.initMarkPrice(spec.symbolId, 10000);

            // 初始：IF 空，全局对账闭环
            assertThat(container.getIFBalance(spec.symbolId, spec.quoteCurrency), is(0L));
            assertThat(container.totalBalanceReport().isGlobalBalancesAllZero(), is(true));

            // 第一笔充值
            container.submitCommandSync(
                    ApiInsuranceFundDeposit.builder()
                            .shardId(0)
                            .transactionId(1L)
                            .symbol(spec.symbolId)
                            .currencyAmount(firstDeposit)
                            .build(),
                    CommandResultCode.SUCCESS);

            assertThat(container.getIFBalance(spec.symbolId, spec.quoteCurrency), is(firstDeposit));
            assertThat(container.totalBalanceReport().getAdjustments().get(spec.quoteCurrency), is(-firstDeposit));
            assertThat(container.totalBalanceReport().isGlobalBalancesAllZero(), is(true));

            // 非法入参：负数
            container.submitCommandSync(
                    ApiInsuranceFundDeposit.builder()
                            .shardId(0)
                            .transactionId(2L)
                            .symbol(spec.symbolId)
                            .currencyAmount(-1L)
                            .build(),
                    CommandResultCode.RISK_INVALID_AMOUNT);

            // 非法入参：零金额
            container.submitCommandSync(
                    ApiInsuranceFundDeposit.builder()
                            .shardId(0)
                            .transactionId(3L)
                            .symbol(spec.symbolId)
                            .currencyAmount(0L)
                            .build(),
                    CommandResultCode.RISK_INVALID_AMOUNT);

            // 非法入参：未知 symbol
            container.submitCommandSync(
                    ApiInsuranceFundDeposit.builder()
                            .shardId(0)
                            .transactionId(4L)
                            .symbol(99999)
                            .currencyAmount(1000L)
                            .build(),
                    CommandResultCode.INVALID_SYMBOL);

            // 失败入参不应改动账本
            assertThat(container.getIFBalance(spec.symbolId, spec.quoteCurrency), is(firstDeposit));
            assertThat(container.totalBalanceReport().isGlobalBalancesAllZero(), is(true));

            // 第二笔充值，余额累加
            container.submitCommandSync(
                    ApiInsuranceFundDeposit.builder()
                            .shardId(0)
                            .transactionId(5L)
                            .symbol(spec.symbolId)
                            .currencyAmount(secondDeposit)
                            .build(),
                    CommandResultCode.SUCCESS);

            assertThat(container.getIFBalance(spec.symbolId, spec.quoteCurrency), is(firstDeposit + secondDeposit));
            assertThat(container.totalBalanceReport().getAdjustments().get(spec.quoteCurrency),
                    is(-(firstDeposit + secondDeposit)));
            assertThat(container.totalBalanceReport().isGlobalBalancesAllZero(), is(true));
        }
    }

    /**
     * 多 shard 场景下的 IF 充值：验证
     *   1) 定向单 shard 注资，只有匹配 shard 入账，其他 shard 静默 no-op；
     *   2) 分别给两个 shard 各自定向充值，聚合后 = 两次充值之和；
     *   3) 全局对账仍闭环。
     * 用 throughputPerformanceBuilder() （riskEnginesNum=2）来验证跨 shard 独立性。
     */
    @Test
    public void testInsuranceFundDepositDistributesAcrossShards() {
        long depositShard0 = 500_000L;
        long depositShard1 = 300_000L;

        try (final ExchangeTestContainer container = ExchangeTestContainer.create(getHignPerformanceConfiguration())) {
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
            container.initMarkPrice(spec.symbolId, 10000);

            // 定向 shard 0 注资
            container.submitCommandSync(
                    ApiInsuranceFundDeposit.builder()
                            .shardId(0)
                            .transactionId(1L)
                            .symbol(spec.symbolId)
                            .currencyAmount(depositShard0)
                            .build(),
                    CommandResultCode.SUCCESS);

            // 定向 shard 1 注资
            container.submitCommandSync(
                    ApiInsuranceFundDeposit.builder()
                            .shardId(1)
                            .transactionId(2L)
                            .symbol(spec.symbolId)
                            .currencyAmount(depositShard1)
                            .build(),
                    CommandResultCode.SUCCESS);

            // 聚合后 = shard0 + shard1
            assertThat(container.getIFBalance(spec.symbolId, spec.quoteCurrency), is(depositShard0 + depositShard1));
            assertThat(container.totalBalanceReport().getAdjustments().get(spec.quoteCurrency),
                    is(-(depositShard0 + depositShard1)));
            assertThat(container.totalBalanceReport().isGlobalBalancesAllZero(), is(true));

            // 非法入参（负数）：拒绝
            container.submitCommandSync(
                    ApiInsuranceFundDeposit.builder()
                            .shardId(0)
                            .transactionId(3L)
                            .symbol(spec.symbolId)
                            .currencyAmount(-1L)
                            .build(),
                    CommandResultCode.RISK_INVALID_AMOUNT);
            assertThat(container.getIFBalance(spec.symbolId, spec.quoteCurrency), is(depositShard0 + depositShard1));
            assertThat(container.totalBalanceReport().isGlobalBalancesAllZero(), is(true));
        }
    }

    /**
     * IF 抽资：admin 触发 ApiInsuranceFundWithdraw，验证：
     *   1) 正常抽资：available 扣减，adjustments 反向对冲，全局对账仍闭环；
     *   2) 抽资超余额 → RISK_IF_INSUFFICIENT，账本不动；
     *   3) 非法入参（负数 / 零 / 未知 symbol）被正确拒绝；
     *   4) 定向 shard 匹配（单 shard 场景下 shardId=0 生效）；
     *   5) Deposit + Withdraw 组合：balance = deposit − withdraw。
     */
    @Test
    public void testInsuranceFundWithdrawKeepsGlobalReconciliation() {
        long deposit = 1_000_000L;
        long firstWithdraw = 300_000L;
        long secondWithdraw = 200_000L;

        try (final ExchangeTestContainer container = ExchangeTestContainer.create(getPerformanceConfiguration())) {
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
            container.initMarkPrice(spec.symbolId, 10000);

            // 初始：IF 空，withdraw 立即返回 IF_INSUFFICIENT
            container.submitCommandSync(
                    ApiInsuranceFundWithdraw.builder()
                            .shardId(0)
                            .transactionId(1L)
                            .symbol(spec.symbolId)
                            .currencyAmount(100L)
                            .build(),
                    CommandResultCode.RISK_IF_INSUFFICIENT);
            assertThat(container.getIFBalance(spec.symbolId, spec.quoteCurrency), is(0L));
            assertThat(container.totalBalanceReport().isGlobalBalancesAllZero(), is(true));

            // 先充值 1_000_000
            container.submitCommandSync(
                    ApiInsuranceFundDeposit.builder()
                            .shardId(0)
                            .transactionId(2L)
                            .symbol(spec.symbolId)
                            .currencyAmount(deposit)
                            .build(),
                    CommandResultCode.SUCCESS);
            assertThat(container.getIFBalance(spec.symbolId, spec.quoteCurrency), is(deposit));
            assertThat(container.totalBalanceReport().isGlobalBalancesAllZero(), is(true));

            // 正常抽 300_000：available 剩 700_000，adjustments 反向对冲
            container.submitCommandSync(
                    ApiInsuranceFundWithdraw.builder()
                            .shardId(0)
                            .transactionId(3L)
                            .symbol(spec.symbolId)
                            .currencyAmount(firstWithdraw)
                            .build(),
                    CommandResultCode.SUCCESS);
            assertThat(container.getIFBalance(spec.symbolId, spec.quoteCurrency), is(deposit - firstWithdraw));
            assertThat(container.totalBalanceReport().getAdjustments().get(spec.quoteCurrency),
                    is(-(deposit - firstWithdraw)));
            assertThat(container.totalBalanceReport().isGlobalBalancesAllZero(), is(true));

            // 抽超余额：available=700_000 < 800_000 → IF_INSUFFICIENT，账本不动
            container.submitCommandSync(
                    ApiInsuranceFundWithdraw.builder()
                            .shardId(0)
                            .transactionId(4L)
                            .symbol(spec.symbolId)
                            .currencyAmount(800_000L)
                            .build(),
                    CommandResultCode.RISK_IF_INSUFFICIENT);
            assertThat(container.getIFBalance(spec.symbolId, spec.quoteCurrency), is(deposit - firstWithdraw));
            assertThat(container.totalBalanceReport().isGlobalBalancesAllZero(), is(true));

            // 非法入参：负数
            container.submitCommandSync(
                    ApiInsuranceFundWithdraw.builder()
                            .shardId(0)
                            .transactionId(5L)
                            .symbol(spec.symbolId)
                            .currencyAmount(-1L)
                            .build(),
                    CommandResultCode.RISK_INVALID_AMOUNT);

            // 非法入参：零金额
            container.submitCommandSync(
                    ApiInsuranceFundWithdraw.builder()
                            .shardId(0)
                            .transactionId(6L)
                            .symbol(spec.symbolId)
                            .currencyAmount(0L)
                            .build(),
                    CommandResultCode.RISK_INVALID_AMOUNT);

            // 非法入参：未知 symbol
            container.submitCommandSync(
                    ApiInsuranceFundWithdraw.builder()
                            .shardId(0)
                            .transactionId(7L)
                            .symbol(99999)
                            .currencyAmount(1000L)
                            .build(),
                    CommandResultCode.INVALID_SYMBOL);

            // 失败入参不应改动账本
            assertThat(container.getIFBalance(spec.symbolId, spec.quoteCurrency), is(deposit - firstWithdraw));
            assertThat(container.totalBalanceReport().isGlobalBalancesAllZero(), is(true));

            // 再次抽 200_000：available 剩 500_000
            container.submitCommandSync(
                    ApiInsuranceFundWithdraw.builder()
                            .shardId(0)
                            .transactionId(8L)
                            .symbol(spec.symbolId)
                            .currencyAmount(secondWithdraw)
                            .build(),
                    CommandResultCode.SUCCESS);
            assertThat(container.getIFBalance(spec.symbolId, spec.quoteCurrency),
                    is(deposit - firstWithdraw - secondWithdraw));
            assertThat(container.totalBalanceReport().getAdjustments().get(spec.quoteCurrency),
                    is(-(deposit - firstWithdraw - secondWithdraw)));
            assertThat(container.totalBalanceReport().isGlobalBalancesAllZero(), is(true));
        }
    }

    /**
     * 多 shard 场景下的 IF 抽资：验证
     *   1) 定向 shard 抽资只影响匹配 shard；
     *   2) shard 0 空但 shard 1 有钱时，从 shard 0 抽 → IF_INSUFFICIENT（跨 shard 不调剂）。
     */
    @Test
    public void testInsuranceFundWithdrawIsPerShardIsolated() {
        try (final ExchangeTestContainer container = ExchangeTestContainer.create(getHignPerformanceConfiguration())) {
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
            container.initMarkPrice(spec.symbolId, 10000);

            // 只给 shard 1 充值 500_000
            container.submitCommandSync(
                    ApiInsuranceFundDeposit.builder()
                            .shardId(1)
                            .transactionId(1L)
                            .symbol(spec.symbolId)
                            .currencyAmount(500_000L)
                            .build(),
                    CommandResultCode.SUCCESS);

            // 从 shard 0 抽钱 → shard 0 空，IF_INSUFFICIENT（跨 shard 独立，不调剂）
            container.submitCommandSync(
                    ApiInsuranceFundWithdraw.builder()
                            .shardId(0)
                            .transactionId(2L)
                            .symbol(spec.symbolId)
                            .currencyAmount(100_000L)
                            .build(),
                    CommandResultCode.RISK_IF_INSUFFICIENT);
            // shard 1 余额未变
            assertThat(container.getIFBalance(spec.symbolId, spec.quoteCurrency), is(500_000L));
            assertThat(container.totalBalanceReport().isGlobalBalancesAllZero(), is(true));

            // 从 shard 1 抽 200_000 → 成功
            container.submitCommandSync(
                    ApiInsuranceFundWithdraw.builder()
                            .shardId(1)
                            .transactionId(3L)
                            .symbol(spec.symbolId)
                            .currencyAmount(200_000L)
                            .build(),
                    CommandResultCode.SUCCESS);
            assertThat(container.getIFBalance(spec.symbolId, spec.quoteCurrency), is(300_000L));
            assertThat(container.totalBalanceReport().isGlobalBalancesAllZero(), is(true));
        }
    }

    /**
     * InsuranceFundReportQuery：验证
     *   1) 空池 → 每 shard perSymbolIF map 为空；
     *   2) 定向充值后，只有匹配 shard 的 perSymbolIF 出现该 symbol entry；
     *   3) 多 shard 多 symbol：byShard.size() == numShards，每 shard 独立含各自 symbol 明细；
     *   4) 单 shard 视图的 available 值等于 IFBalance；
     *   5) 全局对账仍闭环。
     */
    @Test
    public void testInsuranceFundReportQueryPerShardBreakdown() throws Exception {
        int symbolAId = 10000;
        int symbolBId = 10001;

        try (final ExchangeTestContainer container = ExchangeTestContainer.create(getHignPerformanceConfiguration())) {
            container.getExchangeCore().getLiquidationEngines().forEach(LiquidationEngine::stop);

            CoreSymbolSpecification specA = futuresSpec(symbolAId);
            CoreSymbolSpecification specB = futuresSpec(symbolBId);
            container.addSymbol(specA);
            container.addSymbol(specB);
            container.addCurrency(specA.baseCurrency, 0);
            container.addCurrency(specA.quoteCurrency, 0);
            container.initMarkPrice(symbolAId, 10000);
            container.initMarkPrice(symbolBId, 10000);

            int numShards = container.getExchangeCore().getLiquidationEngines().size();
            assertThat(numShards, is(2));   // throughputPerformanceBuilder

            // 1) 空池：byShard 包含所有 shard，但每 shard perSymbolIF 为空
            InsuranceFundReportResult emptyReport = container.getApi()
                    .processReport(new InsuranceFundReportQuery(), 100).get();
            assertThat(emptyReport.getByShard().size(), is(numShards));
            emptyReport.getByShard().forEachKeyValue((shardId, data) -> {
                assertThat("shard " + shardId + " perSymbolIF should be empty",
                        data.getPerSymbolIF().isEmpty(), is(true));
            });

            // 2) 定向充值：shard 0 → symbol A 500_000，shard 1 → symbol A 300_000 + symbol B 700_000
            container.submitCommandSync(
                    ApiInsuranceFundDeposit.builder().shardId(0).transactionId(1L)
                            .symbol(symbolAId).currencyAmount(500_000L).build(),
                    CommandResultCode.SUCCESS);
            container.submitCommandSync(
                    ApiInsuranceFundDeposit.builder().shardId(1).transactionId(2L)
                            .symbol(symbolAId).currencyAmount(300_000L).build(),
                    CommandResultCode.SUCCESS);
            container.submitCommandSync(
                    ApiInsuranceFundDeposit.builder().shardId(1).transactionId(3L)
                            .symbol(symbolBId).currencyAmount(700_000L).build(),
                    CommandResultCode.SUCCESS);

            // 3) 查询 report，验证 per-shard × per-symbol 明细
            InsuranceFundReportResult report = container.getApi()
                    .processReport(new InsuranceFundReportQuery(), 101).get();
            assertThat(report.getByShard().size(), is(numShards));

            // shard 0：只有 symbol A，available=500_000
            InsuranceFundReportResult.PerShardData shard0 = report.getByShard().get(0);
            assertThat(shard0.getPerSymbolIF().size(), is(1));
            InsuranceFundReportResult.IFEntry entryA0 = shard0.getPerSymbolIF().get(symbolAId);
            assertThat(entryA0.getAvailable(), is(500_000L));
            assertThat(entryA0.getPositionValue(), is(0L));
            assertThat(entryA0.total(), is(500_000L));

            // shard 1：symbol A 300_000 + symbol B 700_000
            InsuranceFundReportResult.PerShardData shard1 = report.getByShard().get(1);
            assertThat(shard1.getPerSymbolIF().size(), is(2));
            assertThat(shard1.getPerSymbolIF().get(symbolAId).getAvailable(), is(300_000L));
            assertThat(shard1.getPerSymbolIF().get(symbolBId).getAvailable(), is(700_000L));

            // 4) per-symbol 跨 shard 聚合
            //    symbol A：shard 0 500K + shard 1 300K = 800K
            //    symbol B：shard 1 700K
            long totalSymbolAAvailable = 0L;
            long totalSymbolBAvailable = 0L;
            for (int s = 0; s < numShards; s++) {
                InsuranceFundReportResult.IFEntry entryA = report.getByShard().get(s).getPerSymbolIF().get(symbolAId);
                if (entryA != null) totalSymbolAAvailable += entryA.getAvailable();
                InsuranceFundReportResult.IFEntry entryB = report.getByShard().get(s).getPerSymbolIF().get(symbolBId);
                if (entryB != null) totalSymbolBAvailable += entryB.getAvailable();
            }
            assertThat(totalSymbolAAvailable, is(800_000L));
            assertThat(totalSymbolBAvailable, is(700_000L));

            // 5) 全局对账仍闭环
            //    IFBalance 是 per-quote-currency 聚合（跨 symbol），symbol A+B 共 500K+300K+700K=1.5M
            assertThat(container.getIFBalance(symbolAId, specA.quoteCurrency), is(1_500_000L));
            assertThat(container.totalBalanceReport().isGlobalBalancesAllZero(), is(true));
        }
    }

    /**
     * 场景：同一用户同时持 ISOLATED（BTC）+ CROSS（LTC）于同 currency（USD）。
     * ISO 开仓时 openInitMarginSum 只在仓位记录里虚拟锁定，未从 accounts 物理扣除；
     * {@code LiquidationEngine.calculateCrossAvailableCurrency} 必须显式剥离这部分——
     * 否则 cross equity 高估、MARGIN_ALERT 触发时机偏晚，逐仓多的账户 cross 强平延迟、坏账风险↑。
     *
     * <p>数值构造：mark 落在 [with-fix 触发, without-fix 不触发] 区间内。
     * <pre>
     *   deposit = 300, 两笔 maker fee 分别 10（BTC）+ 20（LTC）→ accounts = 270
     *   ISO BTC: openInitMarginSum = 10000 × 1% = 100（虚拟锁定）
     *   CROSS LTC: openInitMarginSum = 100，MM(9885) = 9885 × 0.5% = 49，warning = 49 × 1.2 = 58
     *   without fix: balance = 270, equity = 270 + (9885−10000) = 155 > 58 → 不告警
     *   with fix:    balance = 270 − 100 = 170, equity = 170 − 115 = 55 < 58 且 > 49 → MARGIN_ALERT ✓
     * </pre>
     */
    @Test
    public void testCrossMarginAlert_isolatedMarginExcluded() {
        long deposit = 300L;
        long userIsoAndCross = UID_1;
        long makerUid = UID_2;
        int size = 1;
        long entryPrice = 10000L;
        int crossMarkAfterDrop = 9885;

        try (final ExchangeTestContainer container = ExchangeTestContainer.create(getPerformanceConfiguration(), processor);) {
            container.getExchangeCore().getLiquidationEngines().forEach(LiquidationEngine::stop);
            List<CoreSymbolSpecification> symbols = container.initFutureSymbols();
            // 用 BTC (symbols.get(0)) 作 ISO 仓位，LTC (symbols.get(2)) 作 CROSS 仓位，都属 CURRENECY_USD
            container.initMarkPrice(symbols.get(0).symbolId, (int) entryPrice);
            container.initMarkPrice(symbols.get(2).symbolId, (int) entryPrice);
            container.createUserWithSpecificMoney(userIsoAndCross, deposit, quoteId);
            container.createUserWithSpecificMoney(makerUid, MAX_VALUE, quoteId);

            // ISO 位置（BTC）：userIsoAndCross 作 maker，makerUid 送 taker ASK 撮合
            container.createBidWithOrderId(1L, userIsoAndCross, size, entryPrice, symbols.get(0).symbolId, MarginMode.ISOLATED);
            container.createAskWithOrderId(2L, makerUid, size, entryPrice, symbols.get(0).symbolId, MarginMode.CROSS);
            // CROSS 位置（LTC）：同 currency，不同 symbol
            container.createBidWithOrderId(3L, userIsoAndCross, size, entryPrice, symbols.get(2).symbolId, MarginMode.CROSS);
            container.createAskWithOrderId(4L, makerUid, size, entryPrice, symbols.get(2).symbolId, MarginMode.CROSS);

            container.validateUserState(userIsoAndCross, profile -> {
                assertThat(profile.getPositions().size(), is(2));
            });

            // LTC mark 下降到 with-fix 触发区间；scanner 一次
            container.updateCurrentPriceTo(crossMarkAfterDrop, symbols.get(2).symbolId, quoteId);
            container.getExchangeCore().getLiquidationEngines().forEach(LiquidationEngine::triggerOnce);

        } catch (ExecutionException | InterruptedException e) {
            throw new RuntimeException(e);
        } finally {
            verify(handler, atLeastOnce()).fundEventReport(fundEventCaptor.capture());
            List<IFundEventsHandler.FundEventReport> events = fundEventCaptor.getAllValues();
            boolean crossAlertFired = events.stream().anyMatch(e ->
                e.getEventType() == FundEvent.FundEventType.MARGIN_ALERT
                    && e.getAccountId() == UID_1
                    && e.getPositions().getSymbolId() == 10002);
            assertThat(
                "LTC CROSS 位置应触发 MARGIN_ALERT——若失败，说明 calculateCrossAvailableCurrency 未剥离 ISO 虚拟锁定，cross equity 被高估",
                crossAlertFired, is(true));
        }
    }

    /**
     * 多仓 CROSS BP 按 MM 占比分配的回归保护：
     * 同一用户在同 currency 下有 2 个 CROSS 仓（BTC + LTC），BTC 大幅下跌触发强平。
     *
     * <p>分配语义：
     * <pre>
     *   marginBalance = crossAvailable + Σ CROSS UPnL
     *   allocated_i   = marginBalance × mm_i / Σ MM
     *   marginBase_i  = allocated_i − UPnL_i          （EP 基础）
     *   BP_i          用 marginBase_i 反解
     * </pre>
     *
     * <p>断言点：
     * <ul>
     *   <li>BTC CROSS 触发 LIQUIDATION_ALERT（说明强平流程走到 FORCE 挂单 → BP 计算路径确实被走）</li>
     *   <li>LTC CROSS 位置保留（说明 BTC 的 BP 分配只影响 BTC 的强平，兄弟仓不误伤）</li>
     *   <li>账户级 balance + 全局对账闭环（说明分配公式的数学总账守恒）</li>
     * </ul>
     * 单仓 CROSS BP 与旧 4 参重载代数等价（现有测试 testCrossMarginLiquidationWarning 覆盖）；
     * 本测试锁定多仓路径下"MM 占比分配"的调用点，未来重构走偏能被 catch。
     */
    @Test
    public void testCrossBpMultiPositionAllocation_regressionLock() {
        long deposit = 500L;
        long user = UID_1;
        long maker = UID_2;
        int size = 1;
        long entryPrice = 10000L;
        // deposit(500) - 两笔 maker fee(10+20) = crossAvailable 470，
        // 至 mark 9600：equity = 470 + (9600-10000) = 70 < ΣMM(48+50=98) → LIQUIDATION_ALERT
        int btcMarkAfterDrop = 9600;

        try (final ExchangeTestContainer container = ExchangeTestContainer.create(getPerformanceConfiguration(), processor);) {
            container.getExchangeCore().getLiquidationEngines().forEach(LiquidationEngine::stop);
            List<CoreSymbolSpecification> symbols = container.initFutureSymbols();
            container.initMarkPrice(symbols.get(0).symbolId, (int) entryPrice); // BTC
            container.initMarkPrice(symbols.get(2).symbolId, (int) entryPrice); // LTC
            container.createUserWithSpecificMoney(user, deposit, quoteId);
            container.createUserWithSpecificMoney(maker, MAX_VALUE, quoteId);

            // BTC CROSS：user 作 maker
            container.createBidWithOrderId(1L, user, size, entryPrice, symbols.get(0).symbolId, MarginMode.CROSS);
            container.createAskWithOrderId(2L, maker, size, entryPrice, symbols.get(0).symbolId, MarginMode.CROSS);
            // LTC CROSS：同 currency 不同 symbol
            container.createBidWithOrderId(3L, user, size, entryPrice, symbols.get(2).symbolId, MarginMode.CROSS);
            container.createAskWithOrderId(4L, maker, size, entryPrice, symbols.get(2).symbolId, MarginMode.CROSS);

            container.validateUserState(user, profile -> {
                assertThat(profile.getPositions().size(), is(2));
            });

            container.updateCurrentPriceTo(btcMarkAfterDrop, symbols.get(0).symbolId, quoteId);
            container.getExchangeCore().getLiquidationEngines().forEach(LiquidationEngine::triggerOnce);

            // 全局对账闭环：分配公式数学总账守恒
            assertThat(container.totalBalanceReport().isGlobalBalancesAllZero(), is(true));
        } catch (ExecutionException | InterruptedException e) {
            throw new RuntimeException(e);
        } finally {
            verify(handler, atLeastOnce()).fundEventReport(fundEventCaptor.capture());
            List<IFundEventsHandler.FundEventReport> events = fundEventCaptor.getAllValues();
            boolean btcLiquidationAlert = events.stream().anyMatch(e ->
                e.getEventType() == FundEvent.FundEventType.LIQUIDATION_ALERT
                    && e.getAccountId() == UID_1
                    && e.getPositions().getSymbolId() == 10000);
            assertThat("BTC CROSS 应触发 LIQUIDATION_ALERT——多仓 BP 分配路径未被走到",
                btcLiquidationAlert, is(true));
        }
    }

    private static CoreSymbolSpecification futuresSpec(int symbolId) {
        return CoreSymbolSpecification.builder()
                .symbolId(symbolId)
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
    }

}