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

import exchange.core2.core.processors.loan.LoanService;
import exchange.core2.core.processors.loan.rate.FloatingRateModel;
import net.openhft.chronicle.bytes.Bytes;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 动态利率定价纯函数（loan.md §13.2/§13.3）+ 新增利率状态的序列化 round-trip。
 * 曲线逻辑随 as-built 设计并入 {@link FloatingRateModel}（无独立 LoanRateCurve 类，见 §13.1）。
 * kinked 曲线默认参数：base=200 / kink=8000 / slope1=400 / slope2=6000。
 */
class LoanRateCurveTest {

    private static final int BASE = FloatingRateModel.DEFAULT_BASE_BPS;   // 200
    private static final int KINK = FloatingRateModel.DEFAULT_KINK_UTIL_BPS; // 8000
    private static final int S1 = FloatingRateModel.DEFAULT_SLOPE1_BPS;   // 400
    private static final int S2 = FloatingRateModel.DEFAULT_SLOPE2_BPS;   // 6000

    private static long curve(long util) {
        return FloatingRateModel.curveRateBps(util, BASE, KINK, S1, S2);
    }

    @Test
    void curve_atZeroUtil_isBase() {
        assertEquals(200L, curve(0));
    }

    @Test
    void curve_belowKink_linearOnSlope1() {
        // util=4000（拐点一半）→ 200 + 400×4000/8000 = 400
        assertEquals(400L, curve(4000));
    }

    @Test
    void curve_atKink_isBasePlusSlope1() {
        // util=8000 → 200 + 400 = 600（两分支在拐点连续）
        assertEquals(600L, curve(KINK));
    }

    @Test
    void curve_aboveKink_steepOnSlope2() {
        // util=9000 → 600 + 6000×(9000−8000)/(10000−8000) = 600 + 3000 = 3600
        assertEquals(3600L, curve(9000));
    }

    @Test
    void curve_atFullUtil_isBasePlusBothSlopes() {
        // util=10000 → 200 + 400 + 6000 = 6600
        assertEquals(6600L, curve(10000));
    }

    @Test
    void curve_clampsOutOfRangeUtil() {
        assertEquals(200L, curve(-5), "负 util clamp 到 0");
        assertEquals(6600L, curve(20000), "超 100% clamp 到 BPS");
    }

    @Test
    void curve_kinkZero_wholeRangeIsSlope2() {
        // kink=0：util>0 全走 slope2 段。util=5000 → 100 + 400 + 600×5000/10000 = 800
        assertEquals(800L, FloatingRateModel.curveRateBps(5000, 100, 0, 400, 600));
    }

    @Test
    void utilization_basic() {
        assertEquals(0L, FloatingRateModel.utilizationBps(0, 0), "空池");
        assertEquals(3000L, FloatingRateModel.utilizationBps(30, 70), "30/(30+70)=30%");
        assertEquals(10000L, FloatingRateModel.utilizationBps(100, 0), "全借出=100%");
    }

    @Test
    void floatingModel_openRate_fallsBackToBaseWhenUnpriced() {
        LoanService svc = new LoanService();
        assertEquals(200, svc.getFloatingRate().openRateBps(2), "未 reprice → 回退曲线 base=200");
        svc.getFloatingRate().getCurrentRateBps().put(2, 555L);
        assertEquals(555, svc.getFloatingRate().openRateBps(2), "已 reprice → 用生效值");
    }

    @Test
    void fixedModel_openRate_appliesAdjustWithFloor() {
        LoanService svc = new LoanService();
        svc.getFloatingRate().getCurrentRateBps().put(2, 500L);
        assertEquals(500, svc.getFixedRate().openRateBps(2), "adjust=0 → 同 Floating");
        svc.getFixedRate().setLockedRateAdjustBps(50);
        assertEquals(550, svc.getFixedRate().openRateBps(2), "Fixed = Floating + adjust");
        svc.getFixedRate().setLockedRateAdjustBps(-600);
        assertEquals(0, svc.getFixedRate().openRateBps(2), "减穿则封底 0");
    }

    @Test
    void serialization_roundTrips_rateSubsystem() {
        LoanService orig = new LoanService();
        orig.getFloatingRate().getCurrentRateBps().put(2, 480L);
        orig.getFloatingRate().getCurrentRateBps().put(5, 3600L);
        orig.getFloatingRate().setBaseBps(150);
        orig.getFloatingRate().setKinkUtilBps(7500);
        orig.getFloatingRate().setSlope1Bps(350);
        orig.getFloatingRate().setSlope2Bps(5000);
        orig.getFixedRate().setLockedRateAdjustBps(-25);
        orig.getFloatingRate().setLastRepriceTs(1_700_000_000_000L);
        orig.getFloatingRate().getAccRateBpsMs().put(2, 987_654L);

        Bytes<?> buf = Bytes.allocateElasticOnHeap(512);
        orig.writeMarshallable(buf);
        LoanService parsed = new LoanService(buf);

        assertEquals(480L, parsed.getFloatingRate().getCurrentRateBps().get(2));
        assertEquals(3600L, parsed.getFloatingRate().getCurrentRateBps().get(5));
        assertEquals(150, parsed.getFloatingRate().getBaseBps());
        assertEquals(7500, parsed.getFloatingRate().getKinkUtilBps());
        assertEquals(350, parsed.getFloatingRate().getSlope1Bps());
        assertEquals(5000, parsed.getFloatingRate().getSlope2Bps());
        assertEquals(-25, parsed.getFixedRate().getLockedRateAdjustBps());
        assertEquals(1_700_000_000_000L, parsed.getFloatingRate().getLastRepriceTs());
        assertEquals(987_654L, parsed.getFloatingRate().getAccRateBpsMs().get(2));
        assertEquals(orig.stateHash(), parsed.stateHash(), "序列化 round-trip 后 stateHash 一致");
    }

