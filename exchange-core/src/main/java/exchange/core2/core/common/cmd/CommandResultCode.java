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
package exchange.core2.core.common.cmd;

import lombok.Getter;

import java.util.Arrays;

@Getter
public enum CommandResultCode {
    NEW(0),
    VALID_FOR_MATCHING_ENGINE(1),

    SUCCESS(100),
    ACCEPTED(110),

    AUTH_INVALID_USER(-1001),
    AUTH_TOKEN_EXPIRED(-1002),

    INVALID_SYMBOL(-1201),
    INVALID_PRICE_STEP(-1202),
    UNSUPPORTED_SYMBOL_TYPE(-1203),

    RISK_NSF(-2001),
    RISK_INVALID_RESERVE_BID_PRICE(-2002),
    RISK_ASK_PRICE_LOWER_THAN_FEE(-2003),
    RISK_MARGIN_TRADING_DISABLED(-2004),
    RISK_INVALID_AMOUNT(-2005),
    RISK_INVALID_LEVERAGE(-2006), // 杠杆倍率非法，不在 symbol 支持的范围内
    RISK_LEVERAGE_MISMATCH(-2007), // 新杠杆与当前仓位的杠杆不匹配
    RISK_MARGIN_MODE_MISMATCH(-2008), // 仓位模式不匹配
    RISK_MARGIN_POSITION_NOT_EXISTS(-2009), // 仓位不存在
    RISK_MARGIN_POSITION_EXISTS(-2010), // 仓位存在
    RISK_MARKPRICE_NOT_AVAILABLE(-2011), // 标记价格不存在
    RISK_IF_INSUFFICIENT(-2012), // IF_WITHDRAW 时 available 不足以覆盖

    MATCHING_UNKNOWN_ORDER_ID(-3002),
    // MATCHING_DUPLICATE_ORDER_ID(-3003),
    MATCHING_UNSUPPORTED_COMMAND(-3004),
    MATCHING_INVALID_ORDER_BOOK_ID(-3005),
    // MATCHING_ORDER_BOOK_ALREADY_EXISTS(-3006),
    // MATCHING_UNSUPPORTED_ORDER_TYPE(-3007),
    // MATCHING_MOVE_REJECTED_DIFFERENT_PRICE(-3040),
    MATCHING_MOVE_FAILED_PRICE_OVER_RISK_LIMIT(-3041),
    MATCHING_REDUCE_FAILED_WRONG_SIZE(-3051),

    USER_MGMT_USER_ALREADY_EXISTS(-4001),

    USER_MGMT_ACCOUNT_BALANCE_ADJUSTMENT_ALREADY_APPLIED_SAME(-4101),
    USER_MGMT_ACCOUNT_BALANCE_ADJUSTMENT_NSF(-4103),
    USER_MGMT_NON_ZERO_ACCOUNT_BALANCE(-4104),

    USER_MGMT_USER_NOT_SUSPENDABLE_HAS_POSITIONS(-4130),
    USER_MGMT_USER_NOT_SUSPENDABLE_NON_EMPTY_ACCOUNTS(-4131),
    USER_MGMT_USER_NOT_SUSPENDED(-4132),
    USER_MGMT_USER_ALREADY_SUSPENDED(-4133),

    USER_MGMT_USER_NOT_FOUND(-4201),

    SYMBOL_MGMT_SYMBOL_ALREADY_EXISTS(-5001),

    // ============================================================
    // 现货借贷（loan，详见 loan.md §8.1）
    // ============================================================
    // —— 命令级 / 身份 / 状态 ——
    LOAN_NOT_ENABLED(-6001),                          // spec.loanInitialLtvBps == 0
    LOAN_ALREADY_EXISTS(-6002),                       // loanId 已存在（Isolated / Cross 命名空间独立）
    LOAN_NOT_FOUND(-6003),                            // loanId 不存在
    LOAN_UID_MISMATCH(-6004),                         // loan.uid ≠ cmd.uid
    LOAN_USER_SUSPENDED(-6005),                       // userStatus == SUSPEND 后拒绝所有 LOAN_* 命令

