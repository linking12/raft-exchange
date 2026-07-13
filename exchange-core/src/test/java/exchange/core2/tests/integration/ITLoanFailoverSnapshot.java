package exchange.core2.tests.integration;

import exchange.core2.core.common.CoreSymbolSpecification;
import exchange.core2.core.common.IsolatedLoanRecord;
import exchange.core2.core.common.OrderAction;
import exchange.core2.core.common.OrderType;
import exchange.core2.core.common.SymbolType;
import exchange.core2.core.common.api.ApiLoanCreate;
import exchange.core2.core.common.api.ApiLoanCrossAddCollateral;
import exchange.core2.core.common.api.ApiLoanCrossBorrow;
import exchange.core2.core.common.api.ApiLoanForceLiquidate;
import exchange.core2.core.common.api.ApiPersistState;
import exchange.core2.core.common.api.ApiPoolDeposit;
import exchange.core2.core.common.api.ApiRecoverState;
import exchange.core2.core.common.api.binary.UpdateLoanGlobalConfigCommand;
import exchange.core2.core.common.api.binary.UpdateSymbolLoanConfigCommand;
import exchange.core2.core.common.cmd.CommandResultCode;
import exchange.core2.core.common.config.InitialStateConfiguration;
import exchange.core2.core.common.config.PerformanceConfiguration;
import exchange.core2.core.common.config.SerializationConfiguration;
import exchange.core2.core.processors.liquidation.LiquidationEngine;
import exchange.core2.core.processors.loan.LoanService;
import exchange.core2.tests.util.ExchangeTestContainer;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Loan 的 failover / snapshot 幂等验证。
 *
 * <p>失败切换安全性依赖：① loan 状态（loan records + LoanService 池子/坏账/收入桶）完整进 raft snapshot；
 * ② 新 leader 从快照恢复出<b>字节一致</b>的状态。本测试用真实 DISK 快照 round-trip 证明这两点：
 * 建仓 + 全拒强平(卡单) + 开一笔 Cross 贷 → snapshot → 全新实例恢复 → <b>stateHash 完全一致 + 全局守恒</b>。
 *
 * <p>stateHash 一致意味着恢复出的"新 leader"看到的 loan / 池子 / 抵押状态与原 leader 逐字节相同，
 * 因此其 scanner（纯状态函数）会做出相同决策，不会双重/过量强平。scanner 决策的状态驱动性由
 * {@code LoanLiquidationEngineTest.check_freshScanner_*} 单测另行覆盖。
 */
@Slf4j
class ITLoanFailoverSnapshot {

    private static final int WBTC = 720;   // base：digit=2 → currencyScaleK=100
    private static final int USDT = 721;   // quote：digit=0
    private static final int SYMBOL = 72010; // baseScaleK=1 → scale 错配
    private static final long MARK = 50_000L;
    private static final long POOL_FUND = 10_000_000L;
    private static final long BORROWER = 9001L;
    private static final long LP = 9002L;

