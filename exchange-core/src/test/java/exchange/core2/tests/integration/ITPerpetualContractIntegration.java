package exchange.core2.tests.integration;

import exchange.core2.core.IFundEventsHandler;
import exchange.core2.core.ITradeEventsHandler;
import exchange.core2.core.common.*;
import exchange.core2.core.common.api.*;
import exchange.core2.core.common.cmd.CommandResultCode;
import exchange.core2.core.common.config.PerformanceConfiguration;
import exchange.core2.core.event.IEventsHandler4Test;
import exchange.core2.core.event.SimpleEventsProcessor4Test;
import exchange.core2.tests.util.ExchangeTestContainer;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.collections.impl.map.sorted.mutable.TreeSortedMap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static exchange.core2.tests.util.TestConstants.*;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.core.Is.is;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Slf4j
@ExtendWith(MockitoExtension.class)
class ITPerpetualContractIntegration {

    private int symbolId = 2;
    private int quoteId = 840;

    private SimpleEventsProcessor4Test processor;

    @Mock
    private IEventsHandler4Test handler;

    @Captor
    private ArgumentCaptor<ITradeEventsHandler.ApiCommandResult> commandResultCaptor;

    @Captor
    private ArgumentCaptor<ITradeEventsHandler.TradeEvent> tradeEventCaptor;

    @Captor
    private ArgumentCaptor<IFundEventsHandler.FundsEvent> fundEventCaptor;

    @Captor
    private ArgumentCaptor<ITradeEventsHandler.ReduceEvent> reduceEventCaptor;

    @Captor
    private ArgumentCaptor<ITradeEventsHandler.RejectEvent> rejectEventCaptor;


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

    @Test
    public void testInvalidSymbol() {
        try (final ExchangeTestContainer container = ExchangeTestContainer.create(getPerformanceConfiguration())) {
            container.setConsumer(processor);
            int symbolId0 = 10000;
            CoreSymbolSpecification spec0 = CoreSymbolSpecification.builder()
                    .symbolId(symbolId0)
                    .type(SymbolType.FUTURES_CONTRACT_PERPETUAL)
                    .baseCurrency(11)
                    .quoteCurrency(12)
//                    .marginBuy(100)
//                    .marginSell(100)
                    .feeScaleK(100)
                    .makerFee(1)
                    .takerFee(2)
//                    .maxLeverage(50)
                    .maintenanceMargin(TreeSortedMap.newMapWith(1000L, 5L, 100000L, 10L))
                    .maxLeverage(TreeSortedMap.newMapWith(2000L, 5L, 100000L, 10L))
                    .build();
            container.addSymbol(spec0);

            int symbolId1 = 10001;
            CoreSymbolSpecification spec1 = CoreSymbolSpecification.builder()
                    .symbolId(symbolId1)
                    .type(SymbolType.FUTURES_CONTRACT_DELIVERY)
                    .baseCurrency(11)
                    .quoteCurrency(12)
//                    .marginBuy(100)
//                    .marginSell(100)
                    .feeScaleK(100)
                    .makerFee(1)
                    .takerFee(2)
//                    .maxLeverage(50)
                    .maintenanceMargin(TreeSortedMap.newMapWith(1000L, 5L, 100000L, 10L))
                    .maxLeverage(TreeSortedMap.newMapWith(2000L, 5L, 100000L, 10L))
                    .build();
            container.addSymbol(spec1);

            ApiSettleFundingFees cmd1 = ApiSettleFundingFees.builder()
                    .transactionId(1004L)
                    .symbol(symbolId1)
                    .fundingRate(33)
                    .rateScaleK(100)
                    .build();
            container.submitCommandSync(cmd1, CommandResultCode.INVALID_SYMBOL);

            ApiSettleFundingFees cmd0 = ApiSettleFundingFees.builder()
                    .transactionId(1003L)
                    .symbol(symbolId0)
                    .fundingRate(33)
                    .rateScaleK(100)
                    .build();
            container.submitCommandSync(cmd0, CommandResultCode.RISK_MARKPRICE_NOT_AVAILABLE);

            for (int i = 0; i < 100; i++) {
                container.updateCurrentPriceTo(10000, spec0.symbolId, spec0.quoteCurrency);
            }
            container.submitCommandSync(cmd0, CommandResultCode.SUCCESS);
        }
    }

