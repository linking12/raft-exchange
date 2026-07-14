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
 * Isolated / Cross 贷款凭证共享的债务视图，让 accrue / repay / 强平结算逻辑只写一份。金额均为 loanCurrency 的 currencyScale。
 */
public interface LoanRecord {

    int getLoanCurrency();

    int getRateBps(); // 借入时锁定的年化利率（bps）

    long getOutstandingPrincipal();

    void setOutstandingPrincipal(long value);

    long getAccumulatedInterest(); // 已计提未付利息，抵债利息优先

    void setAccumulatedInterest(long value);

    long getLastAccrueTs(); // LOCKED 计息起点游标

    void setLastAccrueTs(long value);

    long getAccSnapshot(); // FLOATING 计息游标：上次 accrue 的 liveAcc 快照（bps·ms）。见 loan.md §13.5

    void setAccSnapshot(long value);

    boolean isFixedRate(); // true=Fixed 走线性计息 / false=Floating 走累加器；Cross 恒 false

    int getStuckLiqAttempts(); // 连续零成交强平次数（replicated），驱动 scanner 容差爬梯

    void setStuckLiqAttempts(int value);
}
