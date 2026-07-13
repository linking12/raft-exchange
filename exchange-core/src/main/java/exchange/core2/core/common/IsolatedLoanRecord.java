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
 * Isolated 模式单笔贷款凭证，挂在 {@link UserProfile#isolatedLoans}（loanId -> record）。抵押与本笔 loan
 * 一对一绑定（{@link #collateralAmount}）。对象池复用，故 identity 字段非 final（由 {@link #initialize} 重置）。
 */
@Slf4j
@NoArgsConstructor
public final class IsolatedLoanRecord implements WriteBytesMarshallable, StateHash, LoanRecord {

    // 所属用户 uid（由 UserProfile 上下文注入，不序列化，仅进 stateHash）。
    public long uid;

    // 贷款唯一 id（客户端提供，per-user 唯一，Isolated 命名空间独立于 Cross）。创建时锁死。
    public long loanId;
    // 抵押币种（= spec.baseCurrency）。
    public int collateralCurrency;
    // 借出币种（= spec.quoteCurrency）。
    public int loanCurrency;
    // 借入时锁定的年化利率（bps），存续期不变。
    public int rateBps;
    // 开仓时间戳（ms），期限强平用（now - openedAtTs > loanMaxTermDays 触发）。
    public long openedAtTs;

    // 已抵押的 collateralCurrency 数量（currencyScale）。force-sell 作卖出量时须经 LoanService.collateralAmountToLots
    // 换成张数（lot），不能直接当 size；反向记账用 lotsToCollateralAmount。不足一张的尘埃卖不掉，underwater 时并入 badDebt。
    public long collateralAmount;
    // 剩余未偿本金（loanCurrency，currencyScale）。REPAY / force-sell 递减，underwater 归零走 badDebt。
    public long outstandingPrincipal;
    // 已计提但未支付的利息（loanCurrency，currencyScale）。惰性 accrue 写入，结算时进 interestRevenue。
    public long accumulatedInterest;
    // 上次计息时间戳（ms），惰性 accrue 到此点；初始 = openedAtTs。
    public long lastAccrueTs;
    // 连续零成交的强平尝试次数；零成交 +1、有成交归 0。scanner 用它爬容差（1%→2%→5%）+ 卡单告警。
    public int stuckLiqAttempts;

    public IsolatedLoanRecord(long uid, long loanId, int collateralCurrency, int loanCurrency, int rateBps,
        long openedAtTs) {
        initialize(uid, loanId, collateralCurrency, loanCurrency, rateBps, openedAtTs);
    }

    public IsolatedLoanRecord(long uid, BytesIn bytes) {
        this.uid = uid;
        this.loanId = bytes.readLong();
        this.collateralCurrency = bytes.readInt();
        this.loanCurrency = bytes.readInt();
        this.rateBps = bytes.readInt();
        this.openedAtTs = bytes.readLong();
        this.collateralAmount = bytes.readLong();
        this.outstandingPrincipal = bytes.readLong();
        this.accumulatedInterest = bytes.readLong();
        this.lastAccrueTs = bytes.readLong();
        this.stuckLiqAttempts = bytes.readInt();
    }

    /** 从对象池拿到 record 后必须先 initialize 重置 identity + 可变状态。跟 {@link SymbolPositionRecord#initialize} 同款契约。 */
    public void initialize(long uid, long loanId, int collateralCurrency, int loanCurrency, int rateBps,
        long openedAtTs) {
        this.uid = uid;
        this.loanId = loanId;
        this.collateralCurrency = collateralCurrency;
        this.loanCurrency = loanCurrency;
        this.rateBps = rateBps;
        this.openedAtTs = openedAtTs;
        this.collateralAmount = 0;
        this.outstandingPrincipal = 0;
        this.accumulatedInterest = 0;
        this.lastAccrueTs = openedAtTs;
        this.stuckLiqAttempts = 0;
    }

    public boolean isEmpty() {
        return collateralAmount == 0 && outstandingPrincipal == 0 && accumulatedInterest == 0;
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
    public int getStuckLiqAttempts() {
        return stuckLiqAttempts;
    }

    @Override
    public void setStuckLiqAttempts(int value) {
        this.stuckLiqAttempts = value;
    }

    public void validateInternalState() {
        if (collateralAmount < 0 || outstandingPrincipal < 0 || accumulatedInterest < 0) {
            log.error("uid {} loanId {} : negative amount collateral={} principal={} interest={}", uid, loanId,
                collateralAmount, outstandingPrincipal, accumulatedInterest);
            throw new IllegalStateException();
        }
    }

    @Override
    public void writeMarshallable(BytesOut bytes) {
        bytes.writeLong(loanId);
        bytes.writeInt(collateralCurrency);
        bytes.writeInt(loanCurrency);
        bytes.writeInt(rateBps);
        bytes.writeLong(openedAtTs);
        bytes.writeLong(collateralAmount);
        bytes.writeLong(outstandingPrincipal);
        bytes.writeLong(accumulatedInterest);
        bytes.writeLong(lastAccrueTs);
        bytes.writeInt(stuckLiqAttempts);
    }

    @Override
    public int stateHash() {
        return Objects.hash(uid, loanId, collateralCurrency, loanCurrency, rateBps, openedAtTs, collateralAmount,
            outstandingPrincipal, accumulatedInterest, lastAccrueTs, stuckLiqAttempts);
    }

    @Override
    public String toString() {
        return "IsolatedLoan{" + "u" + uid + " id" + loanId + " colCur" + collateralCurrency + " loanCur" + loanCurrency
            + " rate" + rateBps + " openedAt" + openedAtTs + " col=" + collateralAmount + " prin="
            + outstandingPrincipal + " int=" + accumulatedInterest + " lastAccrue=" + lastAccrueTs + '}';
    }
}