    // cmd若发送的symbol不为永续, 需要报INVALID_SYMBOL错误 -- delivery
    @Test
    public void testInvalidSymbol2() {
        try (final ExchangeTestContainer container = ExchangeTestContainer.create(PerformanceConfiguration.DEFAULT)) {
            int symbolId0 = 10000;
            CoreSymbolSpecification spec0 = CoreSymbolSpecification.builder()
                    .symbolId(symbolId0)
                    .type(SymbolType.FUTURES_CONTRACT_PERPETUAL)
                    .baseCurrency(11)
                    .quoteCurrency(12)
//                    .marginBuy(100)
//                    .marginSell(100)
                    .feeScaleK(100)
                    .makerFee(1)
                    .takerFee(2)
//                    .maxLeverage(50)
                    .maintenanceMargin(TreeSortedMap.newMapWith(1000L, 5L, 100000L, 10L))
                    .maxLeverage(TreeSortedMap.newMapWith(2000L, 5L, 100000L, 10L))
                    .build();
            container.addSymbol(spec0);

            int symbolId1 = 10001;
            CoreSymbolSpecification spec1 = CoreSymbolSpecification.builder()
                    .symbolId(symbolId1)
                    .type(SymbolType.FUTURES_CONTRACT_DELIVERY)
                    .baseCurrency(11)
                    .quoteCurrency(12)
//                    .marginBuy(100)
//                    .marginSell(100)
                    .feeScaleK(100)
                    .makerFee(1)
                    .takerFee(2)
//                    .maxLeverage(50)
                    .maintenanceMargin(TreeSortedMap.newMapWith(1000L, 5L, 100000L, 10L))
                    .maxLeverage(TreeSortedMap.newMapWith(2000L, 5L, 100000L, 10L))
                    .build();
            container.addSymbol(spec1);

            ApiSettlePNL cmd0 = ApiSettlePNL.builder()
                    .settlePrice(10000L)
                    .symbol(symbolId0)
                    .build();
            container.submitCommandSync(cmd0, CommandResultCode.INVALID_SYMBOL);

            ApiSettlePNL cmd1 = ApiSettlePNL.builder()
                    .settlePrice(10000L)
                    .symbol(symbolId1)
                    .build();
            container.submitCommandSync(cmd1, CommandResultCode.SUCCESS);
        }
    }

