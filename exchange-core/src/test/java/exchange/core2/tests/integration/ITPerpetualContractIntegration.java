//package exchange.core2.tests.integration;
//
//import exchange.core2.core.IFundEventsHandler;
//import exchange.core2.core.ITradeEventsHandler;
//import exchange.core2.core.common.*;
//import exchange.core2.core.common.api.*;
//import exchange.core2.core.common.cmd.CommandResultCode;
//import exchange.core2.core.common.config.PerformanceConfiguration;
//import exchange.core2.core.event.IEventsHandler4Test;
//import exchange.core2.core.event.SimpleEventsProcessor4Test;
//import exchange.core2.tests.util.ExchangeTestContainer;
//import lombok.extern.slf4j.Slf4j;
//import org.eclipse.collections.impl.map.sorted.mutable.TreeSortedMap;
//import org.junit.jupiter.api.AfterEach;
//import org.junit.jupiter.api.BeforeEach;
//import org.junit.jupiter.api.Test;
//import org.junit.jupiter.api.extension.ExtendWith;
//import org.mockito.ArgumentCaptor;
//import org.mockito.Captor;
//import org.mockito.Mock;
//import org.mockito.junit.jupiter.MockitoExtension;
//
//import java.util.Arrays;
//import java.util.Collections;
//import java.util.HashSet;
//import java.util.List;
//import java.util.Set;
//import java.util.concurrent.TimeUnit;
//import java.util.stream.Collectors;
//
//import static exchange.core2.tests.util.TestConstants.*;
//import static org.hamcrest.MatcherAssert.assertThat;
//import static org.hamcrest.core.Is.is;
//import static org.junit.jupiter.api.Assertions.assertTrue;
//import static org.mockito.Mockito.*;
//
//@Slf4j
//@ExtendWith(MockitoExtension.class)
//class ITPerpetualContractIntegration {
//
//    private int symbolId = 2;
//    private int quoteId = 840;
//
//    private SimpleEventsProcessor4Test processor;
//
//    @Mock
//    private IEventsHandler4Test handler;
//
//    @BeforeEach
//    public void before() {
//        processor = new SimpleEventsProcessor4Test(handler);
//    }
//
//    @AfterEach()
//    public void after() {
//    }
//
//    private PerformanceConfiguration getPerformanceConfiguration() {
//        return PerformanceConfiguration.baseBuilder().build();
//    }
//
//    @Test
//    public void testInvalidSymbol() {
//        try (final ExchangeTestContainer container = ExchangeTestContainer.create(getPerformanceConfiguration())) {
//            int symbolId0 = 10000;
//            CoreSymbolSpecification spec0 = CoreSymbolSpecification.builder()
//                    .symbolId(symbolId0)
//                    .type(SymbolType.FUTURES_CONTRACT_PERPETUAL)
//                    .baseCurrency(11)
//                    .quoteCurrency(12)
//                    .baseScaleK(1)
//                    .quoteScaleK(1)
//                    .initMargin(1)
//                    .initMarginScaleK(100)
//                    .feeScaleK(100)
//                    .makerFee(1)
//                    .takerFee(2)
//                    .maintenanceMargin(TreeSortedMap.newMapWith(1000L, 5L, 100000L, 10L))
//                    .maintenanceMarginScaleK(100)
//                    .maxLeverage(TreeSortedMap.newMapWith(2000L, 5L, 100000L, 10L))
//                    .build();
//            container.addSymbol(spec0);
//
//            int symbolId1 = 10001;
//            CoreSymbolSpecification spec1 = CoreSymbolSpecification.builder()
//                    .symbolId(symbolId1)
//                    .type(SymbolType.FUTURES_CONTRACT_DELIVERY)
//                    .baseCurrency(11)
//                    .quoteCurrency(12)
//                    .baseScaleK(1)
//                    .quoteScaleK(1)
//                    .initMargin(1)
//                    .initMarginScaleK(100)
//                    .feeScaleK(100)
//                    .makerFee(1)
//                    .takerFee(2)
//                    .maintenanceMargin(TreeSortedMap.newMapWith(1000L, 5L, 100000L, 10L))
//                    .maintenanceMarginScaleK(100)
//                    .maxLeverage(TreeSortedMap.newMapWith(2000L, 5L, 100000L, 10L))
//                    .build();
//            container.addSymbol(spec1);
//
//            container.addCurrency(spec0.baseCurrency, 0);
//            container.addCurrency(spec0.quoteCurrency, 0);
//            ApiSettleFundingFees cmd1 = ApiSettleFundingFees.builder()
//                    .transactionId(1004L)
//                    .symbol(symbolId1)
//                    .fundingRate(33)
//                    .rateScaleK(100)
//                    .build();
//            container.submitCommandSync(cmd1, CommandResultCode.INVALID_SYMBOL);
//
//            ApiSettleFundingFees cmd0 = ApiSettleFundingFees.builder()
//                    .transactionId(1003L)
//                    .symbol(symbolId0)
//                    .fundingRate(33)
//                    .rateScaleK(100)
//                    .build();
//            container.submitCommandSync(cmd0, CommandResultCode.RISK_MARKPRICE_NOT_AVAILABLE);
//
//            container.updateCurrentPriceTo(10000, spec0.symbolId, spec0.quoteCurrency);
//            container.submitCommandSync(cmd0, CommandResultCode.SUCCESS);
//        }
//    }
//
//    // cmd若发送的symbol不为永续, 需要报INVALID_SYMBOL错误 -- delivery
//    @Test
//    public void testInvalidSymbol2() {
//        try (final ExchangeTestContainer container = ExchangeTestContainer.create(PerformanceConfiguration.DEFAULT)) {
//            int symbolId0 = 10000;
//            CoreSymbolSpecification spec0 = CoreSymbolSpecification.builder()
//                    .symbolId(symbolId0)
//                    .type(SymbolType.FUTURES_CONTRACT_PERPETUAL)
//                    .baseCurrency(11)
//                    .quoteCurrency(12)
//                    .baseScaleK(1)
//                    .quoteScaleK(1)
//                    .initMargin(1)
//                    .initMarginScaleK(100)
//                    .feeScaleK(100)
//                    .makerFee(1)
//                    .takerFee(2)
//                    .maintenanceMargin(TreeSortedMap.newMapWith(1000L, 5L, 100000L, 10L))
//                    .maintenanceMarginScaleK(100)
//                    .maxLeverage(TreeSortedMap.newMapWith(2000L, 5L, 100000L, 10L))
//                    .build();
//            container.addSymbol(spec0);
//
//            int symbolId1 = 10001;
//            CoreSymbolSpecification spec1 = CoreSymbolSpecification.builder()
//                    .symbolId(symbolId1)
//                    .type(SymbolType.FUTURES_CONTRACT_DELIVERY)
//                    .baseCurrency(11)
//                    .quoteCurrency(12)
//                    .baseScaleK(1)
//                    .quoteScaleK(1)
//                    .initMargin(1)
//                    .initMarginScaleK(100)
//                    .feeScaleK(100)
//                    .makerFee(1)
//                    .takerFee(2)
//                    .maintenanceMargin(TreeSortedMap.newMapWith(1000L, 5L, 100000L, 10L))
//                    .maintenanceMarginScaleK(100)
//                    .maxLeverage(TreeSortedMap.newMapWith(2000L, 5L, 100000L, 10L))
//                    .build();
//            container.addSymbol(spec1);
//
//            container.addCurrency(spec0.baseCurrency, 0);
//            container.addCurrency(spec0.quoteCurrency, 0);
//
//            ApiSettlePNL cmd0 = ApiSettlePNL.builder()
//                    .settlePrice(10000L)
//                    .symbol(symbolId0)
//                    .build();
//            container.submitCommandSync(cmd0, CommandResultCode.INVALID_SYMBOL);
//
//            ApiSettlePNL cmd1 = ApiSettlePNL.builder()
//                    .settlePrice(10000L)
//                    .symbol(symbolId1)
//                    .build();
//            container.submitCommandSync(cmd1, CommandResultCode.SUCCESS);
//        }
//    }
//
//    // 没开出来单子交割后不需要结算 -- 交割
//    @Test
//    public void testDeliveryScenario0() throws Exception {
//        long deposit = 20000L;
//        try (final ExchangeTestContainer container = ExchangeTestContainer.create(PerformanceConfiguration.DEFAULT)) {
//            container.getExchangeCore().liquidationScanner.stop(5, TimeUnit.MINUTES);
//            List<CoreSymbolSpecification> deliverySymbols = container.initDeliverySymbols();
//
//            // 0. 充钱
//            List<Long> userIds = Collections.singletonList(UID_1);
//            Set<Integer> symbolIds = deliverySymbols.stream().map(CoreSymbolSpecification::getQuoteCurrency).collect(Collectors.toSet());
//            container.doDeposit(userIds, symbolIds, deposit);
//
//            container.validateUserState(UID_1, profile -> {
//                assertThat(profile.getAccounts().get(quoteId), is(deposit));
//                assertThat(profile.getPositions().size(), is(0));
//                assertTrue(container.totalBalanceReport().isGlobalBalancesAllZero());
//            });
//
//            container.updateCurrentPriceTo(1000, deliverySymbols.get(0).symbolId, deliverySymbols.get(0).quoteCurrency);
//
//            // 下期货单但是没有成交, 所有没有开仓成功
//            container.createBidWithOrderId(MAKER_1, UID_1, 10, 1000, deliverySymbols.get(0).symbolId, MarginMode.CROSS);
//            container.validateUserState(UID_1, profile -> {
//                assertThat(profile.getAccounts().get(quoteId), is(deposit));
//                assertThat(profile.getPositions().size(), is(1));
//                assertThat(profile.getPositions().get(deliverySymbols.get(0).symbolId).get(0).getDirection(), is(PositionDirection.LONG));
//                assertThat(profile.getPositions().get(deliverySymbols.get(0).symbolId).get(0).pendingSellSize, is(0L));
//                assertThat(profile.getPositions().get(deliverySymbols.get(0).symbolId).get(0).pendingBuySize, is(10L));
//                assertThat(profile.getPositions().get(deliverySymbols.get(0).symbolId).get(0).marginMode, is(MarginMode.CROSS));
//                assertThat(profile.getPositions().get(deliverySymbols.get(0).symbolId).get(0).pendingSellAvgPrice, is(0L));
//                assertThat(profile.getPositions().get(deliverySymbols.get(0).symbolId).get(0).pendingBuyAvgPrice, is(1000L));
//            });
//
//            ApiSettlePNL cmd1 = ApiSettlePNL.builder()
//                    .settlePrice(200L)
//                    .symbol(deliverySymbols.get(0).symbolId)
//                    .build();
//            container.submitCommandSync(cmd1, CommandResultCode.SUCCESS);
//
//            container.validateUserState(UID_1, profile -> {
//                assertThat(profile.getAccounts().get(quoteId), is(deposit));
//                assertThat(profile.getPositions().size(), is(1));
//                assertThat(profile.getPositions().get(deliverySymbols.get(0).symbolId).get(0).getDirection(), is(PositionDirection.LONG));
//                assertThat(profile.getPositions().get(deliverySymbols.get(0).symbolId).get(0).pendingSellSize, is(0L));
//                assertThat(profile.getPositions().get(deliverySymbols.get(0).symbolId).get(0).pendingBuySize, is(10L));
//                assertThat(profile.getPositions().get(deliverySymbols.get(0).symbolId).get(0).marginMode, is(MarginMode.CROSS));
//                assertThat(profile.getPositions().get(deliverySymbols.get(0).symbolId).get(0).pendingSellAvgPrice, is(0L));
//                assertThat(profile.getPositions().get(deliverySymbols.get(0).symbolId).get(0).pendingBuyAvgPrice, is(1000L));
//            });
//        }
//    }
//
//    // 开出来单子后需要做交割结算 -- 交割
//    @Test
//    public void testDeliveryScenario1() throws Exception {
//        long deposit = 20000L;
//        int makerFee = 100;
//        int takerFee = 200;
//        try (final ExchangeTestContainer container = ExchangeTestContainer.create(PerformanceConfiguration.DEFAULT)) {
//            container.getExchangeCore().liquidationScanner.stop(5, TimeUnit.MINUTES);
//            List<CoreSymbolSpecification> deliverySymbols = container.initDeliverySymbols();
//
//            // 0. 充钱
//            List<Long> userIds = Arrays.asList(UID_1, UID_2);
//            Set<Integer> symbolIds = deliverySymbols.stream().map(CoreSymbolSpecification::getQuoteCurrency).collect(Collectors.toSet());
//            container.doDeposit(userIds, symbolIds, deposit);
//
//            container.validateUserState(UID_1, profile -> {
//                assertThat(profile.getAccounts().get(quoteId), is(deposit));
//                assertThat(profile.getPositions().size(), is(0));
//            });
//            assertTrue(container.totalBalanceReport().isGlobalBalancesAllZero());
//
//            container.updateCurrentPriceTo(1000, deliverySymbols.get(0).symbolId, deliverySymbols.get(0).quoteCurrency);
//
//            // 下期货单但是没有成交, 所有没有开仓成功
//            container.createBidWithOrderId(MAKER_1, UID_1, 10, 1000, deliverySymbols.get(0).symbolId, MarginMode.CROSS);
//            container.createAskWithOrderId(TAKER_1, UID_2, 10, 1000, deliverySymbols.get(0).symbolId, MarginMode.CROSS);
//            container.validateUserState(UID_1, profile -> {
//                assertThat(profile.getAccounts().get(quoteId), is(deposit - makerFee));
//                assertThat(profile.getPositions().size(), is(1));
//                assertThat(profile.getPositions().get(deliverySymbols.get(0).symbolId).get(0).getDirection(), is(PositionDirection.LONG));
//                assertThat(profile.getPositions().get(deliverySymbols.get(0).symbolId).get(0).pendingSellSize, is(0L));
//                assertThat(profile.getPositions().get(deliverySymbols.get(0).symbolId).get(0).pendingBuySize, is(0L));
//                assertThat(profile.getPositions().get(deliverySymbols.get(0).symbolId).get(0).marginMode, is(MarginMode.CROSS));
//                assertThat(profile.getPositions().get(deliverySymbols.get(0).symbolId).get(0).pendingSellAvgPrice, is(0L));
//                assertThat(profile.getPositions().get(deliverySymbols.get(0).symbolId).get(0).pendingBuyAvgPrice, is(0L));
//            });
//            container.validateUserState(UID_2, profile -> {
//                assertThat(profile.getAccounts().get(quoteId), is(deposit - takerFee));
//                assertThat(profile.getPositions().size(), is(1));
//                assertThat(profile.getPositions().get(deliverySymbols.get(0).symbolId).get(0).getDirection(), is(PositionDirection.SHORT));
//                assertThat(profile.getPositions().get(deliverySymbols.get(0).symbolId).get(0).pendingSellSize, is(0L));
//                assertThat(profile.getPositions().get(deliverySymbols.get(0).symbolId).get(0).pendingBuySize, is(0L));
//                assertThat(profile.getPositions().get(deliverySymbols.get(0).symbolId).get(0).marginMode, is(MarginMode.CROSS));
//                assertThat(profile.getPositions().get(deliverySymbols.get(0).symbolId).get(0).pendingSellAvgPrice, is(0L));
//                assertThat(profile.getPositions().get(deliverySymbols.get(0).symbolId).get(0).pendingBuyAvgPrice, is(0L));
//            });
//
//            ApiSettlePNL cmd1 = ApiSettlePNL.builder()
//                    .settlePrice(1500L)
//                    .symbol(deliverySymbols.get(0).symbolId)
//                    .build();
//            container.submitCommandSync(cmd1, CommandResultCode.SUCCESS);
//
//            container.validateUserState(UID_1, profile -> {
//                assertThat(profile.getAccounts().get(quoteId), is(deposit - makerFee + 5000));
//                assertThat(profile.getPositions().size(), is(0));
//            });
//            container.validateUserState(UID_2, profile -> {
//                assertThat(profile.getAccounts().get(quoteId), is(deposit - takerFee - 5000));
//                assertThat(profile.getPositions().size(), is(0));
//            });
//            assertTrue(container.totalBalanceReport().isGlobalBalancesAllZero());
//        }
//    }
//
//    // 没开出来单子交割后不需要结算 -- 永续
//    @Test
//    public void testPerpetualScenario0() throws Exception {
//        long deposit = 20000L;
//        try (final ExchangeTestContainer container = ExchangeTestContainer.create(PerformanceConfiguration.DEFAULT)) {
//            container.getExchangeCore().liquidationScanner.stop(5, TimeUnit.MINUTES);
//            List<CoreSymbolSpecification> perpetualSymbols = container.initPerpetualSymbols();
//
//            // 0. 充钱
//            List<Long> userIds = Collections.singletonList(UID_1);
//            Set<Integer> symbolIds = perpetualSymbols.stream().map(CoreSymbolSpecification::getQuoteCurrency).collect(Collectors.toSet());
//            container.doDeposit(userIds, symbolIds, deposit);
//
//            container.validateUserState(UID_1, profile -> {
//                assertThat(profile.getAccounts().get(quoteId), is(deposit));
//                assertThat(profile.getPositions().size(), is(0));
//                assertTrue(container.totalBalanceReport().isGlobalBalancesAllZero());
//            });
//
//            // update market price
//            container.updateCurrentPriceTo(10000, perpetualSymbols.get(0).symbolId, perpetualSymbols.get(0).quoteCurrency);
//
//            // 下期货单但是没有成交, 所有没有开仓成功
//            container.createBidWithOrderId(MAKER_1, UID_1, 10, 1000, perpetualSymbols.get(0).symbolId, MarginMode.CROSS);
//            container.validateUserState(UID_1, profile -> {
//                assertThat(profile.getAccounts().get(quoteId), is(deposit));
//                assertThat(profile.getPositions().size(), is(1));
//                assertThat(profile.getPositions().get(perpetualSymbols.get(0).symbolId).get(0).getDirection(), is(PositionDirection.LONG));
//                assertThat(profile.getPositions().get(perpetualSymbols.get(0).symbolId).get(0).pendingSellSize, is(0L));
//                assertThat(profile.getPositions().get(perpetualSymbols.get(0).symbolId).get(0).pendingBuySize, is(10L));
//                assertThat(profile.getPositions().get(perpetualSymbols.get(0).symbolId).get(0).marginMode, is(MarginMode.CROSS));
//                assertThat(profile.getPositions().get(perpetualSymbols.get(0).symbolId).get(0).pendingSellAvgPrice, is(0L));
//                assertThat(profile.getPositions().get(perpetualSymbols.get(0).symbolId).get(0).pendingBuyAvgPrice, is(1000L));
//            });
//
//            ApiSettleFundingFees cmd = ApiSettleFundingFees.builder()
//                    .transactionId(1345L)
//                    .symbol(perpetualSymbols.get(0).symbolId)
//                    .rateScaleK(100L)
//                    .fundingRate(-100L)
//                    .build();
//            container.submitCommandSync(cmd, CommandResultCode.SUCCESS);
//
//            container.validateUserState(UID_1, profile -> {
//                assertThat(profile.getAccounts().get(quoteId), is(deposit));
//                assertThat(profile.getPositions().size(), is(1));
//                assertThat(profile.getPositions().get(perpetualSymbols.get(0).symbolId).get(0).getDirection(), is(PositionDirection.LONG));
//                assertThat(profile.getPositions().get(perpetualSymbols.get(0).symbolId).get(0).pendingSellSize, is(0L));
//                assertThat(profile.getPositions().get(perpetualSymbols.get(0).symbolId).get(0).pendingBuySize, is(10L));
//                assertThat(profile.getPositions().get(perpetualSymbols.get(0).symbolId).get(0).marginMode, is(MarginMode.CROSS));
//                assertThat(profile.getPositions().get(perpetualSymbols.get(0).symbolId).get(0).pendingSellAvgPrice, is(0L));
//                assertThat(profile.getPositions().get(perpetualSymbols.get(0).symbolId).get(0).pendingBuyAvgPrice, is(1000L));
//            });
//        }
//    }
//
//    /*  开出来单子后需要做结算 -- 永续, 正向
//        合约价格高于现货价格时设置资金费率 > 0(fundingRate/rateScale=1%), 此时做多用户按比例减钱给做空用户, 鼓励做空
//        1. 开仓成功, 1000@10
//        2. 发起SettleFundingFees cmd
//        3. 做多profit -1%
//        4. 做空profit +1%
//        5. 平仓1手, check initMargin
//        6. 平仓9手, check initMarg
//        7. UID_1 check balance - profit
//    */
//    @Test
//    public void testPerpetualScenario1() throws Exception {
//        long deposit = 20000L;
//        int makerFee = 100;
//        int takerFee = 200;
//        int size = 10;
//        int fundingRate = 1;
//        int rateScale = 100;
//        int updatedPrice = 1500;
//        try (final ExchangeTestContainer container = ExchangeTestContainer.create(getPerformanceConfiguration())) {
//            container.getExchangeCore().liquidationScanner.stop(5, TimeUnit.MINUTES);
//            List<CoreSymbolSpecification> perpetualSymbols = container.initPerpetualSymbols();
//
//            // 0. 充钱
//            List<Long> userIds = Arrays.asList(UID_1, UID_2, UID_3);
//            Set<Integer> symbolIds = new HashSet<>();
//            perpetualSymbols.forEach(spec -> {
//                symbolIds.add(spec.quoteCurrency);
//            });
//            container.doDeposit(userIds, symbolIds, deposit);
//
//            container.validateUserState(UID_1, profile -> {
//                assertThat(profile.getAccounts().get(quoteId), is(deposit));
//                assertThat(profile.getPositions().size(), is(0));
//            });
//            assertTrue(container.totalBalanceReport().isGlobalBalancesAllZero());
//            // 初始标记价格1000
//            container.submitCommandSync(ApiAdjustMarkPrice.builder().transactionId(1001).symbol(perpetualSymbols.get(0).symbolId).markPrice(updatedPrice).build(), CommandResultCode.SUCCESS);
//
//            // 开仓成功, 1000@10
//            container.createBidWithOrderId(MAKER_1, UID_1, size, 1000, perpetualSymbols.get(0).symbolId, MarginMode.CROSS);
//            container.createAskWithOrderId(TAKER_1, UID_2, size, 1000, perpetualSymbols.get(0).symbolId, MarginMode.CROSS);
//
//            // update market price to 1500
//            container.updateCurrentPriceTo(updatedPrice, perpetualSymbols.get(0).symbolId, perpetualSymbols.get(0).quoteCurrency);
//
//            // openInitMargin用标记价格计算, 1500(标记价格) * 10(size) / 100(scale) = 150
//            container.validateUserState(UID_1, profile -> {
//                assertThat(profile.getAccounts().get(quoteId), is(deposit - makerFee));
//                assertThat(profile.getPositions().size(), is(1));
//                assertThat(profile.getPositions().get(perpetualSymbols.get(0).symbolId).get(0).openInitMarginSum, is(150L));
//                assertThat(profile.getPositions().get(perpetualSymbols.get(0).symbolId).get(0).profit, is(0L));
//                assertThat(profile.getPositions().get(perpetualSymbols.get(0).symbolId).get(0).getDirection(), is(PositionDirection.LONG));
//                assertThat(profile.getPositions().get(perpetualSymbols.get(0).symbolId).get(0).pendingSellSize, is(0L));
//                assertThat(profile.getPositions().get(perpetualSymbols.get(0).symbolId).get(0).pendingBuySize, is(0L));
//                assertThat(profile.getPositions().get(perpetualSymbols.get(0).symbolId).get(0).pendingSellAvgPrice, is(0L));
//                assertThat(profile.getPositions().get(perpetualSymbols.get(0).symbolId).get(0).pendingBuyAvgPrice, is(0L));
//                assertThat(profile.getPositions().get(perpetualSymbols.get(0).symbolId).get(0).marginMode, is(MarginMode.CROSS));
//            });
//            container.validateUserState(UID_2, profile -> {
//                assertThat(profile.getAccounts().get(quoteId), is(deposit - takerFee));
//                assertThat(profile.getPositions().size(), is(1));
//                assertThat(profile.getPositions().get(perpetualSymbols.get(0).symbolId).get(0).openInitMarginSum, is(150L));
//                assertThat(profile.getPositions().get(perpetualSymbols.get(0).symbolId).get(0).profit, is(0L));
//                assertThat(profile.getPositions().get(perpetualSymbols.get(0).symbolId).get(0).getDirection(), is(PositionDirection.SHORT));
//                assertThat(profile.getPositions().get(perpetualSymbols.get(0).symbolId).get(0).pendingSellSize, is(0L));
//                assertThat(profile.getPositions().get(perpetualSymbols.get(0).symbolId).get(0).pendingBuySize, is(0L));
//                assertThat(profile.getPositions().get(perpetualSymbols.get(0).symbolId).get(0).pendingSellAvgPrice, is(0L));
//                assertThat(profile.getPositions().get(perpetualSymbols.get(0).symbolId).get(0).pendingBuyAvgPrice, is(0L));
//                assertThat(profile.getPositions().get(perpetualSymbols.get(0).symbolId).get(0).marginMode, is(MarginMode.CROSS));
//            });
//            // 永续cmd请求发起后, 合约价格高于现货价格时设置资金费率 > 0(fundingRate/rateScale=1%), 此时做多用户按比例减钱给做空用户, 鼓励做空
//            ApiSettleFundingFees cmd = ApiSettleFundingFees.builder()
//                    .transactionId(1345L)
//                    .symbol(perpetualSymbols.get(0).symbolId)
//                    .rateScaleK(rateScale)
//                    .fundingRate(fundingRate)
//                    .build();
//            container.submitCommandSync(cmd, CommandResultCode.SUCCESS);
//
//            container.validateUserState(UID_1, profile -> {
//                assertThat(profile.getAccounts().get(quoteId), is(deposit - makerFee));
//                assertThat(profile.getPositions().size(), is(1));
//                assertThat(profile.getPositions().get(perpetualSymbols.get(0).symbolId).get(0).openVolume, is(10L));
//                assertThat(profile.getPositions().get(perpetualSymbols.get(0).symbolId).get(0).openInitMarginSum, is(150L));
//                assertThat(profile.getPositions().get(perpetualSymbols.get(0).symbolId).get(0).openPriceSum, is(10000L));
//                assertThat(profile.getPositions().getFirst().get(0).profit < 0, is(true));
//                assertThat(profile.getPositions().getFirst().get(0).profit, is(-1L * size * fundingRate * updatedPrice / rateScale));
//            });
//            container.validateUserState(UID_2, profile -> {
//                assertThat(profile.getAccounts().get(quoteId), is(deposit - takerFee));
//                assertThat(profile.getPositions().size(), is(1));
//                assertThat(profile.getPositions().get(perpetualSymbols.get(0).symbolId).get(0).openVolume, is(10L));
//                assertThat(profile.getPositions().get(perpetualSymbols.get(0).symbolId).get(0).openInitMarginSum, is(150L));
//                assertThat(profile.getPositions().get(perpetualSymbols.get(0).symbolId).get(0).openPriceSum, is(10000L));
//                assertThat(profile.getPositions().getFirst().get(0).profit > 0, is(true));
//                assertThat(profile.getPositions().getFirst().get(0).profit, is(1L * size * fundingRate * updatedPrice / rateScale));
//            });
//            assertTrue(container.totalBalanceReport().isGlobalBalancesAllZero());
//
//            // 平仓一手
//            container.createAskWithOrderId(MAKER_2, UID_1, 1, updatedPrice, perpetualSymbols.get(0).symbolId, MarginMode.CROSS);
//            container.createBidWithOrderId(TAKER_2, UID_3, 1, updatedPrice, perpetualSymbols.get(0).symbolId, MarginMode.CROSS);
//            // init margin = 150 - 150/10 = 135
//            container.validateUserState(UID_1, profile -> {
//                assertThat(profile.getAccounts().get(quoteId), is(deposit - makerFee));
//                assertThat(profile.getPositions().getFirst().get(0).openVolume, is(size - 1L));
//                assertThat(profile.getPositions().getFirst().get(0).profit, is(-150L));
//                assertThat(profile.getPositions().getFirst().get(0).openPriceSum, is(1000L * size - updatedPrice));
//                assertThat(profile.getPositions().getFirst().get(0).openInitMarginSum, is(135L));
//            });
//
//            // 平仓剩余所有, balance = deposit - makerFee - fundingFee + profit
//            container.createAskWithOrderId(MAKER_3, UID_1, 9, updatedPrice, perpetualSymbols.get(0).symbolId, MarginMode.CROSS);
//            container.createBidWithOrderId(TAKER_3, UID_3, 9, updatedPrice, perpetualSymbols.get(0).symbolId, MarginMode.CROSS);
//            container.validateUserState(UID_1, profile -> {
//                assertThat(profile.getPositions().size(), is(0));
//                assertThat(profile.getAccounts().get(quoteId), is(deposit - makerFee + 5000 - 150));
//            });
//            assertTrue(container.totalBalanceReport().isGlobalBalancesAllZero());
//        } finally {
//            verify(handler, times(33)).fundsEvent(fundEventCapor.capture());
//            List<IFundEventsHandler.FundsEvent> fundEvents = fundEventCapor.getAllValues();
//
//            IFundEventsHandler.FundsEvent event1 = fundEvents.get(17);
//            assertThat(UID_1, is(event1.uid));
//            assertThat(quoteId, is(event1.currency));
//            assertThat(10000, is(event1.symbol));
//            // fee = funding fee
//            assertThat(-150L, is(event1.fee));
//            assertThat(-150L, is(event1.profit));
//            assertThat(PositionDirection.LONG, is(event1.direction));
//            assertThat(FundEvent.FundEventType.FUNDINGFEE_SETTLEMENT, is(event1.eventType));
//            assertThat(0L, is(event1.free));
//            assertThat(0L, is(event1.locked));
//            assertThat(10000L, is(event1.openPriceSum));
//            assertThat(10L, is(event1.openVolume));
//            assertThat(0L, is(event1.tradeSize));
//            assertThat(0L, is(event1.tradePrice));
//            // 标记价格从10000变为1500, openVolume * priceRecord.markPrice - openPriceSum = 10 * 1500 - 10000 = 5000
//            assertThat(5000L, is(event1.unrealizedProfit));
//            // maintenanceMargin = 75
//            // totalPnl = 4850
//            // totalMargin = balance + pnl = 24750
//            // liquidationPrice = direction * (maintenanceMargin - totalMargin) + openPriceSum
//            // numerator = openPriceSum - totalBalance - pnlOther + mmOther
//            // numerator < 0时liquidationPrice=0
//            assertThat(-1L, is(event1.liquidationPrice));
//            // marginRatioScaleK = maintenanceMarginScaleK * maintenanceMargin / totalMargin = long (1000 * 75 / 24750) = 3
//            assertThat(3L, is(event1.marginRatioScaleK));
//
//            IFundEventsHandler.FundsEvent event2 = fundEvents.get(18);
//            assertThat(UID_2, is(event2.uid));
//            assertThat(quoteId, is(event2.currency));
//            assertThat(10000, is(event2.symbol));
//            assertThat(150L, is(event2.fee));
//            assertThat(150L, is(event2.profit));
//            assertThat(PositionDirection.SHORT, is(event2.direction));
//            assertThat(FundEvent.FundEventType.FUNDINGFEE_SETTLEMENT, is(event2.eventType));
//            assertThat(0L, is(event2.free));
//            assertThat(0L, is(event2.locked));
//            assertThat(10000L, is(event2.openPriceSum));
//            assertThat(10L, is(event2.openVolume));
//            assertThat(0L, is(event2.tradeSize));
//            assertThat(0L, is(event2.tradePrice));
//            // 标记价格和开仓价格相同, 所以算出来的unrealizedProfit=10000-10*1500=-5000
//            assertThat(-5000L, is(event2.unrealizedProfit));
//            // numerator = openPriceSum - totalBalance - pnlOther + mmOther
//            // numerator < 0时liquidationPrice=0
//            assertThat(2980L, is(event2.liquidationPrice));
//            // totalMargin = balance + totalPnl = 19800 - 4850 = 14950
//            // marginRatioScaleK = maintenanceMarginScaleK * maintenanceMargin / totalMargin = long (1000 * 75 / 14950) = 5
//            assertThat(5L, is(event2.marginRatioScaleK));
//        }
//    }
//
//    /*  开出来单子后需要做结算 -- 永续, 反向
//        合约价格高于现货价格时设置资金费率 < 0(fundingRate/rateScale=-1%), 此时做空用户按比例减钱给做多用户, 鼓励做多
//        1. 开仓成功, 1000@10
//        2. 发起SettleFundingFees cmd
//        3. 做多profit +1%
//        4. 做空profit -1%
//        5. 平仓1手, check initMargin
//        6. 平仓9手, check initMarg
//        7. UID_1 check balance + profit
//    */
//    @Test
//    public void testPerpetualScenario2() throws Exception {
//        long deposit = 20000L;
//        int makerFee = 100;
//        int takerFee = 200;
//        int size = 10;
//        int fundingRate = -1;
//        int rateScale = 100;
//        int updatedPrice = 1500;
//        try (final ExchangeTestContainer container = ExchangeTestContainer.create(PerformanceConfiguration.DEFAULT)) {
//            container.getExchangeCore().liquidationScanner.stop(5, TimeUnit.MINUTES);
//            List<CoreSymbolSpecification> perpetualSymbols = container.initPerpetualSymbols();
//
//            // 0. 充钱
//            List<Long> userIds = Arrays.asList(UID_1, UID_2, UID_3);
//            Set<Integer> symbolIds = new HashSet<>();
//            perpetualSymbols.forEach(spec -> {
//                symbolIds.add(spec.quoteCurrency);
//            });
//            container.doDeposit(userIds, symbolIds, deposit);
//
//            container.validateUserState(UID_1, profile -> {
//                assertThat(profile.getAccounts().get(quoteId), is(deposit));
//                assertThat(profile.getPositions().size(), is(0));
//            });
//            assertTrue(container.totalBalanceReport().isGlobalBalancesAllZero());
//
//            container.submitCommandSync(ApiAdjustMarkPrice.builder().transactionId(1001).symbol(perpetualSymbols.get(0).symbolId).markPrice(updatedPrice).build(), CommandResultCode.SUCCESS);
//
//            container.createBidWithOrderId(MAKER_1, UID_1, size, 1000, perpetualSymbols.get(0).symbolId, MarginMode.CROSS);
//            container.createAskWithOrderId(TAKER_1, UID_2, size, 1000, perpetualSymbols.get(0).symbolId, MarginMode.CROSS);
//
//            // update market price
//            container.updateCurrentPriceTo(updatedPrice, perpetualSymbols.get(0).symbolId, perpetualSymbols.get(0).quoteCurrency);
//
//            container.validateUserState(UID_1, profile -> {
//                assertThat(profile.getAccounts().get(quoteId), is(deposit - makerFee));
//                assertThat(profile.getPositions().size(), is(1));
//                assertThat(profile.getPositions().get(perpetualSymbols.get(0).symbolId).get(0).getDirection(), is(PositionDirection.LONG));
//                assertThat(profile.getPositions().get(perpetualSymbols.get(0).symbolId).get(0).pendingSellSize, is(0L));
//                assertThat(profile.getPositions().get(perpetualSymbols.get(0).symbolId).get(0).pendingBuySize, is(0L));
//                assertThat(profile.getPositions().get(perpetualSymbols.get(0).symbolId).get(0).marginMode, is(MarginMode.CROSS));
//                assertThat(profile.getPositions().get(perpetualSymbols.get(0).symbolId).get(0).pendingSellAvgPrice, is(0L));
//                assertThat(profile.getPositions().get(perpetualSymbols.get(0).symbolId).get(0).pendingBuyAvgPrice, is(0L));
//            });
//            container.validateUserState(UID_2, profile -> {
//                assertThat(profile.getAccounts().get(quoteId), is(deposit - takerFee));
//                assertThat(profile.getPositions().size(), is(1));
//                assertThat(profile.getPositions().get(perpetualSymbols.get(0).symbolId).get(0).getDirection(), is(PositionDirection.SHORT));
//                assertThat(profile.getPositions().get(perpetualSymbols.get(0).symbolId).get(0).pendingSellSize, is(0L));
//                assertThat(profile.getPositions().get(perpetualSymbols.get(0).symbolId).get(0).pendingBuySize, is(0L));
//                assertThat(profile.getPositions().get(perpetualSymbols.get(0).symbolId).get(0).marginMode, is(MarginMode.CROSS));
//                assertThat(profile.getPositions().get(perpetualSymbols.get(0).symbolId).get(0).pendingSellAvgPrice, is(0L));
//                assertThat(profile.getPositions().get(perpetualSymbols.get(0).symbolId).get(0).pendingBuyAvgPrice, is(0L));
//            });
//
//            // 永续cmd请求发起后, 合约价格低于现货价格时设置资金费率 < 0(fundingRate/rateScale=-1%), 此时做空用户按比例减钱给做多用户, 鼓励做多
//            ApiSettleFundingFees cmd = ApiSettleFundingFees.builder()
//                    .transactionId(1345L)
//                    .symbol(perpetualSymbols.get(0).symbolId)
//                    .rateScaleK(rateScale)
//                    .fundingRate(fundingRate)
//                    .build();
//            container.submitCommandSync(cmd, CommandResultCode.SUCCESS);
//
//            container.validateUserState(UID_1, profile -> {
//                assertThat(profile.getAccounts().get(quoteId), is(deposit - makerFee));
//                assertThat(profile.getPositions().size(), is(1));
//                assertThat(profile.getPositions().getFirst().get(0).profit > 0, is(true));
//                assertThat(profile.getPositions().getFirst().get(0).profit, is(-1L * size * fundingRate * updatedPrice / rateScale));
//            });
//            container.validateUserState(UID_2, profile -> {
//                assertThat(profile.getAccounts().get(quoteId), is(deposit - takerFee));
//                assertThat(profile.getPositions().size(), is(1));
//                assertThat(profile.getPositions().getFirst().get(0).profit < 0, is(true));
//                assertThat(profile.getPositions().getFirst().get(0).profit, is(1L * size * fundingRate * updatedPrice / rateScale));
//
//            });
//            assertTrue(container.totalBalanceReport().isGlobalBalancesAllZero());
//
//            // 平仓一手
//            container.createAskWithOrderId(MAKER_2, UID_1, 1, updatedPrice, perpetualSymbols.get(0).symbolId, MarginMode.CROSS);
//            container.createBidWithOrderId(TAKER_2, UID_3, 1, updatedPrice, perpetualSymbols.get(0).symbolId, MarginMode.CROSS);
//            // init margin = 150 - 150/10 = 135
//            container.validateUserState(UID_1, profile -> {
//                assertThat(profile.getAccounts().get(quoteId), is(deposit - makerFee));
//                assertThat(profile.getPositions().getFirst().get(0).openVolume, is(size - 1L));
//                assertThat(profile.getPositions().getFirst().get(0).profit, is(150L));
//                assertThat(profile.getPositions().getFirst().get(0).openPriceSum, is(1000L * size - updatedPrice));
//                assertThat(profile.getPositions().getFirst().get(0).openInitMarginSum, is(135L));
//            });
//
//            // 平仓剩余所有, balance = deposit - makerFee - fundingFee + profit
//            container.createAskWithOrderId(MAKER_3, UID_1, 9, updatedPrice, perpetualSymbols.get(0).symbolId, MarginMode.CROSS);
//            container.createBidWithOrderId(TAKER_3, UID_3, 9, updatedPrice, perpetualSymbols.get(0).symbolId, MarginMode.CROSS);
//            container.validateUserState(UID_1, profile -> {
//                assertThat(profile.getPositions().size(), is(0));
//                assertThat(profile.getAccounts().get(quoteId), is(deposit - makerFee + 5000 + 150));
//            });
//            assertTrue(container.totalBalanceReport().isGlobalBalancesAllZero());
//        }
//    }
//
//    /* 测试某订单多次发起SettleFundingFees是否正常
//        1. 开仓1000@10
//        2. 发起SettleFundingFees
//        3. check profit
//        4. 重复2/3
//        5. 触发liquidation
//     */
//    @Test
//    public void testPerpetualScenario3() throws Exception {
//        long deposit = 5000L;
//        int makerFee = 100;
//        int takerFee = 200;
//        int size = 10;
//        int fundingRate = 1;
//        int rateScale = 100;
//        int updatedPrice = 1100;
//        int price = 1000;
//        try (final ExchangeTestContainer container = ExchangeTestContainer.create(PerformanceConfiguration.DEFAULT)) {
//            container.getExchangeCore().liquidationScanner.stop(5, TimeUnit.MINUTES);
//            CoreSymbolSpecification spec = CoreSymbolSpecification.builder()
//                    .symbolId(10000)
//                    .type(SymbolType.FUTURES_CONTRACT_PERPETUAL)
//                    .baseCurrency(CURRENECY_XBT)
//                    .quoteCurrency(CURRENECY_USD)
//                    .baseScaleK(1)
//                    .quoteScaleK(1)
//                    .makerFee(10)
//                    .takerFee(20)
//                    .maintenanceMargin(TreeSortedMap.newMapWith(1000L, 5L, 100000L, 10L))
//                    .maintenanceMarginScaleK(10)
//                    .maxLeverage(TreeSortedMap.newMapWith(2000L, 5L, 100000L, 10L))
//                    .initMargin(1)
//                    .initMarginScaleK(100)
//                    .build();
//            container.addSymbol(spec);
//            container.addCurrency(spec.baseCurrency, 0);
//            container.addCurrency(spec.quoteCurrency, 0);
//            // 0. 充钱
//            List<Long> userIds = Arrays.asList(UID_1, UID_2, UID_3);
//            userIds.forEach(uid -> container.createUserWithMoney(uid, quoteId, deposit));
//
//            container.validateUserState(UID_1, profile -> {
//                assertThat(profile.getAccounts().get(quoteId), is(deposit));
//                assertThat(profile.getPositions().size(), is(0));
//            });
//            assertTrue(container.totalBalanceReport().isGlobalBalancesAllZero());
//
//            container.submitCommandSync(ApiAdjustMarkPrice.builder().transactionId(1001).symbol(spec.symbolId).markPrice(updatedPrice).build(), CommandResultCode.SUCCESS);
//
//            container.createBidWithOrderId(MAKER_1, UID_1, size, price, spec.symbolId, MarginMode.CROSS);
//            container.createAskWithOrderId(TAKER_1, UID_2, size, price, spec.symbolId, MarginMode.CROSS);
//
//            // update market price
//            container.updateCurrentPriceTo(updatedPrice, spec.symbolId, spec.quoteCurrency);
//
//            container.validateUserState(UID_1, profile -> {
//                assertThat(profile.getAccounts().get(quoteId), is(deposit - makerFee));
//                assertThat(profile.getPositions().size(), is(1));
//                assertThat(profile.getPositions().get(spec.symbolId).get(0).getDirection(), is(PositionDirection.LONG));
//                assertThat(profile.getPositions().get(spec.symbolId).get(0).profit, is(0L));
//            });
//
//            // 永续cmd请求发起后, 合约价格高于现货价格时设置资金费率 > 0(fundingRate/rateScale=1%), 此时做多用户按比例减钱给做空用户, 鼓励做空
//            ApiSettleFundingFees cmd = ApiSettleFundingFees.builder()
//                    .transactionId(1345L)
//                    .symbol(spec.symbolId)
//                    .rateScaleK(rateScale)
//                    .fundingRate(fundingRate)
//                    .build();
//            container.submitCommandSync(cmd, CommandResultCode.SUCCESS);
//            // 第一次触发强平uid_1 position不会被强平
//            container.getExchangeCore().getLiquidationScanner().triggerOnce();
//
//            container.validateUserState(UID_1, profile -> {
//                assertThat(profile.getAccounts().get(quoteId), is(deposit - makerFee));
//                assertThat(profile.getPositions().size(), is(1));
//                assertThat(profile.getPositions().getFirst().get(0).profit, is(-1L * size * fundingRate * updatedPrice / rateScale));
//            });
//
//            // 再次发起settle funding fee cmd, unitFee为100%, 即每手按照标记价格给对方
//            ApiSettleFundingFees cmd2 = ApiSettleFundingFees.builder()
//                    .transactionId(1346L)
//                    .symbol(spec.symbolId)
//                    .rateScaleK(1)
//                    .fundingRate(1)
//                    .build();
//            container.submitCommandSync(cmd2, CommandResultCode.SUCCESS);
//
//            // openMarginInitSum = 标记价格 * size / rate = 1100 * 10 / 100 = 110
//            // profit分两次settle funding fee
//            // 第一次为 标记价格 * size * fundingRate / rateScale = 1100 * 10 * 1 / 100 = 110
//            // 第二次为 标记价格 * size * fundingRate / rateScale = 1100 * 10 / 1 = 11000
//            container.validateUserState(UID_1, profile -> {
//                assertThat(profile.getAccounts().get(quoteId), is(deposit - makerFee));
//                assertThat(profile.getPositions().size(), is(1));
//                assertThat(profile.getPositions().getFirst().get(0).openVolume, is(10L));
//                assertThat(profile.getPositions().getFirst().get(0).openInitMarginSum, is(110L));
//                assertThat(profile.getPositions().getFirst().get(0).openPriceSum, is(size * price * 1L));
//                assertThat(profile.getPositions().getFirst().get(0).profit, is(-1L * size * fundingRate * updatedPrice / rateScale - size * updatedPrice));
//            });
//            assertTrue(container.totalBalanceReport().isGlobalBalancesAllZero());
//
//            // 此时profit = 11000 - 10000 = 1000
//            // maintenance = notional * marginValue / maintenanceMarginScaleK = 11000 * 5 / 10 = 5500
//            // equity = balance + profit = 4900 + 1000 = 5900 > maintenance
//            // 此时不会触发强平
//            container.getExchangeCore().getLiquidationScanner().triggerOnce();
//
//            // openPriceSum = 10 * 1000 - 6 * 1100 = 3400
//            // openInitMarginSum -= openInitMarginSum * tradeSize / openVolume = 110
//            container.validateUserState(UID_1, profile -> {
//                assertThat(profile.getAccounts().get(quoteId), is(deposit - makerFee));
//                assertThat(profile.getPositions().size(), is(1));
//                assertThat(profile.getPositions().getFirst().get(0).openInitMarginSum, is(110L));
//                assertThat(profile.getPositions().getFirst().get(0).openPriceSum, is(10000L));
//                assertThat(profile.getPositions().size(), is(1));
//                assertThat(profile.getPositions().getFirst().get(0).openVolume, is(10L));
//                assertThat(profile.getPositions().getFirst().get(0).profit, is(-11110L));
//            });
//
//            container.updateCurrentPriceTo(600, spec.symbolId, spec.quoteCurrency);
//
//            // UID_3先下一单准备吃掉UID_1强平单, 开10手预计会被吃掉4手
//            container.createBidWithOrderId(MAKER_3, UID_3, size, 600, spec.symbolId, MarginMode.CROSS);
//            container.getUserProfile(UID_1);
//            // 此时profit = 6000 - 10000 = -4000
//            // maintenance = notional * marginValue / maintenanceMarginScaleK = 6000 * 5 / 10 = 3000
//            // equity = balance + profit = 4900 - 4000 = 900 < maintenance(3000)
//            // 此时会触发强平, 需要强平4手 900 + 4 * 600 = 3300 > maintenance(3000)即可
//            container.getExchangeCore().getLiquidationScanner().triggerOnce();
//
//            //  openInitMarginSum -= openInitMarginSum * tradeSize / openVolume = 110 - 110 * 4/10 = 66L
//            // openPriceSum = 10 * 1000 - 4 * 600 = 7600L
//            container.validateUserState(UID_1, profile -> {
//                assertThat(profile.getAccounts().get(quoteId), is(deposit - makerFee));
//                assertThat(profile.getPositions().size(), is(1));
//                assertThat(profile.getPositions().getFirst().get(0).openInitMarginSum, is(110L));
//                assertThat(profile.getPositions().getFirst().get(0).openPriceSum, is(10000L));
//                assertThat(profile.getPositions().size(), is(1));
//                assertThat(profile.getPositions().getFirst().get(0).openVolume, is(10L));
//                assertThat(profile.getPositions().getFirst().get(0).profit, is(-11110L));
//            });
//
//            // 再次触发强平, 此时期待当前持仓不再被强平
//            container.getExchangeCore().getLiquidationScanner().triggerOnce();
//            container.validateUserState(UID_1, profile -> {
//                assertThat(profile.getAccounts().get(quoteId), is(deposit - makerFee));
//                assertThat(profile.getPositions().size(), is(1));
//                assertThat(profile.getPositions().getFirst().get(0).openInitMarginSum, is(110L));
//                assertThat(profile.getPositions().getFirst().get(0).openPriceSum, is(10000L));
//                assertThat(profile.getPositions().size(), is(1));
//                assertThat(profile.getPositions().getFirst().get(0).openVolume, is(10L));
//                assertThat(profile.getPositions().getFirst().get(0).profit, is(-11110L));
//            });
//        }
//    }
//
//}