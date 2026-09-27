package exchange.core2.tests.integration;

import exchange.core2.core.IFundEventsHandler;
import exchange.core2.core.ITradeEventsHandler;
import exchange.core2.core.common.FundEvent;
import exchange.core2.core.common.MarginMode;
import exchange.core2.core.common.OrderAction;
import exchange.core2.core.common.OrderType;
import exchange.core2.core.common.api.ApiPlaceOrder;
import exchange.core2.core.common.api.ApiResetFee;
import exchange.core2.core.common.api.reports.TotalCurrencyBalanceReportResult;
import exchange.core2.core.common.cmd.CommandResultCode;
import exchange.core2.core.common.config.PerformanceConfiguration;
import exchange.core2.core.event.IEventsHandler4Test;
import exchange.core2.core.event.SimpleEventsProcessor4Test;
import exchange.core2.tests.util.ExchangeTestContainer;
import exchange.core2.tests.util.LatencyTools;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static exchange.core2.core.common.OrderType.GTC;
import static exchange.core2.tests.util.TestConstants.*;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.core.Is.is;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * RESET_FEE 专项：验 R1 每 shard 清 fees、matcher 阶段跨 currency 聚合、
 * R2 shardId==0 逐 currency 发一份合并 FundEvent。
 * 现有 ITFees* 只断言 fees=0 + globalBalancesAllZero，这里补齐 event 结构 + 多 currency 聚合。
 */
@Slf4j
class ITResetFee {

    private static final class ResetFeeSnap {
        final long accountId;
        final int currency;
        final long free;
        final long locked;

        ResetFeeSnap(long accountId, int currency, long free, long locked) {
            this.accountId = accountId;
            this.currency = currency;
            this.free = free;
            this.locked = locked;
        }
    }

    /**
     * 纯 handler 记 RESET_FEE 快照，回避 mockito spy/doAnswer 在 disruptor consumer 里的开销。
     * FundEventReport 走对象池，回调后可能被 recycle——所以立刻抽字段存独立 snapshot。
     */
    private static IEventsHandler4Test recordingHandler(List<ResetFeeSnap> captured) {
        return new IEventsHandler4Test() {
            @Override
            public void process(IFundEventsHandler.FundEventReport r) {
                fundEventReport(r);
            }

            @Override
            public void process(ITradeEventsHandler.SpotExecutionReport r) {
            }

            @Override
            public void process(ITradeEventsHandler.FuturesExecutionReport r) {
            }

            @Override
            public void orderBook(ITradeEventsHandler.OrderBook orderBook) {
            }

            @Override
            public void spotExecutionReport(ITradeEventsHandler.SpotExecutionReport r) {
            }

            @Override
            public void futuresExecutionReport(ITradeEventsHandler.FuturesExecutionReport r) {
            }

            @Override
            public void fundEventReport(IFundEventsHandler.FundEventReport r) {
                if (FundEvent.FundEventType.RESET_FEE == r.getEventType()) {
                    captured.add(new ResetFeeSnap(
                        r.getAccountId(),
                        r.getBalances().getCurrency(),
                        r.getBalances().getFree(),
                        r.getBalances().getLocked()));
                }
            }
        };
    }

    private static PerformanceConfiguration perfCfg() {
        return PerformanceConfiguration.baseBuilder().build();
    }

    @Test
    @Timeout(90)
    public void resetFee_spot_aggregatesAndClears() throws Exception {
        final List<ResetFeeSnap> resetFees = Collections.synchronizedList(new ArrayList<>());
        final SimpleEventsProcessor4Test processor = new SimpleEventsProcessor4Test(recordingHandler(resetFees));

        try (ExchangeTestContainer container = ExchangeTestContainer.create(perfCfg(), processor)) {
            container.addSymbol(SYMBOLSPECFEE_XBT_LTC);
            container.addCurrency(CURRENECY_XBT, 8);
            container.addCurrency(CURRENECY_LTC, 8);

            container.createUserWithMoney(UID_1, CURRENECY_LTC, 3_000_000_000L);
            container.createUserWithMoney(UID_2, CURRENECY_XBT, 200_000_000L);

            container.submitCommandSync(ApiPlaceOrder.builder()
                    .uid(UID_1).orderId(201L)
                    .price(11_400L).reservePrice(11_400L)
                    .size(20L).action(OrderAction.BID).orderType(GTC)
                    .symbol(SYMBOL_EXCHANGE_FEE).marginMode(MarginMode.ISOLATED).build(),
                cmd -> assertThat(cmd.resultCode, is(CommandResultCode.SUCCESS)));

            container.submitCommandSync(ApiPlaceOrder.builder()
                    .uid(UID_2).orderId(202L)
                    .price(11_400L)
                    .size(20L).action(OrderAction.ASK).orderType(OrderType.IOC)
                    .symbol(SYMBOL_EXCHANGE_FEE).marginMode(MarginMode.ISOLATED).build(),
                cmd -> assertThat(cmd.resultCode, is(CommandResultCode.SUCCESS)));

            TotalCurrencyBalanceReportResult preReset = container.totalBalanceReport();
            long ltcFees = preReset.getFees().get(CURRENECY_LTC);
            assertTrue(ltcFees > 0L);
            long adjLtcBefore = preReset.getAdjustments().get(CURRENECY_LTC);

            container.submitCommandSync(ApiResetFee.builder().build(), CommandResultCode.SUCCESS);
            // R2 result forward 走独立 disruptor 分支，跟 submitCommandSync 的 ack 不同步；
            // 用 totalBalanceReport 顶一次 pipeline，帮忙唤醒 R2 分支 consumer；随后 wait handler 收 event。
            TotalCurrencyBalanceReportResult postReset = container.totalBalanceReport();
            LatencyTools.waitForCondition(15_000, () -> resetFees.size() >= 1);
            assertThat(postReset.getFees().get(CURRENECY_LTC), is(0L));
            assertThat(postReset.getAdjustments().get(CURRENECY_LTC), is(adjLtcBefore + ltcFees));
            assertTrue(postReset.isGlobalBalancesAllZero());

            assertThat(resetFees.size(), is(1));
            ResetFeeSnap snap = resetFees.get(0);
            assertThat(snap.currency, is(CURRENECY_LTC));
            assertThat(snap.free, is(ltcFees));
            assertThat(snap.locked, is(0L));
            assertThat(snap.accountId, is(0L));
        }
    }

    @Test
    @Timeout(15)
    public void resetFee_empty_noEventsAndIdempotent() throws Exception {
        final List<ResetFeeSnap> resetFees = Collections.synchronizedList(new ArrayList<>());
        final SimpleEventsProcessor4Test processor = new SimpleEventsProcessor4Test(recordingHandler(resetFees));

        try (ExchangeTestContainer container = ExchangeTestContainer.create(perfCfg(), processor)) {
            container.addSymbol(SYMBOLSPECFEE_XBT_LTC);
            container.addCurrency(CURRENECY_XBT, 8);
            container.addCurrency(CURRENECY_LTC, 8);

            container.submitCommandSync(ApiResetFee.builder().build(), CommandResultCode.SUCCESS);
            container.submitCommandSync(ApiResetFee.builder().build(), CommandResultCode.SUCCESS);

            TotalCurrencyBalanceReportResult report = container.totalBalanceReport();
            assertThat(report.getFees().get(CURRENECY_LTC), is(0L));
            assertTrue(report.isGlobalBalancesAllZero());

            assertThat(resetFees.size(), is(0));
        }
    }
}