    // 没开出来单子交割后不需要结算 -- 交割
    @Test
    public void testDeliveryScenario0() throws Exception {
        long deposit = 20000L;
        try (final ExchangeTestContainer container = ExchangeTestContainer.create(PerformanceConfiguration.DEFAULT)) {
            container.getExchangeCore().liquidationScanner.stop(5, TimeUnit.MINUTES);
            List<CoreSymbolSpecification> deliverySymbols = container.initDeliverySymbols();

            // 0. 充钱
            List<Long> userIds = Collections.singletonList(UID_1);
            Set<Integer> symbolIds = deliverySymbols.stream().map(CoreSymbolSpecification::getQuoteCurrency).collect(Collectors.toSet());
            container.doDeposit(userIds, symbolIds, deposit);

            container.validateUserState(UID_1, profile -> {
                assertThat(profile.getAccounts().get(quoteId), is(deposit));
                assertThat(profile.getPositions().size(), is(0));
                assertTrue(container.totalBalanceReport().isGlobalBalancesAllZero());
            });

            container.updateCurrentPriceTo(1000, deliverySymbols.get(0).symbolId, deliverySymbols.get(0).quoteCurrency);

            // 下期货单但是没有成交, 所有没有开仓成功
            container.createBidWithOrderId(MAKER_1, UID_1, 10, 1000, deliverySymbols.get(0).symbolId, MarginMode.CROSS);
            container.validateUserState(UID_1, profile -> {
                assertThat(profile.getAccounts().get(quoteId), is(deposit));
                assertThat(profile.getPositions().size(), is(1));
                assertThat(profile.getPositions().get(deliverySymbols.get(0).symbolId).getDirection(), is(PositionDirection.EMPTY));
                assertThat(profile.getPositions().get(deliverySymbols.get(0).symbolId).pendingSellSize, is(0L));
                assertThat(profile.getPositions().get(deliverySymbols.get(0).symbolId).pendingBuySize, is(10L));
                assertThat(profile.getPositions().get(deliverySymbols.get(0).symbolId).marginMode, is(MarginMode.CROSS));
                assertThat(profile.getPositions().get(deliverySymbols.get(0).symbolId).pendingSellAvgPrice, is(0L));
                assertThat(profile.getPositions().get(deliverySymbols.get(0).symbolId).pendingBuyAvgPrice, is(1000L));
            });

            ApiSettlePNL cmd1 = ApiSettlePNL.builder()
                    .settlePrice(200L)
                    .symbol(deliverySymbols.get(0).symbolId)
                    .build();
            container.submitCommandSync(cmd1, CommandResultCode.SUCCESS);

            container.validateUserState(UID_1, profile -> {
                assertThat(profile.getAccounts().get(quoteId), is(deposit));
                assertThat(profile.getPositions().size(), is(1));
                assertThat(profile.getPositions().get(deliverySymbols.get(0).symbolId).getDirection(), is(PositionDirection.EMPTY));
                assertThat(profile.getPositions().get(deliverySymbols.get(0).symbolId).pendingSellSize, is(0L));
                assertThat(profile.getPositions().get(deliverySymbols.get(0).symbolId).pendingBuySize, is(10L));
                assertThat(profile.getPositions().get(deliverySymbols.get(0).symbolId).marginMode, is(MarginMode.CROSS));
                assertThat(profile.getPositions().get(deliverySymbols.get(0).symbolId).pendingSellAvgPrice, is(0L));
                assertThat(profile.getPositions().get(deliverySymbols.get(0).symbolId).pendingBuyAvgPrice, is(1000L));
            });
        }
    }