    private void setupLoansWithStuckLiquidation(ExchangeTestContainer c) throws Exception {
        c.addCurrency(WBTC, 2);
        c.addCurrency(USDT, 0);
        c.addSymbol(CoreSymbolSpecification.builder()
            .symbolId(SYMBOL).type(SymbolType.CURRENCY_EXCHANGE_PAIR)
            .baseCurrency(WBTC).quoteCurrency(USDT).baseScaleK(1).quoteScaleK(1).takerFee(0).makerFee(0).build());
        c.initMarkPrice(SYMBOL, MARK);
        c.sendBinaryDataCommandSync(
            new UpdateSymbolLoanConfigCommand(SYMBOL, 6000, 8500, 7500, 0, Long.MAX_VALUE, 365, 10000), 5000);
        c.sendBinaryDataCommandSync(new UpdateLoanGlobalConfigCommand(USDT), 5001);
        c.submitCommandSync(ApiPoolDeposit.builder()
            .externalId(1_000_001L).shardId(0).currency(USDT).amount(POOL_FUND).build(), CommandResultCode.SUCCESS);
        c.initOneUser(BORROWER);
        c.initOneUser(LP);
        c.addMoneyToUser(BORROWER, WBTC, 1_000L);          // 10 WBTC
        c.addMoneyToUser(LP, USDT, POOL_FUND);

        // Isolated：抵押 3 WBTC 借 80k，然后无对手盘强平 → 全拒 → 抵押/债务原样保留 + stuckLiqAttempts=1（覆盖新字段）
        c.submitCommandSync(ApiLoanCreate.builder()
            .externalId(1_000_002L).uid(BORROWER).loanId(1L).symbol(SYMBOL)
            .collateralAmount(300L).principal(80_000L).build(), CommandResultCode.SUCCESS);
        IsolatedLoanRecord id = new IsolatedLoanRecord();
        id.uid = BORROWER;
        id.loanId = 1L;
        c.submitCommandSync(ApiLoanForceLiquidate.builder()
            .uid(BORROWER).symbol(SYMBOL).loanId(1L).price(MARK).size(3L) // book 空 → IOC 全拒
            .orderId(LoanService.generateIsolatedForceSellOrderId(id))
            .action(OrderAction.ASK).orderType(OrderType.IOC).build(), CommandResultCode.SUCCESS);

        // Cross：账户级抵押 3 WBTC 借 60k，保持开仓（丰富 crossLoans / crossLoanCollateral / 池子桶）
        c.submitCommandSync(ApiLoanCrossAddCollateral.builder()
            .externalId(1_000_003L).uid(BORROWER).currency(WBTC).amount(300L).build(), CommandResultCode.SUCCESS);
        c.submitCommandSync(ApiLoanCrossBorrow.builder()
            .externalId(1_000_004L).uid(BORROWER).loanId(2L).loanCurrency(USDT).principal(60_000L).build(),
            CommandResultCode.SUCCESS);
    }

    @Test
    public void loanState_survivesSnapshotRestore_identicalHashAndConserved() throws Exception {
        final PerformanceConfiguration perf =
            PerformanceConfiguration.baseBuilder().matchingEnginesNum(1).riskEnginesNum(1).build();
        final String exchangeId = String.format("%012X", System.nanoTime());
        final long stateId = System.nanoTime();
        final int originalHash;

        // ===== 原 leader：建仓 + 全拒强平(stuck) + Cross，然后落快照 =====
        try (ExchangeTestContainer c = ExchangeTestContainer.create(
                perf, InitialStateConfiguration.cleanStart(exchangeId), SerializationConfiguration.DISK_SNAPSHOT_ONLY)) {
            c.getExchangeCore().getLiquidationEngines().forEach(LiquidationEngine::stop); // 手动控，避免自动强平干扰快照
            setupLoansWithStuckLiquidation(c);
            assertTrue(c.totalBalanceReport().isGlobalBalancesAllZero(), "快照前应守恒");

            c.submitCommandSync(ApiPersistState.builder().dumpId(stateId).build(), CommandResultCode.SUCCESS);
            originalHash = c.requestStateHash();
        }

        // ===== 新 leader：从快照恢复，比对 stateHash + 守恒 =====
        try (ExchangeTestContainer r = ExchangeTestContainer.create(perf,
                InitialStateConfiguration.fromSnapshotOnly(exchangeId, stateId, 0),
                SerializationConfiguration.DISK_SNAPSHOT_ONLY)) {
            r.getExchangeCore().getLiquidationEngines().forEach(LiquidationEngine::stop);
            r.getApi().submitRecoverCommandAsync(ApiRecoverState.builder().snapshotId(stateId).build()).get();
            r.totalBalanceReport(); // 等 core 起来

            assertEquals(originalHash, r.requestStateHash(),
                "恢复后 stateHash 必须与原 leader 逐字节一致（loan records + 池子桶都在快照里）");
            assertTrue(r.totalBalanceReport().isGlobalBalancesAllZero(), "恢复后应守恒");
        }
    }
}
