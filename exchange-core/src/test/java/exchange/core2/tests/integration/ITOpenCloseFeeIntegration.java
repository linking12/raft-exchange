package exchange.core2.tests.integration;

import exchange.core2.core.common.api.reports.TotalCurrencyBalanceReportResult;
import exchange.core2.core.common.config.PerformanceConfiguration;
import exchange.core2.tests.util.ExchangeTestContainer;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static exchange.core2.tests.util.TestConstants.BASE_CURRENCY_ID;
import static exchange.core2.tests.util.TestConstants.UID_1;
import static exchange.core2.tests.util.TestConstants.UID_2;
import static exchange.core2.tests.util.TestConstants.UID_3;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.core.Is.is;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 端到端验证开仓 / 关仓手续费的 6 个核心不变量。
 *
 * <p>使用 {@code initFutureSymbol}（fixed fee 模式：makerFee=10, takerFee=20，
 * 不依赖 price），以便所有金额都是手算可验的整数。
 *
 * <p>跟其他大量已有 IT 不同，这个文件**只**关心 fee 行为，不验证 position size /
 * margin / event 数量等无关字段，便于将来公式漂移时定位回此处。
 */
@Slf4j
final class ITOpenCloseFeeIntegration {

    private static final int SYMBOL_ID = 2;
    private static final int QUOTE_ID = 840;
    private static final long DEPOSIT = 1_000_000L;
    private static final long PRICE = 10_000L;

    // initFutureSymbol 写死的费率
    private static final long MAKER_FEE_PER_CONTRACT = 10L;
    private static final long TAKER_FEE_PER_CONTRACT = 20L;

    private static PerformanceConfiguration cfg() {
        return PerformanceConfiguration.DEFAULT;
    }

    private static ExchangeTestContainer freshContainer() throws Exception {
        ExchangeTestContainer c = ExchangeTestContainer.create(cfg());
        c.initFutureSymbol(SYMBOL_ID, QUOTE_ID);
        c.addCurrency(BASE_CURRENCY_ID);
        c.addCurrency(QUOTE_ID);
        c.initMarkPrice(SYMBOL_ID, PRICE);
        return c;
    }

    @Test
    @Timeout(10)
    @SuppressWarnings("try")
    void pureOpen_chargesMakerAndTakerFee() throws Exception {
        // 纯开仓场景：maker 挂 ASK，taker 吃单。maker 付 makerFee × size，taker 付 takerFee × size，
        // fees bucket 增加二者之和。
        try (ExchangeTestContainer c = freshContainer()) {
            c.createUserWithSpecificMoney(UID_1, DEPOSIT, QUOTE_ID);
            c.createUserWithSpecificMoney(UID_2, DEPOSIT, QUOTE_ID);

            long size = 5;
            c.createBidWithOrderId(101L, UID_1, (int) size, PRICE, SYMBOL_ID);
            c.createAskWithOrderId(102L, UID_2, (int) size, PRICE, SYMBOL_ID);

            long expectedMakerFee = MAKER_FEE_PER_CONTRACT * size; // 50
            long expectedTakerFee = TAKER_FEE_PER_CONTRACT * size; // 100

            c.validateUserState(UID_1, profile ->
                    assertThat("maker 开仓后扣 makerFee", profile.getAccounts().get(QUOTE_ID),
                            is(DEPOSIT - expectedMakerFee)));
            c.validateUserState(UID_2, profile ->
                    assertThat("taker 开仓后扣 takerFee", profile.getAccounts().get(QUOTE_ID),
                            is(DEPOSIT - expectedTakerFee)));

            TotalCurrencyBalanceReportResult bal = c.totalBalanceReport();
            assertThat("fees bucket = makerFee + takerFee", bal.getFees().get(QUOTE_ID),
                    is(expectedMakerFee + expectedTakerFee));
            assertTrue(bal.isGlobalBalancesAllZero(), "全局守恒方程闭环");
        }
    }

