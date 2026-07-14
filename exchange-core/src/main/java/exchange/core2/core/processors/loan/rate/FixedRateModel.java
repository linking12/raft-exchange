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

import exchange.core2.core.common.LoanRecord;
import exchange.core2.core.common.StateHash;
import exchange.core2.core.processors.loan.LoanService;
import exchange.core2.core.utils.CoreArithmeticUtils;
import lombok.Getter;
import lombok.Setter;
import net.openhft.chronicle.bytes.BytesIn;
import net.openhft.chronicle.bytes.BytesOut;
import net.openhft.chronicle.bytes.WriteBytesMarshallable;

/**
 * Fixed（Lock / 定期）利率实现，仅适用 Isolated LOCKED。
 *
 * <p>
 * Fixed = <b>开仓锁定 {@link FloatingRateModel} 当前利率 + 点差</b>，之后不变；计息走线性（利率恒为开仓锁进 loan.rateBps 的值）。
 * 独有状态只有 {@code lockedRateAdjustBps}，利率来源从 floating 引擎读。
 */
public final class FixedRateModel implements WriteBytesMarshallable, StateHash {

    private final FloatingRateModel floating;

    // Fixed 相对曲线的加/减价（bps），默认 0 = 与 Floating 同价
    @Getter
    @Setter
    private int lockedRateAdjustBps;

    public FixedRateModel(FloatingRateModel floating) {
        this.floating = floating;
        this.lockedRateAdjustBps = 0;
    }

    public FixedRateModel(FloatingRateModel floating, BytesIn bytes) {
        this.floating = floating;
        this.lockedRateAdjustBps = bytes.readInt();
    }

    /** Fixed 开仓利率 = floating 当前利率（未 reprice 回退 base）+ lockedRateAdjustBps，下限 0。锁进 loan.rateBps。 */
    public int openRateBps(int loanCurrency) {
        return (int)Math.max(0L, (long)floating.currentRateBpsOrBase(loanCurrency) + lockedRateAdjustBps);
    }

    // ================================================================
    // 计息（线性，利率恒 loan.rateBps 锁定值；now < lastAccrueTs 视为 0 elapsed）
    // interest = elapsed_ms × principal × rateBps / (YEAR_MS × BPS_SCALE)，两次 truncMulDiv 防溢出
    // ================================================================

    /** 写路径：补计利息到 now，累加进 accumulatedInterest 并推进 lastAccrueTs；返回本次新增利息（≥ 0）。 */
    public long accrue(LoanRecord loan, long now) {
        long delta = accrueDelta(loan.getOutstandingPrincipal(), loan.getRateBps(), loan.getLastAccrueTs(), now);
        if (delta > 0) {
            loan.setAccumulatedInterest(Math.addExact(loan.getAccumulatedInterest(), delta));
        }
        if (now > loan.getLastAccrueTs()) {
            loan.setLastAccrueTs(now);
        }
        return delta;
    }

    /** 读路径：accumulatedInterest + 到 now 的 pending 利息，不改 loan。 */
    public long displayInterest(LoanRecord loan, long now) {
        long pending = accrueDelta(loan.getOutstandingPrincipal(), loan.getRateBps(), loan.getLastAccrueTs(), now);
        return Math.addExact(loan.getAccumulatedInterest(), pending);
    }

    private static long accrueDelta(long outstandingPrincipal, int rateBps, long lastAccrueTs, long now) {
        if (outstandingPrincipal <= 0 || rateBps <= 0) {
            return 0L;
        }
        long elapsed = now - lastAccrueTs;
        if (elapsed <= 0) {
            return 0L;
        }
        long step1 = CoreArithmeticUtils.truncMulDiv(elapsed, outstandingPrincipal, LoanService.YEAR_MS);
        return CoreArithmeticUtils.truncMulDiv(step1, rateBps, LoanService.BPS_SCALE);
    }

    public void reset() {
        lockedRateAdjustBps = 0;
    }

    @Override
    public void writeMarshallable(BytesOut bytes) {
        bytes.writeInt(lockedRateAdjustBps);
    }

    @Override
    public int stateHash() {
        return Objects.hash(lockedRateAdjustBps);
    }
}