    // 开出来单子后需要做交割结算 -- 交割
    @Test
    public void testDeliveryScenario1() throws Exception {
        long deposit = 20000L;
        int makerFee = 100;
        int takerFee = 200;
        try (final ExchangeTestContainer container = ExchangeTestContainer.create(PerformanceConfiguration.DEFAULT)) {
            container.getExchangeCore().liquidationScanner.stop(5, TimeUnit.MINUTES);
            List<CoreSymbolSpecification> deliverySymbols = container.initDeliverySymbols();

            // 0. 充钱
            List<Long> userIds = Arrays.asList(UID_1, UID_2);
            Set<Integer> symbolIds = deliverySymbols.stream().map(CoreSymbolSpecification::getQuoteCurrency).collect(Collectors.toSet());
            container.doDeposit(userIds, symbolIds, deposit);

            container.validateUserState(UID_1, profile -> {
                assertThat(profile.getAccounts().get(quoteId), is(deposit));
                assertThat(profile.getPositions().size(), is(0));
            });
            assertTrue(container.totalBalanceReport().isGlobalBalancesAllZero());

            container.updateCurrentPriceTo(1000, deliverySymbols.get(0).symbolId, deliverySymbols.get(0).quoteCurrency);

            // 下期货单但是没有成交, 所有没有开仓成功
            container.createBidWithOrderId(MAKER_1, UID_1, 10, 1000, deliverySymbols.get(0).symbolId, MarginMode.CROSS);
            container.createAskWithOrderId(TAKER_1, UID_2, 10, 1000, deliverySymbols.get(0).symbolId, MarginMode.CROSS);
            container.validateUserState(UID_1, profile -> {
                assertThat(profile.getAccounts().get(quoteId), is(deposit - makerFee));
                assertThat(profile.getPositions().size(), is(1));
                assertThat(profile.getPositions().get(deliverySymbols.get(0).symbolId).getDirection(), is(PositionDirection.LONG));
                assertThat(profile.getPositions().get(deliverySymbols.get(0).symbolId).pendingSellSize, is(0L));
                assertThat(profile.getPositions().get(deliverySymbols.get(0).symbolId).pendingBuySize, is(0L));
                assertThat(profile.getPositions().get(deliverySymbols.get(0).symbolId).marginMode, is(MarginMode.CROSS));
                assertThat(profile.getPositions().get(deliverySymbols.get(0).symbolId).pendingSellAvgPrice, is(0L));
                assertThat(profile.getPositions().get(deliverySymbols.get(0).symbolId).pendingBuyAvgPrice, is(0L));
            });
            container.validateUserState(UID_2, profile -> {
                assertThat(profile.getAccounts().get(quoteId), is(deposit - takerFee));
                assertThat(profile.getPositions().size(), is(1));
                assertThat(profile.getPositions().get(deliverySymbols.get(0).symbolId).getDirection(), is(PositionDirection.SHORT));
                assertThat(profile.getPositions().get(deliverySymbols.get(0).symbolId).pendingSellSize, is(0L));
                assertThat(profile.getPositions().get(deliverySymbols.get(0).symbolId).pendingBuySize, is(0L));
                assertThat(profile.getPositions().get(deliverySymbols.get(0).symbolId).marginMode, is(MarginMode.CROSS));
                assertThat(profile.getPositions().get(deliverySymbols.get(0).symbolId).pendingSellAvgPrice, is(0L));
                assertThat(profile.getPositions().get(deliverySymbols.get(0).symbolId).pendingBuyAvgPrice, is(0L));
            });

            ApiSettlePNL cmd1 = ApiSettlePNL.builder()
                    .settlePrice(1500L)
                    .symbol(deliverySymbols.get(0).symbolId)
                    .build();
            container.submitCommandSync(cmd1, CommandResultCode.SUCCESS);

            container.validateUserState(UID_1, profile -> {
                assertThat(profile.getAccounts().get(quoteId), is(deposit - makerFee + 5000));
                assertThat(profile.getPositions().size(), is(0));
            });
            container.validateUserState(UID_2, profile -> {
                assertThat(profile.getAccounts().get(quoteId), is(deposit - takerFee - 5000));
                assertThat(profile.getPositions().size(), is(0));
            });
            assertTrue(container.totalBalanceReport().isGlobalBalancesAllZero());
        }
    }

