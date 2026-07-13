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
package exchange.core2.core.common.api.binary;

import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;
import net.openhft.chronicle.bytes.BytesIn;
import net.openhft.chronicle.bytes.BytesOut;

/**
 * UPDATE_LOAN_GLOBAL_CONFIG —— 全局 Cross 借贷估值基准币 + 三大运行时风控阈值（loan.md §1.2 "Cross 基准币: USDT"）。
 *
 * <p>
 * numeraireCurrency 需在启用 Cross 借贷前配好；未配（0）时 Cross BORROW / WITHDRAW handler fail-close
 * ({@code LOAN_NUMERAIRE_NOT_CONFIGURED})，scanner 保守跳过强平。跨节点走 raft snapshot 复制， 因此只需在 leader 提交一次即可。
 *
 * <p>
 * <b>partial update 语义</b>：各字段独立，{@code &le; 0} 表示"本次不改"，{@code &gt; 0} 才 apply。 因此可用一条命令只配
 * numeraire、只调某项、或组合更新。numeraire 传 0 仍视为"不改"（防误清，与旧行为一致）。
 * <ul>
 * <li>{@code numeraireCurrency} —— Cross 估值基准币（需 currencySpec 存在）</li>
 * <li>{@code crossLiquidationLtvBps} —— Cross 账户级强平线（bps）</li>
 * <li>{@code crossMarginCallLtvBps} —— Cross 账户级预警线（bps）</li>
 * <li>{@code loanPoolUtilizationCapBps} —— 借贷池利用率上限（bps）</li>
 * <li>{@code loanLiquidationFeeBps} —— 强平专项费率（bps）</li>
 * </ul>
 */
@AllArgsConstructor
@EqualsAndHashCode
@Getter
@ToString
public final class UpdateLoanGlobalConfigCommand implements BinaryDataCommand {

    private final int numeraireCurrency;
    private final int crossLiquidationLtvBps;
    private final int crossMarginCallLtvBps;
    private final int loanPoolUtilizationCapBps;
    private final int loanLiquidationFeeBps;

    /** 便捷构造：仅配 numeraire，其余保持不变（传 0 = 不改）。 */
    public UpdateLoanGlobalConfigCommand(int numeraireCurrency) {
        this(numeraireCurrency, 0, 0, 0, 0);
    }

    public UpdateLoanGlobalConfigCommand(BytesIn bytes) {
        this.numeraireCurrency = bytes.readInt();
        this.crossLiquidationLtvBps = bytes.readInt();
        this.crossMarginCallLtvBps = bytes.readInt();
        this.loanPoolUtilizationCapBps = bytes.readInt();
        this.loanLiquidationFeeBps = bytes.readInt();
    }

    /** bps 满值（100%）。阈值须严格在 (0, BPS_FULL) 内。 */
    private static final int BPS_FULL = 10_000;

    /**
     * partial update 语义下校验"生效后"的阈值是否自洽（apply-all-or-nothing 用）：
     * <ul>
     * <li>预警线严格低于强平线，且两者都在 (0, 100%)：{@code 0 < effMarginCall < effLiquidation < BPS_FULL}； 否则 scanner 的 marginCall
     * 预警带会塌缩/倒挂，用户被强平前收不到预警</li>
     * <li>利用率上限 ≤ 100%、强平费 < 100%（本次未改的字段 ≤ 0，跳过其范围校验）</li>
     * </ul>
     * numeraire 的 currencySpec 存在性由调用方（RiskEngine）另行校验，不在此。
     */
    public boolean thresholdsValidGivenCurrent(int currentCrossLiquidationLtvBps, int currentCrossMarginCallLtvBps) {
        final int effLiquidation = crossLiquidationLtvBps > 0 ? crossLiquidationLtvBps : currentCrossLiquidationLtvBps;
        final int effMarginCall = crossMarginCallLtvBps > 0 ? crossMarginCallLtvBps : currentCrossMarginCallLtvBps;
        return effMarginCall > 0 && effMarginCall < effLiquidation && effLiquidation < BPS_FULL
            && (loanPoolUtilizationCapBps <= 0 || loanPoolUtilizationCapBps <= BPS_FULL)
            && (loanLiquidationFeeBps <= 0 || loanLiquidationFeeBps < BPS_FULL);
    }

    @Override
    public void writeMarshallable(BytesOut bytes) {
        bytes.writeInt(numeraireCurrency);
        bytes.writeInt(crossLiquidationLtvBps);
        bytes.writeInt(crossMarginCallLtvBps);
        bytes.writeInt(loanPoolUtilizationCapBps);
        bytes.writeInt(loanLiquidationFeeBps);
    }

    @Override
    public int getBinaryCommandTypeCode() {
        return BinaryCommandType.UPDATE_LOAN_GLOBAL_CONFIG.getCode();
    }
}
