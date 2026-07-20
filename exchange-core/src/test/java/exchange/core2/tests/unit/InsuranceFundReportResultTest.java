package exchange.core2.tests.unit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.eclipse.collections.impl.map.mutable.primitive.IntLongHashMap;
import org.eclipse.collections.impl.map.mutable.primitive.IntObjectHashMap;
import org.junit.jupiter.api.Test;

import exchange.core2.core.common.api.reports.InsuranceFundReportResult;
import exchange.core2.core.common.api.reports.InsuranceFundReportResult.FuturesIfEntry;
import exchange.core2.core.common.api.reports.InsuranceFundReportResult.PerShardData;
import net.openhft.chronicle.bytes.Bytes;

/**
 * {@link InsuranceFundReportResult} 的 section wire format：期货 IF（per-symbol）与借贷 LIF（per-currency）
 * 两段都要原样往返。LIF 允许为负，负值即平台已垫资额，序列化不得过滤或钳零。
 */
class InsuranceFundReportResultTest {

    private static final int SYMBOL = 101;
    private static final int USDT = 2;
    private static final int WBTC = 3;

    private static InsuranceFundReportResult roundTrip(InsuranceFundReportResult orig) {
        Bytes<?> buf = Bytes.allocateElasticOnHeap(256);
        orig.writeMarshallable(buf);
        return new InsuranceFundReportResult(buf);
    }

    @Test
    void sectionRoundTrip_carriesBothFuturesIfAndLoanLif() {
        IntObjectHashMap<FuturesIfEntry> futuresInsuranceFund = new IntObjectHashMap<>();
        futuresInsuranceFund.put(SYMBOL, new FuturesIfEntry(500_000L, 12_000L));
        IntLongHashMap lif = IntLongHashMap.newWithKeysValues(USDT, 250_000L, WBTC, 7L);

        IntLongHashMap markPriceTs = IntLongHashMap.newWithKeysValues(SYMBOL, 1_700_000_000_000L);

        PerShardData d =
            roundTrip(InsuranceFundReportResult.ofShard(3, futuresInsuranceFund, lif, markPriceTs))
                .getByShard().get(3);

        assertEquals(500_000L, d.getFuturesInsuranceFund().get(SYMBOL).getAvailable());
        assertEquals(12_000L, d.getFuturesInsuranceFund().get(SYMBOL).getPositionValue());
        assertEquals(250_000L, d.getLoanInsuranceFund().get(USDT));
        assertEquals(7L, d.getLoanInsuranceFund().get(WBTC), "接管收进的抵押币库存");
        assertEquals(1_700_000_000_000L, d.getMarkPriceTs().get(SYMBOL), "喂价时间随 section 一起过线");
    }

    @Test
    void sectionRoundTrip_preservesNegativeLif() {
        // LIF 为负是接管的常态结果，报表正是靠它回答"要补多少钱"
        IntLongHashMap lif = IntLongHashMap.newWithKeysValues(USDT, -10_000_000L, WBTC, 200L);

        PerShardData d = roundTrip(
            InsuranceFundReportResult.ofShard(0, new IntObjectHashMap<>(), lif, new IntLongHashMap()))
            .getByShard().get(0);

        assertEquals(-10_000_000L, d.getLoanInsuranceFund().get(USDT), "负值不得被过滤或钳零");
        assertEquals(200L, d.getLoanInsuranceFund().get(WBTC), "同一 shard 上可正负并存（用负债换资产）");
    }

    @Test
    void sectionRoundTrip_emptyBothBuckets() {
        PerShardData d = roundTrip(InsuranceFundReportResult.ofShard(
            1, new IntObjectHashMap<>(), new IntLongHashMap(), new IntLongHashMap())).getByShard().get(1);

        assertTrue(d.getFuturesInsuranceFund().isEmpty());
        assertTrue(d.getLoanInsuranceFund().isEmpty());
        assertTrue(d.getMarkPriceTs().isEmpty());
    }
}
