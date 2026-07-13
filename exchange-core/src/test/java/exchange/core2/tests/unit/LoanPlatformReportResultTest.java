package exchange.core2.tests.unit;

import exchange.core2.core.common.api.reports.LoanPlatformReportResult;
import exchange.core2.core.common.api.reports.LoanPlatformReportResult.PerShardData;
import net.openhft.chronicle.bytes.Bytes;
import net.openhft.chronicle.bytes.BytesIn;
import org.eclipse.collections.impl.map.mutable.primitive.IntLongHashMap;
import org.junit.jupiter.api.Test;

import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * loan 平台账本报表 —— per-shard section write/read round-trip + 多 shard merge。
 */
class LoanPlatformReportResultTest {

    /** 五桶各放 currency=2 一个值，base 用于区分不同 shard。 */
    private static PerShardData shardData(long base) {
        return new PerShardData(
            IntLongHashMap.newWithKeysValues(2, base),
            IntLongHashMap.newWithKeysValues(2, base + 1),
            IntLongHashMap.newWithKeysValues(2, base + 2),
            IntLongHashMap.newWithKeysValues(2, base + 3),
            IntLongHashMap.newWithKeysValues(2, base + 4));
    }

    @Test
    void sectionRoundTrip_preservesAllBuckets() {
        LoanPlatformReportResult orig = LoanPlatformReportResult.ofShard(3, shardData(1000L));

        Bytes<?> buf = Bytes.allocateElasticOnHeap(256);
        orig.writeMarshallable(buf);
        LoanPlatformReportResult parsed = new LoanPlatformReportResult(buf);

        PerShardData d = parsed.getByShard().get(3);
        assertEquals(1000L, d.getInterestRevenue().get(2));
        assertEquals(1001L, d.getLoanLiqFees().get(2));
        assertEquals(1002L, d.getBadDebt().get(2));
        assertEquals(1003L, d.getPoolAvailable().get(2));
        assertEquals(1004L, d.getPoolBorrowed().get(2));
    }

    @Test
    void merge_collectsAllShardSections() {
        Bytes<?> b0 = Bytes.allocateElasticOnHeap(256);
        LoanPlatformReportResult.ofShard(0, shardData(1000L)).writeMarshallable(b0);
        Bytes<?> b1 = Bytes.allocateElasticOnHeap(256);
        LoanPlatformReportResult.ofShard(1, shardData(2000L)).writeMarshallable(b1);

        LoanPlatformReportResult merged = LoanPlatformReportResult.merge(Stream.<BytesIn>of(b0, b1));

        assertEquals(2, merged.getByShard().size());
        assertEquals(1000L, merged.getByShard().get(0).getInterestRevenue().get(2));
        assertEquals(2000L, merged.getByShard().get(1).getInterestRevenue().get(2));
    }
}
