package exchange.core2.tests.unit;

import exchange.core2.core.common.api.binary.BinaryCommandType;
import exchange.core2.core.common.api.binary.UpdateLoanGlobalConfigCommand;
import net.openhft.chronicle.bytes.Bytes;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * UPDATE_LOAN_GLOBAL_CONFIG binary command 单元测试 —— write/read bytes round-trip 保 4 字段无丢无串，
 * 便捷构造只配 numeraire（三阈值 = 0 表示"不改"），以及 partial-update 生效值的阈值自洽校验。
 * 走 raft 日志复制 + snapshot 存档都靠这个契约。
 */
class UpdateLoanGlobalConfigCommandTest {

    // 当前生效配置（默认值），供 partial-update 校验的 current 参数用
    private static final int CUR_LIQ = 8500;
    private static final int CUR_MC = 8000;

    private static UpdateLoanGlobalConfigCommand cfg(int liq, int mc, int cap, int fee) {
        return new UpdateLoanGlobalConfigCommand(0, liq, mc, cap, fee);
    }

    @Test
    void validFullUpdate_marginCallBelowLiquidation_returnsTrue() {
        assertTrue(cfg(8500, 8000, 9000, 200).thresholdsValidGivenCurrent(CUR_LIQ, CUR_MC));
    }

    @Test
    void marginCallNotBelowLiquidation_returnsFalse() {
        assertFalse(cfg(8000, 8000, 0, 0).thresholdsValidGivenCurrent(CUR_LIQ, CUR_MC), "预警=强平（相等）应拒");
        assertFalse(cfg(8000, 8500, 0, 0).thresholdsValidGivenCurrent(CUR_LIQ, CUR_MC), "预警>强平（倒挂）应拒");
    }

    @Test
    void partialUpdate_onlyMarginCall_checkedAgainstCurrentLiquidation() {
        // 只调预警线：强平线取 current(8500)。8600 ≥ 8500 → 拒；7000 < 8500 → 过
        assertFalse(cfg(0, 8600, 0, 0).thresholdsValidGivenCurrent(CUR_LIQ, CUR_MC));
        assertTrue(cfg(0, 7000, 0, 0).thresholdsValidGivenCurrent(CUR_LIQ, CUR_MC));
    }

    @Test
    void partialUpdate_onlyLiquidation_belowCurrentMarginCall_returnsFalse() {
        // 只调强平线到 7000，但预警线 current=8000 → 8000 ≥ 7000 倒挂 → 拒
        assertFalse(cfg(7000, 0, 0, 0).thresholdsValidGivenCurrent(CUR_LIQ, CUR_MC));
    }

    @Test
    void liquidationAtOrAbove100pct_returnsFalse() {
        assertFalse(cfg(10000, 8000, 0, 0).thresholdsValidGivenCurrent(CUR_LIQ, CUR_MC), "强平线 = 100% 应拒");
    }

    @Test
    void poolUtilizationCapAbove100pct_returnsFalse() {
        assertFalse(cfg(8500, 8000, 10001, 0).thresholdsValidGivenCurrent(CUR_LIQ, CUR_MC));
    }

    @Test
    void liquidationFeeAt100pct_returnsFalse() {
        assertFalse(cfg(8500, 8000, 0, 10000).thresholdsValidGivenCurrent(CUR_LIQ, CUR_MC), "强平费 = 100% 应拒");
    }

    @Test
    void allFieldsUnchanged_skipsRangeChecks_returnsTrueGivenValidCurrent() {
        // 全 ≤0（都不改）→ 生效值取 current（自洽），cap/fee 跳过范围校验 → 过
        assertTrue(cfg(0, 0, 0, 0).thresholdsValidGivenCurrent(CUR_LIQ, CUR_MC));
    }

    @Test
    void bytesRoundTrip_preservesAllFields() {
        UpdateLoanGlobalConfigCommand orig = new UpdateLoanGlobalConfigCommand(2, 8500, 8000, 9000, 200);

        Bytes<?> buf = Bytes.allocateElasticOnHeap(32);
        orig.writeMarshallable(buf);
        UpdateLoanGlobalConfigCommand parsed = new UpdateLoanGlobalConfigCommand(buf);

        assertEquals(orig, parsed);
        assertEquals(2, parsed.getNumeraireCurrency());
        assertEquals(8500, parsed.getCrossLiquidationLtvBps());
        assertEquals(8000, parsed.getCrossMarginCallLtvBps());
        assertEquals(9000, parsed.getLoanPoolUtilizationCapBps());
        assertEquals(200, parsed.getLoanLiquidationFeeBps());
    }

    @Test
    void convenienceCtor_setsNumeraireOnly_thresholdsZeroMeansUnchanged() {
        UpdateLoanGlobalConfigCommand cmd = new UpdateLoanGlobalConfigCommand(2);

        assertEquals(2, cmd.getNumeraireCurrency());
        assertEquals(0, cmd.getCrossLiquidationLtvBps());
        assertEquals(0, cmd.getCrossMarginCallLtvBps());
        assertEquals(0, cmd.getLoanPoolUtilizationCapBps());
        assertEquals(0, cmd.getLoanLiquidationFeeBps());
    }

    @Test
    void thresholdsOnlyUpdate_numeraireZeroMeansUnchanged_roundTrips() {
        // 只调阈值、不动 numeraire：numeraire=0 → handler 视为"不改"
        UpdateLoanGlobalConfigCommand orig = new UpdateLoanGlobalConfigCommand(0, 7000, 6500, 8500, 150);

        Bytes<?> buf = Bytes.allocateElasticOnHeap(32);
        orig.writeMarshallable(buf);
        UpdateLoanGlobalConfigCommand parsed = new UpdateLoanGlobalConfigCommand(buf);

        assertEquals(orig, parsed);
        assertEquals(0, parsed.getNumeraireCurrency());
        assertEquals(7000, parsed.getCrossLiquidationLtvBps());
    }

    @Test
    void binaryCommandTypeCode_matches1006() {
        UpdateLoanGlobalConfigCommand cmd = new UpdateLoanGlobalConfigCommand(2);
        assertEquals(BinaryCommandType.UPDATE_LOAN_GLOBAL_CONFIG.getCode(), cmd.getBinaryCommandTypeCode());
        assertEquals(1006, cmd.getBinaryCommandTypeCode());
    }
}
