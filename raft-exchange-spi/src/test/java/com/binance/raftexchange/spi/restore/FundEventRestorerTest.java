package com.binance.raftexchange.spi.restore;

import com.binance.raftexchange.stubs.BalanceSnapshot;
import com.binance.raftexchange.stubs.FundEventReportPB;
import com.binance.raftexchange.stubs.FundEventType;
import com.binance.raftexchange.stubs.LoanSnapshot;
import com.binance.raftexchange.stubs.PositionDirection;
import com.binance.raftexchange.stubs.PositionSnapshot;
import com.binance.raftexchange.spi.restore.model.RestoredFundEvent;
import com.google.protobuf.InvalidProtocolBufferException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 测试场景统一使用 BTC/USDT 现货+期货的典型配置：
 *   BTC.digit  = 8 → currencyScaleK 暂不使用（BTC 余额非本测试关注）
 *   USDT.digit = 6 → currencyScaleK = 10^6
 *   baseScaleK  = 10^4
 *   quoteScaleK = 10^5
 *   productScale = baseScaleK × quoteScaleK = 10^9
 */
class FundEventRestorerTest {

    private static final long CURRENCY_SCALE_K = 1_000_000L;       // 10^6 (USDT.digit=6)
    private static final long BASE_SCALE_K     = 10_000L;          // 10^4
    private static final long QUOTE_SCALE_K    = 100_000L;         // 10^5
    private static final long PRODUCT_SCALE_K  = BASE_SCALE_K * QUOTE_SCALE_K; // 10^9

    private static void assertBd(String expected, BigDecimal actual) {
        assertEquals(0, actual.compareTo(new BigDecimal(expected)),
                () -> "expected " + expected + " but was " + actual);
    }

    // ---------------- Balance 还原 ----------------

    @Test
    void restore_deposit_recoversBalanceByCurrencyScale() {
        // 用户充值 1000 USDT
        FundEventReportPB pb = FundEventReportPB.newBuilder()
                .setAccountId(42L)
                .setEventType(FundEventType.DEPOSIT)
                .setBalances(BalanceSnapshot.newBuilder()
                        .setCurrency(2)
                        .setCurrencyScaleK(CURRENCY_SCALE_K)
                        .setFree(1_000L * CURRENCY_SCALE_K)        // 10^9
                        .setLocked(0L))
                .build();

        RestoredFundEvent v = FundEventRestorer.restore(pb);

        assertEquals(42L, v.accountId);
        assertEquals(FundEventType.DEPOSIT, v.eventType);
        assertEquals(2, v.currency);
        assertBd("1000", v.free);
        assertBd("0", v.locked);
        // 没有 position
        assertNull(v.direction);
        assertBd("0", v.quantity);
    }

    @Test
    void restore_locked_recoversFreeAndLocked() {
        // 余额 1000，挂单冻结 60.06 USDT
        FundEventReportPB pb = FundEventReportPB.newBuilder()
                .setEventType(FundEventType.LOCKED)
                .setBalances(BalanceSnapshot.newBuilder()
                        .setCurrencyScaleK(CURRENCY_SCALE_K)
                        .setFree(939_940_000L)        // 939.94 USDT
                        .setLocked(60_060_000L))      // 60.06 USDT
                .build();

        RestoredFundEvent v = FundEventRestorer.restore(pb);

        assertBd("939.94", v.free);
        assertBd("60.06", v.locked);
    }

    // ---------------- Position 还原（多 scale 混合） ----------------

