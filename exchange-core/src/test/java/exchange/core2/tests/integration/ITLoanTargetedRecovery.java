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

import java.util.concurrent.TimeUnit;

import exchange.core2.core.common.MarginMode;
import exchange.core2.core.common.OrderAction;
import exchange.core2.core.common.OrderType;
import exchange.core2.core.common.api.ApiLoanCreate;
import exchange.core2.core.common.api.ApiPersistState;
import exchange.core2.core.common.api.ApiPlaceOrder;
import exchange.core2.core.common.api.ApiPoolDeposit;
import exchange.core2.core.common.api.ApiRecoverState;
import exchange.core2.core.common.api.binary.BatchAddLoanCommand;
import exchange.core2.core.common.cmd.CommandResultCode;
import exchange.core2.core.common.config.InitialStateConfiguration;
import exchange.core2.core.common.config.PerformanceConfiguration;
import exchange.core2.core.common.config.SerializationConfiguration;
import exchange.core2.core.processors.liquidation.LiquidationEngine;
import exchange.core2.tests.util.ExchangeTestContainer;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;

/**
 * 验证 loan targeted 索引在 snapshot 恢复后仍工作：建仓 → 落盘快照 → 全新实例 {@code ApiRecoverState} 恢复
 * （驱动 {@code RiskEngine.recoverStateBySnapshot} → {@code LiquidationEngine.updateProvider} →
 * {@code LoanLiquidationEngine.updateProvider} 从恢复出的用户态重建 isolatedLoanSymbolToUsers /
 * crossLoanCurrencyToUsers）→ 恢复后仅发 MARKPRICE 抵押价暴跌（不发 {@code LIQUIDATION_SCAN}）→
 * 轮询到抵押被 targeted 强平消费。
 *
 * <p>对齐 {@link ITLoanTargetedLiquidation}（targeted 端到端，未过 failover）与 {@link ITLoanFailoverSnapshot}
 * （snapshot round-trip，未验证 targeted 命中）：本测试证明二者组合——targeted 索引不是构造时的一次性产物，
 * recover 路径重建出的索引同样可用，新 leader 不会因为索引空白而漏检。
 */
@Slf4j
class ITLoanTargetedRecovery {

    private static final long BORROWER = 7001L;
    private static final long LP = 7002L;
    private static final long LOAN_ID = 88L;
    private static final long OPEN_MARK = 1000L;  // 开仓抵押价：LTV = 50k/(100·1000) = 50% < 60% initial
    private static final long CRASH_MARK = 500L;  // 暴跌后：LTV = 50k/(100·500) = 100% ≥ 80% liquidation
    private static final long ETH_COLLATERAL = 100L;
    private static final long XBT_PRINCIPAL = 50_000L;
    private static final long POOL_FUND = 1_000_000L;

