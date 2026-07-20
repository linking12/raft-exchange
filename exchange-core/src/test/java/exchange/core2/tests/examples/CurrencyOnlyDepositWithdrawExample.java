package exchange.core2.tests.examples;

import exchange.core2.core.ExchangeApi;
import exchange.core2.core.ExchangeCore;
import exchange.core2.core.IFundEventsHandler;
import exchange.core2.core.ITradeEventsHandler;
import exchange.core2.core.SimpleEventsProcessor;
import exchange.core2.core.common.CoreCurrencySpecification;
import exchange.core2.core.common.api.ApiAddUser;
import exchange.core2.core.common.api.ApiAdjustUserBalance;
import exchange.core2.core.common.api.binary.BatchAddCurrenciesCommand;
import exchange.core2.core.common.cmd.CommandResultCode;
import exchange.core2.core.common.config.ExchangeConfiguration;
import org.junit.jupiter.api.Test;

import java.util.concurrent.Future;

/**
 * 演示「只注册 Currency、不注册 Symbol」场景下的完整充值/提现流程，
 * 自定义 IFundEventsHandler 把每一条 FundEvent 还原后打到 stdout。
 *
 * 运行：mvn -pl exchange-core test -Dtest=CurrencyOnlyDepositWithdrawExample
 */
public class CurrencyOnlyDepositWithdrawExample {

    private static final int  USDT_ID      = 2;
    private static final long UID          = 42L;
    private static final long USDT_SCALE_K = 1_000_000L; // digit=6 → 1 USDT = 10^6 内部单位

    @Test
    void fullFlow() throws Exception {
        // ---------- 1) 装配监听器 ----------
        SimpleEventsProcessor processor = new SimpleEventsProcessor(
                new ITradeEventsHandler() {
                    @Override public void spotExecutionReport(SpotExecutionReport r)    { /* 无现货撮合 */ }
                    @Override public void futuresExecutionReport(FuturesExecutionReport r) { /* 无期货 */ }
                    @Override public void orderBook(OrderBook ob)                       { /* 无订单簿 */ }
                },
                CurrencyOnlyDepositWithdrawExample::printRestored);

        ExchangeConfiguration conf = ExchangeConfiguration.defaultBuilder().build();
        ExchangeCore exchangeCore = ExchangeCore.builder()
                .resultsConsumer(processor)
                .exchangeConfiguration(conf)
                .build();
        exchangeCore.startup();
        ExchangeApi api = exchangeCore.getApi();

        // ---------- 2) 注册 USDT（不注册任何 Symbol）----------
        CoreCurrencySpecification usdt = CoreCurrencySpecification.builder()
                .id(USDT_ID).name("USDT").digit(6).build();
        Future<CommandResultCode> f0 = api.submitBinaryDataAsync(new BatchAddCurrenciesCommand(usdt));
        System.out.println(">> addCurrency(USDT)            → " + f0.get());

        // ---------- 3) 创建用户 ----------
        CommandResultCode r1 = api.submitCommandAsync(
                ApiAddUser.builder().uid(UID).build()).get();
        System.out.println(">> addUser(uid=42)              → " + r1);

        // ---------- 4) 充值 1000 USDT → DEPOSIT ----------
        CommandResultCode r2 = api.submitCommandAsync(
                ApiAdjustUserBalance.builder()
                        .uid(UID).currency(USDT_ID)
                        .amount(1000L * USDT_SCALE_K)        // +1_000_000_000
                        .transactionId(1L).build()).get();
        System.out.println(">> deposit(+1000 USDT)          → " + r2);

        // ---------- 5) 提现 300 USDT → WITHDRAW ----------
        CommandResultCode r3 = api.submitCommandAsync(
                ApiAdjustUserBalance.builder()
                        .uid(UID).currency(USDT_ID)
                        .amount(-300L * USDT_SCALE_K)        // -300_000_000
                        .transactionId(2L).build()).get();
        System.out.println(">> withdraw(-300 USDT)          → " + r3);

        // ---------- 6) 提现 1000 USDT（余额不足）→ 无事件 ----------
        CommandResultCode r4 = api.submitCommandAsync(
                ApiAdjustUserBalance.builder()
                        .uid(UID).currency(USDT_ID)
                        .amount(-1000L * USDT_SCALE_K)
                        .transactionId(3L).build()).get();
        System.out.println(">> withdraw(-1000 USDT)         → " + r4 + "   (无事件)");

        Thread.sleep(100); // 等异步事件流出
        exchangeCore.shutdown();
    }

    /**
     * FundEventReport 还原器（in-process 版本，直接吃 POJO）。
     * 与 raft-exchange-spi 里的 FundEventRestorer 逻辑一致 ——
     * 都是 raw long ÷ 对应 scale 系数。
     */
    private static void printRestored(IFundEventsHandler.FundEventReport report) {
        IFundEventsHandler.FundEventReport.BalanceSnapshot b = report.getBalances();
        long cur = b.getCurrencyScaleK();
        double free   = cur == 0 ? 0.0 : b.getFree()   / (double) cur;
        double locked = cur == 0 ? 0.0 : b.getLocked() / (double) cur;
        System.out.printf("   [FUND-EVENT] %-9s uid=%d currency=%d free=%.6f locked=%.6f%n",
                report.getEventType(), report.getAccountId(), b.getCurrency(), free, locked);
    }
}
