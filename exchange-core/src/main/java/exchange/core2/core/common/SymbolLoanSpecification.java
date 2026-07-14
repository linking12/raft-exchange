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

import java.util.Objects;

import lombok.Builder;
import net.openhft.chronicle.bytes.BytesIn;
import net.openhft.chronicle.bytes.BytesOut;
import net.openhft.chronicle.bytes.WriteBytesMarshallable;

/**
 * 一个 symbol（pair）的现货借贷 per-symbol 配置，挂在 {@link CoreSymbolSpecification#loanConfig} 上。 "业务不可变、物理可写"：仅
 * {@code UPDATE_SYMBOL_LOAN_CONFIG} 命令经 {@link #update} 改写。全 0 = 该 pair 借贷未启用。
 *
 * <p>
 * 注意：<b>利率不在此</b> —— 利率是 per-loanCurrency（池级）概念，归 LoanService 利率子系统。
 */
public final class SymbolLoanSpecification implements WriteBytesMarshallable, StateHash {

    public int initialLtvBps; // 开仓 LTV 上限；0 = 借贷未启用
    public int liquidationLtvBps; // Isolated 单笔强平触发线（Cross 用 LoanService 全局阈值）
    public int marginCallLtvBps; // Isolated 预警线；0 = 关闭
    public long maxAmount; // 单笔本金上限；0 = 无上限
    public int maxTermDays; // 最大贷款期限（天）；0 = 无期限（仅 Isolated LOCKED 生效）
    public int collateralWeightBps; // Cross 抵押折价率（bps）；0 = 该 currency 不能作 Cross 抵押

    public SymbolLoanSpecification() {
        // 全 0：loan 未启用
    }

    @Builder
    public SymbolLoanSpecification(int initialLtvBps, int liquidationLtvBps, int marginCallLtvBps, long maxAmount,
        int maxTermDays, int collateralWeightBps) {
        this.initialLtvBps = initialLtvBps;
        this.liquidationLtvBps = liquidationLtvBps;
        this.marginCallLtvBps = marginCallLtvBps;
        this.maxAmount = maxAmount;
        this.maxTermDays = maxTermDays;
        this.collateralWeightBps = collateralWeightBps;
    }

    public SymbolLoanSpecification(BytesIn bytes) {
        this.initialLtvBps = bytes.readInt();
        this.liquidationLtvBps = bytes.readInt();
        this.marginCallLtvBps = bytes.readInt();
        this.maxAmount = bytes.readLong();
        this.maxTermDays = bytes.readInt();
        this.collateralWeightBps = bytes.readInt();
    }

    /** 借贷是否在该 pair 启用（开仓 LTV 上限 > 0）。 */
    public boolean isEnabled() {
        return initialLtvBps > 0;
    }

    /** 物理改写（UPDATE_SYMBOL_LOAN_CONFIG 唯一 mutation point；caller 已完成字段层校验）。 */
    public void update(int initialLtvBps, int liquidationLtvBps, int marginCallLtvBps, long maxAmount, int maxTermDays,
        int collateralWeightBps) {
        this.initialLtvBps = initialLtvBps;
        this.liquidationLtvBps = liquidationLtvBps;
        this.marginCallLtvBps = marginCallLtvBps;
        this.maxAmount = maxAmount;
        this.maxTermDays = maxTermDays;
        this.collateralWeightBps = collateralWeightBps;
    }

    @Override
    public void writeMarshallable(BytesOut bytes) {
        bytes.writeInt(initialLtvBps);
        bytes.writeInt(liquidationLtvBps);
        bytes.writeInt(marginCallLtvBps);
        bytes.writeLong(maxAmount);
        bytes.writeInt(maxTermDays);
        bytes.writeInt(collateralWeightBps);
    }

    @Override
    public int stateHash() {
        return Objects.hash(initialLtvBps, liquidationLtvBps, marginCallLtvBps, maxAmount, maxTermDays,
            collateralWeightBps);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof SymbolLoanSpecification)) {
            return false;
        }
        SymbolLoanSpecification that = (SymbolLoanSpecification)o;
        return initialLtvBps == that.initialLtvBps && liquidationLtvBps == that.liquidationLtvBps
            && marginCallLtvBps == that.marginCallLtvBps && maxAmount == that.maxAmount
            && maxTermDays == that.maxTermDays && collateralWeightBps == that.collateralWeightBps;
    }

    @Override
    public int hashCode() {
        return stateHash();
    }

    @Override
    public String toString() {
        return "Loan{initLtv=" + initialLtvBps + " liqLtv=" + liquidationLtvBps + " mcLtv=" + marginCallLtvBps
            + " maxAmt=" + maxAmount + " maxTerm=" + maxTermDays + " colWeight=" + collateralWeightBps + '}';
    }
}
