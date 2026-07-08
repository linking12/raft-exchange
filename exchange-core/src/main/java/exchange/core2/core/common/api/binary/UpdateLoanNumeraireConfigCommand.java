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
 * UPDATE_LOAN_NUMERAIRE_CONFIG —— 全局 Cross 借贷估值基准币（loan.md §1.2 "Cross 基准币: USDT"）。
 *
 * <p>numeraireCcy 需在启用 Cross 借贷前配好；未配（0）时 Cross BORROW / WITHDRAW handler fail-close
 * ({@code LOAN_NUMERAIRE_NOT_CONFIGURED})，scanner 保守跳过强平。跨节点走 raft snapshot 复制，
 * 因此只需在 leader 提交一次即可。
 *
 * <p>应用侧守卫：{@code numeraireCcy > 0} 才 apply；0 视为"清空"意图但当前实现拒绝（防误清）。
 */
@AllArgsConstructor
@EqualsAndHashCode
@Getter
@ToString
public final class UpdateLoanNumeraireConfigCommand implements BinaryDataCommand {

    private final int numeraireCcy;

    public UpdateLoanNumeraireConfigCommand(BytesIn bytes) {
        this.numeraireCcy = bytes.readInt();
    }

    @Override
    public void writeMarshallable(BytesOut bytes) {
        bytes.writeInt(numeraireCcy);
    }

    @Override
    public int getBinaryCommandTypeCode() {
        return BinaryCommandType.UPDATE_LOAN_NUMERAIRE_CONFIG.getCode();
    }
}
