package exchange.core2.tests.integration;

import exchange.core2.core.common.CoreCurrencySpecification;
import exchange.core2.core.common.CoreSymbolSpecification;
import exchange.core2.core.common.MarginMode;
import exchange.core2.core.common.OrderAction;
import exchange.core2.core.common.OrderType;
import exchange.core2.core.common.SymbolType;
import exchange.core2.core.common.api.ApiAddUser;
import exchange.core2.core.common.api.ApiAdjustUserBalance;
import exchange.core2.core.common.api.ApiPlaceOrder;
import exchange.core2.core.common.cmd.CommandResultCode;
import exchange.core2.core.common.config.PerformanceConfiguration;
import exchange.core2.tests.util.ExchangeTestContainer;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

/**
 * 差分模糊(exchange-core-rs conformance)抓到的现象,经此测试定性:
 * <p>
 * <b>不是释放逻辑 bug</b>:未成交(空簿)IOC 现货 ASK 的 base 锁,在管线 <b>settle 之后</b>确实被完整释放
 * ({@link #unfilledIocAskReleasesBaseLock} 通过)。
 * <p>
 * <b>是批处理 R1/R2 时序 hazard</b>:两条 IOC ASK 之间若无 report/barrier 直接连提,第二条会读到第一条
 * 尚未生效(R2 滞后)的 exchangeLocked → spurious {@code RISK_NSF}({@link #consecutiveIocAskWithoutFlushHazard})。
 * 属 exchange-core Disruptor 批处理已知特性(同 reprice R2/R1 序 hazard),用 barrier 规避,非引擎逻辑错。
 * Rust 移植是单线程同步管线(R2 恒先于下条 R1),无此 hazard。
 */
public class ITIocAskLockRelease {

    private static final int CUR_BASE = 1;
    private static final int CUR_QUOTE = 2;
    private static final int SYM = 100;

    private static CoreSymbolSpecification spotSpec() {
        return CoreSymbolSpecification.builder()
                .symbolId(SYM).type(SymbolType.CURRENCY_EXCHANGE_PAIR)
                .baseCurrency(CUR_BASE).quoteCurrency(CUR_QUOTE)
                .baseScaleK(1).quoteScaleK(1).takerFee(0).makerFee(0).build();
    }

    private static ExchangeTestContainer setup() throws Exception {
        ExchangeTestContainer c = ExchangeTestContainer.create(PerformanceConfiguration.DEFAULT);
        c.addCurrency(CoreCurrencySpecification.builder().id(CUR_BASE).digit(0).build());
        c.addCurrency(CoreCurrencySpecification.builder().id(CUR_QUOTE).digit(0).build());
        c.addSymbol(spotSpec());
        c.getApi().submitCommandAsync(ApiAddUser.builder().uid(2).build()).join();
        c.getApi().submitCommandAsync(ApiAdjustUserBalance.builder()
                .uid(2).currency(CUR_BASE).amount(1000).transactionId(1).build()).join();
        return c;
    }

    private static ApiPlaceOrder iocAsk(long oid, long size) {
        return ApiPlaceOrder.builder().uid(2).orderId(oid).symbol(SYM).price(10000).size(size)
                .action(OrderAction.ASK).orderType(OrderType.IOC).marginMode(MarginMode.ISOLATED).build();
    }

    /** settle 后释放逻辑正确:未成交 IOC ASK 撤单后 exchangeLocked 归零、可用 base 全额恢复。 */
    @Test
    public void unfilledIocAskReleasesBaseLock() throws Exception {
        try (ExchangeTestContainer c = setup()) {
            assertThat(c.getApi().submitCommandAsync(iocAsk(1, 600)).join(), is(CommandResultCode.SUCCESS));
            // getUserProfile = report query,走完整管线 → 强制 settle 上一条 R2(释放锁)。
            c.validateUserState(2, p -> {
                assertThat(p.getExchangeLocked().get(CUR_BASE), is(0L));
                assertThat(ExchangeTestContainer.available(p, CUR_BASE), is(1000L));
            });
            assertThat(c.getApi().submitCommandAsync(iocAsk(2, 600)).join(), is(CommandResultCode.SUCCESS));
        }
    }

    /** 批处理时序 hazard 特征化:两条 IOC ASK 之间不 flush → 第二条读到未 settle 的锁 → 当前引擎返回 RISK_NSF。 */
    @Test
    public void consecutiveIocAskWithoutFlushHazard() throws Exception {
        try (ExchangeTestContainer c = setup()) {
            assertThat(c.getApi().submitCommandAsync(iocAsk(1, 600)).join(), is(CommandResultCode.SUCCESS));
            // 不做任何 report/barrier,直接连提:R2(释放)滞后于本条 R1(读)。
            assertThat("批处理 R1/R2 lag:第一条 R2 锁释放未生效前,第二条 R1 误判 NSF",
                    c.getApi().submitCommandAsync(iocAsk(2, 600)).join(), is(CommandResultCode.RISK_NSF));
        }
    }
}
