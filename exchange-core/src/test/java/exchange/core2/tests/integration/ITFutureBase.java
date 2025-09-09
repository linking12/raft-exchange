package exchange.core2.tests.integration;

import exchange.core2.core.IFundEventsHandler;
import exchange.core2.core.ITradeEventsHandler;
import exchange.core2.core.common.*;
import exchange.core2.core.common.api.ApiPlaceOrder;
import exchange.core2.core.common.config.PerformanceConfiguration;
import exchange.core2.core.event.IEventsHandler4Test;
import exchange.core2.core.event.SimpleEventsProcessor4Test;
import exchange.core2.tests.util.ExchangeTestContainer;
import org.eclipse.collections.impl.map.sorted.mutable.TreeSortedMap;
import org.hamcrest.core.Is;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;

import java.util.List;

import static exchange.core2.core.common.OrderType.*;
import static exchange.core2.core.common.OrderType.IOC;
import static exchange.core2.tests.util.TestConstants.*;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

public abstract class ITFutureBase {
    int symbolId = 2;
    int quoteId = 840;

    SimpleEventsProcessor4Test processor;

    @Mock
    IEventsHandler4Test handler;

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

    public PerformanceConfiguration getPerformanceConfiguration() {
        return PerformanceConfiguration.baseBuilder().build();
    }

    public void checkEvent(IFundEventsHandler.FundEventReport evt) {
        assertThat(0L, Is.is(evt.getPositions().getUnrealizedProfit()));
        assertThat(0L, Is.is(evt.getPositions().getLiquidationPrice()));
        assertThat(0L, Is.is(evt.getPositions().getMarginRatioScaleK()));
    }

    public void checkEventPending(IFundEventsHandler.FundEventReport evt) {
        assertThat(0L, Is.is(evt.getPositions().getBidsNotional()));
        assertThat(0L, Is.is(evt.getPositions().getAsksNotional()));
        assertThat(0L, Is.is(evt.getPositions().getBidsQty()));
        assertThat(0L, Is.is(evt.getPositions().getAsksQty()));
    }
    public void doCheckEvtCnt(final CoreSymbolSpecification symbolSpec, final OrderType orderType, final ITFutureCross.RejectionCause rejectionCause) {
        // 如果是budget订单，且不成交的话只有下单
        if (orderType == FOK_BUDGET && rejectionCause != ITFutureCross.RejectionCause.NO_REJECTION) {
            if (symbolSpec.type.equals(SymbolType.CURRENCY_EXCHANGE_PAIR)) {
                verify(handler, times(6)).spotExecutionReport(spotEventCaptor.capture());
                verify(handler, never()).futuresExecutionReport(any());
            } else {
                verify(handler, never()).spotExecutionReport(any());
                verify(handler, times(6)).futuresExecutionReport(futuresEventCaptor.capture());
            }
        } else {
            if (rejectionCause == ITFutureCross.RejectionCause.REJECTION_BY_SIZE) {
                int cnt = orderType.equals(GTC) ? 13 : 14;
                if (symbolSpec.type.equals(SymbolType.CURRENCY_EXCHANGE_PAIR)) {
                    verify(handler, times(cnt)).spotExecutionReport(spotEventCaptor.capture());
                    verify(handler, never()).futuresExecutionReport(any());
                    List<ITradeEventsHandler.SpotExecutionReport> allValues = spotEventCaptor.getAllValues();
                    System.out.println(allValues);
                } else {
                    verify(handler, never()).spotExecutionReport(any());
                    verify(handler, times(cnt)).futuresExecutionReport(futuresEventCaptor.capture());
                }
            } else {
                if (symbolSpec.type.equals(SymbolType.CURRENCY_EXCHANGE_PAIR)) {
                    verify(handler, times(13)).spotExecutionReport(spotEventCaptor.capture());
                    verify(handler, never()).futuresExecutionReport(any());
                } else {
                    verify(handler, never()).spotExecutionReport(any());
                    verify(handler, times(13)).futuresExecutionReport(futuresEventCaptor.capture());
                }
            }
        }
    }

    public ApiPlaceOrder.ApiPlaceOrderBuilder builderPlace(int symbolId, long uid, OrderAction action, OrderType type) {
        return ApiPlaceOrder.builder().uid(uid).action(action).orderType(type).symbol(symbolId).marginMode(MarginMode.ISOLATED);
    }

    public ApiPlaceOrder.ApiPlaceOrderBuilder builderPlace(int symbolId, long uid, OrderAction action, OrderType type, MarginMode mode) {
        return ApiPlaceOrder.builder().uid(uid).action(action).orderType(type).symbol(symbolId).marginMode(mode);
    }

