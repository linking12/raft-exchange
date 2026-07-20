package com.binance.raftexchange.client;

import com.binance.raftexchange.stubs.CoreCurrencySpecification;
import com.binance.raftexchange.stubs.CoreSymbolSpecification;
import com.binance.raftexchange.stubs.MarginMode;
import com.binance.raftexchange.stubs.OrderAction;
import com.binance.raftexchange.stubs.PositionDirection;
import com.binance.raftexchange.stubs.report.Order;
import com.binance.raftexchange.stubs.report.OrderList;
import com.binance.raftexchange.stubs.report.Position;
import com.binance.raftexchange.stubs.report.PositionList;
import com.binance.raftexchange.stubs.report.QueryExecutionStatus;
import com.binance.raftexchange.stubs.report.SingleUserReportResult;
import com.binance.raftexchange.stubs.report.UserStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * SingleUserReportResultView 还原口径单测。重点验证：
 * <ul>
 * <li>各字段按 base / quote / product / currency / maintenanceMargin scale 正确还原</li>
 * <li>openVolume 与 pendingSellSize / pendingBuySize 同口径（base scale，本轮修的 bug）</li>
 * <li>spec 缺失时按 currency / symbol 粒度降级，整份报告不挂</li>
 * <li>scale==0 不返回 NaN/Infinity，按 BigDecimal.ZERO 兜底</li>
 * <li>大数（>2^53）不丢精度</li>
 * </ul>
 *
 * 所有比较走 {@link BigDecimal#compareTo}（不用 equals，否则 scale 不同会判不等）。
 */
@ExtendWith(MockitoExtension.class)
class SingleUserReportResultViewTest {

    // 测试 fixtures（与 BaseTest 历史保持一致，方便记忆，不依赖具体业务含义）
    private static final int USDT_ID = 2; // digit = 6 → currencyScaleK = 10^6
    private static final int BNB_ID = 1; // digit = 8 → currencyScaleK = 10^8
    private static final int BNB_USDT_SPOT = 11; // base 10^3，quote 10^5，mmm 0（spot）
    private static final int BNB_USDT_FU = 12; // base 10^3，quote 10^5，mmm 10^4（futures）
    private static final long UID = 10001L;

    @Mock
    private ExchangeMetadataManager metadataManager;

    @BeforeEach
    void setUp() {
        // 用 lenient 是因为不是每条测试都用全部 mock；StrictStubs 默认对未使用的 stubbing 报错
        lenient().when(metadataManager.getCurrencySpec(USDT_ID)).thenReturn(currencySpec(USDT_ID, "USDT", 6));
        lenient().when(metadataManager.getCurrencySpec(BNB_ID)).thenReturn(currencySpec(BNB_ID, "BNB", 8));
        lenient().when(metadataManager.getSymbolSpec(BNB_USDT_SPOT))
            .thenReturn(symbolSpec(BNB_USDT_SPOT, 1_000L, 100_000L, 0L));
        lenient().when(metadataManager.getSymbolSpec(BNB_USDT_FU))
            .thenReturn(symbolSpec(BNB_USDT_FU, 1_000L, 100_000L, 10_000L));
    }

    // ---------- accounts ----------

    @Nested
    @DisplayName("accounts 还原")
    class Accounts {

        @Test
        @DisplayName("currency.digit 决定 scale：USDT 10^6, BNB 10^8")
        void differentDigitsRestoredCorrectly() {
            SingleUserReportResult pb = SingleUserReportResult.newBuilder().setUserId(UID)
                .setQueryExecutionStatus(QueryExecutionStatus.OK).putAccounts(USDT_ID, 12345_678900L) // 12345.6789 USDT
                                                                                                      // (digit 6)
                .putAccounts(BNB_ID, 250_00000000L) // 250 BNB (digit 8)
                .build();

            SingleUserReportResultView view = SingleUserReportResultView.build(pb, metadataManager);

            assertEqualsBD(new BigDecimal("12345.6789"), view.getAccounts().get(USDT_ID));
            assertEqualsBD(new BigDecimal("250"), view.getAccounts().get(BNB_ID));
        }

        @Test
        @DisplayName("单 currency spec 抛异常：跳过该 currency，其余仍然还原")
        void perCurrencyMissDoesNotKillReport() {
            when(metadataManager.getCurrencySpec(BNB_ID))
                .thenThrow(new IllegalArgumentException("unknown currency: " + BNB_ID));

            SingleUserReportResult pb = SingleUserReportResult.newBuilder().putAccounts(USDT_ID, 100_000000L)
                .putAccounts(BNB_ID, 10_00000000L).build();

            SingleUserReportResultView view = SingleUserReportResultView.build(pb, metadataManager);

            assertEqualsBD(new BigDecimal("100"), view.getAccounts().get(USDT_ID));
            assertFalse(view.getAccounts().containsKey(BNB_ID), "缺 spec 的 currency 应该被跳过");
        }

        @Test
        @DisplayName("超过 2^53 的余额必须保留精度，不能因为 double 截断而错")
        void preservesLargeBalancePrecision() {
            // 10^15 / 10^6 = 10^9。这里制造一个末位 7、超过 2^53 (~9.0×10^15) 的余额
            long raw = 12_345_678_901_234_567L; // 12345678901.234567 USDT
            SingleUserReportResult pb = SingleUserReportResult.newBuilder().putAccounts(USDT_ID, raw).build();

            SingleUserReportResultView view = SingleUserReportResultView.build(pb, metadataManager);

            BigDecimal expected = new BigDecimal("12345678901.234567");
            assertEqualsBD(expected, view.getAccounts().get(USDT_ID));
        }
    }

    // ---------- exchangeLocked ----------

    @Nested
    @DisplayName("exchangeLocked 还原")
    class ExchangeLocked {

        @Test
        @DisplayName("按 currency.digit scale 还原，与 accounts 共用 currency spec")
        void differentDigitsRestoredCorrectly() {
            SingleUserReportResult pb = SingleUserReportResult.newBuilder().setUserId(UID)
                .setQueryExecutionStatus(QueryExecutionStatus.OK).putAccounts(USDT_ID, 1000_000000L) // 1000 USDT total
                .putExchangeLocked(USDT_ID, 250_500000L) // 250.5 USDT locked
                .putExchangeLocked(BNB_ID, 1_50000000L) // 1.5 BNB locked
                .build();

            SingleUserReportResultView view = SingleUserReportResultView.build(pb, metadataManager);

            assertEqualsBD(new BigDecimal("250.5"), view.getExchangeLocked().get(USDT_ID));
            assertEqualsBD(new BigDecimal("1.5"), view.getExchangeLocked().get(BNB_ID));
            // accounts 字段是 total，不受 exchangeLocked 影响
            assertEqualsBD(new BigDecimal("1000"), view.getAccounts().get(USDT_ID));
        }

        @Test
        @DisplayName("单 currency spec 抛异常：跳过该 currency，其余 exchangeLocked 仍然还原")
        void perCurrencyMissDoesNotKillReport() {
            when(metadataManager.getCurrencySpec(BNB_ID))
                .thenThrow(new IllegalArgumentException("unknown currency: " + BNB_ID));

            SingleUserReportResult pb = SingleUserReportResult.newBuilder().putExchangeLocked(USDT_ID, 100_000000L)
                .putExchangeLocked(BNB_ID, 10_00000000L).build();

            SingleUserReportResultView view = SingleUserReportResultView.build(pb, metadataManager);

            assertEqualsBD(new BigDecimal("100"), view.getExchangeLocked().get(USDT_ID));
            assertFalse(view.getExchangeLocked().containsKey(BNB_ID), "缺 spec 的 currency 应该被跳过");
        }

        @Test
        @DisplayName("PB 缺省 exchange_locked → view.exchangeLocked 为空 map（向后兼容）")
        void missingFieldYieldsEmptyMap() {
            SingleUserReportResult pb = SingleUserReportResult.newBuilder().putAccounts(USDT_ID, 500_000000L).build();

            SingleUserReportResultView view = SingleUserReportResultView.build(pb, metadataManager);

            assertTrue(view.getExchangeLocked().isEmpty(), "老服务端不带 exchange_locked，客户端要能拿到空 map 而不是 NPE");
            assertEqualsBD(new BigDecimal("500"), view.getAccounts().get(USDT_ID));
        }
    }

    // ---------- positions ----------

    @Nested
    @DisplayName("positions 还原")
    class Positions {

        @Test
        @DisplayName("所有 14 个数值字段按 base/quote/product/maintenanceMargin scale 还原")
        void allFieldsRestored() {
            // base = 10^3, quote = 10^5, product = 10^8, mmm = 10^4
            // 选数值时让恢复后等于易记的纯小数
            Position raw = Position.newBuilder().setQuoteCurrency(USDT_ID).setDirection(PositionDirection.LONG)
                .setLeverage(10).setMarginMode(MarginMode.ISOLATED)
                // base scale (10^3) → 还原 0.5
                .setOpenVolume(500L).setPendingSellSize(300L).setPendingBuySize(700L)
                // quote scale (10^5) → 还原 850.2 / 900.4
                .setPendingSellAvgPrice(900_40000L).setPendingBuyAvgPrice(850_20000L).setLiquidationPrice(700_00000L)
                .setMarkPrice(820_50000L)
                // product scale (10^8) → 还原 12.34
                .setOpenInitMarginSum(12_34000000L).setOpenPriceSum(425_10000000L) // 425.1 = 0.5 * 850.2
                .setProfit(1_23000000L) // 1.23
                .setExtraMargin(50_00000000L) // 50
                .setUnrealizedProfit(-2_50000000L) // -2.5
                // mmm scale (10^4) → 还原 0.0125
                .setMarginRatioScaleK(125L).build();

            SingleUserReportResult pb = SingleUserReportResult.newBuilder()
                .putPositions(BNB_USDT_FU, PositionList.newBuilder().addPositions(raw).build()).build();

            SingleUserReportResultView view = SingleUserReportResultView.build(pb, metadataManager);

            List<SingleUserReportResultView.PositionView> list = view.getPositions().get(BNB_USDT_FU);
            assertNotNull(list);
            assertEquals(1, list.size());
            SingleUserReportResultView.PositionView pv = list.get(0);

            // 直传字段
            assertEquals(USDT_ID, pv.getQuoteCurrency());
            assertEquals(PositionDirection.LONG, pv.getDirection());
            assertEquals(10, pv.getLeverage());
            assertEquals(MarginMode.ISOLATED, pv.getMarginMode());

            // base scale
            assertEqualsBD(new BigDecimal("0.5"), pv.getOpenVolume());
            assertEqualsBD(new BigDecimal("0.3"), pv.getPendingSellSize());
            assertEqualsBD(new BigDecimal("0.7"), pv.getPendingBuySize());

            // quote scale
            assertEqualsBD(new BigDecimal("900.4"), pv.getPendingSellAvgPrice());
            assertEqualsBD(new BigDecimal("850.2"), pv.getPendingBuyAvgPrice());
            assertEqualsBD(new BigDecimal("700"), pv.getLiquidationPrice());
            assertEqualsBD(new BigDecimal("820.5"), pv.getMarkPrice());

            // product scale
            assertEqualsBD(new BigDecimal("12.34"), pv.getOpenInitMarginSum());
            assertEqualsBD(new BigDecimal("425.1"), pv.getOpenPriceSum());
            assertEqualsBD(new BigDecimal("1.23"), pv.getProfit());
            assertEqualsBD(new BigDecimal("50"), pv.getExtraMargin());
            assertEqualsBD(new BigDecimal("-2.5"), pv.getUnrealizedProfit());

            // maintenanceMargin scale
            assertEqualsBD(new BigDecimal("0.0125"), pv.getMarginRatio());
        }

        @Test
        @DisplayName("openVolume 与 pendingSellSize / pendingBuySize 同口径（修 bug 的回归用例）")
        void openVolumeRestoredAsBaseScale() {
            // 500 / 1000 = 0.5；如果回归回旧逻辑直接 long 透传，断言会拿到 500
            Position raw = Position.newBuilder().setDirection(PositionDirection.LONG).setOpenVolume(500L)
                .setPendingSellSize(500L).setPendingBuySize(500L).build();

            SingleUserReportResult pb = SingleUserReportResult.newBuilder()
                .putPositions(BNB_USDT_SPOT, PositionList.newBuilder().addPositions(raw).build()).build();

            SingleUserReportResultView.PositionView pv =
                SingleUserReportResultView.build(pb, metadataManager).getPositions().get(BNB_USDT_SPOT).get(0);

            assertEqualsBD(new BigDecimal("0.5"), pv.getOpenVolume());
            assertEqualsBD(pv.getPendingSellSize(), pv.getOpenVolume(),
                "openVolume 和 pendingSellSize 都是 base scale，还原值必须一致");
            assertEqualsBD(pv.getPendingBuySize(), pv.getOpenVolume());
        }

        @Test
        @DisplayName("单 symbol spec 抛异常：跳过该 symbol，其他 symbol 仍然还原")
        void perSymbolMissDoesNotKillReport() {
            when(metadataManager.getSymbolSpec(BNB_USDT_FU)).thenThrow(new IllegalArgumentException("unknown symbol"));

            SingleUserReportResult pb = SingleUserReportResult.newBuilder()
                .putPositions(BNB_USDT_SPOT,
                    PositionList.newBuilder().addPositions(Position.newBuilder().setOpenVolume(500L).build()).build())
                .putPositions(BNB_USDT_FU,
                    PositionList.newBuilder().addPositions(Position.newBuilder().setOpenVolume(999L).build()).build())
                .build();

            SingleUserReportResultView view = SingleUserReportResultView.build(pb, metadataManager);

            assertTrue(view.getPositions().containsKey(BNB_USDT_SPOT));
            assertFalse(view.getPositions().containsKey(BNB_USDT_FU), "缺 spec 的 symbol 应被跳过");
        }

        @Test
        @DisplayName("spec 的 maintenanceMarginScaleK = 0（spot 类）→ marginRatio = ZERO 而非 NaN")
        void zeroMaintenanceMarginScaleYieldsZero() {
            Position raw = Position.newBuilder().setMarginRatioScaleK(125L).build();
            SingleUserReportResult pb = SingleUserReportResult.newBuilder()
                .putPositions(BNB_USDT_SPOT, PositionList.newBuilder().addPositions(raw).build()).build();

            BigDecimal marginRatio = SingleUserReportResultView.build(pb, metadataManager).getPositions()
                .get(BNB_USDT_SPOT).get(0).getMarginRatio();

            assertEqualsBD(BigDecimal.ZERO, marginRatio);
        }

        @Test
        @DisplayName("spec scale 乘积溢出（>Long.MAX_VALUE）→ 整个 symbol 被跳过，不污染其他 symbol")
        void productScaleOverflowSkipsSymbol() {
            // 制造一个 product 溢出的 spec
            when(metadataManager.getSymbolSpec(BNB_USDT_FU))
                .thenReturn(symbolSpec(BNB_USDT_FU, Long.MAX_VALUE, 2L, 10_000L));

            SingleUserReportResult pb = SingleUserReportResult.newBuilder()
                .putPositions(BNB_USDT_SPOT,
                    PositionList.newBuilder().addPositions(Position.newBuilder().setOpenVolume(500L).build()).build())
                .putPositions(BNB_USDT_FU,
                    PositionList.newBuilder().addPositions(Position.newBuilder().setOpenVolume(500L).build()).build())
                .build();

            SingleUserReportResultView view = SingleUserReportResultView.build(pb, metadataManager);

            assertTrue(view.getPositions().containsKey(BNB_USDT_SPOT));
            assertFalse(view.getPositions().containsKey(BNB_USDT_FU), "scale 溢出的 symbol 应被跳过");
        }

        @Test
        @DisplayName("position 列表顺序保留（LONG 应该出现在 SHORT 之前）")
        void preservesListOrder() {
            Position longPos = Position.newBuilder().setDirection(PositionDirection.LONG).build();
            Position shortPos = Position.newBuilder().setDirection(PositionDirection.SHORT).build();

            SingleUserReportResult pb = SingleUserReportResult.newBuilder().putPositions(BNB_USDT_FU,
                PositionList.newBuilder().addPositions(longPos).addPositions(shortPos).build()).build();

            List<SingleUserReportResultView.PositionView> list =
                SingleUserReportResultView.build(pb, metadataManager).getPositions().get(BNB_USDT_FU);

            assertEquals(PositionDirection.LONG, list.get(0).getDirection());
            assertEquals(PositionDirection.SHORT, list.get(1).getDirection());
        }
    }

    // ---------- orders ----------

    @Nested
    @DisplayName("orders 还原")
    class Orders {

        @Test
        @DisplayName("8 个字段按 base/quote 还原 + 直传字段")
        void allFieldsRestored() {
            Order raw = Order.newBuilder().setOrderId(7777L).setUid(UID).setTimestamp(1_700_000_000_000L)
                .setAction(OrderAction.BID).setPrice(850_20000L) // 850.2 (quote 10^5)
                .setSize(500L) // 0.5 (base 10^3)
                .setFilled(200L) // 0.2 (base)
                .setReserveBidPrice(855_00000L) // 855 (quote)
                .build();

            SingleUserReportResult pb = SingleUserReportResult.newBuilder()
                .putOrders(BNB_USDT_SPOT, OrderList.newBuilder().addOrders(raw).build()).build();

            SingleUserReportResultView.OrderView ov =
                SingleUserReportResultView.build(pb, metadataManager).getOrders().get(BNB_USDT_SPOT).get(0);

            assertEquals(7777L, ov.getOrderId());
            assertEquals(UID, ov.getUid());
            assertEquals(1_700_000_000_000L, ov.getTimestamp());
            assertEquals(OrderAction.BID, ov.getAction());
            assertEqualsBD(new BigDecimal("850.2"), ov.getPrice());
            assertEqualsBD(new BigDecimal("0.5"), ov.getSize());
            assertEqualsBD(new BigDecimal("0.2"), ov.getFilled());
            assertEqualsBD(new BigDecimal("855"), ov.getReserveBidPrice());
        }

        @Test
        @DisplayName("单 symbol spec 抛异常：跳过该 symbol 的 orders")
        void perSymbolMissDoesNotKillReport() {
            when(metadataManager.getSymbolSpec(BNB_USDT_FU)).thenThrow(new IllegalArgumentException("unknown symbol"));

            SingleUserReportResult pb = SingleUserReportResult.newBuilder()
                .putOrders(BNB_USDT_SPOT,
                    OrderList.newBuilder().addOrders(Order.newBuilder().setOrderId(1L).build()).build())
                .putOrders(BNB_USDT_FU,
                    OrderList.newBuilder().addOrders(Order.newBuilder().setOrderId(2L).build()).build())
                .build();

            SingleUserReportResultView view = SingleUserReportResultView.build(pb, metadataManager);

            assertTrue(view.getOrders().containsKey(BNB_USDT_SPOT));
            assertFalse(view.getOrders().containsKey(BNB_USDT_FU));
        }
    }

    // ---------- 顶层状态 ----------

    @Nested
    @DisplayName("顶层状态透传")
    class TopLevel {

        @Test
        @DisplayName("userId / userStatus / queryExecutionStatus 直接透传")
        void passThroughTopLevelFields() {
            SingleUserReportResult pb = SingleUserReportResult.newBuilder().setUserId(UID)
                .setUserStatus(UserStatus.SUSPENDED).setQueryExecutionStatus(QueryExecutionStatus.OK).build();

            SingleUserReportResultView view = SingleUserReportResultView.build(pb, metadataManager);

            assertEquals(UID, view.getUserId());
            assertEquals(UserStatus.SUSPENDED, view.getUserStatus());
            assertEquals(QueryExecutionStatus.OK, view.getQueryExecutionStatus());
        }

        @Test
        @DisplayName("USER_NOT_FOUND：状态透传，三张 map 都为空（不抛）")
        void userNotFound() {
            SingleUserReportResult pb = SingleUserReportResult.newBuilder().setUserId(UID)
                .setQueryExecutionStatus(QueryExecutionStatus.USER_NOT_FOUND).build();

            SingleUserReportResultView view = SingleUserReportResultView.build(pb, metadataManager);

            assertEquals(QueryExecutionStatus.USER_NOT_FOUND, view.getQueryExecutionStatus());
            assertTrue(view.getAccounts().isEmpty());
            assertTrue(view.getExchangeLocked().isEmpty());
            assertTrue(view.getPositions().isEmpty());
            assertTrue(view.getOrders().isEmpty());
        }

        @Test
        @DisplayName("空 PB（只有默认值）→ build 不抛，四张 map 都为空")
        void emptyPbProducesEmptyView() {
            SingleUserReportResult pb = SingleUserReportResult.newBuilder().build();
            SingleUserReportResultView view = SingleUserReportResultView.build(pb, metadataManager);
            assertNotNull(view);
            assertTrue(view.getAccounts().isEmpty());
            assertTrue(view.getExchangeLocked().isEmpty());
            assertTrue(view.getPositions().isEmpty());
            assertTrue(view.getOrders().isEmpty());
        }
    }

    // ---------- fixture helpers ----------

    private static CoreCurrencySpecification currencySpec(int id, String name, int digit) {
        return CoreCurrencySpecification.newBuilder().setId(id).setName(name).setDigit(digit).build();
    }

    private static CoreSymbolSpecification symbolSpec(int symbolId, long baseScaleK, long quoteScaleK, long mmmScaleK) {
        return CoreSymbolSpecification.newBuilder().setSymbolId(symbolId).setBaseScaleK(baseScaleK)
            .setQuoteScaleK(quoteScaleK).setMaintenanceMarginScaleK(mmmScaleK).build();
    }

    private static void assertEqualsBD(BigDecimal expected, BigDecimal actual) {
        assertEquals(0, actual.compareTo(expected),
            () -> "expected " + expected.toPlainString() + " but got " + actual.toPlainString());
    }

    private static void assertEqualsBD(BigDecimal expected, BigDecimal actual, String hint) {
        assertEquals(0, actual.compareTo(expected),
            () -> hint + ": expected " + expected.toPlainString() + " but got " + actual.toPlainString());
    }

    /** 让编译器不报 "Map unused" 之类的警告 */
    @SuppressWarnings("unused")
    private static Map<Integer, BigDecimal> typeHint() {
        return null;
    }

    // ============================================================
    // loan 视图：抵押侧与借款侧是两个币种、两套精度，必须各自还原
    // ============================================================

    @Test
    @DisplayName("Isolated loan：抵押按 BNB(10^8)、债务按 USDT(10^6) 各自还原")
    void isolatedLoanRestoresCollateralAndDebtWithOwnScales() {
        SingleUserReportResult pb = SingleUserReportResult.newBuilder().setUserId(UID)
            .addIsolatedLoans(com.binance.raftexchange.stubs.report.IsolatedLoan.newBuilder()
                .setLoanId(77L).setSymbolId(BNB_USDT_SPOT)
                .setCollateralCurrency(BNB_ID).setLoanCurrency(USDT_ID)
                .setCollateralAmount(150_000_000L)      // 1.5 BNB
                .setOutstandingPrincipal(300_250_000L)  // 300.25 USDT
                .setAccumulatedInterest(1_000_000L)     // 1 USDT
                .setDisplayInterest(1_500_000L)         // 1.5 USDT
                .setLtvBps(6000).setMarkPrice(500L).setRateBps(500))
            .build();

        var view = SingleUserReportResultView.build(pb, metadataManager);

        assertEquals(1, view.getIsolatedLoans().size());
        var loan = view.getIsolatedLoans().get(0);
        assertEquals(77L, loan.getLoanId());
        assertEquals(0, new BigDecimal("1.5").compareTo(loan.getCollateralAmount()), "抵押走 BNB 精度");
        assertEquals(0, new BigDecimal("300.25").compareTo(loan.getOutstandingPrincipal()), "本金走 USDT 精度");
        assertEquals(0, new BigDecimal("1.5").compareTo(loan.getDisplayInterest()));
        assertEquals(6000L, loan.getLtvBps());
    }

    @Test
    @DisplayName("Cross loan + 账户级抵押池：债务走借款币精度，抵押池逐币各自还原")
    void crossLoanAndCollateralPoolRestore() {
        SingleUserReportResult pb = SingleUserReportResult.newBuilder().setUserId(UID)
            .addCrossLoans(com.binance.raftexchange.stubs.report.CrossLoan.newBuilder()
                .setLoanId(88L).setLoanCurrency(USDT_ID).setOutstandingPrincipal(500_500_000L)
                .setAccumulatedInterest(500_000L).setDisplayInterest(750_000L).setRateBps(300))
            .putCrossLoanCollateral(BNB_ID, 200_000_000L)  // 2 BNB
            .setCrossAccountLtvBps(4444L)
            .build();

        var view = SingleUserReportResultView.build(pb, metadataManager);

        assertEquals(1, view.getCrossLoans().size());
        var loan = view.getCrossLoans().get(0);
        assertEquals(0, new BigDecimal("500.5").compareTo(loan.getOutstandingPrincipal()));
        assertEquals(0, new BigDecimal("0.75").compareTo(loan.getDisplayInterest()));
        assertEquals(0, new BigDecimal("2").compareTo(view.getCrossLoanCollateral().get(BNB_ID)), "抵押池走 BNB 精度");
        assertEquals(4444L, view.getCrossAccountLtvBps());
    }

    @Test
    @DisplayName("单笔 loan 的 currency spec 缺失只跳过该笔，不让整份报告作废")
    void unknownCurrencySkipsOnlyThatLoan() {
        SingleUserReportResult pb = SingleUserReportResult.newBuilder().setUserId(UID)
            .addIsolatedLoans(com.binance.raftexchange.stubs.report.IsolatedLoan.newBuilder()
                .setLoanId(1L).setCollateralCurrency(999).setLoanCurrency(USDT_ID))  // 999 未注册
            .addIsolatedLoans(com.binance.raftexchange.stubs.report.IsolatedLoan.newBuilder()
                .setLoanId(2L).setCollateralCurrency(BNB_ID).setLoanCurrency(USDT_ID)
                .setCollateralAmount(100_000_000L))
            .build();

        var view = SingleUserReportResultView.build(pb, metadataManager);

        assertEquals(1, view.getIsolatedLoans().size(), "坏的那笔被跳过，好的那笔仍在");
        assertEquals(2L, view.getIsolatedLoans().get(0).getLoanId());
    }
}