    @Test
    void restore_openPosition_recoversAllScaleClasses() {
        // 场景：0.1 BTC 多头 @ 50000 USDT，杠杆 10x
        //   quantity        long = 0.1 × baseScaleK     = 1_000
        //   openPriceSum    long = quantity × priceLong = 1_000 × (50000 × 10^5)
        //                                              = 5×10^12     (product)
        //   openInitMargin  long = openPriceSum / 10    = 5×10^11     (product)
        //   markPrice       long = 50000 × 10^5         = 5×10^9
        //   liquidationPrice long = 40000 × 10^5        = 4×10^9
        //   unrealizedProfit long = 100 × productScale  = 10^11
        //   isolatedWallet  long = 500 × productScale   = 5×10^11
        //   bidsQty         long = 0.05 × baseScaleK    = 500
        //   bidsNotional    long = 2000 × productScale  = 2×10^12

        PositionSnapshot pos = PositionSnapshot.newBuilder()
                .setSymbolId(1)
                .setBaseScaleK(BASE_SCALE_K)
                .setQuoteScaleK(QUOTE_SCALE_K)
                .setDirection(PositionDirection.LONG)
                .setLeverage(10)
                .setIsolated(true)
                .setQuantity(1_000L)
                .setOpenPriceSum(5_000_000_000_000L)
                .setOpenInitMarginSum(500_000_000_000L)
                .setMarkPrice(5_000_000_000L)
                .setLiquidationPrice(4_000_000_000L)
                .setUnrealizedProfit(100_000_000_000L)
                .setIsolatedWallet(500_000_000_000L)
                .setCumRealized(0L)
                .setBidsQty(500L)
                .setAsksQty(0L)
                .setBidsNotional(2_000_000_000_000L)
                .setAsksNotional(0L)
                .build();

        FundEventReportPB pb = FundEventReportPB.newBuilder()
                .setEventType(FundEventType.OPEN_POSITION)
                .setBalances(BalanceSnapshot.newBuilder()
                        .setCurrencyScaleK(CURRENCY_SCALE_K)
                        .setFree(94_000_000_000L)
                        .setLocked(6_000_000_000L))
                .setPositions(pos)
                .build();

        RestoredFundEvent v = FundEventRestorer.restore(pb);

        // Balance
        assertBd("94000", v.free);
        assertBd("6000",  v.locked);

        // Basic
        assertEquals(1, v.symbolId);
        assertEquals(PositionDirection.LONG, v.direction);
        assertEquals(10, v.leverage);
        assertEquals(true, v.isolated);

        // base scale
        assertBd("0.1",  v.quantity);
        assertBd("0.05", v.bidsQty);
        assertBd("0",    v.asksQty);

        // quote scale
        assertBd("50000", v.markPrice);
        assertBd("40000", v.liquidationPrice);

        // product scale
        assertBd("5000", v.openPriceSum);          // 0.1 × 50000 名义价值
        assertBd("500",  v.openInitMarginSum);     // 5000 / 10 杠杆
        assertBd("100",  v.unrealizedProfit);
        assertBd("500",  v.isolatedWallet);
        assertBd("2000", v.bidsNotional);
        assertBd("0",    v.asksNotional);
        assertBd("0",    v.cumRealized);

        // 衍生
        assertBd("50000", v.avgOpenPrice);
    }

    // ---------------- 边界条件 ----------------

    @Test
    void restore_emptyPb_doesNotThrowAndAllZeros() {
        FundEventReportPB pb = FundEventReportPB.newBuilder().build();

        RestoredFundEvent v = FundEventRestorer.restore(pb);

        assertNotNull(v);
        // 无 Balance：currencyScaleK=0 时 div 应安全返回 0
        assertBd("0", v.free);
        assertBd("0", v.locked);
        // 无 Position
        assertNull(v.direction);
        assertBd("0", v.quantity);
        assertBd("0", v.avgOpenPrice);
        assertFalse(v.isolated);
    }

    @Test
    void restore_zeroQuantity_doesNotDivideByZeroForAvgPrice() {
        // 持仓刚清零或挂单未成交时 quantity=0，avgOpenPrice 必须 0 而不是抛 ArithmeticException
        FundEventReportPB pb = FundEventReportPB.newBuilder()
                .setPositions(PositionSnapshot.newBuilder()
                        .setBaseScaleK(BASE_SCALE_K)
                        .setQuoteScaleK(QUOTE_SCALE_K)
                        .setQuantity(0L)
                        .setOpenPriceSum(0L))
                .build();

        RestoredFundEvent v = FundEventRestorer.restore(pb);

        assertBd("0", v.avgOpenPrice);
    }