    @Test
    void liveAcc_coldStart_returnsAccUnchanged() {
        // lastRepriceTs≤0（冷启动）：即使 now>0，也不叠加 currentRate×elapsed，原样返回 acc。
        FloatingRateModel m = new FloatingRateModel();
        m.getAccRateBpsMs().put(2, 987_654L);
        m.getCurrentRateBps().put(2, 555L); // 若误累积会污染结果
        m.setLastRepriceTs(0L);
        assertEquals(987_654L, m.liveAccRateBpsMs(2, 1_000_000L), "冷启动 now>0 仍返回 acc 原值");
    }

    @Test
    void liveAcc_nonPositiveElapsed_returnsAccUnchanged() {
        // lastRepriceTs>0 但 now≤lastRepriceTs（elapsed≤0）：同样原样返回 acc；now>last 才累积。
        FloatingRateModel m = new FloatingRateModel();
        m.getAccRateBpsMs().put(2, 987_654L);
        m.getCurrentRateBps().put(2, 555L);
        m.setLastRepriceTs(1_000L);
        assertEquals(987_654L, m.liveAccRateBpsMs(2, 1_000L), "elapsed=0 返回 acc 原值");
        assertEquals(987_654L, m.liveAccRateBpsMs(2, 500L), "elapsed<0 返回 acc 原值");
        // 对照：now>last 时正常累积 acc + 555×(2000−1000)。
        assertEquals(987_654L + 555L * 1_000L, m.liveAccRateBpsMs(2, 2_000L), "elapsed>0 正常累积");
    }

    @Test
    void advanceAccumulator_coldStart_isNoOp() {
        // lastRepriceTs≤0 守卫：acc 不动。
        FloatingRateModel m = new FloatingRateModel();
        m.getAccRateBpsMs().put(2, 100L);
        m.getCurrentRateBps().put(2, 555L);
        m.setLastRepriceTs(0L);
        m.advanceAccumulator(2, 5_000L);
        assertEquals(100L, m.getAccRateBpsMs().get(2), "冷启动 advance 为 no-op");
    }

    @Test
    void advanceAccumulator_tickNotAfterLastReprice_isNoOp() {
        // tickTs≤lastRepriceTs 守卫：acc 不动。
        FloatingRateModel m = new FloatingRateModel();
        m.getAccRateBpsMs().put(2, 100L);
        m.getCurrentRateBps().put(2, 555L);
        m.setLastRepriceTs(1_000L);
        m.advanceAccumulator(2, 1_000L); // 相等
        assertEquals(100L, m.getAccRateBpsMs().get(2), "tickTs=lastRepriceTs 为 no-op");
        m.advanceAccumulator(2, 500L); // 更早
        assertEquals(100L, m.getAccRateBpsMs().get(2), "tickTs<lastRepriceTs 为 no-op");
    }

    @Test
    void advanceAccumulator_positive_accumulatesRateTimesElapsed() {
        // lastRepriceTs>0 && tickTs>lastRepriceTs：acc += currentRate×(tickTs−lastRepriceTs)。
        FloatingRateModel m = new FloatingRateModel();
        m.getAccRateBpsMs().put(2, 100L);
        m.getCurrentRateBps().put(2, 555L);
        m.setLastRepriceTs(1_000L);
        m.advanceAccumulator(2, 3_000L);
        assertEquals(100L + 555L * 2_000L, m.getAccRateBpsMs().get(2), "累积 555×(3000−1000)");
    }

    @Test
    void curve_kinkAtBpsScale_noSlope2Segment() {
        // kink=BPS_SCALE(10000)：util clamp 到 ≤10000 恒 ≤kink，永不进入 slope2 段；
        // denom = BPS_SCALE − kink = 0 被守卫，结果为 base+slope1（无除零、无 slope2 贡献）。
        assertEquals(600L, FloatingRateModel.curveRateBps(10000, 200, 10000, 400, 6000), "kink=10000 满 util → base+slope1");
        assertEquals(600L, FloatingRateModel.curveRateBps(20000, 200, 10000, 400, 6000), "超范围 clamp 后仍 base+slope1");
    }

    @Test
    void utilization_overflowScalePath_usesTruncMulDiv128() {
        // borrowed×BPS_SCALE 溢出 64-bit fast path，走 truncMulDiv 的 128-bit 兜底仍得正确比值。
        long half = Long.MAX_VALUE / 2; // borrowed×10000 溢出
        assertEquals(5000L, FloatingRateModel.utilizationBps(half, half), "溢出 fallback 后 50% 利用率");
    }
}
