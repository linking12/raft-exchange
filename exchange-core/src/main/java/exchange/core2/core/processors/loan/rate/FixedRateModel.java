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
 * 定期利率模型（Fixed/Lock），仅用于 Isolated LOCKED：开仓时锁定 {@link FloatingRateModel} 当前利率 + 点差，
 * 此后利率不再变化，按固定利率线性计息。
 *
 * <p>唯一自有状态是 {@code lockedRateAdjustBps}（点差），利率基准仍读 floating 引擎的曲线值。见 loan.md §13。
 */
@Getter
@Setter
public final class FixedRateModel implements WriteBytesMarshallable, StateHash {
    private final FloatingRateModel floating;
    private int lockedRateAdjustBps; // 相对 floating 曲线的加/减价（bps），默认 0 = 与 floating 同价

    public FixedRateModel(FloatingRateModel floating) {
        this.floating = floating;
        this.lockedRateAdjustBps = 0;
    }

    public FixedRateModel(FloatingRateModel floating, BytesIn bytes) {
        this.floating = floating;
        this.lockedRateAdjustBps = bytes.readInt();
    }

    /** 开仓利率 = floating 当前利率（未 reprice 过则回退 base）+ lockedRateAdjustBps，下限 0；结果固化进 loan.rateBps，此后不再随 floating 变化。 */
    public int openRateBps(int loanCurrency) {
        long adjustedRateBps = (long)floating.currentRateBpsOrBase(loanCurrency) + lockedRateAdjustBps;
        return (int)Math.max(0L, adjustedRateBps);
    }

    /** 写路径：按 loan.rateBps 补计利息到 now，推进 lastAccrueTs；返回本次新增利息（≥ 0）。 */
    public long accrue(LoanRecord loan, long now) {
        long delta = accrueDelta(loan.getOutstandingPrincipal(), loan.getRateBps(), loan.getLastAccrueTs(), now);
        if (delta > 0) {
            loan.setAccumulatedInterest(Math.addExact(loan.getAccumulatedInterest(), delta));
        }
        // 只在“已计息(delta>0)”或“本就不可能计息(无本金/免息)”时推进游标；有本金有利率却因截断得 0 时保留游标，
        // 让被截断的 elapsed 继续累积到跨过精度阈值再计——否则高频 accrue（如反复 REPAY）会把每段亚阈值利息永久吞掉（F1）。
        if (now > loan.getLastAccrueTs()
            && (delta > 0 || loan.getOutstandingPrincipal() <= 0 || loan.getRateBps() <= 0)) {
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
        // 分两步 truncMulDiv：先 elapsed×principal/YEAR_MS 再 ×rateBps/BPS_SCALE，避免中间值溢出
        long interestBase = CoreArithmeticUtils.truncMulDiv(elapsed, outstandingPrincipal, LoanService.YEAR_MS);
        return CoreArithmeticUtils.truncMulDiv(interestBase, rateBps, LoanService.BPS_SCALE);
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