    @Test
    void loanIndex_rebuildsAfterSnapshotRecovery_targetedStillTriggersForceSell() throws Exception {
        final PerformanceConfiguration perf =
            PerformanceConfiguration.baseBuilder().matchingEnginesNum(1).riskEnginesNum(1).build();
        final String exchangeId = String.format("%012X", System.nanoTime());
        final long stateId = System.nanoTime();

        // ===== 原 leader：建仓，落盘快照 =====
        try (ExchangeTestContainer c = ExchangeTestContainer.create(
                perf, InitialStateConfiguration.cleanStart(exchangeId), SerializationConfiguration.DISK_SNAPSHOT_ONLY)) {
            c.getExchangeCore().getLiquidationEngines().forEach(LiquidationEngine::stop); // 手动控，避免自动强平干扰快照

            c.addCurrency(CURRENECY_ETH, 0);
            c.addCurrency(CURRENECY_XBT, 0);
            c.addSymbol(SYMBOLSPEC_ETH_XBT);
            c.initMarkPrice(SYMBOL_EXCHANGE, OPEN_MARK);

            c.sendBinaryDataCommandSync(
                BatchAddLoanCommand.ofSymbol(SYMBOL_EXCHANGE, 6000, 8000, 7000, Long.MAX_VALUE, 365, 10000), 5000);
            c.submitCommandSync(ApiPoolDeposit.builder().externalId(2_000_001L).shardId(0)
                .currency(CURRENECY_XBT).amount(POOL_FUND).build(), CommandResultCode.SUCCESS);

            c.initOneUser(BORROWER);
            c.initOneUser(LP);
            c.addMoneyToUser(BORROWER, CURRENECY_ETH, ETH_COLLATERAL);
            c.addMoneyToUser(LP, CURRENECY_XBT, ETH_COLLATERAL * OPEN_MARK * 2);

            // externalId 用大唯一值：避免与 addMoneyToUser 内部自增 txid（从 1 起）撞号触发幂等 ALREADY_APPLIED
            c.submitCommandSync(ApiLoanCreate.builder().externalId(2_000_002L).uid(BORROWER).loanId(LOAN_ID)
                .symbol(SYMBOL_EXCHANGE).collateralAmount(ETH_COLLATERAL).principal(XBT_PRINCIPAL).build(),
                CommandResultCode.SUCCESS);

            assertTrue(c.totalBalanceReport().isGlobalBalancesAllZero(), "快照前应守恒");
            c.submitCommandSync(ApiPersistState.builder().dumpId(stateId).build(), CommandResultCode.SUCCESS);
        }

        // ===== 全新实例：从快照恢复（驱动 LoanLiquidationEngine.updateProvider 重建 targeted 索引） =====
        try (ExchangeTestContainer r = ExchangeTestContainer.create(perf,
                InitialStateConfiguration.fromSnapshotOnly(exchangeId, stateId, 0),
                SerializationConfiguration.DISK_SNAPSHOT_ONLY)) {
            r.getExchangeCore().getLiquidationEngines().forEach(LiquidationEngine::stop);
            r.getApi().submitRecoverCommandAsync(ApiRecoverState.builder().snapshotId(stateId).build()).get();
            r.totalBalanceReport(); // 等 core 起来

            // LP 在暴跌价挂 BID，接强平的 ASK IOC 卖单（LP 余额已随快照恢复，这里只补挂单）
            r.submitCommandSync(ApiPlaceOrder.builder().uid(LP).orderId(2000L).action(OrderAction.BID)
                .size(ETH_COLLATERAL).price(CRASH_MARK).reservePrice(CRASH_MARK).symbol(SYMBOL_EXCHANGE)
                .orderType(OrderType.GTC).marginMode(MarginMode.ISOLATED).build(), CommandResultCode.SUCCESS);

            r.enableLiquidationEngines(); // isRunning=true，但不发 scan

            // 关键：仅抵押 spot 对 MARKPRICE 暴跌 → targeted 触发；若恢复后索引未重建，本该命中的 loan-only 用户会被漏检
            r.updateCurrentPriceTo((int) CRASH_MARK, SYMBOL_EXCHANGE, CURRENECY_XBT);

            // 轮询到 loan 抵押被强平消费（force-sell 级联异步，flush grouping + 查 loan 记录），最长 10s
            final long deadline = System.currentTimeMillis() + 10_000L;
            boolean liquidated = false;
            while (System.currentTimeMillis() < deadline) {
                r.getApi().groupingControl(0, 1);
                final long collateral = r.getUserProfile(BORROWER).getIsolatedLoans().stream()
                    .filter(l -> l.loanId == LOAN_ID).mapToLong(l -> l.collateralAmount).findFirst().orElse(0L);
                if (collateral < ETH_COLLATERAL) {
                    liquidated = true;
                    break;
                }
                TimeUnit.MILLISECONDS.sleep(20L);
            }
            assertTrue(liquidated,
                "snapshot 恢复后抵押价暴跌应仍经 targeted 路径即时强平；抵押未减少说明恢复后 loan 索引未重建/未命中");
        }
    }
}