    public void doInit(ExchangeTestContainer container) {
        container.addCurrency(SYMBOLSPECFEE_USD_JPY.baseCurrency, 0);
        container.addCurrency(SYMBOLSPECFEE_USD_JPY.quoteCurrency, 0);
        container.addSymbol(CoreSymbolSpecification.builder()
                .symbolId(SYMBOL_MARGIN)
                .type(SymbolType.FUTURES_CONTRACT_PERPETUAL)
                .baseCurrency(CURRENECY_USD)
                .quoteCurrency(CURRENECY_JPY)
                .baseScaleK(1)
                .quoteScaleK(1)
                .initMargin(1)
                .initMarginScaleK(21)
                .maintenanceMargin(TreeSortedMap.newMapWith(1000L, 5L, 100000L, 10L))
                .maxLeverage(TreeSortedMap.newMapWith(2000L, 5L, 100000L, 10L))
                .takerFee(3)
                .makerFee(2)
                .build());

        container.addCurrency(SYMBOLSPECFEE_XBT_LTC.baseCurrency, 0);
        container.addCurrency(SYMBOLSPECFEE_XBT_LTC.quoteCurrency, 0);
        container.addSymbol(CoreSymbolSpecification.builder()
                .symbolId(SYMBOL_EXCHANGE_FEE)
                .type(SymbolType.CURRENCY_EXCHANGE_PAIR)
                .baseCurrency(CURRENECY_XBT)
                .quoteCurrency(CURRENECY_LTC)
                .baseScaleK(1)
                .quoteScaleK(1)
                .takerFee(1900)
                .makerFee(700)
                .build());
        container.initFeeSymbolsMarkPrice();
        container.initFeeUsers();
    }

    enum RejectionCause {
        NO_REJECTION,
        REJECTION_BY_SIZE,
        REJECTION_BY_BUDGET
    }

    public abstract void testMultiBuy(final CoreSymbolSpecification symbolSpec, final OrderType orderType, final RejectionCause rejectionCause);

    public abstract void testMultiSell(final CoreSymbolSpecification symbolSpec, final OrderType orderType, final RejectionCause rejectionCause);

    // -------------------------- buy no rejection tests -----------------------------
    @Test
    @Timeout(5)
    public void testMultiBuyNoRejectionMarginGtc() {
        testMultiBuy(SYMBOLSPECFEE_USD_JPY, GTC, RejectionCause.NO_REJECTION);
    }

    @Test
    @Timeout(5)
    public void testMultiBuyNoRejectionExchangeGtc() {
        testMultiBuy(SYMBOLSPECFEE_XBT_LTC, GTC, RejectionCause.NO_REJECTION);
    }

    @Test
    @Timeout(5)
    public void testMultiBuyNoRejectionExchangeIoc() {
        testMultiBuy(SYMBOLSPECFEE_XBT_LTC, IOC, RejectionCause.NO_REJECTION);
    }

    @Test
    @Timeout(5)
    public void testMultiBuyNoRejectionMarginIoc() {
        testMultiBuy(SYMBOLSPECFEE_USD_JPY, IOC, RejectionCause.NO_REJECTION);
    }

    @Test
    @Timeout(5)
    public void testMultiBuyNoRejectionExchangeFokB() {
        testMultiBuy(SYMBOLSPECFEE_XBT_LTC, FOK_BUDGET, RejectionCause.NO_REJECTION);
    }

    @Test
    @Timeout(5)
    public void testMultiBuyNoRejectionMarginFokB() {
        testMultiBuy(SYMBOLSPECFEE_USD_JPY, FOK_BUDGET, RejectionCause.NO_REJECTION);
    }

    // -------------------------- buy with rejection tests -----------------------------
    @Test
    @Timeout(5)
    public void testMultiBuyWithRejectionMarginGtc() {
        testMultiBuy(SYMBOLSPECFEE_USD_JPY, GTC, RejectionCause.REJECTION_BY_SIZE);
    }

    @Test
    @Timeout(5)
    public void testMultiBuyWithRejectionExchangeGtc() {
        testMultiBuy(SYMBOLSPECFEE_XBT_LTC, GTC, RejectionCause.REJECTION_BY_SIZE);
    }

    @Test
    @Timeout(5)
    public void testMultiBuyWithRejectionExchangeIoc() {
        testMultiBuy(SYMBOLSPECFEE_XBT_LTC, IOC, RejectionCause.REJECTION_BY_SIZE);
    }

    @Test
    @Timeout(5)
    public void testMultiBuyWithRejectionMarginIoc() {
        testMultiBuy(SYMBOLSPECFEE_USD_JPY, IOC, RejectionCause.REJECTION_BY_SIZE);
    }

