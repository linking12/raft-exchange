package exchange.core2.tests.unit;

import exchange.core2.core.common.CoreSymbolSpecification;
import exchange.core2.core.common.SymbolType;
import net.openhft.chronicle.bytes.Bytes;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * CoreSymbolSpecification.updateLoanConfig 单元测试。
 *
 * <p>只覆盖 loan 相关 6 字段（利率已移出 spec，归 LoanService 利率子系统，见 loan.md §3.5/§13）。
 */
class CoreSymbolSpecificationLoanConfigTest {

    private static final int SYMBOL = 100;
    private static final int BTC = 1;
    private static final int USDT = 2;

    private CoreSymbolSpecification freshDisabledSpec() {
        // loanConfig 省略 → @Builder.Default 给全零 SymbolLoanSpecification（= 借贷未启用）
        return CoreSymbolSpecification.builder()
                .symbolId(SYMBOL).type(SymbolType.CURRENCY_EXCHANGE_PAIR)
                .baseCurrency(BTC).quoteCurrency(USDT).baseScaleK(1).quoteScaleK(1)
                .build();
    }

    private static CoreSymbolSpecification roundTrip(CoreSymbolSpecification spec) {
        Bytes<?> buf = Bytes.allocateElasticOnHeap(256);
        spec.writeMarshallable(buf);
        return new CoreSymbolSpecification(buf);
    }

    @Test
    void updateLoanConfig_setsAll6FieldsAtomically() {
        CoreSymbolSpecification spec = freshDisabledSpec();

        spec.updateLoanConfig(6000, 8000, 7000, 1_000_000L, 90, 9000);

        assertEquals(6000, spec.loanConfig.initialLtvBps);
        assertEquals(8000, spec.loanConfig.liquidationLtvBps);
        assertEquals(7000, spec.loanConfig.marginCallLtvBps);
        assertEquals(1_000_000L, spec.loanConfig.maxAmount);
        assertEquals(90, spec.loanConfig.maxTermDays);
        assertEquals(9000, spec.loanConfig.collateralWeightBps);
    }

    @Test
    void updateLoanConfig_snapshotRoundTrip_preservesUpdatedValues() {
        // 关键不变量：updateLoanConfig 改的值必须能 survive raft snapshot；
        // 否则 leader 改配后重建 follower 会退回旧 spec，产生跨节点分叉。
        CoreSymbolSpecification spec = freshDisabledSpec();
        spec.updateLoanConfig(5000, 8500, 7500, 2_000_000L, 60, 8500);

        CoreSymbolSpecification recovered = roundTrip(spec);

        assertEquals(5000, recovered.loanConfig.initialLtvBps);
        assertEquals(8500, recovered.loanConfig.liquidationLtvBps);
        assertEquals(7500, recovered.loanConfig.marginCallLtvBps);
        assertEquals(2_000_000L, recovered.loanConfig.maxAmount);
        assertEquals(60, recovered.loanConfig.maxTermDays);
        assertEquals(8500, recovered.loanConfig.collateralWeightBps);
    }

    @Test
    void updateLoanConfig_disable_zeroInitial_stateHashEqualsFreshDisabled() {
        // "disable = initial=0"：重置回全零后，stateHash 应等于初始 disabled spec
        // （证明 update 是完全覆盖而非累积，避免旧 stale 值残留）
        CoreSymbolSpecification enabled = freshDisabledSpec();
        enabled.updateLoanConfig(6000, 8000, 7000, 100L, 90, 9000);

        enabled.updateLoanConfig(0, 0, 0, 0, 0, 0);

        assertEquals(freshDisabledSpec().stateHash(), enabled.stateHash());
    }
}
