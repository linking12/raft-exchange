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
 * UPDATE_SYMBOL_LOAN_CONFIG —— 给已上架的现货 pair 追配 / 重置 loan 参数（详见 loan.md UPDATE_SYMBOL_LOAN_CONFIG 章节）。
 *
 * <p>
 * 应用侧守卫：
 * <ol>
 * <li>symbol 存在（否则 log.warn skip）</li>
 * <li>symbol.type == {@code CURRENCY_EXCHANGE_PAIR} —— 期货 / 交割 / 其他类型全部拒绝</li>
 * <li>7 个 loan 字段范围 + 阈值序（{@code initial < marginCall < liquidation < 10000}）</li>
 * </ol>
 * 校验失败按 {@code BatchAddSymbolsCommand} 同款 pattern：log.warn 跳过，不 mutate spec。
 *
 * <p>
 * "关闭 loan" 语义 = {@code loanInitialLtvBps = 0}；existing IsolatedLoanRecord / CrossLoanRecord 不作废（契约 lock at issuance）。
 */
@AllArgsConstructor
@EqualsAndHashCode
@Getter
@ToString
public final class UpdateSymbolLoanConfigCommand implements BinaryDataCommand {

    private final int symbolId;
    private final int loanInitialLtvBps;
    private final int loanLiquidationLtvBps;
    private final int loanMarginCallLtvBps;
    private final int loanRateBps;
    private final long loanMaxAmount;
    private final int loanMaxTermDays;
    private final int collateralWeightBps;

    public UpdateSymbolLoanConfigCommand(BytesIn bytes) {
        this.symbolId = bytes.readInt();
        this.loanInitialLtvBps = bytes.readInt();
        this.loanLiquidationLtvBps = bytes.readInt();
        this.loanMarginCallLtvBps = bytes.readInt();
        this.loanRateBps = bytes.readInt();
        this.loanMaxAmount = bytes.readLong();
        this.loanMaxTermDays = bytes.readInt();
        this.collateralWeightBps = bytes.readInt();
    }

    @Override
    public void writeMarshallable(BytesOut bytes) {
        bytes.writeInt(symbolId);
        bytes.writeInt(loanInitialLtvBps);
        bytes.writeInt(loanLiquidationLtvBps);
        bytes.writeInt(loanMarginCallLtvBps);
        bytes.writeInt(loanRateBps);
        bytes.writeLong(loanMaxAmount);
        bytes.writeInt(loanMaxTermDays);
        bytes.writeInt(collateralWeightBps);
    }

    @Override
    public int getBinaryCommandTypeCode() {
        return BinaryCommandType.UPDATE_SYMBOL_LOAN_CONFIG.getCode();
    }
}
