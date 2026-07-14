package exchange.core2.tests.unit;

import exchange.core2.core.common.api.binary.BinaryCommandType;
import exchange.core2.core.common.api.binary.BatchAddLoanCommand;
import exchange.core2.core.common.api.binary.BatchAddLoanCommand.GlobalLoanConfig;
import exchange.core2.core.common.api.binary.BatchAddLoanCommand.SymbolLoanConfig;
import net.openhft.chronicle.bytes.Bytes;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ADD_LOAN binary command（{@link BatchAddLoanCommand}）单元测试（合并自旧 UPDATE_SYMBOL_LOAN_CONFIG / UPDATE_LOAN_GLOBAL_CONFIG）。
 *
 * <p>覆盖：global / symbol 两部分可选的 write/read round-trip（走 raft 日志复制 + snapshot 存档的契约），
 * GlobalLoanConfig partial-update 生效值的阈值自洽校验，SymbolLoanConfig 字段层校验，以及"至少一部分"的构造守卫。
 */
class BatchAddLoanCommandTest {

    // 当前生效的全局配置（默认值），供 partial-update 校验的 current 参数用
    private static final int CUR_LIQ = 8500;
    private static final int CUR_MC = 8000;

    private static BatchAddLoanCommand roundTrip(BatchAddLoanCommand orig) {
        Bytes<?> buf = Bytes.allocateElasticOnHeap(64);
        orig.writeMarshallable(buf);
        return new BatchAddLoanCommand(buf);
    }

    // ================================================================
    // 序列化 round-trip：symbol only / global only / both / 构造守卫
    // ================================================================

    @Test
    void symbolOnly_bytesRoundTrip_preservesAllFields() {
        BatchAddLoanCommand orig =
            BatchAddLoanCommand.ofSymbol(101, 6000, 8000, 7000, 1_234_567_890L, 90, 9000);
        BatchAddLoanCommand parsed = roundTrip(orig);

        assertEquals(orig, parsed);
        assertTrue(parsed.hasSymbol());
        assertFalse(parsed.hasGlobal(), "symbol-only 命令没有 global 部分");
        assertNull(parsed.getGlobal());
        SymbolLoanConfig s = parsed.getSymbol();
        assertEquals(101, s.getSymbolId());
        assertEquals(6000, s.getLoanInitialLtvBps());
        assertEquals(8000, s.getLoanLiquidationLtvBps());
        assertEquals(7000, s.getLoanMarginCallLtvBps());
        assertEquals(1_234_567_890L, s.getLoanMaxAmount());
        assertEquals(90, s.getLoanMaxTermDays());
        assertEquals(9000, s.getCollateralWeightBps());
    }

    @Test
    void globalOnly_bytesRoundTrip_preservesAllFields() {
        BatchAddLoanCommand orig = BatchAddLoanCommand.ofGlobal(2, 8500, 8000, 9000, 200);
        BatchAddLoanCommand parsed = roundTrip(orig);

        assertEquals(orig, parsed);
        assertTrue(parsed.hasGlobal());
        assertFalse(parsed.hasSymbol(), "global-only 命令没有 symbol 部分");
        assertNull(parsed.getSymbol());
        GlobalLoanConfig g = parsed.getGlobal();
        assertEquals(2, g.getNumeraireCurrency());
        assertEquals(8500, g.getCrossLiquidationLtvBps());
        assertEquals(8000, g.getCrossMarginCallLtvBps());
        assertEquals(9000, g.getLoanPoolUtilizationCapBps());
        assertEquals(200, g.getLoanLiquidationFeeBps());
    }

    @Test
    void bothParts_bytesRoundTrip_preservesEachIndependently() {
        // 合并后的核心能力：一条命令同时改全局 + 某 symbol
        BatchAddLoanCommand orig = new BatchAddLoanCommand(
            new GlobalLoanConfig(2, 7000, 6500, 8500, 150),
            new SymbolLoanConfig(101, 6000, 8000, 7000, 1_000_000L, 60, 9000));
        BatchAddLoanCommand parsed = roundTrip(orig);

        assertEquals(orig, parsed);
        assertTrue(parsed.hasGlobal());
        assertTrue(parsed.hasSymbol());
        assertEquals(2, parsed.getGlobal().getNumeraireCurrency());
        assertEquals(7000, parsed.getGlobal().getCrossLiquidationLtvBps());
        assertEquals(101, parsed.getSymbol().getSymbolId());
        assertEquals(60, parsed.getSymbol().getLoanMaxTermDays());
    }

    @Test
    void ofGlobalNumeraire_setsNumeraireOnly_thresholdsZeroMeansUnchanged() {
        BatchAddLoanCommand cmd = BatchAddLoanCommand.ofGlobalNumeraire(2);
        GlobalLoanConfig g = cmd.getGlobal();
        assertEquals(2, g.getNumeraireCurrency());
        assertEquals(0, g.getCrossLiquidationLtvBps());
        assertEquals(0, g.getCrossMarginCallLtvBps());
        assertEquals(0, g.getLoanPoolUtilizationCapBps());
        assertEquals(0, g.getLoanLiquidationFeeBps());
    }

