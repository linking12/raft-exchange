package exchange.core2.tests.integration;

import exchange.core2.core.IFundEventsHandler.FundEventReport;
import exchange.core2.core.ITradeEventsHandler;
import exchange.core2.core.common.FundEvent.FundEventType;
import exchange.core2.core.common.api.ApiInternalTransfer;
import exchange.core2.core.common.cmd.CommandResultCode;
import exchange.core2.core.common.config.PerformanceConfiguration;
import exchange.core2.core.event.IEventsHandler4Test;
import exchange.core2.core.event.SimpleEventsProcessor4Test;
import exchange.core2.tests.util.ExchangeTestContainer;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * INTERNAL_TRANSFER 端到端集成测试：走真实 R1→ME→R2 pipeline，覆盖单/跨 shard 转账、守恒、幂等、
 * NSF、自转、to 自动建档。金额均为 raw 值（scale 在 client 层，此处直接用引擎单位）。
 */
@Slf4j
class ITInternalTransfer {

    private static final int USD = 2;   // digit=0，raw = 实际值
    private static final long A = 100L;
    private static final long B = 101L; // 与 A 在 2-shard 下异 shard（100&1=0 / 101&1=1）
    private static final long TX = 1_000_001L; // 高位 txId，避开 addMoneyToUser 的递增 seeding 计数器

    private static ApiInternalTransfer transfer(long from, long to, long amount, long txId) {
        return ApiInternalTransfer.builder().fromUid(from).toUid(to).currency(USD).amount(amount).transactionId(txId)
            .build();
    }

    private ExchangeTestContainer twoUsers(int shards, long balanceA) throws Exception {
        final ExchangeTestContainer c =
            ExchangeTestContainer.create(PerformanceConfiguration.baseBuilder().riskEnginesNum(shards).build());
        c.addCurrency(USD, 0);
        c.initOneUser(A);
        c.initOneUser(B);
        c.addMoneyToUser(A, USD, balanceA);
        return c;
    }

    private long balance(ExchangeTestContainer c, long uid) throws Exception {
        return c.getUserProfile(uid).getAccounts().get(USD);
    }

    @Test
    void transfer_debitsFromCreditsTo_conserved() throws Exception {
        try (ExchangeTestContainer c = twoUsers(1, 1_000L)) {
            c.submitCommandSync(transfer(A, B, 300L, TX), CommandResultCode.SUCCESS);
            assertEquals(700L, balance(c, A));
            assertEquals(300L, balance(c, B));
            assertTrue(c.totalBalanceReport().isGlobalBalancesAllZero(), "转账守恒中性");
        }
    }

    @Test
    void transfer_crossShard_atomicAndConserved() throws Exception {
        try (ExchangeTestContainer c = twoUsers(2, 1_000L)) { // A→shard0, B→shard1
            c.submitCommandSync(transfer(A, B, 400L, TX), CommandResultCode.SUCCESS);
            assertEquals(600L, balance(c, A));
            assertEquals(400L, balance(c, B));
            assertTrue(c.totalBalanceReport().isGlobalBalancesAllZero(), "跨 shard 转账守恒");
        }
    }

    @Test
    void transfer_nsf_rejected_noChange() throws Exception {
        try (ExchangeTestContainer c = twoUsers(1, 100L)) {
            c.submitCommandSync(transfer(A, B, 500L, TX), CommandResultCode.RISK_NSF);
            assertEquals(100L, balance(c, A));
            assertEquals(0L, balance(c, B));
        }
    }

    @Test
    void transfer_idempotent_replaySameTransactionId() throws Exception {
        try (ExchangeTestContainer c = twoUsers(2, 1_000L)) {
            c.submitCommandSync(transfer(A, B, 300L, TX), CommandResultCode.SUCCESS);
            // 同 transactionId 重投：拒为 ALREADY_APPLIED_SAME，余额不二次变动
            c.submitCommandSync(transfer(A, B, 300L, TX),
                CommandResultCode.USER_MGMT_ACCOUNT_BALANCE_ADJUSTMENT_ALREADY_APPLIED_SAME);
            assertEquals(700L, balance(c, A));
            assertEquals(300L, balance(c, B));
        }
    }

