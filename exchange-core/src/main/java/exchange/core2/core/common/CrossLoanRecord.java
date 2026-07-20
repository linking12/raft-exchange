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

    // ── 身份
    public long uid; // 所属用户（上下文注入，不序列化，仅进 stateHash）
    public long loanId; // 客户端提供，per-user 唯一，创建时锁死

    // ── 开仓条款：存续期不变
    public int symbolId; // 匹配的现货 pair，scanner 据此取 spec
    public int loanCurrency; // = 该 pair 的 quoteCurrency
    public int rateBps; // 借入时锁定的年化利率（bps）
    public long openedAtTs; // 开仓时间戳（ms），期限校验用

    // ── 债务：随借还、计息、强平变动。抵押不在此处——Cross 抵押是账户级的，见 UserProfile#crossLoanCollateral
    public long outstandingPrincipal; // 剩余未偿本金（loanCurrency）
    public long accumulatedInterest; // 已计提未付利息（loanCurrency），结算时进 interestRevenue
    public long lastAccrueTs; // 上次计息时间戳（ms），初始 = openedAtTs；Cross 恒 FLOATING，此游标不参与计息
    public long accSnapshot; // FLOATING 计息游标：上次 accrue 的 liveAcc 快照（bps·ms）

    // ── 累计量：FundEvent 只发快照，本次量由下游相邻两条相减得出
    public long cumInterestPaid; // 累计已付利息（loanCurrency）

    public CrossLoanRecord(long uid, long loanId, int symbolId, int loanCurrency, int rateBps, long openedAtTs) {
        initialize(uid, loanId, symbolId, loanCurrency, rateBps, openedAtTs);
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
        this.cumInterestPaid = bytes.readLong();
    }

    /** 从对象池拿到 record 后必须先 initialize 重置 identity + 可变状态。 */
    public void initialize(long uid, long loanId, int symbolId, int loanCurrency, int rateBps, long openedAtTs) {
        this.uid = uid;
        this.loanId = loanId;
        this.symbolId = symbolId;
        this.loanCurrency = loanCurrency;
        this.rateBps = rateBps;
        this.openedAtTs = openedAtTs;
        this.outstandingPrincipal = 0;
        this.accumulatedInterest = 0;
        this.lastAccrueTs = openedAtTs;
        this.accSnapshot = 0;
        this.cumInterestPaid = 0;
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

    public void validateInternalState() {
        if (outstandingPrincipal < 0 || accumulatedInterest < 0) {
            log.error("uid {} loanId {} : negative amount principal={} interest={}", uid, loanId, outstandingPrincipal,
                accumulatedInterest);
            throw new IllegalStateException();
        }
    }

    @Override
    public long getCumInterestPaid() {
        return cumInterestPaid;
    }

    @Override
    public void setCumInterestPaid(long value) {
        this.cumInterestPaid = value;
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
        bytes.writeLong(cumInterestPaid);
    }

    @Override
    public int stateHash() {
        return Objects.hash(uid, loanId, symbolId, loanCurrency, rateBps, openedAtTs, outstandingPrincipal,
            accumulatedInterest, lastAccrueTs, accSnapshot, cumInterestPaid);
    }

    @Override
    public String toString() {
        return "CrossLoan{" + "u" + uid + " id" + loanId + " sym" + symbolId + " loanCur" + loanCurrency + " rate"
            + rateBps + " openedAt" + openedAtTs + " prin=" + outstandingPrincipal + " int=" + accumulatedInterest
            + " lastAccrue=" + lastAccrueTs + " accSnap=" + accSnapshot
            + " cumIntPaid=" + cumInterestPaid + '}';
    }
}
