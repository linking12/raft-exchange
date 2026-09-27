package com.binance.raftexchange.client;

import com.binance.raftexchange.client.grpc.ApiStream;
import com.binance.raftexchange.client.grpc.ExchangeClient;
import com.binance.raftexchange.stubs.CoreCurrencySpecification;
import com.binance.raftexchange.stubs.CoreSymbolSpecification;
import com.binance.raftexchange.stubs.MarginMode;
import com.binance.raftexchange.stubs.OrderAction;
import com.binance.raftexchange.stubs.OrderType;
import com.binance.raftexchange.stubs.SymbolType;
import com.binance.raftexchange.stubs.report.SingleUserReportResult;
import com.binance.raftexchange.stubs.request.ApiAdjustMargin;
import com.binance.raftexchange.stubs.request.ApiAdjustUserBalance;
import com.binance.raftexchange.stubs.request.ApiClosePosition;
import com.binance.raftexchange.stubs.request.ApiCommand;
import com.binance.raftexchange.stubs.request.ApiMoveOrder;
import com.binance.raftexchange.stubs.request.ApiPlaceOrder;
import com.binance.raftexchange.stubs.request.ApiReduceOrder;
import com.binance.raftexchange.stubs.request.ApiAdjustMarkPrice;
import com.binance.raftexchange.stubs.request.ApiSettlePNL;
import com.binance.raftexchange.stubs.response.CommandResult;
import com.binance.raftexchange.stubs.response.CommandResultCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * ExchangeApi 的 {@code build*Command} 方法单测。
 *
 * <p>
 * 所有 build 方法都是纯函数（输入参数 + metadataManager → ApiCommand PB），跟 gRPC 解耦。 测试通过
 * {@link ExchangeApi#ExchangeApi(ExchangeClient, ExchangeMetadataManager)} 包级构造器注入 mock metadataManager，直接验证产出的
 * ApiCommand 字段是否正确缩放。
 * </p>
 *
 * <p>
 * 重点钉死注释里写过"以前出过事故"的回归点：
 * </p>
 * <ul>
 * <li>{@code buildPlaceOrderCommand} BUDGET 单 price 走 product scale (base × quote)， LIMIT 单走 quote scale</li>
 * <li>{@code buildAdjustMarginCommand} ISOLATED 用 quoteCurrency 的 currencyScaleK (= 10^digit) 而不是 symbol 的
 * quoteScaleK——历史 bug 是按 quoteScaleK 编码， 扣款金额少 10 倍</li>
 * <li>{@code buildAddSymbolCommand} / {@code buildAddCurrencyCommand} 重复 add 抛 IAE</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class ExchangeApiTest {

    // fixtures
    private static final int USDT_ID = 2; // digit = 6 → currencyScaleK = 10^6
    private static final int BNB_ID = 1; // digit = 8 → currencyScaleK = 10^8
    private static final int BNB_USDT_SPOT = 11; // baseScaleK=10^3, quoteScaleK=10^5
    private static final int BNB_USDT_FU = 12; // 同上, type = FUTURES_CONTRACT_PERPETUAL
    private static final long UID = 10001L;

    @Mock
    private ExchangeClient mockClient;
    @Mock
    private ExchangeMetadataManager metadataManager;

    private ExchangeApi api;

    @BeforeEach
    void setUp() {
        // metadata stubs：spot 和 futures 都用同一组 base/quote scale，方便算
        lenient().when(metadataManager.getCurrencySpec(USDT_ID)).thenReturn(currencySpec(USDT_ID, "USDT", 6));
        lenient().when(metadataManager.getCurrencySpec(BNB_ID)).thenReturn(currencySpec(BNB_ID, "BNB", 8));
        lenient().when(metadataManager.getSymbolSpec(BNB_USDT_SPOT))
            .thenReturn(spotSpec(BNB_USDT_SPOT, BNB_ID, USDT_ID, 1_000L, 100_000L));
        lenient().when(metadataManager.getSymbolSpec(BNB_USDT_FU))
            .thenReturn(futuresSpec(BNB_USDT_FU, BNB_ID, USDT_ID, 1_000L, 100_000L));

        api = new ExchangeApi(mockClient, metadataManager);
    }

    // ============================================================
    // adjustUserBalance
    // ============================================================

    @Nested
    @DisplayName("buildAdjustUserBalanceCommand")
    class AdjustUserBalance {

        @Test
        @DisplayName("amount 按 currency.digit 编码：1000.123456 USDT → 1000123456 (10^6)")
        void scalesAmountByCurrencyDigit() {
            ApiCommand cmd = api.buildAdjustUserBalanceCommand(UID, 99L, USDT_ID, 1000.123456);
            ApiAdjustUserBalance adj = cmd.getAdjustBalance();
            assertEquals(UID, adj.getUid());
            assertEquals(USDT_ID, adj.getCurrency());
            assertEquals(1_000_123_456L, adj.getAmount()); // 1000.123456 × 10^6
            assertEquals(99L, adj.getTransactionId());
        }

        @Test
        @DisplayName("负数（提现）：-30.32 USDT → -30320000")
        void scalesNegativeAmount() {
            ApiCommand cmd = api.buildAdjustUserBalanceCommand(UID, 1L, USDT_ID, -30.32);
            assertEquals(-30_320_000L, cmd.getAdjustBalance().getAmount());
        }

        @Test
        @DisplayName("currency 未知：metadataManager 抛 IAE，build 直接传递出来")
        void missingCurrencyPropagatesIae() {
            when(metadataManager.getCurrencySpec(999)).thenThrow(new IllegalArgumentException("unknow currency:999"));
            assertThrows(IllegalArgumentException.class, () -> api.buildAdjustUserBalanceCommand(UID, 1L, 999, 100));
        }
    }

    // ============================================================
    // placeOrder ── 最容易出 bug 的一块，重点覆盖
    // ============================================================

    @Nested
    @DisplayName("buildPlaceOrderCommand")
    class PlaceOrder {

        @Test
        @DisplayName("现货 LIMIT：price 走 quote (10^5)，size 走 base (10^3)")
        void spotLimitOrderScaling() {
            ApiCommand cmd = api.buildPlaceOrderCommand(UID, 7L, BNB_USDT_SPOT, OrderAction.BID, OrderType.GTC, 850.2,
                855, 0.5, null, 0, false);
            ApiPlaceOrder p = cmd.getPlaceOrder();
            assertEquals(85_020_000L, p.getPrice()); // 850.2 × 10^5
            assertEquals(85_500_000L, p.getReservePrice()); // 855 × 10^5
            assertEquals(500L, p.getSize()); // 0.5 × 10^3
            assertEquals(OrderAction.BID, p.getAction());
            assertEquals(OrderType.GTC, p.getOrderType());
        }

        @Test
        @DisplayName("FOK_BUDGET：price 走 PRODUCT scale (base × quote = 10^8)，size 仍走 base")
        void budgetOrderUsesProductScaleForPrice() {
            ApiCommand cmd = api.buildPlaceOrderCommand(UID, 7L, BNB_USDT_SPOT, OrderAction.BID, OrderType.FOK_BUDGET,
                100.0, 100.0, 0.5, null, 0, false);
            ApiPlaceOrder p = cmd.getPlaceOrder();
            // 100 × (10^3 × 10^5) = 10^10
            assertEquals(10_000_000_000L, p.getPrice());
            assertEquals(10_000_000_000L, p.getReservePrice());
            assertEquals(500L, p.getSize()); // 0.5 × 10^3
        }

        @Test
        @DisplayName("IOC_BUDGET：price 也走 PRODUCT scale，与 FOK_BUDGET 行为一致")
        void iocBudgetUsesProductScaleForPrice() {
            ApiCommand cmd = api.buildPlaceOrderCommand(UID, 7L, BNB_USDT_SPOT, OrderAction.ASK, OrderType.IOC_BUDGET,
                50.0, 50.0, 1.0, null, 0, false);
            assertEquals(5_000_000_000L, cmd.getPlaceOrder().getPrice());
        }

        @Test
        @DisplayName("期货下单：marginMode 必填，传 null 抛 IAE")
        void futuresOrderRequiresMarginMode() {
            assertThrows(IllegalArgumentException.class, () -> api.buildPlaceOrderCommand(UID, 7L, BNB_USDT_FU,
                OrderAction.BID, OrderType.GTC, 850.2, 855, 0.5, null, 1, false));
        }

        @Test
        @DisplayName("期货下单：填了 marginMode 就 OK，leverage / marginMode 透传")
        void futuresOrderWithMarginMode() {
            ApiCommand cmd = api.buildPlaceOrderCommand(UID, 7L, BNB_USDT_FU, OrderAction.BID, OrderType.GTC, 100.0,
                100.0, 0.5, MarginMode.ISOLATED, 5, true);
            ApiPlaceOrder p = cmd.getPlaceOrder();
            assertEquals(5, p.getLeverage());
            assertEquals(MarginMode.ISOLATED, p.getMarginMode());
            assertTrue(p.getReduceOnly());
        }

        @Test
        @DisplayName("现货下单：marginMode 不填也 OK，leverage 字段被忽略")
        void spotOrderNoMarginNeeded() {
            ApiCommand cmd = api.buildPlaceOrderCommand(UID, 7L, BNB_USDT_SPOT, OrderAction.BID, OrderType.GTC, 100.0,
                100.0, 0.5, null, 0, false);
            assertEquals(MarginMode.ISOLATED, cmd.getPlaceOrder().getMarginMode()); // proto3 默认
        }

        @Test
        @DisplayName("期货 leverage 超过 maxLeverage slot 表上限：抛 IAE")
        void futuresLeverageOverMaxThrows() {
            // maxLeverage map: notional <= 100 → 10x；<= 200 → 20x；<= 300 → 30x
            assertThrows(IllegalArgumentException.class, () -> api.buildPlaceOrderCommand(UID, 7L, BNB_USDT_FU,
                OrderAction.BID, OrderType.GTC, 100.0, 100.0, 0.5, MarginMode.ISOLATED, 999, false));
        }

        @Test
        @DisplayName("期货 leverage <= 0：抛 IAE")
        void futuresLeverageZeroThrows() {
            assertThrows(IllegalArgumentException.class, () -> api.buildPlaceOrderCommand(UID, 7L, BNB_USDT_FU,
                OrderAction.BID, OrderType.GTC, 100.0, 100.0, 0.5, MarginMode.ISOLATED, 0, false));
        }
    }

    // ============================================================
    // adjustMargin ── 注释里写过"历史用错 scale，扣款少 10 倍"，重点覆盖
    // ============================================================

    @Nested
    @DisplayName("buildAdjustMarginCommand")
    class AdjustMargin {

        @Test
        @DisplayName("ISOLATED：amount 用 quoteCurrency 的 currencyScaleK (= 10^digit)，NOT quoteScaleK")
        void isolatedUsesCurrencyScaleKNotQuoteScaleK() {
            // BNB_USDT_FU 的 quoteCurrency = USDT_ID = digit 6 → currencyScaleK = 10^6
            // 历史 bug 是用 quoteScaleK = 10^5（少一个数量级）
            ApiCommand cmd = api.buildAdjustMarginCommand(UID, MarginMode.ISOLATED, BNB_USDT_FU, 500.0);
            ApiAdjustMargin adj = cmd.getAdjustMargin();
            assertEquals(BNB_USDT_FU, adj.getSymbol());
            assertEquals(500_000_000L, adj.getAmount()); // 500 × 10^6
            assertEquals(0, adj.getCurrency(), "ISOLATED 走 symbol 路径，currency 字段为 0");
        }

        @Test
        @DisplayName("CROSS：amount 用传入 currency 的 currencyScaleK")
        void crossUsesCurrencyScaleK() {
            ApiCommand cmd = api.buildAdjustMarginCommand(UID, MarginMode.CROSS, USDT_ID, 500.0);
            ApiAdjustMargin adj = cmd.getAdjustMargin();
            assertEquals(USDT_ID, adj.getCurrency());
            assertEquals(500_000_000L, adj.getAmount()); // 500 × 10^6
            assertEquals(0, adj.getSymbol(), "CROSS 走 currency 路径，symbol 字段为 0");
        }

        @Test
        @DisplayName("未识别的 MarginMode 抛 IAE")
        void unknownMarginModeThrows() {
            assertThrows(IllegalArgumentException.class,
                () -> api.buildAdjustMarginCommand(UID, null, BNB_USDT_FU, 500.0));
        }
    }

    // ============================================================
    // addCurrency / addSymbol ── 重复 add / 缺前置 currency
    // ============================================================

    @Nested
    @DisplayName("buildAddCurrencyCommand & buildAddSymbolCommand")
    class AddSpec {

        @Test
        @DisplayName("buildAddCurrencyCommand：currency 已存在 → 抛 IAE")
        void duplicateCurrencyThrows() {
            when(metadataManager.currencyExists(USDT_ID)).thenReturn(true);
            assertThrows(IllegalArgumentException.class, () -> api.buildAddCurrencyCommand(USDT_ID, "USDT", 6));
        }

        @Test
        @DisplayName("buildAddCurrencyCommand：currency 不存在 → 构建命令 OK")
        void newCurrencyOk() {
            when(metadataManager.currencyExists(USDT_ID)).thenReturn(false);
            ApiCommand cmd = api.buildAddCurrencyCommand(USDT_ID, "USDT", 6);
            CoreCurrencySpecification spec =
                cmd.getBinaryData().getData().getAddCurrencies().getCurrenciesMap().get(USDT_ID);
            assertEquals(USDT_ID, spec.getId());
            assertEquals("USDT", spec.getName());
            assertEquals(6, spec.getDigit());
        }

        @Test
        @DisplayName("buildAddSymbolCommand：symbol 已存在 → 抛 IAE")
        void duplicateSymbolThrows() {
            when(metadataManager.symbolExists(BNB_USDT_FU)).thenReturn(true);
            assertThrows(IllegalArgumentException.class,
                () -> api.buildAddSymbolCommand(BNB_USDT_FU, SymbolType.FUTURES_CONTRACT_PERPETUAL, BNB_ID, USDT_ID,
                    1_000L, 100_000L, 15, 10, 1000, 0, 10, 100, Map.of(100L, 10L), 100, Map.of(100L, 10L)));
        }

        @Test
        @DisplayName("buildAddSymbolCommand：baseCurrency 未注册 → 抛 IAE")
        void missingBaseCurrencyThrows() {
            when(metadataManager.symbolExists(BNB_USDT_FU)).thenReturn(false);
            when(metadataManager.currencyExists(BNB_ID)).thenReturn(false);
            assertThrows(IllegalArgumentException.class,
                () -> api.buildAddSymbolCommand(BNB_USDT_FU, SymbolType.FUTURES_CONTRACT_PERPETUAL, BNB_ID, USDT_ID,
                    1_000L, 100_000L, 15, 10, 1000, 0, 10, 100, Map.of(100L, 10L), 100, Map.of(100L, 10L)));
        }

        @Test
        @DisplayName("buildAddSymbolCommand：quoteCurrency 未注册 → 抛 IAE")
        void missingQuoteCurrencyThrows() {
            when(metadataManager.symbolExists(BNB_USDT_FU)).thenReturn(false);
            when(metadataManager.currencyExists(BNB_ID)).thenReturn(true);
            when(metadataManager.currencyExists(USDT_ID)).thenReturn(false);
            assertThrows(IllegalArgumentException.class,
                () -> api.buildAddSymbolCommand(BNB_USDT_FU, SymbolType.FUTURES_CONTRACT_PERPETUAL, BNB_ID, USDT_ID,
                    1_000L, 100_000L, 15, 10, 1000, 0, 10, 100, Map.of(100L, 10L), 100, Map.of(100L, 10L)));
        }

        @Test
        @DisplayName("buildAddSymbolCommand：现货合约不带保证金字段")
        void spotSymbolHasNoMarginFields() {
            when(metadataManager.symbolExists(BNB_USDT_SPOT)).thenReturn(false);
            when(metadataManager.currencyExists(BNB_ID)).thenReturn(true);
            when(metadataManager.currencyExists(USDT_ID)).thenReturn(true);

            ApiCommand cmd = api.buildAddSymbolCommand(BNB_USDT_SPOT, SymbolType.CURRENCY_EXCHANGE_PAIR, BNB_ID,
                USDT_ID, 1_000L, 100_000L, 1000, 500, 100, 1_000_000, 0, 0, null, 0, null);

            CoreSymbolSpecification spec =
                cmd.getBinaryData().getData().getAddSymbols().getSymbolsMap().get(BNB_USDT_SPOT);
            assertEquals(SymbolType.CURRENCY_EXCHANGE_PAIR, spec.getType());
            assertEquals(0L, spec.getInitMargin(), "现货 spec 不写 initMargin");
            assertEquals(0, spec.getMaintenanceMarginCount(), "现货 spec 不写 maintenanceMargin");
            assertEquals(0, spec.getMaxLeverageCount(), "现货 spec 不写 maxLeverage");
        }
    }

    // ============================================================
    // 其余一组缩放是「单字段 × 单 scale」的简单 build
    // ============================================================

    @Nested
    @DisplayName("其它 build*Command (单 scale 字段)")
    class SimpleScalingBuilders {

        @Test
        @DisplayName("closePosition：price 走 quote，size 走 base")
        void closePositionScales() {
            ApiCommand cmd = api.buildClosePositionCommand(UID, 1L, BNB_USDT_FU, OrderAction.ASK, 850.2, 0.5);
            ApiClosePosition c = cmd.getClosePosition();
            assertEquals(85_020_000L, c.getPrice());
            assertEquals(500L, c.getSize());
        }

        @Test
        @DisplayName("moveOrder：newPrice 走 quote")
        void moveOrderScalesPrice() {
            ApiCommand cmd = api.buildMoveOrderCommand(UID, 1L, BNB_USDT_FU, 851.0);
            ApiMoveOrder m = cmd.getMoveOrder();
            assertEquals(85_100_000L, m.getNewPrice());
        }

        @Test
        @DisplayName("reduceOrder：size 走 base")
        void reduceOrderScalesSize() {
            ApiCommand cmd = api.buildReduceOrderCommand(UID, 1L, BNB_USDT_FU, 0.5);
            ApiReduceOrder r = cmd.getReduceOrder();
            assertEquals(500L, r.getReduceSize());
        }

        @Test
        @DisplayName("adjustMarkPrice：markPrice 走 quote")
        void adjustMarkPriceScales() {
            ApiCommand cmd = api.buildAdjustMarkPriceCommand(BNB_USDT_FU, 750.2);
            ApiAdjustMarkPrice m = cmd.getAdjustMarkprice();
            assertEquals(75_020_000L, m.getMarkPrice());
        }

        @Test
        @DisplayName("settlePnl：price 走 quote")
        void settlePnlScales() {
            ApiCommand cmd = api.buildSettlePnlCommand(BNB_USDT_FU, 800.0);
            ApiSettlePNL s = cmd.getSettlePnl();
            assertEquals(80_000_000L, s.getSettlePrice());
        }

        @Test
        @DisplayName("cancelOrder / suspendUser / resumeUser / addUser：纯透传，没有缩放")
        void purePassThroughBuilders() {
            assertEquals(UID, api.buildCancelOrderCommand(UID, 1L, BNB_USDT_SPOT).getCancelOrder().getUid());
            assertEquals(UID, api.buildSuspendUserCommand(UID).getSuspendUser().getUid());
            assertEquals(UID, api.buildResumeUserCommand(UID).getResumeUser().getUid());
            assertEquals(UID, api.buildAdjustLeverageCommand(UID, BNB_USDT_FU, 5).getAdjustLeverage().getUid());
        }
    }

    // ============================================================
    // 读写分离路由 —— 把 "写走 createStream(leader)、读走只读接口" 这条 invariant 钉死。
    // 之后谁意外把 queryUserReport 改成走 stream（leader），
    // 或者写命令改成走只读接口，这些用例都会立刻报红。
    // 注意：这里只验证 ExchangeApi 层的路由正确性；grpc channel 层的 round-robin
    // / EventLoopGroup 等是网络/集成层关心，不在单测里管。
    // ============================================================

    @Nested
    @DisplayName("读写分离路由")
    class ReadWriteSeparation {

        @Test
        @DisplayName("queryUserReport：走 client.singleUserReport(...)，绝不走 createStream")
        void queryUserReportGoesThroughSingleUserReport() {
            when(mockClient.singleUserReport(anyInt(), eq(UID)))
                .thenReturn(CompletableFuture.completedFuture(SingleUserReportResult.getDefaultInstance()));

            api.queryUserReport(UID).join();

            verify(mockClient).singleUserReport(anyInt(), eq(UID));
            verifyNoWriteStreamUsed();
            verifyNoOtherReadEndpointsUsed("singleUserReport");
        }

        @Test
        @DisplayName("searchOrderBook：走 client.searchOrderBook(...)，绝不走 createStream")
        void searchOrderBookGoesThroughReadOnlyEndpoint() {
            // 注意：默认实例（getDefaultInstance）会让 CommandResultView.build 抛
            // "neither resultCode nor orderCommand present"，所以这里必须显式 setResultCode
            when(mockClient.searchOrderBook(eq(BNB_USDT_SPOT), anyInt())).thenReturn(CompletableFuture
                .completedFuture(CommandResult.newBuilder().setResultCode(CommandResultCode.SUCCESS).build()));

            api.searchOrderBook(BNB_USDT_SPOT).join();

            verify(mockClient).searchOrderBook(eq(BNB_USDT_SPOT), anyInt());
            verifyNoWriteStreamUsed();
            verifyNoOtherReadEndpointsUsed("searchOrderBook");
        }

        @Test
        @DisplayName("queryInsuranceFundReport：走只读接口，绝不走 createStream")
        void insuranceFundReportGoesThroughReadOnlyEndpoint() {
            when(mockClient.insuranceFundReport(anyInt())).thenReturn(CompletableFuture.completedFuture(
                com.binance.raftexchange.stubs.report.InsuranceFundReportResult.getDefaultInstance()));

            api.queryInsuranceFundReport().join();

            verify(mockClient).insuranceFundReport(anyInt());
            verifyNoWriteStreamUsed();
            verifyNoOtherReadEndpointsUsed("insuranceFundReport");
        }

        @Test
        @DisplayName("queryLoanPlatformReport：走只读接口，绝不走 createStream")
        void loanPlatformReportGoesThroughReadOnlyEndpoint() {
            when(mockClient.loanPlatformReport(anyInt())).thenReturn(CompletableFuture.completedFuture(
                com.binance.raftexchange.stubs.report.LoanPlatformReportResult.getDefaultInstance()));

            api.queryLoanPlatformReport().join();

            verify(mockClient).loanPlatformReport(anyInt());
            verifyNoWriteStreamUsed();
            verifyNoOtherReadEndpointsUsed("loanPlatformReport");
        }

        @Test
        @DisplayName("其余三个平台报表：同样只走只读接口")
        void otherPlatformReportsGoThroughReadOnlyEndpoints() {
            when(mockClient.totalCurrencyBalanceReport(anyInt())).thenReturn(CompletableFuture.completedFuture(
                com.binance.raftexchange.stubs.report.TotalCurrencyBalanceReportResult.getDefaultInstance()));
            api.queryTotalCurrencyBalanceReport().join();
            verify(mockClient).totalCurrencyBalanceReport(anyInt());
            verifyNoWriteStreamUsed();

            when(mockClient.feeReport(anyInt())).thenReturn(CompletableFuture.completedFuture(
                com.binance.raftexchange.stubs.report.FeeReportResult.getDefaultInstance()));
            api.queryFeeReport().join();
            verify(mockClient).feeReport(anyInt());

            when(mockClient.stateHashReport(anyInt())).thenReturn(CompletableFuture.completedFuture(
                com.binance.raftexchange.stubs.report.StateHashReportResult.getDefaultInstance()));
            api.queryStateHashReport().join();
            verify(mockClient).stateHashReport(anyInt());
            verifyNoWriteStreamUsed();
        }

        @Test
        @DisplayName("写操作（addUserAsync）：走 createStream + stream.onNext，不碰任何只读接口")
        void writeAddUserGoesThroughCreateStreamOnly() {
            ApiStream mockStream = stubCreateStream();

            api.addUserAsync(UID);

            ArgumentCaptor<ApiCommand> captor = ArgumentCaptor.forClass(ApiCommand.class);
            verify(mockClient).createStream(any());
            verify(mockStream).onNext(captor.capture());
            assertTrue(captor.getValue().hasAddUser(), "写命令的 oneof 必须是 addUser");
            assertEquals(UID, captor.getValue().getAddUser().getUid());

            verifyNoReadEndpointsUsed();
        }

        @Test
        @DisplayName("写操作（placeOrderAsync）：走 createStream + stream.onNext，不碰任何只读接口")
        void writePlaceOrderGoesThroughCreateStreamOnly() {
            ApiStream mockStream = stubCreateStream();

            api.placeOrderAsync(UID, 1L, BNB_USDT_SPOT, OrderAction.BID, OrderType.GTC, 850.2, 855, 0.5, null, 0,
                false);

            ArgumentCaptor<ApiCommand> captor = ArgumentCaptor.forClass(ApiCommand.class);
            verify(mockClient).createStream(any());
            verify(mockStream).onNext(captor.capture());
            assertTrue(captor.getValue().hasPlaceOrder(), "写命令的 oneof 必须是 placeOrder");

            verifyNoReadEndpointsUsed();
        }

        @Test
        @DisplayName("写操作 build 阶段抛 IAE：不走 createStream，也不污染任何只读接口")
        void writeBuildFailureNeverTouchesAnyEndpoint() {
            // 期货下单不填 marginMode → buildPlaceOrderCommand 阶段就抛 IAE，
            // sendAsync(Supplier) 把它翻成 failedFuture，根本进不到 createStream。
            api.placeOrderAsync(UID, 1L, BNB_USDT_FU, OrderAction.BID, OrderType.GTC, 100.0, 100.0, 0.5, null, 1,
                false);

            verify(mockClient, never()).createStream(any());
            verifyNoReadEndpointsUsed();
        }

        /** mock 出一个 ApiStream，让 createStream(...) 返回它，避免 sendAsync 内部 NPE。 */
        private ApiStream stubCreateStream() {
            ApiStream mockStream = org.mockito.Mockito.mock(ApiStream.class);
            when(mockClient.createStream(any())).thenReturn(mockStream);
            return mockStream;
        }

        private void verifyNoWriteStreamUsed() {
            verify(mockClient, never()).createStream(any());
        }

        private void verifyNoReadEndpointsUsed() {
            verify(mockClient, never()).singleUserReport(anyInt(), anyLong());
            verify(mockClient, never()).searchOrderBook(anyInt(), anyInt());
            verify(mockClient, never()).symbolCurrencyReport(anyInt());
            verify(mockClient, never()).stateHashReport(anyInt());
            verify(mockClient, never()).totalCurrencyBalanceReport(anyInt());
        }

        private void verifyNoOtherReadEndpointsUsed(String except) {
            if (!"singleUserReport".equals(except))
                verify(mockClient, never()).singleUserReport(anyInt(), anyLong());
            if (!"searchOrderBook".equals(except))
                verify(mockClient, never()).searchOrderBook(anyInt(), anyInt());
            if (!"insuranceFundReport".equals(except))
                verify(mockClient, never()).insuranceFundReport(anyInt());
            if (!"loanPlatformReport".equals(except))
                verify(mockClient, never()).loanPlatformReport(anyInt());
            if (!"totalCurrencyBalanceReport".equals(except))
                verify(mockClient, never()).totalCurrencyBalanceReport(anyInt());
            if (!"feeReport".equals(except))
                verify(mockClient, never()).feeReport(anyInt());
            if (!"stateHashReport".equals(except))
                verify(mockClient, never()).stateHashReport(anyInt());
            verify(mockClient, never()).symbolCurrencyReport(anyInt());
        }
    }

    // ============================================================
    // Options 集成 —— 自定义 sendTimeout 真的生效，不是被忽略的摆设
    // ============================================================

    @Nested
    @DisplayName("ExchangeApiOptions 集成")
    class OptionsIntegration {

        @org.junit.jupiter.api.Test
        @DisplayName("默认构造的 ExchangeApi.options() = ExchangeApiOptions.defaults()")
        void defaultsExposedThroughGetter() {
            assertEquals(java.time.Duration.ofSeconds(2), api.options().sendTimeout());
        }

        @org.junit.jupiter.api.Test
        @DisplayName("注入自定义 options：api.options() 返回的就是注入的实例")
        void customOptionsReachInstance() {
            ExchangeApiOptions custom =
                ExchangeApiOptions.builder().sendTimeout(java.time.Duration.ofSeconds(30)).build();
            ExchangeApi customApi = new ExchangeApi(mockClient, metadataManager, custom);

            assertEquals(java.time.Duration.ofSeconds(30), customApi.options().sendTimeout());
        }

        @org.junit.jupiter.api.Test
        @DisplayName("send(cmd) 使用 options.sendTimeout()：超时 100ms 时 server 不回 → 即时 RuntimeException")
        void shortTimeoutFiresFast() {
            // mock createStream 返回一个不会回响应的 mock stream → sendAsync 的 future 永远 pending
            com.binance.raftexchange.client.grpc.ApiStream mockStream =
                org.mockito.Mockito.mock(com.binance.raftexchange.client.grpc.ApiStream.class);
            when(mockClient.createStream(any())).thenReturn(mockStream);

            ExchangeApiOptions shortTimeout =
                ExchangeApiOptions.builder().sendTimeout(java.time.Duration.ofMillis(100)).build();
            ExchangeApi shortApi = new ExchangeApi(mockClient, metadataManager, shortTimeout);

            long start = System.currentTimeMillis();
            org.junit.jupiter.api.Assertions.assertThrows(RuntimeException.class,
                () -> shortApi.send(com.binance.raftexchange.stubs.request.ApiCommand.getDefaultInstance()));
            long elapsed = System.currentTimeMillis() - start;

            // 100ms timeout，实际不该超过 2 秒（旧默认值）
            org.junit.jupiter.api.Assertions.assertTrue(elapsed < 2000,
                "实际耗时 " + elapsed + "ms，明显应该用的是 100ms 而非旧 2s 默认值");
        }
    }

    // ============================================================
    // fixture helpers
    // ============================================================

    private static CoreCurrencySpecification currencySpec(int id, String name, int digit) {
        return CoreCurrencySpecification.newBuilder().setId(id).setName(name).setDigit(digit).build();
    }

    private static CoreSymbolSpecification spotSpec(int symbolId, int baseCcy, int quoteCcy, long baseScaleK,
        long quoteScaleK) {
        return CoreSymbolSpecification.newBuilder().setSymbolId(symbolId).setType(SymbolType.CURRENCY_EXCHANGE_PAIR)
            .setBaseCurrency(baseCcy).setQuoteCurrency(quoteCcy).setBaseScaleK(baseScaleK).setQuoteScaleK(quoteScaleK)
            .build();
    }

    private static CoreSymbolSpecification futuresSpec(int symbolId, int baseCcy, int quoteCcy, long baseScaleK,
        long quoteScaleK) {
        // maxLeverage slot 表：notional 100 -> 10x，200 -> 20x，300 -> 30x
        // 测试里只要不超 300 都按 10x ~ 30x 来 clamp
        return CoreSymbolSpecification.newBuilder().setSymbolId(symbolId).setType(SymbolType.FUTURES_CONTRACT_PERPETUAL)
            .setBaseCurrency(baseCcy).setQuoteCurrency(quoteCcy).setBaseScaleK(baseScaleK).setQuoteScaleK(quoteScaleK)
            .setMaintenanceMarginScaleK(10_000L).putMaxLeverage(100L, 10L).putMaxLeverage(200L, 20L)
            .putMaxLeverage(300L, 30L).build();
    }

    // ============================================================
    // 借贷：金额须按各自 currency 的 digit 编码——抵押走 base 币、借款走 quote 币，两者精度不同
    // ============================================================

    @Nested
    @DisplayName("loan 命令编码")
    class Loan {

        @Test
        @DisplayName("loanCreate：抵押按 base(BNB,10^8)、本金按 quote(USDT,10^6) 各自编码")
        void loanCreateScalesCollateralAndPrincipalSeparately() {
            ApiCommand cmd = api.buildLoanCreateCommand(UID, 7L, BNB_USDT_SPOT, 1.5, 300.25);
            var c = cmd.getLoanCreate();
            assertEquals(UID, c.getUid());
            assertEquals(7L, c.getLoanId());
            assertEquals(BNB_USDT_SPOT, c.getSymbol());
            assertEquals(150_000_000L, c.getCollateralAmount(), "1.5 BNB × 10^8");
            assertEquals(300_250_000L, c.getPrincipal(), "300.25 USDT × 10^6");
        }

        @Test
        @DisplayName("loanRepay：还款额走 quote 币精度；0 表示 payoff 全部本息")
        void loanRepayScalesByLoanCurrency() {
            assertEquals(100_000_000L,
                api.buildLoanRepayCommand(UID, 7L, BNB_USDT_SPOT, 100.0).getLoanRepay().getRepayAmount());
            assertEquals(0L, api.buildLoanRepayCommand(UID, 7L, BNB_USDT_SPOT, 0).getLoanRepay().getRepayAmount(),
                "0 = payoff，不得被 scale 放大成非 0");
        }

        @Test
        @DisplayName("加减抵押：走 base 币精度")
        void collateralCommandsScaleByBaseCurrency() {
            assertEquals(50_000_000L,
                api.buildLoanAddCollateralCommand(UID, 7L, BNB_USDT_SPOT, 0.5).getLoanAddCollateral().getAmount());
            assertEquals(25_000_000L, api.buildLoanReleaseCollateralCommand(UID, 7L, BNB_USDT_SPOT, 0.25)
                .getLoanReleaseCollateral().getAmount());
        }

        @Test
        @DisplayName("Cross 抵押：currency 直接入参，按该币 digit 编码")
        void crossCollateralScalesByGivenCurrency() {
            assertEquals(200_000_000L,
                api.buildLoanCrossAddCollateralCommand(UID, BNB_ID, 2.0).getLoanCrossAddCollateral().getAmount());
            assertEquals(1_500_000L, api.buildLoanCrossWithdrawCollateralCommand(UID, USDT_ID, 1.5)
                .getLoanCrossWithdrawCollateral().getAmount());
        }

        @Test
        @DisplayName("Cross 借：symbolId 反推 quoteCurrency 决定精度；还：loanCcy 决定精度")
        void crossBorrowRepayScaleByLoanCurrency() {
            var borrow = api.buildLoanCrossBorrowCommand(UID, 9L, BNB_USDT_SPOT, 500.5).getLoanCrossBorrow();
            assertEquals(BNB_USDT_SPOT, borrow.getSymbolId());
            assertEquals(500_500_000L, borrow.getPrincipal());
            assertEquals(500_500_000L,
                api.buildLoanCrossRepayCommand(UID, 9L, USDT_ID, 500.5).getLoanCrossRepay().getRepayAmount());
        }
    }

    @Nested
    @DisplayName("借贷运营命令")
    class LoanOps {

        @Test
        @DisplayName("池子与 LIF 充提：shardId 定向，金额按 currency digit 编码")
        void poolAndLifCommandsCarryShardAndScaledAmount() {
            var deposit = api.buildPoolDepositCommand(1, USDT_ID, 10_000.0).getPoolDeposit();
            assertEquals(1, deposit.getShardId());
            assertEquals(10_000_000_000L, deposit.getAmount());

            assertEquals(3, api.buildPoolWithdrawCommand(3, USDT_ID, 1.0).getPoolWithdraw().getShardId());

            var lifDeposit = api.buildLoanIfDepositCommand(2, USDT_ID, 250.0).getLoanIfDeposit();
            assertEquals(2, lifDeposit.getShardId());
            assertEquals(250_000_000L, lifDeposit.getAmount());

            var lifWithdraw = api.buildLoanIfWithdrawCommand(0, BNB_ID, 0.75).getLoanIfWithdraw();
            assertEquals(75_000_000L, lifWithdraw.getAmount(), "0.75 BNB × 10^8");
        }

        @Test
        @DisplayName("repriceLoanRates：无参数命令")
        void repriceCarriesNoPayload() {
            assertTrue(api.buildRepriceLoanRatesCommand().hasRepriceLoanRates());
        }

        @Test
        @DisplayName("addLoanConfig：三段可选，传 null 的段不出现在命令里")
        void addLoanConfigOmitsNullSections() {
            var cmd = api.buildAddLoanConfigCommand(null,
                com.binance.raftexchange.stubs.request.SpotLoanConfig.newBuilder()
                    .setSymbolId(BNB_USDT_SPOT).setLoanInitialLtvBps(6000).build(),
                null);
            var addLoan = cmd.getBinaryData().getData().getAddLoan();
            assertTrue(addLoan.hasSymbol());
            assertEquals(BNB_USDT_SPOT, addLoan.getSymbol().getSymbolId());
            assertTrue(!addLoan.hasGlobal() && !addLoan.hasRateCurve(), "未指定的段不下发，避免覆盖成默认值");
        }
    }
}
