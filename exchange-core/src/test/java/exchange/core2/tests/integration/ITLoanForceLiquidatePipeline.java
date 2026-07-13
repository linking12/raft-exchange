package exchange.core2.tests.integration;

import exchange.core2.core.common.IsolatedLoanRecord;
import exchange.core2.core.common.MarginMode;
import exchange.core2.core.common.MatcherEventType;
import exchange.core2.core.common.MatcherTradeEvent;
import exchange.core2.core.common.OrderAction;
import exchange.core2.core.common.OrderType;
import exchange.core2.core.common.api.ApiLoanCreate;
import exchange.core2.core.common.api.ApiLoanForceLiquidate;
import exchange.core2.core.common.api.ApiPlaceOrder;
import exchange.core2.core.common.api.ApiPoolDeposit;
import exchange.core2.core.common.api.binary.UpdateSymbolLoanConfigCommand;
import exchange.core2.core.common.api.reports.SingleUserReportResult;
import exchange.core2.core.common.cmd.CommandResultCode;
import exchange.core2.core.common.cmd.OrderCommand;
import exchange.core2.core.common.config.PerformanceConfiguration;
import exchange.core2.core.processors.loan.LoanService;
import exchange.core2.tests.util.ExchangeTestContainer;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicReference;

import static exchange.core2.tests.util.TestConstants.CURRENECY_ETH;
import static exchange.core2.tests.util.TestConstants.CURRENECY_XBT;
import static exchange.core2.tests.util.TestConstants.SYMBOL_EXCHANGE;
import static exchange.core2.tests.util.TestConstants.SYMBOLSPEC_ETH_XBT;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 完整 pipeline 集成测试：验证 LOAN_FORCE_LIQUIDATE 从 ExchangeApi 提交后穿透
 * RiskEngine.preProcessCommand → LoanCommandHandlers.dispatch → MatchingEngineRouter →
 * IOrderBook.newOrder → 撮合成 spot TRADE → R2 postProcess 分账整条链。
 *
 * <p>本测试的意义：LoanCommandHandlersTest 直调 handler 方法绕过 disruptor / 路由；
 * 32 E2E 里 loan 场景零覆盖。之前 MatchingEngineRouter 缺 LOAN_FORCE_LIQUIDATE
 * 分支导致 pre-move 完抵押永远卡在 exchangeLocked 的 bug 就是被这类 gap 藏住的。
 */
@Slf4j
class ITLoanForceLiquidatePipeline {

    private static final long BORROWER = 5001L;
    private static final long LP = 5002L;
    private static final long POOL_SHARD_UID = 0L;
    private static final long LOAN_ID = 42L;
    private static final long MARK_PRICE = 1000L;
    private static final long ETH_COLLATERAL = 100L;
    private static final long XBT_PRINCIPAL = 50_000L;
    private static final long POOL_FUND = 1_000_000L;

    @Test
    public void forceLiquidate_flowsThroughOrderbookAndSettles() throws Exception {
        try (final ExchangeTestContainer c = ExchangeTestContainer.create(PerformanceConfiguration.baseBuilder().build())) {
            c.skipGlobalReconcileOnClose();

            c.addCurrency(CURRENECY_ETH, 0);
            c.addCurrency(CURRENECY_XBT, 0);
            c.addSymbol(SYMBOLSPEC_ETH_XBT);
            c.initMarkPrice(SYMBOL_EXCHANGE, MARK_PRICE);

            c.sendBinaryDataCommandSync(
                new UpdateSymbolLoanConfigCommand(
                    SYMBOL_EXCHANGE,
                    6000,
                    8500,
                    7500,
                    0,
                    Long.MAX_VALUE,
                    365,
                    10000),
                5000);

            c.submitCommandSync(
                ApiPoolDeposit.builder()
                    .externalId(1_000_001L)
                    .shardId((int) POOL_SHARD_UID)
                    .currency(CURRENECY_XBT)
                    .amount(POOL_FUND)
                    .build(),
                CommandResultCode.SUCCESS);

            c.initOneUser(BORROWER);
            c.initOneUser(LP);
            c.addMoneyToUser(BORROWER, CURRENECY_ETH, ETH_COLLATERAL);
            c.addMoneyToUser(LP, CURRENECY_XBT, ETH_COLLATERAL * MARK_PRICE * 2);

            c.submitCommandSync(
                ApiLoanCreate.builder()
                    .externalId(1_000_002L)
                    .uid(BORROWER)
                    .loanId(LOAN_ID)
                    .symbol(SYMBOL_EXCHANGE)
                    .collateralAmount(ETH_COLLATERAL)
                    .principal(XBT_PRINCIPAL)
                    .build(),
                CommandResultCode.SUCCESS);

            c.submitCommandSync(
                ApiPlaceOrder.builder()
                    .uid(LP)
                    .orderId(1000L)
                    .action(OrderAction.BID)
                    .size(ETH_COLLATERAL)
                    .price(MARK_PRICE)
                    .reservePrice(MARK_PRICE)
                    .symbol(SYMBOL_EXCHANGE)
                    .orderType(OrderType.GTC)
                    .marginMode(MarginMode.ISOLATED)
                    .build(),
                CommandResultCode.SUCCESS);

            final IsolatedLoanRecord idHolder = new IsolatedLoanRecord();
            idHolder.uid = BORROWER;
            idHolder.loanId = LOAN_ID;
            final long forceSellOrderId = LoanService.forceSellOrderId(LoanService.ORDERID_SUBTYPE_ISOLATED, idHolder.uid, idHolder.loanId, 0L);

            final AtomicReference<OrderCommand> resultRef = new AtomicReference<>();
            c.submitCommandSync(
                ApiLoanForceLiquidate.builder()
                    .uid(BORROWER)
                    .symbol(SYMBOL_EXCHANGE)
                    .loanId(LOAN_ID)
                    .price(MARK_PRICE)
                    .size(ETH_COLLATERAL)
                    .orderId(forceSellOrderId)
                    .action(OrderAction.ASK)
                    .orderType(OrderType.IOC)
                    .build(),
                resultRef::set);

            final OrderCommand result = resultRef.get();
            assertEquals(CommandResultCode.SUCCESS, result.resultCode,
                "LOAN_FORCE_LIQUIDATE 未 SUCCESS —— router 或 orderbook 分支缺失会在这里暴露");

            assertNotNull(result.matcherEvent,
                "matcherEvent 链为空 —— 表明 cmd 没走到 orderbook.newOrder（MatchingEngineRouter bug 就是这形态）");

            int tradeCount = 0;
            for (MatcherTradeEvent ev = result.matcherEvent; ev != null; ev = ev.nextEvent) {
                if (ev.eventType == MatcherEventType.TRADE) {
                    tradeCount++;
                }
            }
            assertTrue(tradeCount > 0, "预期至少 1 条 TRADE event，实际 tradeCount=" + tradeCount);

            final SingleUserReportResult borrowerAfter = c.getUserProfile(BORROWER);
            assertEquals(0L, borrowerAfter.getExchangeLocked().get(CURRENECY_ETH),
                "抵押品应被 TRADE 消费而非滞留 exchangeLocked —— 如果 !=0 就说明 pre-move 完没撮合");

            final SingleUserReportResult lpAfter = c.getUserProfile(LP);
            assertEquals(ETH_COLLATERAL, lpAfter.getAccounts().get(CURRENECY_ETH),
                "LP 应收满 " + ETH_COLLATERAL + " ETH");
        }
    }
}