    @Test
    @Timeout(10)
    @SuppressWarnings("try")
    void pureClose_chargesMakerAndTakerFee_atSameRateAsOpen() throws Exception {
        // 开 → 平：开和平按同一费率收。verify maker/taker 都被收了 2 次。
        try (ExchangeTestContainer c = freshContainer()) {
            c.createUserWithSpecificMoney(UID_1, DEPOSIT, QUOTE_ID);
            c.createUserWithSpecificMoney(UID_2, DEPOSIT, QUOTE_ID);

            long size = 5;
            // 开仓
            c.createBidWithOrderId(101L, UID_1, (int) size, PRICE, SYMBOL_ID);
            c.createAskWithOrderId(102L, UID_2, (int) size, PRICE, SYMBOL_ID);
            // 平仓（反向，同价位）
            c.createAskWithOrderId(103L, UID_1, (int) size, PRICE, SYMBOL_ID);
            c.createBidWithOrderId(104L, UID_2, (int) size, PRICE, SYMBOL_ID);

            // 第二轮 UID_1 是 maker（先挂 ASK），UID_2 是 taker（吃 BID）。
            // 所以 UID_1 一直 maker（开 + 关），UID_2 一直 taker（开 + 关）。
            long expectedMakerFeeTotal = 2 * MAKER_FEE_PER_CONTRACT * size; // 开 + 关
            long expectedTakerFeeTotal = 2 * TAKER_FEE_PER_CONTRACT * size;

            c.validateUserState(UID_1, profile -> {
                assertThat("UID_1 仓位平完", profile.getPositions().size(), is(0));
                assertThat("UID_1: deposit - (开 maker fee + 关 maker fee) [价差 = 0]",
                        profile.getAccounts().get(QUOTE_ID), is(DEPOSIT - expectedMakerFeeTotal));
            });
            c.validateUserState(UID_2, profile -> {
                assertThat("UID_2 仓位平完", profile.getPositions().size(), is(0));
                assertThat("UID_2: deposit - (开 taker fee + 关 taker fee) [价差 = 0]",
                        profile.getAccounts().get(QUOTE_ID), is(DEPOSIT - expectedTakerFeeTotal));
            });

            TotalCurrencyBalanceReportResult bal = c.totalBalanceReport();
            assertThat("fees bucket = 4 笔 fee 之和",
                    bal.getFees().get(QUOTE_ID), is(expectedMakerFeeTotal + expectedTakerFeeTotal));
            assertTrue(bal.isGlobalBalancesAllZero(), "全程守恒");
        }
    }

    @Test
    @Timeout(10)
    @SuppressWarnings("try")
    void closeFee_swapsSideOnRoleSwitch() throws Exception {
        // 第二轮换边：开仓 UID_1 maker / UID_2 taker，关仓 UID_2 maker / UID_1 taker。
        // 验证 maker/taker 角色互换时关仓费按新角色收，不是按开仓时的角色。
        try (ExchangeTestContainer c = freshContainer()) {
            c.createUserWithSpecificMoney(UID_1, DEPOSIT, QUOTE_ID);
            c.createUserWithSpecificMoney(UID_2, DEPOSIT, QUOTE_ID);

            long size = 5;
            // 开仓：UID_1 maker BID, UID_2 taker ASK
            c.createBidWithOrderId(101L, UID_1, (int) size, PRICE, SYMBOL_ID);
            c.createAskWithOrderId(102L, UID_2, (int) size, PRICE, SYMBOL_ID);
            // 关仓换边：UID_2 maker BID（先挂）, UID_1 taker ASK（吃）
            c.createBidWithOrderId(103L, UID_2, (int) size, PRICE, SYMBOL_ID);
            c.createAskWithOrderId(104L, UID_1, (int) size, PRICE, SYMBOL_ID);

            long uid1Fee = MAKER_FEE_PER_CONTRACT * size + TAKER_FEE_PER_CONTRACT * size; // 开 maker + 关 taker
            long uid2Fee = TAKER_FEE_PER_CONTRACT * size + MAKER_FEE_PER_CONTRACT * size; // 开 taker + 关 maker

            c.validateUserState(UID_1, profile ->
                    assertThat("UID_1: 开 maker + 关 taker", profile.getAccounts().get(QUOTE_ID),
                            is(DEPOSIT - uid1Fee)));
            c.validateUserState(UID_2, profile ->
                    assertThat("UID_2: 开 taker + 关 maker", profile.getAccounts().get(QUOTE_ID),
                            is(DEPOSIT - uid2Fee)));

            TotalCurrencyBalanceReportResult bal = c.totalBalanceReport();
            assertThat("fees bucket = 4 笔", bal.getFees().get(QUOTE_ID), is(uid1Fee + uid2Fee));
            assertTrue(bal.isGlobalBalancesAllZero());
        }
    }

