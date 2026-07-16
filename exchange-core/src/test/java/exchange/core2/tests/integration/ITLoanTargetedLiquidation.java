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
package exchange.core2.tests.integration;

import static exchange.core2.tests.util.TestConstants.CURRENECY_ETH;
import static exchange.core2.tests.util.TestConstants.CURRENECY_XBT;
import static exchange.core2.tests.util.TestConstants.SYMBOLSPEC_ETH_XBT;
import static exchange.core2.tests.util.TestConstants.SYMBOL_EXCHANGE;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.util.concurrent.TimeUnit;

import exchange.core2.core.common.MarginMode;
import exchange.core2.core.common.OrderAction;
import exchange.core2.core.common.OrderType;
import exchange.core2.core.common.api.ApiLoanCreate;
import exchange.core2.core.common.api.ApiPlaceOrder;
import exchange.core2.core.common.api.ApiPoolDeposit;
import exchange.core2.core.common.api.binary.BatchAddLoanCommand;
import exchange.core2.core.common.cmd.CommandResultCode;
import exchange.core2.core.common.config.PerformanceConfiguration;
import exchange.core2.tests.util.ExchangeTestContainer;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;

/**
 * loan 强平的 targeted 事件驱动端到端验证:纯 loan 用户（无期货持仓），抵押 spot 对 MARKPRICE 暴跌即时触发强平，
 * 全程不发 {@code LIQUIDATION_SCAN}。证明 loan 索引把 targeted 路径覆盖到 loan-only 用户——mock 单测覆盖不到的整链
 * （MARKPRICE → checkPositions → checkLoans → force-sell → 撮合结算）。
 */
@Slf4j
class ITLoanTargetedLiquidation {

    private static final long BORROWER = 6001L;
    private static final long LP = 6002L;
    private static final long LOAN_ID = 77L;
    private static final long OPEN_MARK = 1000L;  // 开仓抵押价：LTV = 50k/(100·1000) = 50% < 60% initial
    private static final long CRASH_MARK = 500L;  // 暴跌后：LTV = 50k/(100·500) = 100% ≥ 80% liquidation
    private static final long ETH_COLLATERAL = 100L;
    private static final long XBT_PRINCIPAL = 50_000L;
    private static final long POOL_FUND = 1_000_000L;

    @Test
    public void collateralPriceCrash_targetedTriggersForceSell_withoutScan() {
        try (final ExchangeTestContainer c = ExchangeTestContainer.create(PerformanceConfiguration.baseBuilder().build())) {
            c.skipGlobalReconcileOnClose();

            c.addCurrency(CURRENECY_ETH, 0);
            c.addCurrency(CURRENECY_XBT, 0);
            c.addSymbol(SYMBOLSPEC_ETH_XBT);
            c.initMarkPrice(SYMBOL_EXCHANGE, OPEN_MARK);

            c.sendBinaryDataCommandSync(
                BatchAddLoanCommand.ofSymbol(SYMBOL_EXCHANGE, 6000, 8000, 7000, Long.MAX_VALUE, 365, 10000), 5000);
            c.submitCommandSync(ApiPoolDeposit.builder().externalId(1_000_001L).shardId(0)
                .currency(CURRENECY_XBT).amount(POOL_FUND).build(), CommandResultCode.SUCCESS);

            c.initOneUser(BORROWER);
            c.initOneUser(LP);
            c.addMoneyToUser(BORROWER, CURRENECY_ETH, ETH_COLLATERAL);
            c.addMoneyToUser(LP, CURRENECY_XBT, ETH_COLLATERAL * OPEN_MARK * 2);

            c.submitCommandSync(ApiLoanCreate.builder().externalId(1_000_002L).uid(BORROWER).loanId(LOAN_ID)
                .symbol(SYMBOL_EXCHANGE).collateralAmount(ETH_COLLATERAL).principal(XBT_PRINCIPAL).build(),
                CommandResultCode.SUCCESS);

            // LP 在暴跌价挂 BID，接强平的 ASK IOC 卖单
            c.submitCommandSync(ApiPlaceOrder.builder().uid(LP).orderId(1000L).action(OrderAction.BID)
                .size(ETH_COLLATERAL).price(CRASH_MARK).reservePrice(CRASH_MARK).symbol(SYMBOL_EXCHANGE)
                .orderType(OrderType.GTC).marginMode(MarginMode.ISOLATED).build(), CommandResultCode.SUCCESS);

            c.enableLiquidationEngines(); // isRunning=true，但不发 scan

            // 关键：仅抵押 spot 对 MARKPRICE 暴跌 → targeted 触发，全程不调 triggerLiquidation
            c.updateCurrentPriceTo((int) CRASH_MARK, SYMBOL_EXCHANGE, CURRENECY_XBT);

            // 轮询到 loan 抵押被强平消费（force-sell 级联异步，flush grouping + 查 loan 记录），最长 10s
            final long deadline = System.currentTimeMillis() + 10_000L;
            boolean liquidated = false;
            while (System.currentTimeMillis() < deadline) {
                c.getApi().groupingControl(0, 1);
                final long collateral = c.getUserProfile(BORROWER).getIsolatedLoans().stream()
                    .filter(l -> l.loanId == LOAN_ID).mapToLong(l -> l.collateralAmount).findFirst().orElse(0L);
                if (collateral < ETH_COLLATERAL) {
                    liquidated = true;
                    break;
                }
                TimeUnit.MILLISECONDS.sleep(20L);
            }
            assertTrue(liquidated,
                "抵押价暴跌应经 targeted 路径即时强平（无 scan）；抵押未减少说明 loan 索引未命中 loan-only 用户");
        } catch (Exception e) {
            log.error("targeted loan 强平测试失败", e);
            fail(e.getMessage());
        }
    }
}
