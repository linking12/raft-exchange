package exchange.core2.tests.unit;

import exchange.core2.core.common.api.binary.BinaryCommandType;
import exchange.core2.core.common.api.binary.UpdateSymbolLoanConfigCommand;
import net.openhft.chronicle.bytes.Bytes;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * UPDATE_SYMBOL_LOAN_CONFIG binary command 单元测试 —— write/read bytes round-trip 保 8 字段无丢无串。
 * 走 raft 日志复制 + snapshot 存档都靠这个契约。
 */
class UpdateSymbolLoanConfigCommandTest {

    @Test
    void bytesRoundTrip_preservesAllFields() {
        UpdateSymbolLoanConfigCommand orig = new UpdateSymbolLoanConfigCommand(
                101, 6000, 8000, 7000, 500, 1_234_567_890L, 90, 9000);

        Bytes<?> buf = Bytes.allocateElasticOnHeap(64);
        orig.writeMarshallable(buf);
        UpdateSymbolLoanConfigCommand parsed = new UpdateSymbolLoanConfigCommand(buf);

        assertEquals(orig, parsed);
        assertEquals(101, parsed.getSymbolId());
        assertEquals(6000, parsed.getLoanInitialLtvBps());
        assertEquals(8000, parsed.getLoanLiquidationLtvBps());
        assertEquals(7000, parsed.getLoanMarginCallLtvBps());
        assertEquals(500, parsed.getLoanRateBps());
        assertEquals(1_234_567_890L, parsed.getLoanMaxAmount());
        assertEquals(90, parsed.getLoanMaxTermDays());
        assertEquals(9000, parsed.getCollateralWeightBps());
    }

    @Test
    void binaryCommandTypeCode_matches1005() {
        UpdateSymbolLoanConfigCommand cmd = new UpdateSymbolLoanConfigCommand(1, 0, 0, 0, 0, 0, 0, 0);
        assertEquals(BinaryCommandType.UPDATE_SYMBOL_LOAN_CONFIG.getCode(), cmd.getBinaryCommandTypeCode());
        assertEquals(1005, cmd.getBinaryCommandTypeCode());
    }
}