    // —— 参数 / 规格 ——
    LOAN_INVALID_AMOUNT(-6010),                       // amount ≤ 0
    LOAN_PRINCIPAL_EXCEEDS_LIMIT(-6011),              // principal > spec.loanMaxAmount
    LOAN_MARKPRICE_NOT_READY(-6012),                  // markPrice 缺失或 0

    // —— LTV 越界（open / 减抵押 / 撤抵押 / Cross borrow 各一）——
    LOAN_LTV_TOO_HIGH(-6020),                         // 开仓 LTV 超线（LOAN_CREATE Isolated）
    LOAN_LTV_TOO_HIGH_AFTER_BORROW(-6021),            // Cross 借后账户级 LTV 超线（LOAN_CROSS_BORROW）
    LOAN_LTV_TOO_HIGH_AFTER_RELEASE(-6022),           // 减 Isolated 抵押后 LTV 超线
    LOAN_CROSS_LTV_TOO_HIGH_AFTER_WITHDRAW(-6023),    // 撤 Cross 抵押后账户级 LTV 超线

    // —— 抵押品相关 ——
    LOAN_COLLATERAL_INSUFFICIENT(-6030),              // accounts − calculateLocked 不足以覆盖新抵押量
    LOAN_COLLATERAL_NOT_ALLOWED(-6031),               // spec.collateralWeightBps == 0（Cross 抵押白名单）
    LOAN_COLLATERAL_EXCEEDS_LOAN(-6032),              // 减 Isolated 抵押量 > loan.collateralAmount

    // —— 还款账户不足 ——
    LOAN_ACCOUNT_INSUFFICIENT(-6040),                 // 还款时 accounts − calculateLocked < 应还金额

    // —— 池子运营 ——
    LOAN_POOL_INSUFFICIENT(-6050),                    // 池子不够 / POOL_WITHDRAW 抽资超
    LOAN_POOL_UTILIZATION_EXCEEDED(-6051),            // 借出后池子利用率超 loanPoolUtilizationCapBps
    LOAN_POOL_WRONG_SHARD(-6052),                     // POOL_DEPOSIT/WITHDRAW 参数级路由错（cmd.uid ∉ [0, N)）

    // —— UPDATE_SYMBOL_LOAN_CONFIG 配置类拒绝码 ——
    // 目前 binary command 通道 handler 返 void，实际以 log.warn 输出（跟 ADD_SYMBOLS 同款）；
    // 加这两条 code 供未来通道支持 result signal 时用，同时让 SerializeHelper 一致性 gate 覆盖 loan 全语义域。
    LOAN_INVALID_CONFIG(-6060),                       // 阈值序 / 范围违规（initial 应 < liquidation < 10000 等）
    LOAN_INVALID_SYMBOL_TYPE(-6070),                  // 试图给非-CURRENCY_EXCHANGE_PAIR（期货/交割）配置 loan
    LOAN_NUMERAIRE_NOT_CONFIGURED(-6080),             // Cross BORROW / WITHDRAW fail-close：sysprop numeraireCurrency 未设

    LOAN_NOT_IMPLEMENTED(-6099),                      // reserved：force-sell 已实装后暂无 caller；将来新加 stub handler 可复用

    BINARY_COMMAND_FAILED(-8001),
    REPORT_QUERY_UNKNOWN_TYPE(-8003),
    STATE_PERSIST_RISK_ENGINE_FAILED(-8010),
    STATE_PERSIST_MATCHING_ENGINE_FAILED(-8020),

    STATE_RECOVER_RISK_ENGINE_FAILED(-8021),
    STATE_RECOVER_MATCHING_ENGINE_FAILED(-8022),
    
    DROP(-9999);

    // codes below -10000 are reserved for gateways


    private int code;

    CommandResultCode(int code) {
        this.code = code;
    }

    public static CommandResultCode mergeToFirstFailed(CommandResultCode... results) {

        return Arrays.stream(results)
                .filter(r -> r != SUCCESS && r != ACCEPTED)
                .findFirst()
                .orElse(Arrays.stream(results).anyMatch(r -> r == SUCCESS) ? SUCCESS : ACCEPTED);
    }

}
