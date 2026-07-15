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
 * Isolated 单笔贷款凭证，挂在 {@link UserProfile#isolatedLoans}；抵押与本笔 loan 一对一绑定。对象池复用，identity 非 final。
 */
@Slf4j
@NoArgsConstructor
public final class IsolatedLoanRecord implements WriteBytesMarshallable, StateHash, LoanRecord {

    // rateMode: LOCKED=Fixed（开仓锁 rateBps） / FLOATING=Flexible（走 accSnapshot 累加器）。见 loan.md §13。
    public static final byte RATE_MODE_LOCKED = 0;
    public static final byte RATE_MODE_FLOATING = 1;

    public long uid; // 所属用户（上下文注入，不序列化，仅进 stateHash）
    public long loanId; // 客户端提供，per-user 唯一，创建时锁死

    public int symbolId; // 现货 pair（= cmd.symbol），scanner/handler 直接 getSymbolSpecification 拿 spec
    public int collateralCurrency; // = spec.baseCurrency
    public int loanCurrency; // = spec.quoteCurrency

    public byte rateMode; // 开仓锁定，见 loan.md §13.2
    public int rateBps; // 年化利率（bps）。LOCKED 计息用；FLOATING 仅作开仓利率展示，计息走累加器
    public long openedAtTs; // 开仓时间戳（ms），期限强平用（仅 LOCKED 有期限）

    // force-sell 作卖出量须经 collateralAmountToLots 换张数；不足一张的尘埃 underwater 时并入 badDebt
    public long collateralAmount; // 已抵押数量（currencyScale）
    public long outstandingPrincipal; // 剩余未偿本金（loanCurrency）
    public long accumulatedInterest; // 已计提未付利息（loanCurrency），结算时进 interestRevenue
    public long lastAccrueTs; // 上次计息时间戳（ms），初始 = openedAtTs；LOCKED 计息游标
    public long accSnapshot; // FLOATING 计息游标：上次 accrue 的 liveAcc 快照（bps·ms）；LOCKED 不用。见 loan.md §13.5
    public int stuckLiqAttempts; // 连续零成交强平次数；驱动 scanner 容差爬梯 + 卡单告警

    public IsolatedLoanRecord(long uid, long loanId, int collateralCurrency, int loanCurrency, int rateBps,
        long openedAtTs) {
        initialize(uid, loanId, collateralCurrency, loanCurrency, rateBps, openedAtTs);
    }

    public IsolatedLoanRecord(long uid, BytesIn bytes) {
        this.uid = uid;
        this.loanId = bytes.readLong();
        this.symbolId = bytes.readInt();
        this.collateralCurrency = bytes.readInt();
        this.loanCurrency = bytes.readInt();
        this.rateMode = bytes.readByte();
        this.rateBps = bytes.readInt();
        this.openedAtTs = bytes.readLong();
        this.collateralAmount = bytes.readLong();
        this.outstandingPrincipal = bytes.readLong();
        this.accumulatedInterest = bytes.readLong();
        this.lastAccrueTs = bytes.readLong();
        this.accSnapshot = bytes.readLong();
        this.stuckLiqAttempts = bytes.readInt();
    }

    /** 从对象池拿到 record 后必须先 initialize 重置 identity + 可变状态。跟 {@link SymbolPositionRecord#initialize} 同款契约。 */
    public void initialize(long uid, long loanId, int collateralCurrency, int loanCurrency, int rateBps,
        long openedAtTs) {
        this.uid = uid;
        this.loanId = loanId;
        this.symbolId = 0; // 由 handleLoanCreate 在 initialize 后写入 cmd.symbol
        this.collateralCurrency = collateralCurrency;
        this.loanCurrency = loanCurrency;
        this.rateMode = RATE_MODE_LOCKED; // 默认 LOCKED；由 handleLoanCreate 在 initialize 后按 cmd 写入
        this.rateBps = rateBps;
        this.openedAtTs = openedAtTs;
        this.collateralAmount = 0;
        this.outstandingPrincipal = 0;
        this.accumulatedInterest = 0;
        this.lastAccrueTs = openedAtTs;
        this.accSnapshot = 0;
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
    public long getAccSnapshot() {
        return accSnapshot;
    }

    @Override
    public void setAccSnapshot(long value) {
        this.accSnapshot = value;
    }

    @Override
    public boolean isFixedRate() {
        return rateMode == RATE_MODE_LOCKED;
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
        bytes.writeInt(symbolId);
        bytes.writeInt(collateralCurrency);
        bytes.writeInt(loanCurrency);
        bytes.writeByte(rateMode);
        bytes.writeInt(rateBps);
        bytes.writeLong(openedAtTs);
        bytes.writeLong(collateralAmount);
        bytes.writeLong(outstandingPrincipal);
        bytes.writeLong(accumulatedInterest);
        bytes.writeLong(lastAccrueTs);
        bytes.writeLong(accSnapshot);
        bytes.writeInt(stuckLiqAttempts);
    }

    @Override
    public int stateHash() {
        return Objects.hash(uid, loanId, symbolId, collateralCurrency, loanCurrency, rateMode, rateBps, openedAtTs,
            collateralAmount, outstandingPrincipal, accumulatedInterest, lastAccrueTs, accSnapshot, stuckLiqAttempts);
    }

    @Override
    public String toString() {
        return "IsolatedLoan{" + "u" + uid + " id" + loanId + " sym" + symbolId + " colCur" + collateralCurrency
            + " loanCur" + loanCurrency + " rateMode" + rateMode + " rate" + rateBps + " openedAt" + openedAtTs
            + " col=" + collateralAmount + " prin=" + outstandingPrincipal + " int=" + accumulatedInterest
            + " lastAccrue=" + lastAccrueTs + " accSnap=" + accSnapshot + '}';
    }
}