    @Test
    void neitherPart_constructorRejects() {
        assertThrows(IllegalArgumentException.class, () -> new BatchAddLoanCommand(null, null),
            "至少要有一部分配置");
    }

    @Test
    void binaryCommandTypeCode_matches1005() {
        BatchAddLoanCommand cmd = BatchAddLoanCommand.ofGlobalNumeraire(2);
        assertEquals(BinaryCommandType.ADD_LOAN.getCode(), cmd.getBinaryCommandTypeCode());
        assertEquals(1005, cmd.getBinaryCommandTypeCode());
    }

    // ================================================================
    // GlobalLoanConfig.thresholdsValidGivenCurrent —— partial-update 生效值自洽校验
    // ================================================================

    private static GlobalLoanConfig glob(int liq, int mc, int cap, int fee) {
        return new GlobalLoanConfig(0, liq, mc, cap, fee);
    }

    @Test
    void global_validFullUpdate_marginCallBelowLiquidation_returnsTrue() {
        assertTrue(glob(8500, 8000, 9000, 200).thresholdsValidGivenCurrent(CUR_LIQ, CUR_MC));
    }

    @Test
    void global_marginCallNotBelowLiquidation_returnsFalse() {
        assertFalse(glob(8000, 8000, 0, 0).thresholdsValidGivenCurrent(CUR_LIQ, CUR_MC), "预警=强平（相等）应拒");
        assertFalse(glob(8000, 8500, 0, 0).thresholdsValidGivenCurrent(CUR_LIQ, CUR_MC), "预警>强平（倒挂）应拒");
    }

    @Test
    void global_partialUpdate_onlyMarginCall_checkedAgainstCurrentLiquidation() {
        assertFalse(glob(0, 8600, 0, 0).thresholdsValidGivenCurrent(CUR_LIQ, CUR_MC));
        assertTrue(glob(0, 7000, 0, 0).thresholdsValidGivenCurrent(CUR_LIQ, CUR_MC));
    }

    @Test
    void global_partialUpdate_onlyLiquidation_belowCurrentMarginCall_returnsFalse() {
        assertFalse(glob(7000, 0, 0, 0).thresholdsValidGivenCurrent(CUR_LIQ, CUR_MC));
    }

    @Test
    void global_liquidationAtOrAbove100pct_returnsFalse() {
        assertFalse(glob(10000, 8000, 0, 0).thresholdsValidGivenCurrent(CUR_LIQ, CUR_MC), "强平线 = 100% 应拒");
    }

    @Test
    void global_poolUtilizationCapAbove100pct_returnsFalse() {
        assertFalse(glob(8500, 8000, 10001, 0).thresholdsValidGivenCurrent(CUR_LIQ, CUR_MC));
    }

    @Test
    void global_liquidationFeeAt100pct_returnsFalse() {
        assertFalse(glob(8500, 8000, 0, 10000).thresholdsValidGivenCurrent(CUR_LIQ, CUR_MC), "强平费 = 100% 应拒");
    }

    @Test
    void global_allFieldsUnchanged_skipsRangeChecks_returnsTrueGivenValidCurrent() {
        assertTrue(glob(0, 0, 0, 0).thresholdsValidGivenCurrent(CUR_LIQ, CUR_MC));
    }

    // ================================================================
    // SymbolLoanConfig.fieldsValid —— per-symbol 字段层校验（不含 spec 存在性/类型）
    // ================================================================

    @Test
    void symbol_validThresholds_returnsTrue() {
        assertTrue(new SymbolLoanConfig(101, 6000, 8000, 7000, 0, 90, 9000).fieldsValid());
    }

    @Test
    void symbol_disabled_initialZero_returnsTrue() {
        assertTrue(new SymbolLoanConfig(101, 0, 0, 0, 0, 0, 0).fieldsValid(), "initial=0 停借该 pair 是合法配置");
    }

    @Test
    void symbol_liquidationNotAboveInitial_returnsFalse() {
        assertFalse(new SymbolLoanConfig(101, 6000, 6000, 0, 0, 0, 0).fieldsValid(), "强平线须 > 开仓线");
    }

    @Test
    void symbol_marginCallNotBetween_returnsFalse() {
        assertFalse(new SymbolLoanConfig(101, 6000, 8000, 6000, 0, 0, 0).fieldsValid(), "预警线须在 (initial, liq) 之间");
    }

    @Test
    void symbol_collateralWeightAbove100pct_returnsFalse() {
        assertFalse(new SymbolLoanConfig(101, 6000, 8000, 7000, 0, 0, 10001).fieldsValid());
    }
}
