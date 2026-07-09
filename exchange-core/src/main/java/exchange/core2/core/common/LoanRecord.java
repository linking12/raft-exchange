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
package exchange.core2.core.common;

/**
 * Isolated / Cross 两类贷款凭证共享的债务视图，让 accrue / repay / 强平结算的金钱逻辑只写一份
 * （见 LoanService.accrueTo / applyDebtPayment）。金额均为 loanCurrency 的 currencyScale。
 */
public interface LoanRecord {

    /** 借出币种；本金 / 利息以及计息、抵债、结算的记账都在此币种下。 */
    int getLoanCurrency();

    /** 借入时锁定的年化利率（bps），存续期不变，惰性计息用。 */
    int getRateBps();

    /** 剩余未偿本金；抵债时递减，归零即本金还清。 */
    long getOutstandingPrincipal();

    void setOutstandingPrincipal(long value);

    /** 已计提但未支付的利息；抵债按"利息优先"先抵此项，进 interestRevenue。 */
    long getAccumulatedInterest();

    void setAccumulatedInterest(long value);

    /** 上次计息时间戳（ns）；惰性 accrue 以此为起点计算新增利息，计息后推进到当前时间。 */
    long getLastAccrueTs();

    void setLastAccrueTs(long value);
}
