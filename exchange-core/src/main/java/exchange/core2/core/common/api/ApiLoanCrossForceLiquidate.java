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
package exchange.core2.core.common.api;

import exchange.core2.core.common.OrderAction;
import exchange.core2.core.common.OrderType;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.RequiredArgsConstructor;

/**
 * LOAN_CROSS_FORCE_LIQUIDATE —— Cross 账户级强平 IOC 单，scanner 触发（详见 loan.md §7.3）。
 * symbol 指现货 pair（base=卖出币, quote=targetLoan 的借款币）；price 是破产价，size 是撮合张数(lot)。
 */
@Builder
@EqualsAndHashCode(callSuper = false)
@RequiredArgsConstructor
public final class ApiLoanCrossForceLiquidate extends ApiCommand {

    public final long uid;
    public final int symbol;
    /** 本轮偿还的目标 loanId */
    public final long targetLoanId;
    /** 限价 IOC 价格 = markPrice × (1 − tolerance) */
    public final long price;
    /** 要卖的 collateral 量 */
    public final long size;
    /** 系统生成 = LoanService.forceSellOrderId(ORDERID_SUBTYPE_CROSS, shardId, seq) */
    public final long orderId;
    public final OrderAction action;
    public final OrderType orderType;

    @Override
    public String toString() {
        return "[LOAN_CROSS_FL o" + orderId + " s" + symbol + " u" + uid + " target" + targetLoanId + " "
            + (action == OrderAction.ASK ? 'A' : 'B') + ":IOC:" + price + ":" + size + "]";
    }
}