    // 没开出来单子交割后不需要结算 -- 永续
    @Test
    public void testPerpetualScenario0() throws Exception {
        long deposit = 20000L;
        try (final ExchangeTestContainer container = ExchangeTestContainer.create(PerformanceConfiguration.DEFAULT)) {
            container.getExchangeCore().liquidationScanner.stop(5, TimeUnit.MINUTES);
            List<CoreSymbolSpecification> perpetualSymbols = container.initPerpetualSymbols();

            // 0. 充钱
            List<Long> userIds = Collections.singletonList(UID_1);
            Set<Integer> symbolIds = perpetualSymbols.stream().map(CoreSymbolSpecification::getQuoteCurrency).collect(Collectors.toSet());
            container.doDeposit(userIds, symbolIds, deposit);

            container.validateUserState(UID_1, profile -> {
                assertThat(profile.getAccounts().get(quoteId), is(deposit));
                assertThat(profile.getPositions().size(), is(0));
                assertTrue(container.totalBalanceReport().isGlobalBalancesAllZero());
            });

            // update market price
            container.updateCurrentPriceTo(10000, perpetualSymbols.get(0).symbolId, perpetualSymbols.get(0).quoteCurrency);

            // 下期货单但是没有成交, 所有没有开仓成功
            container.createBidWithOrderId(MAKER_1, UID_1, 10, 1000, perpetualSymbols.get(0).symbolId, MarginMode.CROSS);
            container.validateUserState(UID_1, profile -> {
                assertThat(profile.getAccounts().get(quoteId), is(deposit));
                assertThat(profile.getPositions().size(), is(1));
                assertThat(profile.getPositions().get(perpetualSymbols.get(0).symbolId).getDirection(), is(PositionDirection.EMPTY));
                assertThat(profile.getPositions().get(perpetualSymbols.get(0).symbolId).pendingSellSize, is(0L));
                assertThat(profile.getPositions().get(perpetualSymbols.get(0).symbolId).pendingBuySize, is(10L));
                assertThat(profile.getPositions().get(perpetualSymbols.get(0).symbolId).marginMode, is(MarginMode.CROSS));
                assertThat(profile.getPositions().get(perpetualSymbols.get(0).symbolId).pendingSellAvgPrice, is(0L));
                assertThat(profile.getPositions().get(perpetualSymbols.get(0).symbolId).pendingBuyAvgPrice, is(1000L));
            });

            ApiSettleFundingFees cmd = ApiSettleFundingFees.builder()
                    .transactionId(1345L)
                    .symbol(perpetualSymbols.get(0).symbolId)
                    .rateScaleK(100L)
                    .fundingRate(-100L)
                    .build();
            container.submitCommandSync(cmd, CommandResultCode.SUCCESS);

            container.validateUserState(UID_1, profile -> {
                assertThat(profile.getAccounts().get(quoteId), is(deposit));
                assertThat(profile.getPositions().size(), is(1));
                assertThat(profile.getPositions().get(perpetualSymbols.get(0).symbolId).getDirection(), is(PositionDirection.EMPTY));
                assertThat(profile.getPositions().get(perpetualSymbols.get(0).symbolId).pendingSellSize, is(0L));
                assertThat(profile.getPositions().get(perpetualSymbols.get(0).symbolId).pendingBuySize, is(10L));
                assertThat(profile.getPositions().get(perpetualSymbols.get(0).symbolId).marginMode, is(MarginMode.CROSS));
                assertThat(profile.getPositions().get(perpetualSymbols.get(0).symbolId).pendingSellAvgPrice, is(0L));
                assertThat(profile.getPositions().get(perpetualSymbols.get(0).symbolId).pendingBuyAvgPrice, is(1000L));
            });
        }
    }

