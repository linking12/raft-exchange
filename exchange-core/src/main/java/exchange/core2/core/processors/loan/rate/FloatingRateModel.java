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
 * Floating（Flexible / 活期）利率实现 = <b>动态利率引擎</b>。适用 Isolated FLOATING + 全部 Cross；也是 {@link FixedRateModel}
 * 的利率来源（Fixed = 开仓锁本引擎的当前利率 + 点差）。per-shard 复制状态。
 *
 * <p>
 * 承载 kinked 曲线 + reprice 生效利率 {@code currentRateBps[ccy]} + 累加器 {@code accRateBpsMs[ccy]}； 计息按累加器差值（利率随 reprice 变），O(1)
 * 惰性、整数精确。自包含序列化 / stateHash。
 */
public final class FloatingRateModel implements WriteBytesMarshallable, StateHash {

    // --- kinked 曲线默认值（全局单曲线；后续可扩每币种）---
    public static final int DEFAULT_BASE_BPS = 200; // 零利用率基础利率 2%
    public static final int DEFAULT_KINK_UTIL_BPS = 8000; // 最优利用率拐点 80%
    public static final int DEFAULT_SLOPE1_BPS = 400; // 0→kink 增幅（至拐点 6%）
    public static final int DEFAULT_SLOPE2_BPS = 6000; // kink→100% 陡增幅（至满 66%）

    // --- 曲线参数 ---
    @Getter
    @Setter
    private int baseBps;
    @Getter
    @Setter
    private int kinkUtilBps;
    @Getter
    @Setter
    private int slope1Bps;
    @Getter
    @Setter
    private int slope2Bps;

    // --- reprice 运行态（REPRICE_LOAN_RATES 每 tick 刷新，各 shard 相同）---
    // 每币种当前曲线利率（bps）= 活期利率
    @Getter
    private final IntLongHashMap currentRateBps;
    // 每币种累积利率·时间（bps·ms），惰性计息累加器
    @Getter
    private final IntLongHashMap accRateBpsMs;
    // 上次 reprice tick 时刻（ms），liveAcc 参照
    @Getter
    @Setter
    private long lastRepriceTs;

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

    // ================================================================
    // 曲线（利用率 → 利率）
    // ================================================================

    /** 利用率（bps）= borrowed / (borrowed + available)；空池返 0。truncMulDiv 防 borrowed×BPS 溢出。 */
    public static long utilizationBps(long borrowed, long available) {
        long total = borrowed + available;
        if (total <= 0) {
            return 0L;
        }
        return CoreArithmeticUtils.truncMulDiv(borrowed, LoanService.BPS_SCALE, total);
    }

    /** kinked 利率曲线：util → rateBps。util clamp 到 [0, BPS]；kink 落在 (0, BPS)。纯整数。 */
    public static long curveRateBps(long utilBps, int baseBps, int kinkUtilBps, int slope1Bps, int slope2Bps) {
        long util = utilBps < 0 ? 0 : Math.min(utilBps, LoanService.BPS_SCALE);
        if (util <= kinkUtilBps) {
            long inc = kinkUtilBps <= 0 ? 0 : (long)slope1Bps * util / kinkUtilBps;
            return baseBps + inc;
        }
        long denom = LoanService.BPS_SCALE - kinkUtilBps;
        long inc = denom <= 0 ? 0 : (long)slope2Bps * (util - kinkUtilBps) / denom;
        return baseBps + slope1Bps + inc;
    }

    public long curveRateBps(long utilBps) {
        return curveRateBps(utilBps, baseBps, kinkUtilBps, slope1Bps, slope2Bps);
    }

    /** 某币种当前利率（bps）；未 reprice → 回退 baseBps（= curveRateBps(0)，冷启动兜底）。Fixed 开仓、Floating 开仓/展示共用。 */
    public int currentRateBpsOrBase(int currency) {
        return currentRateBps.containsKey(currency) ? (int) currentRateBps.get(currency) : baseBps;
    }

    // ================================================================
    // reprice（每 tick）：先用旧率推累加器，再写新率
    // ================================================================

    /** 用**旧** currentRate 把累加器推进 [lastRepriceTs, tickTs]。调用方随后 repriceCurrency + setLastRepriceTs。 */
    public void advanceAccumulator(int currency, long tickTs) {
        if (lastRepriceTs > 0 && tickTs > lastRepriceTs) {
            accRateBpsMs.addToValue(currency, (long)currentRateBpsOrBase(currency) * (tickTs - lastRepriceTs));
        }
    }

    /** 某币种利用率过曲线、写新生效利率。 */
    public void repriceCurrency(int currency, long utilBps) {
        currentRateBps.put(currency, curveRateBps(utilBps));
    }

    // ================================================================
    // 计息（累加器差值法：利率随 reprice 变，计息只看两次 accSnapshot 之间的累积利率·时间）
    // liveAcc = accRateBpsMs + currentRate × (now − lastRepriceTs)（冷启动 lastRepriceTs≤0 → 不累积）
    // interest = principal × (liveAcc − loan.accSnapshot) / (YEAR_MS × BPS_SCALE)，truncMulDiv 防溢出
    // ================================================================

    /** 开仓利率 = 当前活期利率。 */
    public int openRateBps(int loanCurrency) {
        return currentRateBpsOrBase(loanCurrency);
    }

    public long liveAccRateBpsMs(int currency, long now) {
        long acc = accRateBpsMs.get(currency);
        if (lastRepriceTs <= 0) {
            return acc;
        }
        long elapsed = now - lastRepriceTs;
        return elapsed <= 0 ? acc : acc + (long)currentRateBpsOrBase(currency) * elapsed;
    }

    /** 开仓：把 loan.accSnapshot 定在当前 liveAcc，从此点起累积。 */
    public void initOpenSnapshot(LoanRecord loan, long now) {
        loan.setAccSnapshot(liveAccRateBpsMs(loan.getLoanCurrency(), now));
    }

    /** 写路径：按累加器差值补计利息到 now，累加进 accumulatedInterest 并推进 accSnapshot；返回本次新增利息（≥ 0）。 */
    public long accrue(LoanRecord loan, long now) {
        long live = liveAccRateBpsMs(loan.getLoanCurrency(), now);
        long delta = pending(loan, live);
        if (delta > 0) {
            loan.setAccumulatedInterest(Math.addExact(loan.getAccumulatedInterest(), delta));
        }
        loan.setAccSnapshot(live);
        return delta;
    }

    /** 读路径：accumulatedInterest + 到 now 的 pending 利息，不改 loan。 */
    public long displayInterest(LoanRecord loan, long now) {
        return Math.addExact(loan.getAccumulatedInterest(),
            pending(loan, liveAccRateBpsMs(loan.getLoanCurrency(), now)));
    }

    private static long pending(LoanRecord loan, long liveAcc) {
        long deltaAcc = liveAcc - loan.getAccSnapshot();
        if (deltaAcc <= 0 || loan.getOutstandingPrincipal() <= 0) {
            return 0L;
        }
        return CoreArithmeticUtils.truncMulDiv(deltaAcc, loan.getOutstandingPrincipal(),
            LoanService.YEAR_MS * LoanService.BPS_SCALE);
    }

    // ================================================================
    // 序列化 / reset / stateHash
    // ================================================================

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
