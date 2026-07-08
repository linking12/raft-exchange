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
 * 现货借贷 Cross 模式的单笔债务凭证。挂在 {@link UserProfile#crossLoans} 上（loanId -> record）。
 * <b>无抵押字段</b> —— Cross 抵押是账户级的，多笔 debt 共享 {@link UserProfile#crossLoanCollateral} 多币种池。
 *
 * <p><b>对象池管理</b>：通过 {@link exchange.core2.collections.objpool.ObjectsPool#CROSS_LOAN_RECORD}
 * 获取 / 归还，跟 {@link SymbolPositionRecord} 同款 pattern。identity 字段语义"不可变"但物理非 final。
 *
 * <p>详见 loan.md §3.2。
 */
@Slf4j
@NoArgsConstructor
public final class CrossLoanRecord implements WriteBytesMarshallable, StateHash {

    // ===== 用户绑定 =====
    // 所属用户 uid，跟 UserProfile.uid 一致；不参与序列化（由 UserProfile 上下文注入），进 stateHash。
    public long uid;

    // ===== 业务标识（BORROW 创建时锁死；池化需要非 final 才能 initialize 复用）=====
    // 客户端提供的 loan 唯一 id，per-user 唯一，Cross 命名空间独立于 Isolated。
    public long loanId;
    // 借出币种。
    public int loanCcy;
    // 借时锁定的年化利率（bps）。
    public int rateBps;
    // 开仓时间戳（cmd.timestamp, ns），期限校验用；同 IsolatedLoanRecord。
    public long openedAtTs;

    // ===== 可变业务状态 =====
    // 剩余本金（currency = loanCcy, scale = currencyScale）。
    public long outstandingPrincipal;
    // 已累计但未支付利息（currency = loanCcy, scale = currencyScale）。
    public long accumulatedInterest;
    // 上次 accrue 时间戳（cmd.timestamp, ns），初始化 = openedAtTs。
    public long lastAccrueTs;

    public CrossLoanRecord(long uid, long loanId, int loanCcy, int rateBps, long openedAtTs) {
        initialize(uid, loanId, loanCcy, rateBps, openedAtTs);
    }

    public CrossLoanRecord(long uid, BytesIn bytes) {
        this.uid = uid;
        this.loanId = bytes.readLong();
        this.loanCcy = bytes.readInt();
        this.rateBps = bytes.readInt();
        this.openedAtTs = bytes.readLong();
        this.outstandingPrincipal = bytes.readLong();
        this.accumulatedInterest = bytes.readLong();
        this.lastAccrueTs = bytes.readLong();
    }

    /** 从对象池拿到 record 后必须先 initialize 重置 identity + 可变状态。 */
    public void initialize(long uid, long loanId, int loanCcy, int rateBps, long openedAtTs) {
        this.uid = uid;
        this.loanId = loanId;
        this.loanCcy = loanCcy;
        this.rateBps = rateBps;
        this.openedAtTs = openedAtTs;
        this.outstandingPrincipal = 0;
        this.accumulatedInterest = 0;
        this.lastAccrueTs = openedAtTs;
    }

    public boolean isEmpty() {
        return outstandingPrincipal == 0 && accumulatedInterest == 0;
    }

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
        bytes.writeInt(loanCcy);
        bytes.writeInt(rateBps);
        bytes.writeLong(openedAtTs);
        bytes.writeLong(outstandingPrincipal);
        bytes.writeLong(accumulatedInterest);
        bytes.writeLong(lastAccrueTs);
    }

    @Override
    public int stateHash() {
        return Objects.hash(uid, loanId, loanCcy, rateBps, openedAtTs,
            outstandingPrincipal, accumulatedInterest, lastAccrueTs);
    }

    @Override
    public String toString() {
        return "CrossLoan{" + "u" + uid + " id" + loanId + " lCcy" + loanCcy + " rate" + rateBps
            + " openedAt" + openedAtTs + " prin=" + outstandingPrincipal + " int=" + accumulatedInterest
            + " lastAccrue=" + lastAccrueTs + '}';
    }
}
