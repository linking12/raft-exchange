/*
 * Copyright 2019 Maksim Zheravin
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on
 * an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations under the License.
 */
package exchange.core2.core.processors.loan.rate;

import java.util.Objects;

import org.eclipse.collections.impl.map.mutable.primitive.IntLongHashMap;

import exchange.core2.core.common.LoanRecord;
import exchange.core2.core.common.StateHash;
import exchange.core2.core.processors.loan.LoanService;
import exchange.core2.core.utils.CoreArithmeticUtils;
import exchange.core2.core.utils.SerializationUtils;
import lombok.Getter;
import lombok.Setter;
import net.openhft.chronicle.bytes.BytesIn;
import net.openhft.chronicle.bytes.BytesOut;
import net.openhft.chronicle.bytes.WriteBytesMarshallable;

/**
 * 浮动利率引擎（Flexible/活期）：kinked 曲线 + reprice 生效利率 + 累加器计息。适用于 Isolated FLOATING 及全部 Cross
 * 借贷，也是 {@link FixedRateModel} 开仓锁率的来源。per-shard 复制状态，见 loan.md §13。
 *
 * <p>计息用"累加器差值法"（loan.md §13.5）：{@code accRateBpsMs} 按币种累积"利率 × 时间"，只在 reprice 时用旧生效利率
 * 结算 {@code [lastRepriceTs, tickTs)} 这段区间；两次 reprice 之间的即时值由 {@link #liveAccRateBpsMs} 用当前生效利率外推得到。
 * 每笔 loan 记一个 accSnapshot（开仓或上次结息时的累加器读数），欠息 = (liveAcc − accSnapshot) 换算成本金对应的利息——
 * 这样计息与 loan 笔数无关，一次 reprice 即对所有持仓同时生效。
 */
@Getter
@Setter
public final class FloatingRateModel implements WriteBytesMarshallable, StateHash {
    public static final int DEFAULT_BASE_BPS = 200; // 零利用率 2%
    public static final int DEFAULT_KINK_UTIL_BPS = 8000; // 拐点 80%
    public static final int DEFAULT_SLOPE1_BPS = 400; // 0→kink 增幅
    public static final int DEFAULT_SLOPE2_BPS = 6000; // kink→100% 陡增幅

    private int baseBps; // 零利用率基础利率
    private int kinkUtilBps; // 利用率拐点
    private int slope1Bps; // 拐点前斜率
    private int slope2Bps; // 拐点后斜率
    private final IntLongHashMap currentRateBps; // 每币种当前生效利率（reprice 写入）
    private final IntLongHashMap accRateBpsMs; // 每币种计息累加器：截至 lastRepriceTs 累积的"利率 × 时间"
    private long lastRepriceTs; // 上次 reprice 时刻（ms）；≤0 表示尚未 reprice 过（冷启动）

    public FloatingRateModel() {
        this.baseBps = DEFAULT_BASE_BPS;
        this.kinkUtilBps = DEFAULT_KINK_UTIL_BPS;
        this.slope1Bps = DEFAULT_SLOPE1_BPS;
        this.slope2Bps = DEFAULT_SLOPE2_BPS;
        this.currentRateBps = new IntLongHashMap();
        this.accRateBpsMs = new IntLongHashMap();
        this.lastRepriceTs = 0L;
    }

    public FloatingRateModel(BytesIn bytes) {
        this.baseBps = bytes.readInt();
        this.kinkUtilBps = bytes.readInt();
        this.slope1Bps = bytes.readInt();
        this.slope2Bps = bytes.readInt();
        this.currentRateBps = SerializationUtils.readIntLongHashMap(bytes);
        this.accRateBpsMs = SerializationUtils.readIntLongHashMap(bytes);
        this.lastRepriceTs = bytes.readLong();
    }

    /** 利用率（bps）= borrowed / (borrowed + available)；空池返 0。 */
    public static long utilizationBps(long borrowed, long available) {
        long total = borrowed + available;
        return total <= 0 ? 0L : CoreArithmeticUtils.truncMulDiv(borrowed, LoanService.BPS_SCALE, total);
    }

    /** kinked 曲线：util（clamp 到 [0, BPS]）过曲线得 rateBps，纯整数。 */
    public static long curveRateBps(long utilBps, int baseBps, int kinkUtilBps, int slope1Bps, int slope2Bps) {
        long util = utilBps < 0 ? 0 : Math.min(utilBps, LoanService.BPS_SCALE);
        if (util <= kinkUtilBps) {
            return baseBps + (kinkUtilBps <= 0 ? 0 : (long)slope1Bps * util / kinkUtilBps);
        }
        long denom = LoanService.BPS_SCALE - kinkUtilBps;
        return baseBps + slope1Bps + (denom <= 0 ? 0 : (long)slope2Bps * (util - kinkUtilBps) / denom);
    }

    public long curveRateBps(long utilBps) {
        return curveRateBps(utilBps, baseBps, kinkUtilBps, slope1Bps, slope2Bps);
    }

    /** 某币种当前利率；未 reprice 过时回退 baseBps（= curveRateBps(0)，冷启动兜底）。 */
    public int currentRateBpsOrBase(int currency) {
        return currentRateBps.containsKey(currency) ? (int)currentRateBps.get(currency) : baseBps;
    }

