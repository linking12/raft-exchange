/*
 * Copyright 2019 Maksim Zheravin
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package exchange.core2.tests.unit;

import exchange.core2.core.common.UserStatus;
import exchange.core2.core.common.api.reports.SingleUserReportResult;
import net.openhft.chronicle.bytes.Bytes;
import net.openhft.chronicle.bytes.BytesIn;
import org.eclipse.collections.impl.map.mutable.primitive.IntLongHashMap;
import org.eclipse.collections.impl.map.mutable.primitive.IntObjectHashMap;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SingleUserReport 现货借贷 loan 字段的序列化 round-trip（writeMarshallable → merge/read），
 * 护栏手写的 marshalling 字段顺序 —— 逐仓 / 全仓 / 账户级抵押 / 账户 LTV。
 */
class SingleUserReportLoanTest {

    @Test
    void loanFields_surviveSerializationRoundTrip() {
        SingleUserReportResult.IsolatedLoan iso = new SingleUserReportResult.IsolatedLoan(
            111L, 100, 1, 2, 500, 1_700_000_000_000L, 3L, 30_000L, 12L, 15L, 6000L, 50_000L);
        SingleUserReportResult.CrossLoan cross = new SingleUserReportResult.CrossLoan(
            222L, 100, 2, 300, 1_700_000_000_001L, 20_000L, 7L, 9L);
        IntLongHashMap collateral = IntLongHashMap.newWithKeysValues(1, 5L, 2, 100L);

        SingleUserReportResult parsed = roundTrip(SingleUserReportResult.createFromRiskEngineFound(
            42L, UserStatus.ACTIVE, new IntLongHashMap(), new IntLongHashMap(), new IntObjectHashMap<>(),
            Arrays.asList(iso), Arrays.asList(cross), collateral, 7200L));

        List<SingleUserReportResult.IsolatedLoan> isoLoans = parsed.getIsolatedLoans();
        assertEquals(1, isoLoans.size());
        SingleUserReportResult.IsolatedLoan i = isoLoans.get(0);
        assertEquals(111L, i.getLoanId());
        assertEquals(100, i.getSymbolId());
        assertEquals(1, i.getCollateralCurrency());
        assertEquals(2, i.getLoanCurrency());
        assertEquals(500, i.getRateBps());
        assertEquals(1_700_000_000_000L, i.getOpenedAtTs());
        assertEquals(3L, i.getCollateralAmount());
        assertEquals(30_000L, i.getOutstandingPrincipal());
        assertEquals(12L, i.getAccumulatedInterest());
        assertEquals(15L, i.getDisplayInterest());
        assertEquals(6000L, i.getLtvBps());
        assertEquals(50_000L, i.getMarkPrice());

        List<SingleUserReportResult.CrossLoan> crossLoans = parsed.getCrossLoans();
        assertEquals(1, crossLoans.size());
        SingleUserReportResult.CrossLoan c = crossLoans.get(0);
        assertEquals(222L, c.getLoanId());
        assertEquals(100, c.getSymbolId());
        assertEquals(2, c.getLoanCurrency());
        assertEquals(300, c.getRateBps());
        assertEquals(20_000L, c.getOutstandingPrincipal());
        assertEquals(7L, c.getAccumulatedInterest());
        assertEquals(9L, c.getDisplayInterest());

        assertEquals(5L, parsed.getCrossLoanCollateral().get(1));
        assertEquals(100L, parsed.getCrossLoanCollateral().get(2));
        assertEquals(7200L, parsed.getCrossAccountLtvBps());
    }

    @Test
    void emptyLoans_roundTripsToEmptyNotNull() {
        SingleUserReportResult parsed = roundTrip(SingleUserReportResult.createFromRiskEngineFound(
            42L, UserStatus.ACTIVE, new IntLongHashMap(), new IntLongHashMap(), new IntObjectHashMap<>(),
            Collections.emptyList(), Collections.emptyList(), new IntLongHashMap(), 0L));

        assertNotNull(parsed.getIsolatedLoans());
        assertTrue(parsed.getIsolatedLoans().isEmpty());
        assertNotNull(parsed.getCrossLoans());
        assertTrue(parsed.getCrossLoans().isEmpty());
        assertTrue(parsed.getCrossLoanCollateral().isEmpty());
        assertEquals(0L, parsed.getCrossAccountLtvBps());
    }

    private static SingleUserReportResult roundTrip(SingleUserReportResult orig) {
        Bytes<?> buf = Bytes.allocateElasticOnHeap(512);
        orig.writeMarshallable(buf);
        return SingleUserReportResult.merge(Stream.<BytesIn>of(buf));
    }
}