    // 开出来单子后需要做结算 -- 永续, 正向
    @Test
    public void testPerpetualScenario1() throws Exception {
        long deposit = 20000L;
        int makerFee = 100;
        int takerFee = 200;
        int size = 10;
        int fundingRate = 100;
        int rateScale = 100;
        int updatedPrice = 1500;
        try (final ExchangeTestContainer container = ExchangeTestContainer.create(PerformanceConfiguration.DEFAULT)) {
            container.getExchangeCore().liquidationScanner.stop(5, TimeUnit.MINUTES);
            List<CoreSymbolSpecification> perpetualSymbols = container.initPerpetualSymbols();

            // 0. 充钱
            List<Long> userIds = Arrays.asList(UID_1, UID_2);
            Set<Integer> symbolIds = new HashSet<>();
            perpetualSymbols.forEach(spec -> {
                symbolIds.add(spec.quoteCurrency);
            });
            container.doDeposit(userIds, symbolIds, deposit);

            container.validateUserState(UID_1, profile -> {
                assertThat(profile.getAccounts().get(quoteId), is(deposit));
                assertThat(profile.getPositions().size(), is(0));
            });
            assertTrue(container.totalBalanceReport().isGlobalBalancesAllZero());

            container.submitCommandSync(ApiAdjustMarkPrice.builder().transactionId(1001).symbol(perpetualSymbols.get(0).symbolId).markPrice(updatedPrice).build(), CommandResultCode.SUCCESS);

            // 下期货单但是没有成交, 所有没有开仓成功
            container.createBidWithOrderId(MAKER_1, UID_1, size, 1000, perpetualSymbols.get(0).symbolId, MarginMode.CROSS);
            container.createAskWithOrderId(TAKER_1, UID_2, size, 1000, perpetualSymbols.get(0).symbolId, MarginMode.CROSS);

            // update market price
            container.updateCurrentPriceTo(updatedPrice, perpetualSymbols.get(0).symbolId, perpetualSymbols.get(0).quoteCurrency);

            container.validateUserState(UID_1, profile -> {
                assertThat(profile.getAccounts().get(quoteId), is(deposit - makerFee));
                assertThat(profile.getPositions().size(), is(1));
                assertThat(profile.getPositions().get(perpetualSymbols.get(0).symbolId).getDirection(), is(PositionDirection.LONG));
                assertThat(profile.getPositions().get(perpetualSymbols.get(0).symbolId).pendingSellSize, is(0L));
                assertThat(profile.getPositions().get(perpetualSymbols.get(0).symbolId).pendingBuySize, is(0L));
                assertThat(profile.getPositions().get(perpetualSymbols.get(0).symbolId).marginMode, is(MarginMode.CROSS));
                assertThat(profile.getPositions().get(perpetualSymbols.get(0).symbolId).pendingSellAvgPrice, is(0L));
                assertThat(profile.getPositions().get(perpetualSymbols.get(0).symbolId).pendingBuyAvgPrice, is(0L));
            });
            container.validateUserState(UID_2, profile -> {
                assertThat(profile.getAccounts().get(quoteId), is(deposit - takerFee));
                assertThat(profile.getPositions().size(), is(1));
                assertThat(profile.getPositions().get(perpetualSymbols.get(0).symbolId).getDirection(), is(PositionDirection.SHORT));
                assertThat(profile.getPositions().get(perpetualSymbols.get(0).symbolId).pendingSellSize, is(0L));
                assertThat(profile.getPositions().get(perpetualSymbols.get(0).symbolId).pendingBuySize, is(0L));
                assertThat(profile.getPositions().get(perpetualSymbols.get(0).symbolId).marginMode, is(MarginMode.CROSS));
                assertThat(profile.getPositions().get(perpetualSymbols.get(0).symbolId).pendingSellAvgPrice, is(0L));
                assertThat(profile.getPositions().get(perpetualSymbols.get(0).symbolId).pendingBuyAvgPrice, is(0L));
            });

            ApiSettleFundingFees cmd = ApiSettleFundingFees.builder()
                    .transactionId(1345L)
                    .symbol(perpetualSymbols.get(0).symbolId)
                    .rateScaleK(rateScale)
                    .fundingRate(fundingRate)
                    .build();
            container.submitCommandSync(cmd, CommandResultCode.SUCCESS);

            container.validateUserState(UID_1, profile -> {
                assertThat(profile.getAccounts().get(quoteId), is(deposit - makerFee));
                assertThat(profile.getPositions().size(), is(1));
                assertThat(profile.getPositions().getFirst().profit, is(-1L * size * fundingRate / rateScale * updatedPrice));
            });
            container.validateUserState(UID_2, profile -> {
                assertThat(profile.getAccounts().get(quoteId), is(deposit - takerFee));
                assertThat(profile.getPositions().size(), is(1));
                assertThat(profile.getPositions().getFirst().profit, is(1L * size * fundingRate / rateScale * updatedPrice));

            });
            assertTrue(container.totalBalanceReport().isGlobalBalancesAllZero());
        }
    }

}