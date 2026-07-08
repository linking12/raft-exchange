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
package exchange.core2.core.common.api;


import exchange.core2.core.common.OrderAction;
import exchange.core2.core.common.OrderType;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.RequiredArgsConstructor;

/**
 * Cross Loan 强平 IOC 单：scanner 账户级 LTV 触线时 publish。
 * <p>
 * symbol 指现货 pair (base=sellingCcy, quote=targetLoanCcy)——sellingCcy 从 pickCollateralToSell tiebreak 选出，
 * targetLoanId 从 pickLoanToRepay tiebreak 选出。preProcess 阶段 pre-move sellingCcy 抵押到 exchangeLocked，
 * post-process 阶段 quote proceeds 优先偿付 targetLoan (interest → principal)，剩余 overpay 留在账户。
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
    /** 系统生成 = LoanService.generateCrossForceSellOrderId(uid, sellingCcy) */
    public final long orderId;
    public final OrderAction action;
    public final OrderType orderType;

    @Override
    public String toString() {
        return "[LOAN_CROSS_FL o" + orderId + " s" + symbol + " u" + uid + " target" + targetLoanId + " "
            + (action == OrderAction.ASK ? 'A' : 'B') + ":IOC:" + price + ":" + size + "]";
    }
}
