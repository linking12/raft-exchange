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
package exchange.core2.core.common.api.binary;

import lombok.Getter;

@Getter
public enum BinaryCommandType {

    ADD_ACCOUNTS(1002),
    ADD_SYMBOLS(1003),
    ADD_CURRENCIES(1004),
    // 更新已有现货 pair 的 loan 配置（详见 loan.md UPDATE_SYMBOL_LOAN_CONFIG 章节）
    UPDATE_SYMBOL_LOAN_CONFIG(1005),
    // 全局 Cross 借贷估值基准币（loan.md §1.2）；未配时 Cross BORROW/WITHDRAW fail-close
    UPDATE_LOAN_NUMERAIRE_CONFIG(1006);

    private final int code;

    BinaryCommandType(int code) {
        this.code = code;
    }

    public static BinaryCommandType of(int code) {

        switch (code) {
            case 1002:
                return ADD_ACCOUNTS;
            case 1003:
                return ADD_SYMBOLS;
            case 1004:
                return ADD_CURRENCIES;
            case 1005:
                return UPDATE_SYMBOL_LOAN_CONFIG;
            case 1006:
                return UPDATE_LOAN_NUMERAIRE_CONFIG;
            default:
                throw new IllegalArgumentException("unknown BinaryCommandType:" + code);
        }

    }

}
