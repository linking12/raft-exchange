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
 * 现货借贷 <b>全局（per-shard 单例）</b>运行时配置，挂在 {@link LoanService#globalConfig} 上；进 raft snapshot、跨 shard 独立。
 * 仅 {@code UPDATE_LOAN_GLOBAL_CONFIG} 命令改写（RiskEngine 里逐字段 partial-update，任一违规整条拒绝）。
 *
 * <p>与 per-symbol 的 {@link exchange.core2.core.common.SymbolLoanSpecification} 平行、作用域不同：这里是整个 shard 一份的
 * 账户级 / 池级参数（Cross 强平线、池利用率上限、强平费率、numeraire），而 SymbolLoanSpecification 是每 pair 一份的 LTV/期限。
 */
public final class LoanGlobalConfig implements WriteBytesMarshallable, StateHash {

    // --- 默认值（可经 UPDATE_LOAN_GLOBAL_CONFIG 覆盖）---
    public static final int DEFAULT_CROSS_LIQUIDATION_LTV_BPS = 8500; // 85%
    public static final int DEFAULT_CROSS_MARGIN_CALL_LTV_BPS = 8000; // 80%
    public static final int DEFAULT_LOAN_POOL_UTILIZATION_CAP_BPS = 9000; // 90%
    public static final int DEFAULT_LOAN_LIQUIDATION_FEE_BPS = 200; // 2%
    public static final int NUMERAIRE_UNSET = 0; // numeraire 未配置的 sentinel

    public int crossLiquidationLtvBps;   // Cross 账户级强平线（bps）
    public int crossMarginCallLtvBps;    // Cross 账户级预警线（bps）
    public int loanPoolUtilizationCapBps; // 借贷池利用率上限（bps）
    public int loanLiquidationFeeBps;    // 强平专项费率（bps）
    public int numeraireCurrency;        // Cross 估值基准币；NUMERAIRE_UNSET(0)=未配 → Cross BORROW/WITHDRAW fail-close、scanner 跳过

    public LoanGlobalConfig() {
        this.crossLiquidationLtvBps = DEFAULT_CROSS_LIQUIDATION_LTV_BPS;
        this.crossMarginCallLtvBps = DEFAULT_CROSS_MARGIN_CALL_LTV_BPS;
        this.loanPoolUtilizationCapBps = DEFAULT_LOAN_POOL_UTILIZATION_CAP_BPS;
        this.loanLiquidationFeeBps = DEFAULT_LOAN_LIQUIDATION_FEE_BPS;
        this.numeraireCurrency = NUMERAIRE_UNSET;
    }

    public LoanGlobalConfig(BytesIn bytes) {
        this.crossLiquidationLtvBps = bytes.readInt();
        this.crossMarginCallLtvBps = bytes.readInt();
        this.loanPoolUtilizationCapBps = bytes.readInt();
        this.loanLiquidationFeeBps = bytes.readInt();
        this.numeraireCurrency = bytes.readInt();
    }

    public void reset() {
        this.crossLiquidationLtvBps = DEFAULT_CROSS_LIQUIDATION_LTV_BPS;
        this.crossMarginCallLtvBps = DEFAULT_CROSS_MARGIN_CALL_LTV_BPS;
        this.loanPoolUtilizationCapBps = DEFAULT_LOAN_POOL_UTILIZATION_CAP_BPS;
        this.loanLiquidationFeeBps = DEFAULT_LOAN_LIQUIDATION_FEE_BPS;
        this.numeraireCurrency = NUMERAIRE_UNSET;
    }

    /** numeraire 是否已配置（未配则 Cross 借贷 fail-close、scanner 跳过 Cross）。 */
    public boolean isNumeraireConfigured() {
        return numeraireCurrency != NUMERAIRE_UNSET;
    }

    @Override
    public void writeMarshallable(BytesOut bytes) {
        bytes.writeInt(crossLiquidationLtvBps);
        bytes.writeInt(crossMarginCallLtvBps);
        bytes.writeInt(loanPoolUtilizationCapBps);
        bytes.writeInt(loanLiquidationFeeBps);
        bytes.writeInt(numeraireCurrency);
    }

    @Override
    public int stateHash() {
        return Objects.hash(crossLiquidationLtvBps, crossMarginCallLtvBps, loanPoolUtilizationCapBps,
            loanLiquidationFeeBps, numeraireCurrency);
    }

    @Override
    public String toString() {
        return "LoanGlobalConfig{crossLiqLtv=" + crossLiquidationLtvBps + " crossMcLtv=" + crossMarginCallLtvBps
            + " poolCap=" + loanPoolUtilizationCapBps + " liqFee=" + loanLiquidationFeeBps + " numeraire="
            + numeraireCurrency + '}';
    }
}
