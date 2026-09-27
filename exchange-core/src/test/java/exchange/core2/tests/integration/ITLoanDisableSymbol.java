package exchange.core2.tests.integration;

import static exchange.core2.tests.util.TestConstants.CURRENECY_ETH;
import static exchange.core2.tests.util.TestConstants.CURRENECY_XBT;
import static exchange.core2.tests.util.TestConstants.SYMBOLSPEC_ETH_XBT;
import static exchange.core2.tests.util.TestConstants.SYMBOL_EXCHANGE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

import java.util.concurrent.TimeUnit;

import exchange.core2.core.common.api.ApiLoanCreate;
import exchange.core2.core.common.api.ApiPoolDeposit;
import exchange.core2.core.common.api.binary.BatchAddLoanCommand;
import exchange.core2.core.common.cmd.CommandResultCode;
import exchange.core2.core.common.config.PerformanceConfiguration;
import exchange.core2.tests.util.ExchangeTestContainer;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;

/**
 * 停借（ADD_LOAN 把 initialLtvBps 置 0）只关新开仓的闸，不得动存量贷款。
 *
 * <p>liquidationLtv / marginCallLtv / collateralWeight 都从 initialLtv 派生，跟着归零会把该 pair 上的存量贷款
 * 连带强平——运营做最自然的动作就会引爆，故用测试钉死。
 */
@Slf4j
class ITLoanDisableSymbol {

    private static final long BORROWER = 6101L;
    private static final long LOAN_ID = 88L;
    private static final long MARK = 1000L;
    private static final long ETH_COLLATERAL = 100L;
    private static final long XBT_PRINCIPAL = 50_000L; // LTV = 50% < 60% initial
    private static final long POOL_FUND = 1_000_000L;

    @Test
    public void disableSymbol_blocksNewLoans_butKeepsExistingUnliquidated() {
        try (final ExchangeTestContainer c = ExchangeTestContainer.create(PerformanceConfiguration.baseBuilder().build())) {
            c.skipGlobalReconcileOnClose();

            c.addCurrency(CURRENECY_ETH, 0);
            c.addCurrency(CURRENECY_XBT, 0);
            c.addSymbol(SYMBOLSPEC_ETH_XBT);
            c.initMarkPrice(SYMBOL_EXCHANGE, MARK);

            c.sendBinaryDataCommandSync(
                BatchAddLoanCommand.ofSymbol(SYMBOL_EXCHANGE, 6000, 8000, 7000, Long.MAX_VALUE, 365, 10000), 5000);
            c.submitCommandSync(ApiPoolDeposit.builder().shardId(0)
                .currency(CURRENECY_XBT).amount(POOL_FUND).build(), CommandResultCode.SUCCESS);

            c.initOneUser(BORROWER);
            c.addMoneyToUser(BORROWER, CURRENECY_ETH, ETH_COLLATERAL * 2);
            c.submitCommandSync(ApiLoanCreate.builder().transactionId(1_000_001L).uid(BORROWER).loanId(LOAN_ID)
                .symbol(SYMBOL_EXCHANGE).collateralAmount(ETH_COLLATERAL).principal(XBT_PRINCIPAL).build(),
                CommandResultCode.SUCCESS);

            // 停借：只给 initialLtvBps=0，其余走派生（这正是会踩雷的用法）
            c.sendBinaryDataCommandSync(BatchAddLoanCommand.ofMarket(SYMBOL_EXCHANGE, 0).build(), 5001);

            c.submitCommandSync(ApiLoanCreate.builder().transactionId(1_000_002L).uid(BORROWER).loanId(LOAN_ID + 1)
                .symbol(SYMBOL_EXCHANGE).collateralAmount(ETH_COLLATERAL).principal(XBT_PRINCIPAL).build(),
                CommandResultCode.LOAN_NOT_ENABLED);

            // 存量贷款：LTV 仍 50%，远低于原 80% 强平线，价格没动也不该被碰
            c.enableLiquidationEngines();
            c.updateCurrentPriceTo((int) MARK, SYMBOL_EXCHANGE, CURRENECY_XBT);
            TimeUnit.MILLISECONDS.sleep(200L);
            c.getApi().groupingControl(0, 1);

            final long collateral = c.getUserProfile(BORROWER).getIsolatedLoans().stream()
                .filter(l -> l.loanId == LOAN_ID).mapToLong(l -> l.collateralAmount).findFirst().orElse(-1L);
            assertEquals(ETH_COLLATERAL, collateral,
                "停借不得动存量：liquidationLtv 若跟着 initialLtv 归零，此处抵押会被强平消费掉");
        } catch (Exception e) {
            log.error("停借测试失败", e);
            fail(e.getMessage());
        }
    }
}
