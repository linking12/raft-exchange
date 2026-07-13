package exchange.core2.tests.unit;

import exchange.core2.core.common.api.binary.BinaryCommandType;
import exchange.core2.core.common.api.binary.UpdateLoanGlobalConfigCommand;
import net.openhft.chronicle.bytes.Bytes;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * UPDATE_LOAN_GLOBAL_CONFIG binary command 单元测试 —— write/read bytes round-trip 保 4 字段无丢无串，
 * 便捷构造只配 numeraire（三阈值 = 0 表示"不改"）。走 raft 日志复制 + snapshot 存档都靠这个契约。
 */
class UpdateLoanGlobalConfigCommandTest {

    @Test
    void bytesRoundTrip_preservesAllFields() {
        UpdateLoanGlobalConfigCommand orig = new UpdateLoanGlobalConfigCommand(2, 8500, 8000, 9000);

        Bytes<?> buf = Bytes.allocateElasticOnHeap(32);
        orig.writeMarshallable(buf);
        UpdateLoanGlobalConfigCommand parsed = new UpdateLoanGlobalConfigCommand(buf);

        assertEquals(orig, parsed);
        assertEquals(2, parsed.getNumeraireCurrency());
        assertEquals(8500, parsed.getCrossLiquidationLtvBps());
        assertEquals(8000, parsed.getCrossMarginCallLtvBps());
        assertEquals(9000, parsed.getLoanPoolUtilizationCapBps());
    }

    @Test
    void convenienceCtor_setsNumeraireOnly_thresholdsZeroMeansUnchanged() {
        UpdateLoanGlobalConfigCommand cmd = new UpdateLoanGlobalConfigCommand(2);

        assertEquals(2, cmd.getNumeraireCurrency());
        assertEquals(0, cmd.getCrossLiquidationLtvBps());
        assertEquals(0, cmd.getCrossMarginCallLtvBps());
        assertEquals(0, cmd.getLoanPoolUtilizationCapBps());
    }

    @Test
    void thresholdsOnlyUpdate_numeraireZeroMeansUnchanged_roundTrips() {
        // 只调阈值、不动 numeraire：numeraire=0 → handler 视为"不改"
        UpdateLoanGlobalConfigCommand orig = new UpdateLoanGlobalConfigCommand(0, 7000, 6500, 8500);

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