    /**
     * reprice 前半步：用旧生效利率把 {@code [lastRepriceTs, tickTs)} 这段区间计入 accRateBpsMs 累加器。
     * 必须先于 {@link #repriceCurrency} 调用，否则会用新利率错误结算旧区间；调用方在同一次 reprice 处理完所有币种后
     * 才整体推进 lastRepriceTs（见 RiskEngine），故这里读到的仍是推进前的值。
     */
    public void advanceAccumulator(int currency, long tickTs) {
        if (lastRepriceTs > 0 && tickTs > lastRepriceTs) {
            long elapsed = tickTs - lastRepriceTs;
            accRateBpsMs.addToValue(currency, (long)currentRateBpsOrBase(currency) * elapsed);
        }
    }

    /** reprice 后半步：util 过曲线写入 currentRateBps，成为新生效利率。 */
    public void repriceCurrency(int currency, long utilBps) {
        currentRateBps.put(currency, curveRateBps(utilBps));
    }

    public int openRateBps(int loanCurrency) {
        return currentRateBpsOrBase(loanCurrency);
    }

    /** 累加器实时值 = accRateBpsMs + currentRate × (now − lastRepriceTs)，用当前生效利率把上次 reprice 之后的区间外推到 now；冷启动（lastRepriceTs≤0）不外推。 */
    public long liveAccRateBpsMs(int currency, long now) {
        long acc = accRateBpsMs.get(currency);
        long elapsed = now - lastRepriceTs;
        return (lastRepriceTs <= 0 || elapsed <= 0) ? acc : acc + (long)currentRateBpsOrBase(currency) * elapsed;
    }

    /** 开仓：accSnapshot 定在当前 liveAcc，此后只计从此刻起新增的利息。 */
    public void initOpenSnapshot(LoanRecord loan, long now) {
        loan.setAccSnapshot(liveAccRateBpsMs(loan.getLoanCurrency(), now));
    }

    /** 写路径：按累加器差值补计利息到 now，推进 accSnapshot；返回本次新增利息（≥ 0）。 */
    public long accrue(LoanRecord loan, long now) {
        long live = liveAccRateBpsMs(loan.getLoanCurrency(), now);
        long delta = pending(loan, live);
        if (delta > 0) {
            loan.setAccumulatedInterest(Math.addExact(loan.getAccumulatedInterest(), delta));
        }
        // 有本金且累加器确有推进(deltaAcc>0)却因截断得 0 时保留 snapshot，让亚阈值增量继续累积到跨过阈值再计；
        // 否则高频 accrue 会把每段利息永久吞掉（F1，同 FixedRateModel）。已计息/无本金/累加器未动 → 照常推进。
        long deltaAcc = live - loan.getAccSnapshot();
        boolean truncatedButChargeable = delta == 0 && loan.getOutstandingPrincipal() > 0 && deltaAcc > 0;
        if (!truncatedButChargeable) {
            loan.setAccSnapshot(live);
        }
        return delta;
    }

    /** 读路径：accumulatedInterest + 到 now 的 pending，不改 loan。 */
    public long displayInterest(LoanRecord loan, long now) {
        return Math.addExact(loan.getAccumulatedInterest(),
            pending(loan, liveAccRateBpsMs(loan.getLoanCurrency(), now)));
    }

    /** pending = (liveAcc − accSnapshot) 对应的利率·时间增量，换算成本金对应的利息；deltaAcc≤0 或无本金则免息。 */
    private static long pending(LoanRecord loan, long liveAcc) {
        long deltaAcc = liveAcc - loan.getAccSnapshot();
        if (deltaAcc <= 0 || loan.getOutstandingPrincipal() <= 0) {
            return 0L;
        }
        return CoreArithmeticUtils.truncMulDiv(deltaAcc, loan.getOutstandingPrincipal(),
            LoanService.YEAR_MS * LoanService.BPS_SCALE);
    }

    public void reset() {
        baseBps = DEFAULT_BASE_BPS;
        kinkUtilBps = DEFAULT_KINK_UTIL_BPS;
        slope1Bps = DEFAULT_SLOPE1_BPS;
        slope2Bps = DEFAULT_SLOPE2_BPS;
        currentRateBps.clear();
        accRateBpsMs.clear();
        lastRepriceTs = 0L;
    }

    @Override
    public void writeMarshallable(BytesOut bytes) {
        bytes.writeInt(baseBps);
        bytes.writeInt(kinkUtilBps);
        bytes.writeInt(slope1Bps);
        bytes.writeInt(slope2Bps);
        SerializationUtils.marshallIntLongHashMap(currentRateBps, bytes);
        SerializationUtils.marshallIntLongHashMap(accRateBpsMs, bytes);
        bytes.writeLong(lastRepriceTs);
    }

    @Override
    public int stateHash() {
        return Objects.hash(baseBps, kinkUtilBps, slope1Bps, slope2Bps, currentRateBps.hashCode(),
            accRateBpsMs.hashCode(), lastRepriceTs);
    }
}
