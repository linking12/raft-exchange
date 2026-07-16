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

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.HashMap;

@Getter
@AllArgsConstructor
public enum OrderCommandType {
    PLACE_ORDER((byte) 1, true),
    CANCEL_ORDER((byte) 2, true),
    MOVE_ORDER((byte) 3, true),
    REDUCE_ORDER((byte) 4, true),
    CLOSE_POSITION((byte) 5, true),

    ORDER_BOOK_REQUEST((byte) 6, false),

    ADD_USER((byte) 10, true),
    BALANCE_ADJUSTMENT((byte) 11, true),
    SUSPEND_USER((byte) 12, true),
    RESUME_USER((byte) 13, true),

    FORCE_LIQUIDATION((byte) 20, true),
    LEVERAGE_ADJUSTMENT((byte) 21, true),
    POSITION_MODE_ADJUSTMENT((byte) 22, true),
    MARGIN_ADJUSTMENT((byte) 23, true),
    MARKPRICE_ADJUSTMENT((byte) 24, true),
    SETTLE_FUNDINGFEES((byte) 25, true),
    SETTLE_PNL((byte) 26, true),
    RESET_FEE((byte) 27, true),

    SYSTEM_LIQUIDATION_NOTIFY((byte) 31, true),

    IF_TAKEOVER((byte) 40, true),
    AUTO_DELEVERAGING((byte) 41, true),
    IF_DEPOSIT((byte) 42, true),
    IF_WITHDRAW((byte) 43, true),

    // ===== 现货借贷（loan，详见 loan.md §5.1） =====
    // Isolated
    LOAN_CREATE((byte) 50, true),
    LOAN_REPAY((byte) 51, true),
    LOAN_ADD_COLLATERAL((byte) 52, true),
    LOAN_RELEASE_COLLATERAL((byte) 53, true),
    LOAN_FORCE_LIQUIDATE((byte) 54, true),           // scanner published
    // Cross
    LOAN_CROSS_ADD_COLLATERAL((byte) 55, true),
    LOAN_CROSS_WITHDRAW_COLLATERAL((byte) 56, true),
    LOAN_CROSS_BORROW((byte) 57, true),
    LOAN_CROSS_REPAY((byte) 58, true),
    LOAN_CROSS_FORCE_LIQUIDATE((byte) 59, true),     // scanner published
    // 池子运营（cmd.uid 承载 shardId，跟 IF_DEPOSIT/WITHDRAW 同款）
    POOL_DEPOSIT((byte) 60, true),
    POOL_WITHDRAW((byte) 61, true),
    POOL_ABSORB_BAD_DEBT((byte) 62, true),
    // 动态利率重定价（周期系统命令，两步：R1 收池子 → merge 算全局利用率+曲线 → R2 写 currentRateBps，见 loan.md §13.2）
    REPRICE_LOAN_RATES((byte) 63, true),

    // 期货强平兜底心跳（leader 定时器 off-lane 发令，on-lane 整扫破产仓，见 liquidation 事件驱动计划）
    LIQUIDATION_SCAN((byte) 64, true),

    BINARY_DATA_QUERY((byte) 90, false),
    BINARY_DATA_COMMAND((byte) 91, true),

    PERSIST_STATE_MATCHING((byte) 110, true),
    PERSIST_STATE_RISK((byte) 111, true),
    RECOVER_STATE_MATCHING((byte) 112, true),
    RECOVER_STATE_RISK((byte) 113, true),
    

    GROUPING_CONTROL((byte) 118, false),
    NOP((byte) 120, false),
    RESET((byte) 124, true),
    SHUTDOWN_SIGNAL((byte) 127, false),

    RESERVED_COMPRESSED((byte) -1, false);
    
    
    

    private byte code;
    private boolean mutate;

    public static OrderCommandType fromCode(byte code) {
        // TODO try if-else
        final OrderCommandType result = codes.get(code);
        if (result == null) {
            throw new IllegalArgumentException("Unknown order command type code:" + code);
        }
        return result;
    }

    /**
     * loan 子域命令一等公民判断 —— RiskEngine.preProcessCommand 用它做二级 dispatch 门守，
     * 命中则整块委托给 {@code LoanCommandHandlers.dispatch}，主 switch 里永远看不到 loan 命令。
     * 详见 loan.md。
     */
    public boolean isLoan() {
        switch (this) {
            case LOAN_CREATE:
            case LOAN_REPAY:
            case LOAN_ADD_COLLATERAL:
            case LOAN_RELEASE_COLLATERAL:
            case LOAN_FORCE_LIQUIDATE:
            case LOAN_CROSS_ADD_COLLATERAL:
            case LOAN_CROSS_WITHDRAW_COLLATERAL:
            case LOAN_CROSS_BORROW:
            case LOAN_CROSS_REPAY:
            case LOAN_CROSS_FORCE_LIQUIDATE:
            case POOL_DEPOSIT:
            case POOL_WITHDRAW:
            case POOL_ABSORB_BAD_DEBT:
                return true;
            default:
                return false;
        }
    }

    private static HashMap<Byte, OrderCommandType> codes = new HashMap<>();

    static {
        for (OrderCommandType x : values()) {
            codes.put(x.code, x);
        }
    }


}
