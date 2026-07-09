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
package exchange.core2.core.common;

import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.openhft.chronicle.bytes.BytesIn;
import net.openhft.chronicle.bytes.BytesOut;
import net.openhft.chronicle.bytes.WriteBytesMarshallable;

import java.util.Objects;

/**
 * Cross 模式单笔债务凭证，挂在 {@link UserProfile#crossLoans}（loanId -> record）。<b>无抵押字段</b> ——
 * Cross 抵押是账户级的，多笔 debt 共享 {@link UserProfile#crossLoanCollateral} 多币种池。对象池复用，identity 非 final。
 */
@Slf4j
@NoArgsConstructor
public final class CrossLoanRecord implements WriteBytesMarshallable, StateHash, LoanRecord {

    // 所属用户 uid（由 UserProfile 上下文注入，不序列化，仅进 stateHash）。
    public long uid;

    // 贷款唯一 id（客户端提供，per-user 唯一，Cross 命名空间独立于 Isolated）。创建时锁死。
    public long loanId;
    // 借出币种（抵押是账户级的，见 UserProfile.crossLoanCollateral，本 record 不含抵押字段）。
    public int loanCurrency;
    // 借入时锁定的年化利率（bps），存续期不变。
    public int rateBps;
    // 开仓时间戳（ns），期限校验用。
    public long openedAtTs;

    // 剩余未偿本金（loanCurrency，currencyScale）。REPAY / force-sell 递减，账户级 underwater 归零走 badDebt。
    public long outstandingPrincipal;
    // 已计提但未支付的利息（loanCurrency，currencyScale）。惰性 accrue 写入，结算时进 interestRevenue。
    public long accumulatedInterest;
    // 上次计息时间戳（ns），惰性 accrue 到此点；初始 = openedAtTs。
    public long lastAccrueTs;

    public CrossLoanRecord(long uid, long loanId, int loanCurrency, int rateBps, long openedAtTs) {
        initialize(uid, loanId, loanCurrency, rateBps, openedAtTs);
    }

    public CrossLoanRecord(long uid, BytesIn bytes) {
        this.uid = uid;
        this.loanId = bytes.readLong();
        this.loanCurrency = bytes.readInt();
        this.rateBps = bytes.readInt();
        this.openedAtTs = bytes.readLong();
        this.outstandingPrincipal = bytes.readLong();
        this.accumulatedInterest = bytes.readLong();
        this.lastAccrueTs = bytes.readLong();
    }

    /** 从对象池拿到 record 后必须先 initialize 重置 identity + 可变状态。 */
    public void initialize(long uid, long loanId, int loanCurrency, int rateBps, long openedAtTs) {
        this.uid = uid;
        this.loanId = loanId;
        this.loanCurrency = loanCurrency;
        this.rateBps = rateBps;
        this.openedAtTs = openedAtTs;
        this.outstandingPrincipal = 0;
        this.accumulatedInterest = 0;
        this.lastAccrueTs = openedAtTs;
    }

    public boolean isEmpty() {
        return outstandingPrincipal == 0 && accumulatedInterest == 0;
    }

    @Override public int getLoanCurrency() { return loanCurrency; }
    @Override public int getRateBps() { return rateBps; }
    @Override public long getOutstandingPrincipal() { return outstandingPrincipal; }
    @Override public void setOutstandingPrincipal(long value) { this.outstandingPrincipal = value; }
    @Override public long getAccumulatedInterest() { return accumulatedInterest; }
    @Override public void setAccumulatedInterest(long value) { this.accumulatedInterest = value; }
    @Override public long getLastAccrueTs() { return lastAccrueTs; }
    @Override public void setLastAccrueTs(long value) { this.lastAccrueTs = value; }

    public void validateInternalState() {
        if (outstandingPrincipal < 0 || accumulatedInterest < 0) {
            log.error("uid {} loanId {} : negative amount principal={} interest={}",
                uid, loanId, outstandingPrincipal, accumulatedInterest);
            throw new IllegalStateException();
        }
    }

    @Override
    public void writeMarshallable(BytesOut bytes) {
        bytes.writeLong(loanId);
        bytes.writeInt(loanCurrency);
        bytes.writeInt(rateBps);
        bytes.writeLong(openedAtTs);
        bytes.writeLong(outstandingPrincipal);
        bytes.writeLong(accumulatedInterest);
        bytes.writeLong(lastAccrueTs);
    }

    @Override
    public int stateHash() {
        return Objects.hash(uid, loanId, loanCurrency, rateBps, openedAtTs,
            outstandingPrincipal, accumulatedInterest, lastAccrueTs);
    }

    @Override
    public String toString() {
        return "CrossLoan{" + "u" + uid + " id" + loanId + " loanCur" + loanCurrency + " rate" + rateBps
            + " openedAt" + openedAtTs + " prin=" + outstandingPrincipal + " int=" + accumulatedInterest
            + " lastAccrue=" + lastAccrueTs + '}';
    }
}