    @Test
    void restore_missingScaleK_doesNotDivideByZero() {
        // PB 没填 baseScaleK / quoteScaleK 时（如旧版本生产者），不应抛异常
        FundEventReportPB pb = FundEventReportPB.newBuilder()
                .setPositions(PositionSnapshot.newBuilder()
                        .setQuantity(1_000L)
                        .setOpenPriceSum(5_000_000_000_000L))
                .build();

        RestoredFundEvent v = FundEventRestorer.restore(pb);

        // 除数为 0，全部安全置 0
        assertBd("0", v.quantity);
        assertBd("0", v.openPriceSum);
        assertBd("0", v.avgOpenPrice);
    }

    // ---------------- 4403c4d 修复回归 ----------------

    @Test
    void restore_isolatedWallet_usesProductScaleNotQuoteOnly() {
        // 回归 4403c4d：extraMargin/isolatedWallet 是 product scale 不是 quote scale
        // 如果错误地只除 quoteScaleK，会把还原值放大 baseScaleK(=10^4) 倍
        long extraMarginInProductScale = 500L * PRODUCT_SCALE_K; // 500 USDT
        FundEventReportPB pb = FundEventReportPB.newBuilder()
                .setPositions(PositionSnapshot.newBuilder()
                        .setBaseScaleK(BASE_SCALE_K)
                        .setQuoteScaleK(QUOTE_SCALE_K)
                        .setIsolatedWallet(extraMarginInProductScale))
                .build();

        RestoredFundEvent v = FundEventRestorer.restore(pb);

        assertBd("500", v.isolatedWallet);
        // 若误用 quoteScale 还原会得到 500 × 10^4 = 5_000_000，绝不能等
    }

    // ---------------- BigDecimal 精度回归 ----------------

    @Test
    void restore_largeProductScaleValue_keepsExactPrecision() {
        // 用 base=10^8 / quote=10^8 / product=10^16 模拟大精度场景；
        // double 还原 10^16 量级的值会丢精度，BigDecimal 必须精确。
        long base    = 100_000_000L;       // 10^8
        long quote   = 100_000_000L;       // 10^8
        // openPriceSum = 12_345_678_901_234_567 (17 位)，product 还原后 = 1.2345678901234567
        long openPriceSum = 12_345_678_901_234_567L;
        FundEventReportPB pb = FundEventReportPB.newBuilder()
                .setPositions(PositionSnapshot.newBuilder()
                        .setBaseScaleK(base)
                        .setQuoteScaleK(quote)
                        .setOpenPriceSum(openPriceSum))
                .build();

        RestoredFundEvent v = FundEventRestorer.restore(pb);

        assertBd("1.2345678901234567", v.openPriceSum);
    }

    // ---------------- Kafka 消费者 byte[] 入口 ----------------

    @Test
    void restoreFromBytes_equivalentToRestoreFromPb() throws InvalidProtocolBufferException {
        FundEventReportPB pb = FundEventReportPB.newBuilder()
                .setAccountId(42L)
                .setEventType(FundEventType.DEPOSIT)
                .setBalances(BalanceSnapshot.newBuilder()
                        .setCurrency(2)
                        .setCurrencyScaleK(CURRENCY_SCALE_K)
                        .setFree(1_000L * CURRENCY_SCALE_K))
                .build();

        RestoredFundEvent fromPb    = FundEventRestorer.restore(pb);
        RestoredFundEvent fromBytes = FundEventRestorer.restore(pb.toByteArray());

        assertEquals(fromPb.accountId, fromBytes.accountId);
        assertEquals(fromPb.eventType, fromBytes.eventType);
        assertEquals(0, fromPb.free.compareTo(fromBytes.free));
    }

    // ---------------- Loan 还原 ----------------

    /** 抵押币 scale ≠ 借款币 scale：抵押侧若误用借款币 scale，pledged 会差 100 倍。 */
    private static final long COLLATERAL_SCALE_K = 100_000_000L; // 10^8 (BTC.digit=8)

