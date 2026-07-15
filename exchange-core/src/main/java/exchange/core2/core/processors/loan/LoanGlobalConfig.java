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
package exchange.core2.core.processors.loan;

import java.util.Objects;

import exchange.core2.core.common.StateHash;
import net.openhft.chronicle.bytes.BytesIn;
import net.openhft.chronicle.bytes.BytesOut;
import net.openhft.chronicle.bytes.WriteBytesMarshallable;

/**
 * 现货借贷全局（per-shard 单例）运行时配置：Cross 强平线 / 预警线、池利用率上限、强平费率、numeraire。进 raft snapshot、跨 shard 独立， 仅 {@code ADD_LOAN} 命令逐字段
 * partial-update 改写（见 loan.md §11）。
 */
public final class LoanGlobalConfig implements WriteBytesMarshallable, StateHash {
    public static final int DEFAULT_CROSS_LIQUIDATION_LTV_BPS = 8500; // 85%
    public static final int DEFAULT_CROSS_MARGIN_CALL_LTV_BPS = 8000; // 80%
    public static final int DEFAULT_LOAN_POOL_UTILIZATION_CAP_BPS = 9000; // 90%
    public static final int DEFAULT_LOAN_LIQUIDATION_FEE_BPS = 200; // 2%
    public static final int DEFAULT_LTV_LIQUIDATION_BUFFER_BPS = 2000; // 20% initial→liquidation
    public static final int DEFAULT_LTV_MARGIN_CALL_BUFFER_BPS = 1000; // 10% liquidation→marginCall
    public static final int NUMERAIRE_UNSET = 0; // numeraire 未配置的 sentinel

    public int numeraireCurrency; // Cross 估值基准币；NUMERAIRE_UNSET(0)=未配 → Cross BORROW/WITHDRAW fail-close、scanner 跳过
    public int crossLiquidationLtvBps; // Cross 账户级强平线（bps）
    public int crossMarginCallLtvBps; // Cross 账户级预警线（bps）
    public int loanPoolUtilizationCapBps; // 借贷池利用率上限（bps）
    public int loanLiquidationFeeBps; // 强平专项费率（bps）
    public int ltvLiquidationBufferBps; // Symbol 派生:liquidation = initial + 本值
    public int ltvMarginCallBufferBps;  // Symbol/Cross 派生:marginCall = liquidation − 本值

    public LoanGlobalConfig() {
        this.numeraireCurrency = NUMERAIRE_UNSET;
        this.crossLiquidationLtvBps = DEFAULT_CROSS_LIQUIDATION_LTV_BPS;
        this.crossMarginCallLtvBps = DEFAULT_CROSS_MARGIN_CALL_LTV_BPS;
        this.loanPoolUtilizationCapBps = DEFAULT_LOAN_POOL_UTILIZATION_CAP_BPS;
        this.loanLiquidationFeeBps = DEFAULT_LOAN_LIQUIDATION_FEE_BPS;
        this.ltvLiquidationBufferBps = DEFAULT_LTV_LIQUIDATION_BUFFER_BPS;
        this.ltvMarginCallBufferBps = DEFAULT_LTV_MARGIN_CALL_BUFFER_BPS;
    }

    public LoanGlobalConfig(BytesIn bytes) {
        this.numeraireCurrency = bytes.readInt();
        this.crossLiquidationLtvBps = bytes.readInt();
        this.crossMarginCallLtvBps = bytes.readInt();
        this.loanPoolUtilizationCapBps = bytes.readInt();
        this.loanLiquidationFeeBps = bytes.readInt();
        this.ltvLiquidationBufferBps = bytes.readInt();
        this.ltvMarginCallBufferBps = bytes.readInt();
    }

    public void reset() {
        this.numeraireCurrency = NUMERAIRE_UNSET;
        this.crossLiquidationLtvBps = DEFAULT_CROSS_LIQUIDATION_LTV_BPS;
        this.crossMarginCallLtvBps = DEFAULT_CROSS_MARGIN_CALL_LTV_BPS;
        this.loanPoolUtilizationCapBps = DEFAULT_LOAN_POOL_UTILIZATION_CAP_BPS;
        this.loanLiquidationFeeBps = DEFAULT_LOAN_LIQUIDATION_FEE_BPS;
        this.ltvLiquidationBufferBps = DEFAULT_LTV_LIQUIDATION_BUFFER_BPS;
        this.ltvMarginCallBufferBps = DEFAULT_LTV_MARGIN_CALL_BUFFER_BPS;
    }

    /** numeraire 是否已配置（未配则 Cross 借贷 fail-close、scanner 跳过 Cross）。 */
    public boolean isNumeraireConfigured() {
        return numeraireCurrency != NUMERAIRE_UNSET;
    }

    @Override
    public void writeMarshallable(BytesOut bytes) {
        bytes.writeInt(numeraireCurrency);
        bytes.writeInt(crossLiquidationLtvBps);
        bytes.writeInt(crossMarginCallLtvBps);
        bytes.writeInt(loanPoolUtilizationCapBps);
        bytes.writeInt(loanLiquidationFeeBps);
        bytes.writeInt(ltvLiquidationBufferBps);
        bytes.writeInt(ltvMarginCallBufferBps);
    }

    @Override
    public int stateHash() {
        return Objects.hash(numeraireCurrency, crossLiquidationLtvBps, crossMarginCallLtvBps, loanPoolUtilizationCapBps,
            loanLiquidationFeeBps, ltvLiquidationBufferBps, ltvMarginCallBufferBps);
    }

    @Override
    public String toString() {
        return "LoanGlobalConfig{numeraire=" + numeraireCurrency + " crossLiqLtv=" + crossLiquidationLtvBps
            + " crossMcLtv=" + crossMarginCallLtvBps + " poolCap=" + loanPoolUtilizationCapBps + " liqFee="
            + loanLiquidationFeeBps + " liqBuf=" + ltvLiquidationBufferBps + " mcBuf=" + ltvMarginCallBufferBps + '}';
    }
}
