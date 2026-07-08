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
 * 现货借贷 Isolated 模式的单笔贷款业务凭证。挂在 {@link UserProfile#isolatedLoans} 上（loanId -> record）。
 * 抵押跟本笔 loan 一对一绑定（{@link #collateralAmount}）；不像 Cross 走账户级抵押池。
 *
 * <p><b>对象池管理</b>：通过 {@link exchange.core2.collections.objpool.ObjectsPool#ISOLATED_LOAN_RECORD}
 * 获取 / 归还，跟 {@link SymbolPositionRecord} 同款 pattern。原本 identity 字段（loanId 等）语义"不可变"
 * 但物理上不能 final，否则无法在 {@link #initialize} 里重置回池后拿出来复用。
 *
 * <p>详见 loan.md §3.1。
 */
@Slf4j
@NoArgsConstructor
public final class IsolatedLoanRecord implements WriteBytesMarshallable, StateHash {

    // ===== 用户绑定 =====
    // 所属用户 uid，跟 UserProfile.uid 一致；不参与序列化（由 UserProfile 上下文注入），进 stateHash。
    public long uid;

    // ===== 业务标识（loan 创建时锁死；池化需要非 final 才能 initialize 复用）=====
    // 客户端提供的 loan 唯一 id，per-user 唯一，Isolated 命名空间独立于 Cross。
    public long loanId;
    // 抵押币种（LOAN_CREATE 用 spec.baseCurrency 反推）。
    public int collateralCcy;
    // 借出币种（LOAN_CREATE 用 spec.quoteCurrency 反推）。
    public int loanCcy;
    // 借时锁定的年化利率（bps）。v1 从 spec.loanRateBps snapshot；v2 支持动态利率时替换。
    public int rateBps;
    // 开仓时间戳（cmd.timestamp, ns），期限校验用。scanner 判 (now - openedAtTs) > loanMaxTermDays × 1d 时强制强平。
    public long openedAtTs;

    // ===== 可变业务状态（apply 路径写入）=====
    // 剩余抵押量（currency = collateralCcy, scale = currencyScale）；LOAN_ADD/RELEASE_COLLATERAL 修改，force-sell 消耗。
    public long collateralAmount;
    // 剩余本金（currency = loanCcy, scale = currencyScale）；REPAY / force-sell 减少，underwater 归零走 badDebt。
    public long outstandingPrincipal;
    // 已累计但未支付利息（currency = loanCcy, scale = currencyScale）；惰性 accrue 时写入，REPAY / force-sell 结算进 fees。
    public long accumulatedInterest;
    // 上次 accrue 时间戳（cmd.timestamp, ns），初始化 = openedAtTs；触发点仅 REPAY / force-sell apply 前调用 accrueTo 更新。
    public long lastAccrueTs;

    public IsolatedLoanRecord(long uid, long loanId, int collateralCcy, int loanCcy, int rateBps, long openedAtTs) {
        initialize(uid, loanId, collateralCcy, loanCcy, rateBps, openedAtTs);
    }

    public IsolatedLoanRecord(long uid, BytesIn bytes) {
        this.uid = uid;
        this.loanId = bytes.readLong();
        this.collateralCcy = bytes.readInt();
        this.loanCcy = bytes.readInt();
        this.rateBps = bytes.readInt();
        this.openedAtTs = bytes.readLong();
        this.collateralAmount = bytes.readLong();
        this.outstandingPrincipal = bytes.readLong();
        this.accumulatedInterest = bytes.readLong();
        this.lastAccrueTs = bytes.readLong();
    }

    /** 从对象池拿到 record 后必须先 initialize 重置 identity + 可变状态。跟 {@link SymbolPositionRecord#initialize} 同款契约。 */
    public void initialize(long uid, long loanId, int collateralCcy, int loanCcy, int rateBps, long openedAtTs) {
        this.uid = uid;
        this.loanId = loanId;
        this.collateralCcy = collateralCcy;
        this.loanCcy = loanCcy;
        this.rateBps = rateBps;
        this.openedAtTs = openedAtTs;
        this.collateralAmount = 0;
        this.outstandingPrincipal = 0;
        this.accumulatedInterest = 0;
        this.lastAccrueTs = openedAtTs;
    }

    public boolean isEmpty() {
        return collateralAmount == 0 && outstandingPrincipal == 0 && accumulatedInterest == 0;
    }

    public void validateInternalState() {
        if (collateralAmount < 0 || outstandingPrincipal < 0 || accumulatedInterest < 0) {
            log.error("uid {} loanId {} : negative amount collateral={} principal={} interest={}",
                uid, loanId, collateralAmount, outstandingPrincipal, accumulatedInterest);
            throw new IllegalStateException();
        }
    }

    @Override
    public void writeMarshallable(BytesOut bytes) {
        bytes.writeLong(loanId);
        bytes.writeInt(collateralCcy);
        bytes.writeInt(loanCcy);
        bytes.writeInt(rateBps);
        bytes.writeLong(openedAtTs);
        bytes.writeLong(collateralAmount);
        bytes.writeLong(outstandingPrincipal);
        bytes.writeLong(accumulatedInterest);
        bytes.writeLong(lastAccrueTs);
    }

    @Override
    public int stateHash() {
        return Objects.hash(uid, loanId, collateralCcy, loanCcy, rateBps, openedAtTs,
            collateralAmount, outstandingPrincipal, accumulatedInterest, lastAccrueTs);
    }

    @Override
    public String toString() {
        return "IsolatedLoan{" + "u" + uid + " id" + loanId + " cCcy" + collateralCcy + " lCcy" + loanCcy
            + " rate" + rateBps + " openedAt" + openedAtTs + " col=" + collateralAmount + " prin=" + outstandingPrincipal
            + " int=" + accumulatedInterest + " lastAccrue=" + lastAccrueTs + '}';
    }
}