    @Test
    void transfer_selfTransfer_rejected() throws Exception {
        try (ExchangeTestContainer c = twoUsers(1, 1_000L)) {
            c.submitCommandSync(transfer(A, A, 100L, TX), CommandResultCode.INTERNAL_TRANSFER_INVALID_SELF);
            assertEquals(1_000L, balance(c, A));
        }
    }

    @Test
    void transfer_invalidAmount_rejected() throws Exception {
        try (ExchangeTestContainer c = twoUsers(1, 1_000L)) {
            c.submitCommandSync(transfer(A, B, 0L, TX), CommandResultCode.RISK_INVALID_AMOUNT);
            assertEquals(1_000L, balance(c, A));
        }
    }

    @Test
    void transfer_emitsFundEventForBothLegs() throws Exception {
        // from/to 两腿的 fund event 走不同 pipeline 阶段（-seq R2 / +seq E）在不同线程回调 → 用线程安全 map + 轮询等齐
        final Map<Long, Long> transferFree = new ConcurrentHashMap<>();
        final IEventsHandler4Test handler = new IEventsHandler4Test() {
            @Override
            public void process(FundEventReport r) {
                fundEventReport(r);
            }

            @Override
            public void process(ITradeEventsHandler.SpotExecutionReport r) {}

            @Override
            public void process(ITradeEventsHandler.FuturesExecutionReport r) {}

            @Override
            public void orderBook(ITradeEventsHandler.OrderBook o) {}

            @Override
            public void spotExecutionReport(ITradeEventsHandler.SpotExecutionReport r) {}

            @Override
            public void futuresExecutionReport(ITradeEventsHandler.FuturesExecutionReport r) {}

            @Override
            public void fundEventReport(FundEventReport r) {
                if (r.getEventType() == FundEventType.INTERNAL_TRANSFER) {
                    transferFree.put(r.getAccountId(), r.getBalances().getFree()); // 对象池复用，回调里立刻取值
                }
            }
        };
        try (ExchangeTestContainer c = ExchangeTestContainer.create(
            PerformanceConfiguration.baseBuilder().riskEnginesNum(2).build(), new SimpleEventsProcessor4Test(handler))) {
            c.addCurrency(USD, 0);
            c.initOneUser(A);
            c.initOneUser(B);
            c.addMoneyToUser(A, USD, 1_000L);
            c.submitCommandSync(transfer(A, B, 300L, TX), CommandResultCode.SUCCESS);
            // to 腿 fund event 走 R2(-seq) 路径，静止 pipeline 下延迟刷新；后续命令推进 pipeline 使其落地
            final long flushUser = 900L;
            c.initOneUser(flushUser);
            for (int i = 0; i < 100 && transferFree.size() < 2; i++) {
                c.addMoneyToUser(flushUser, USD, 1L); // 推进 pipeline 触发 R2 刷新（DEPOSIT 事件被类型过滤）
                Thread.sleep(2);
            }
            assertEquals(2, transferFree.size(), "两腿各一条 INTERNAL_TRANSFER 事件");
            assertEquals(700L, (long) transferFree.get(A), "付款方 free 快照");
            assertEquals(300L, (long) transferFree.get(B), "收款方 free 快照");
        }
    }

    @Test
    void transfer_toNotExist_autoCreatesAndCredits() throws Exception {
        try (ExchangeTestContainer c = twoUsers(2, 1_000L)) {
            final long unknown = 999L; // 未 initOneUser
            c.submitCommandSync(transfer(A, unknown, 250L, TX), CommandResultCode.SUCCESS);
            assertEquals(750L, balance(c, A));
            assertEquals(250L, balance(c, unknown), "未知 to 自动建档收钱");
            assertTrue(c.totalBalanceReport().isGlobalBalancesAllZero(), "自动建档仍守恒");
        }
    }
}