    @Test
    @Timeout(5)
    public void testMultiBuyWithSizeRejectionExchangeFokB() {
        testMultiBuy(SYMBOLSPECFEE_XBT_LTC, FOK_BUDGET, RejectionCause.REJECTION_BY_SIZE);
    }

    @Test
    @Timeout(5)
    public void testMultiBuyWithSizeRejectionMarginFokB() {
        testMultiBuy(SYMBOLSPECFEE_USD_JPY, FOK_BUDGET, RejectionCause.REJECTION_BY_SIZE);
    }

    @Test
    @Timeout(5)
    public void testMultiBuyWithBudgetRejectionExchangeFokB() {
        testMultiBuy(SYMBOLSPECFEE_XBT_LTC, FOK_BUDGET, RejectionCause.REJECTION_BY_BUDGET);
    }

    @Test
    @Timeout(5)
    public void testMultiBuyWithBudgetRejectionMarginFokB() {
        testMultiBuy(SYMBOLSPECFEE_USD_JPY, FOK_BUDGET, RejectionCause.REJECTION_BY_BUDGET);
    }

    // -------------------------- sell no rejection tests -----------------------------

    @Test
    @Timeout(5)
    public void testMultiSellNoRejectionMarginGtc() {
        testMultiSell(SYMBOLSPECFEE_USD_JPY, GTC, RejectionCause.NO_REJECTION);
    }

    @Test
    @Timeout(5)
    public void testMultiSellNoRejectionExchangeGtc() {
        testMultiSell(SYMBOLSPECFEE_XBT_LTC, GTC, RejectionCause.NO_REJECTION);
    }

    @Test
    @Timeout(5)
    public void testMultiSellNoRejectionMarginIoc() {
        testMultiSell(SYMBOLSPECFEE_USD_JPY, IOC, RejectionCause.NO_REJECTION);
    }

    @Test
    @Timeout(5)
    public void testMultiSellNoRejectionExchangeIoc() {
        testMultiSell(SYMBOLSPECFEE_XBT_LTC, IOC, RejectionCause.NO_REJECTION);
    }

    @Test
    @Timeout(5)
    public void testMultiSellNoRejectionMarginFokB() {
        testMultiSell(SYMBOLSPECFEE_USD_JPY, FOK_BUDGET, RejectionCause.NO_REJECTION);
    }

    @Test
    @Timeout(5)
    public void testMultiSellNoRejectionExchangeFokB() {
        testMultiSell(SYMBOLSPECFEE_XBT_LTC, FOK_BUDGET, RejectionCause.NO_REJECTION);
    }

    // -------------------------- sell with rejection tests -----------------------------

    @Test
    @Timeout(5)
    public void testMultiSellWithRejectionMarginGtc() {
        testMultiSell(SYMBOLSPECFEE_USD_JPY, GTC, RejectionCause.REJECTION_BY_SIZE);
    }

    @Test
    @Timeout(5)
    public void testMultiSellWithRejectionExchangeGtc() {
        testMultiSell(SYMBOLSPECFEE_XBT_LTC, GTC, RejectionCause.REJECTION_BY_SIZE);
    }

    @Test
    @Timeout(5)
    public void testMultiSellWithRejectionMarginIoc() {
        testMultiSell(SYMBOLSPECFEE_USD_JPY, IOC, RejectionCause.REJECTION_BY_SIZE);
    }

    @Test
    @Timeout(5)
    public void testMultiSellWithRejectionExchangeIoc() {
        testMultiSell(SYMBOLSPECFEE_XBT_LTC, IOC, RejectionCause.REJECTION_BY_SIZE);
    }

    @Test
    @Timeout(5)
    public void testMultiSellWithSizeRejectionMarginFokB() {
        testMultiSell(SYMBOLSPECFEE_USD_JPY, FOK_BUDGET, RejectionCause.REJECTION_BY_SIZE);
    }

    @Test
    @Timeout(5)
    public void testMultiSellWithSizeRejectionExchangeFokB() {
        testMultiSell(SYMBOLSPECFEE_XBT_LTC, FOK_BUDGET, RejectionCause.REJECTION_BY_SIZE);
    }

    @Test
    @Timeout(5)
    public void testMultiSellWithExpectationRejectionMarginFokB() {
        testMultiSell(SYMBOLSPECFEE_USD_JPY, FOK_BUDGET, RejectionCause.REJECTION_BY_BUDGET);
    }

    @Test
    @Timeout(5)
    public void testMultiSellWithExpectationRejectionExchangeFokB() {
        testMultiSell(SYMBOLSPECFEE_XBT_LTC, FOK_BUDGET, RejectionCause.REJECTION_BY_BUDGET);
    }
}