    @Test
    @Timeout(10)
    @SuppressWarnings("try")
    void reverseFill_chargesBothCloseAndOpenFee() throws Exception {
        // 反手单：一笔成交同时关 + 开（持仓 LONG → 来一笔反向 SHORT，量大于现有持仓）。
        // 验证关仓部分按关仓费收、开仓部分按开仓费收，两段独立累加，等价于按 ev.size 全量按率收一次。
        try (ExchangeTestContainer c = freshContainer()) {
            c.createUserWithSpecificMoney(UID_1, DEPOSIT, QUOTE_ID);
            c.createUserWithSpecificMoney(UID_2, DEPOSIT, QUOTE_ID);

            // UID_1 LONG 5, UID_2 SHORT 5
            c.createBidWithOrderId(101L, UID_1, 5, PRICE, SYMBOL_ID);
            c.createAskWithOrderId(102L, UID_2, 5, PRICE, SYMBOL_ID);

            // UID_2 挂 BID 10 在同价（maker, 先关 5 再反手开 5 LONG），UID_1 吃单（taker, 关 LONG 5 走完）
            c.createBidWithOrderId(103L, UID_2, 10, PRICE, SYMBOL_ID);
            c.createAskWithOrderId(104L, UID_1, 5, PRICE, SYMBOL_ID);

            // 第一段 UID_1 是 maker（先挂 BID），UID_2 是 taker（吃 ASK）
            // 第二段 UID_2 是 maker（先挂 BID 10），UID_1 是 taker（ASK 5 吃单 → 5 张关仓）
            // UID_1: 开 maker fee (5) + 关 taker fee (5)
            long uid1Fee = MAKER_FEE_PER_CONTRACT * 5 + TAKER_FEE_PER_CONTRACT * 5;
            // UID_2: 开 taker fee (5) + 关 maker fee (5)，剩 5 张 BID 还挂在簿上不收 fee
            long uid2Fee = TAKER_FEE_PER_CONTRACT * 5 + MAKER_FEE_PER_CONTRACT * 5;

            c.validateUserState(UID_1, profile ->
                    assertThat("UID_1: 全平", profile.getPositions().size(), is(0)));
            c.validateUserState(UID_1, profile ->
                    assertThat("UID_1 fee: 开 maker + 关 taker",
                            profile.getAccounts().get(QUOTE_ID), is(DEPOSIT - uid1Fee)));
            c.validateUserState(UID_2, profile ->
                    assertThat("UID_2: 持仓 5 BID（关 5 SHORT 后剩 5 张挂单未匹配）",
                            profile.getPositions().get(SYMBOL_ID).get(0).pendingBuySize, is(5L)));

            TotalCurrencyBalanceReportResult bal = c.totalBalanceReport();
            assertThat("fees bucket = UID_1 + UID_2 累计",
                    bal.getFees().get(QUOTE_ID), is(uid1Fee + uid2Fee));
            assertTrue(bal.isGlobalBalancesAllZero(), "反手场景守恒仍闭环");
        }
    }

    // 强平场景下 close fee 的覆盖由 ITMixedIntegration 提供（agent fix-up 后的测试包含显式断言）：
    //  - testIsolatedLiquidationFullyMatchedWithFee
    //  - testCrossLiquidationFullyMatchedWithFee
    //  - testIsolatedLiquidationPartialMatchedWithIFTakeover[_FundingFee]
    //  - testIsolatedLiquidationPartialMatchedWithAdlTakeover
    // 这些断言里被强平方账户额外扣减了 close taker fee，与 liquidationFee 同时被收，
    // 形成 "LiquidationService.isLiquidationOrderId 不能跳过 fee 路径" 的反退化保证。
    // 强平 setup 涉及 trigger 节奏 + IF/ADL 路径，重复实现易脆，复用 ITMixedIntegration 即可。

    @Test
    @Timeout(10)
    @SuppressWarnings("try")
    void feesBucket_aggregatesAcrossMultipleFills() throws Exception {
        // 跑多笔成交，验证 fees bucket 是所有 open + close fee 之和，无遗漏无重复。
        try (ExchangeTestContainer c = freshContainer()) {
            c.createUserWithSpecificMoney(UID_1, DEPOSIT, QUOTE_ID);
            c.createUserWithSpecificMoney(UID_2, DEPOSIT, QUOTE_ID);

            int n = 4;
            long sizePerFill = 2;
            for (int i = 0; i < n; i++) {
                long oid = 1000L + i * 2;
                // 开 + 平 一组
                c.createBidWithOrderId(oid, UID_1, (int) sizePerFill, PRICE, SYMBOL_ID);
                c.createAskWithOrderId(oid + 1, UID_2, (int) sizePerFill, PRICE, SYMBOL_ID);
                c.createAskWithOrderId(oid + 100, UID_1, (int) sizePerFill, PRICE, SYMBOL_ID);
                c.createBidWithOrderId(oid + 101, UID_2, (int) sizePerFill, PRICE, SYMBOL_ID);
            }

            // 每组：UID_1 maker × 2（开 + 关）, UID_2 taker × 2（开 + 关）
            long expectedFees = n * sizePerFill *
                    (2 * MAKER_FEE_PER_CONTRACT + 2 * TAKER_FEE_PER_CONTRACT);

            TotalCurrencyBalanceReportResult bal = c.totalBalanceReport();
            assertThat("fees bucket = n × (open + close) × (maker + taker)",
                    bal.getFees().get(QUOTE_ID), is(expectedFees));
            assertTrue(bal.isGlobalBalancesAllZero());
        }
    }
}