    @Test
    void restore_loanBorrow_recoversBothSidesByOwnScale() {
        // 押 3 BTC（markPrice 50000 USDT）借 80000 USDT → LTV = 80000/150000 = 53.33%
        FundEventReportPB pb = FundEventReportPB.newBuilder()
                .setAccountId(8001L)
                .setEventType(FundEventType.LOAN_BORROW)
                .setBalances(BalanceSnapshot.newBuilder()
                        .setCurrency(2)                              // USDT
                        .setCurrencyScaleK(CURRENCY_SCALE_K)         // 10^6
                        .setFree(80_000L * CURRENCY_SCALE_K)         // 放款直接进 free
                        .setLocked(0L))
                .setLoan(LoanSnapshot.newBuilder()
                        .setMode(0)                                  // Isolated
                        .setDebtPrincipal(80_000L * CURRENCY_SCALE_K)
                        .setDebtInterest(0L)
                        .setLtvBps(5333L)
                        .setCollateralCurrency(1)                    // BTC
                        .setCollateralCurrencyScaleK(COLLATERAL_SCALE_K)
                        .setCollateralPledged(3L * COLLATERAL_SCALE_K)
                        .setCollateralFree(0L)                       // 抵押占满
                        .setCollateralLocked(3L * COLLATERAL_SCALE_K))
                .build();

        RestoredFundEvent v = FundEventRestorer.restore(pb);

        // 借贷侧：与 free/locked 共用 balances 的 currencyScaleK
        assertEquals(2, v.currency);
        assertBd("80000", v.free);
        assertBd("80000", v.debtPrincipal);
        assertBd("0", v.debtInterest);

        // 抵押侧：必须用 collateralCurrencyScaleK（10^8），用错就会得到 30000000
        assertEquals(1, v.collateralCurrency);
        assertBd("3", v.collateralPledged);
        assertBd("0", v.collateralFree);
        assertBd("3", v.collateralLocked);

        // bps → 比例
        assertBd("0.5333", v.ltv);
        assertEquals(true, v.loanIsolated);
    }

    @Test
    void restore_loanMarginCall_recoversLtvAndThreshold() {
        // 纯预警：仅 LTV 与阈值有效，两侧余额与债务均为 0
        FundEventReportPB pb = FundEventReportPB.newBuilder()
                .setAccountId(8001L)
                .setEventType(FundEventType.LOAN_MARGIN_CALL)
                .setBalances(BalanceSnapshot.newBuilder()
                        .setCurrency(2).setCurrencyScaleK(CURRENCY_SCALE_K))
                .setLoan(LoanSnapshot.newBuilder()
                        .setMode(1)                                  // Cross
                        .setLtvBps(7800L)
                        .setThresholdBps(7500L))
                .build();

        RestoredFundEvent v = FundEventRestorer.restore(pb);

        assertBd("0.78", v.ltv);
        assertBd("0.75", v.ltvThreshold);
        assertEquals(false, v.loanIsolated);
        assertBd("0", v.debtPrincipal);
        assertBd("0", v.collateralPledged);
    }

    @Test
    void restore_nonLoanEvent_loanFieldsAllZero() {
        FundEventReportPB pb = FundEventReportPB.newBuilder()
                .setAccountId(42L)
                .setEventType(FundEventType.DEPOSIT)
                .setBalances(BalanceSnapshot.newBuilder()
                        .setCurrency(2).setCurrencyScaleK(CURRENCY_SCALE_K)
                        .setFree(1_000L * CURRENCY_SCALE_K))
                .build();

        RestoredFundEvent v = FundEventRestorer.restore(pb);

        assertBd("0", v.debtPrincipal);
        assertBd("0", v.collateralPledged);
        assertBd("0", v.ltv);
    }

    @Test
    void restoreFromBytes_malformedBytes_throwsInvalidProtocolBufferException() {
        // 任意非 protobuf wire-format 字节，parseFrom 必须抛 checked 异常给调用方
        byte[] bogus = {(byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff};
        assertThrows(InvalidProtocolBufferException.class,
                () -> FundEventRestorer.restore(bogus));
    }
}
