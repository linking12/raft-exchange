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
package exchange.core2.core.common;

import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.openhft.chronicle.bytes.BytesIn;
import net.openhft.chronicle.bytes.BytesOut;
import net.openhft.chronicle.bytes.WriteBytesMarshallable;

import java.util.Objects;

/**
 * Cross 单笔债务凭证，挂在 {@link UserProfile#crossLoans}。无抵押字段 —— Cross 抵押是账户级的，多笔 debt 共享
 * {@link UserProfile#crossLoanCollateral} 池。对象池复用，identity 非 final。
 */
@Slf4j
@NoArgsConstructor
public final class CrossLoanRecord implements WriteBytesMarshallable, StateHash, LoanRecord {

    public long uid; // 所属用户（上下文注入，不序列化，仅进 stateHash）
    public long loanId; // 客户端提供，per-user 唯一，创建时锁死

    public int symbolId; // 匹配的现货 pair，scanner 直接 getSymbolSpecification 拿 term/spec
    public int loanCurrency; // = 该 pair 的 quoteCurrency

    public int rateBps; // 借入时锁定的年化利率（bps），存续期不变
    public long openedAtTs; // 开仓时间戳（ms），期限校验用

    public long outstandingPrincipal; // 剩余未偿本金（loanCurrency）
    public long accumulatedInterest; // 已计提未付利息（loanCurrency），结算时进 interestRevenue
    public long lastAccrueTs; // 上次计息时间戳（ms），初始 = openedAtTs（Cross 恒 FLOATING，保留兼容）
    public long accSnapshot; // FLOATING 计息游标：上次 accrue 的 liveAcc 快照（bps·ms）。见 loan.md §13.6
    public int stuckLiqAttempts; // 连续零成交强平次数；驱动 scanner 容差爬梯 + 卡单告警

    public CrossLoanRecord(long uid, long loanId, int loanCurrency, int rateBps, long openedAtTs) {
        initialize(uid, loanId, loanCurrency, rateBps, openedAtTs);
    }

    public CrossLoanRecord(long uid, BytesIn bytes) {
        this.uid = uid;
        this.loanId = bytes.readLong();
        this.symbolId = bytes.readInt();
        this.loanCurrency = bytes.readInt();
        this.rateBps = bytes.readInt();
        this.openedAtTs = bytes.readLong();
        this.outstandingPrincipal = bytes.readLong();
        this.accumulatedInterest = bytes.readLong();
        this.lastAccrueTs = bytes.readLong();
        this.accSnapshot = bytes.readLong();
        this.stuckLiqAttempts = bytes.readInt();
    }

    /** 从对象池拿到 record 后必须先 initialize 重置 identity + 可变状态。 */
    public void initialize(long uid, long loanId, int loanCurrency, int rateBps, long openedAtTs) {
        this.uid = uid;
        this.loanId = loanId;
        this.symbolId = 0; // 由 handleLoanCrossBorrow 在 initialize 后写入 spec.symbolId
        this.loanCurrency = loanCurrency;
        this.rateBps = rateBps;
        this.openedAtTs = openedAtTs;
        this.outstandingPrincipal = 0;
        this.accumulatedInterest = 0;
        this.lastAccrueTs = openedAtTs;
        this.accSnapshot = 0;
        this.stuckLiqAttempts = 0;
    }

    public boolean isEmpty() {
        return outstandingPrincipal == 0 && accumulatedInterest == 0;
    }

    @Override
    public int getLoanCurrency() {
        return loanCurrency;
    }

    @Override
    public int getRateBps() {
        return rateBps;
    }

    @Override
    public long getOutstandingPrincipal() {
        return outstandingPrincipal;
    }

    @Override
    public void setOutstandingPrincipal(long value) {
        this.outstandingPrincipal = value;
    }

    @Override
    public long getAccumulatedInterest() {
        return accumulatedInterest;
    }

    @Override
    public void setAccumulatedInterest(long value) {
        this.accumulatedInterest = value;
    }

    @Override
    public long getLastAccrueTs() {
        return lastAccrueTs;
    }

    @Override
    public void setLastAccrueTs(long value) {
        this.lastAccrueTs = value;
    }

    @Override
    public long getAccSnapshot() {
        return accSnapshot;
    }

    @Override
    public void setAccSnapshot(long value) {
        this.accSnapshot = value;
    }

    @Override
    public boolean isFixedRate() {
        return false;
    } // Cross 恒 Floating

    @Override
    public int getStuckLiqAttempts() {
        return stuckLiqAttempts;
    }

    @Override
    public void setStuckLiqAttempts(int value) {
        this.stuckLiqAttempts = value;
    }

    public void validateInternalState() {
        if (outstandingPrincipal < 0 || accumulatedInterest < 0) {
            log.error("uid {} loanId {} : negative amount principal={} interest={}", uid, loanId, outstandingPrincipal,
                accumulatedInterest);
            throw new IllegalStateException();
        }
    }

    @Override
    public void writeMarshallable(BytesOut bytes) {
        bytes.writeLong(loanId);
        bytes.writeInt(symbolId);
        bytes.writeInt(loanCurrency);
        bytes.writeInt(rateBps);
        bytes.writeLong(openedAtTs);
        bytes.writeLong(outstandingPrincipal);
        bytes.writeLong(accumulatedInterest);
        bytes.writeLong(lastAccrueTs);
        bytes.writeLong(accSnapshot);
        bytes.writeInt(stuckLiqAttempts);
    }

    @Override
    public int stateHash() {
        return Objects.hash(uid, loanId, symbolId, loanCurrency, rateBps, openedAtTs, outstandingPrincipal,
            accumulatedInterest, lastAccrueTs, accSnapshot, stuckLiqAttempts);
    }

    @Override
    public String toString() {
        return "CrossLoan{" + "u" + uid + " id" + loanId + " sym" + symbolId + " loanCur" + loanCurrency + " rate"
            + rateBps + " openedAt" + openedAtTs + " prin=" + outstandingPrincipal + " int=" + accumulatedInterest
            + " lastAccrue=" + lastAccrueTs + " accSnap=" + accSnapshot + '}';
    }
}
