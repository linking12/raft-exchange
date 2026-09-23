package exchange.core2.tests.perf;

import exchange.core2.core.ExchangeApi;
import exchange.core2.core.common.MarginMode;
import exchange.core2.core.common.OrderAction;
import exchange.core2.core.common.OrderType;
import exchange.core2.core.common.CoreSymbolSpecification;
import exchange.core2.core.common.api.ApiAddUser;
import exchange.core2.core.common.api.ApiAdjustMarkPrice;
import exchange.core2.core.common.api.ApiAdjustUserBalance;
import exchange.core2.core.common.api.ApiPlaceOrder;
import exchange.core2.core.common.api.ApiSettleFundingFees;
import exchange.core2.core.common.cmd.CommandResultCode;
import exchange.core2.core.common.config.PerformanceConfiguration;
import exchange.core2.core.processors.liquidation.LiquidationEngine;
import exchange.core2.tests.util.ExchangeTestContainer;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Java-side latency of a single SETTLE_FUNDINGFEES / targeted mark-price (liquidation scan) command
 * under the throughput config (riskEnginesNum=2, so funding/liquidation run across 2 risk-engine
 * shards in parallel). Both scans now use the symbol_to_users index, so cost tracks holders.
 * Measures end-to-end submitCommandAsync().join() wall-clock (includes disruptor pipeline).
 *
 * 跑：mvn -pl exchange-core test -Dtest=FundingLiquidationLatencyBench
 */
public class FundingLiquidationLatencyBench {

    private static final long PRICE = 10_000L;
    private static final long BIG = 1_000_000_000L;

    @Test
    public void fundingAndLiquidationLatency() {
        System.out.println("Java throughput config: riskEnginesNum=2 (funding/liquidation across 2 shards)");
        System.out.printf("%12s  %20s  %24s%n", "holders", "funding join ms", "liquidation join ms");

        for (int holders : new int[]{1_000, 10_000, 100_000}) {
            try (final ExchangeTestContainer container = ExchangeTestContainer.create(PerformanceConfiguration.DEFAULT)) {
                final ExchangeApi api = container.getApi();
                final List<CoreSymbolSpecification> symbols = container.initPerpetualSymbols();
                final int symbolId = symbols.get(0).symbolId;
                final int quote = symbols.get(0).quoteCurrency;
                container.updateCurrentPriceTo((int) PRICE, symbolId, quote);

                // Build `holders` open positions via matched pairs (async submit, join the last).
                long orderId = 1;
                CompletableFuture<CommandResultCode> last = null;
                final int pairs = holders / 2;
                for (int i = 0; i < pairs; i++) {
                    final long uidLong = 2L * i + 1;
                    final long uidShort = 2L * i + 2;
                    api.submitCommandAsync(ApiAddUser.builder().uid(uidLong).build());
                    api.submitCommandAsync(ApiAddUser.builder().uid(uidShort).build());
                    api.submitCommandAsync(ApiAdjustUserBalance.builder().uid(uidLong).currency(quote).amount(BIG).transactionId(orderId++).build());
                    api.submitCommandAsync(ApiAdjustUserBalance.builder().uid(uidShort).currency(quote).amount(BIG).transactionId(orderId++).build());
                    api.submitCommandAsync(ApiPlaceOrder.builder()
                            .uid(uidLong).orderId(orderId++).symbol(symbolId).price(PRICE).size(1)
                            .action(OrderAction.BID).orderType(OrderType.GTC).leverage(1).reduceOnly(false).marginMode(MarginMode.ISOLATED).build());
                    last = api.submitCommandAsync(ApiPlaceOrder.builder()
                            .uid(uidShort).orderId(orderId++).symbol(symbolId).price(PRICE).size(1)
                            .action(OrderAction.ASK).orderType(OrderType.GTC).leverage(1).reduceOnly(false).marginMode(MarginMode.ISOLATED).build());
                }
                if (last != null) {
                    last.join();
                }

                // Arm the liquidation engines so LIQUIDATION_SCAN actually scans (same as triggerLiquidation).
                container.getExchangeCore().getLiquidationEngines().forEach(LiquidationEngine::start);

                // Warm up.
                for (int r = 0; r < 2; r++) {
                    api.submitCommandAsync(ApiSettleFundingFees.builder().transactionId(orderId++).symbol(symbolId).action(OrderAction.BID).fundingRate(100L).rateScaleK(1000L).build()).join();
                    api.submitCommandAsync(ApiAdjustMarkPrice.builder().transactionId(orderId++).symbol(symbolId).markPrice(PRICE).build()).join();
                }

                // funding join now includes the R2 on-lane checkPositions (line 980); mark-price join
                // is the targeted scan alone (adjustMarkPrice -> checkPositions), matching Rust's targeted scan.
                final double fundingMs = minMs(3, () ->
                        api.submitCommandAsync(ApiSettleFundingFees.builder().transactionId(nextId()).symbol(symbolId).action(OrderAction.BID).fundingRate(100L).rateScaleK(1000L).build()));
                final double liqMs = minMs(3, () ->
                        api.submitCommandAsync(ApiAdjustMarkPrice.builder().transactionId(nextId()).symbol(symbolId).markPrice(PRICE).build()));

                System.out.printf("%12d  %20.3f  %24.3f%n", holders, fundingMs, liqMs);
                System.out.flush();
            }
        }
    }

    private static long idSeq = 1_000_000_000L;

    private static long nextId() {
        return idSeq++;
    }

    private interface Submit {
        CompletableFuture<CommandResultCode> run();
    }

    private static double minMs(int reps, Submit s) {
        long best = Long.MAX_VALUE;
        for (int r = 0; r < reps; r++) {
            final long t0 = System.nanoTime();
            s.run().join();
            best = Math.min(best, System.nanoTime() - t0);
        }
        return best / 1e6;
    }
}
