package exchange.core2.tests.integration;

import exchange.core2.core.common.CoreSymbolSpecification;
import exchange.core2.core.common.MarginMode;
import exchange.core2.core.common.OrderAction;
import exchange.core2.core.common.OrderType;
import exchange.core2.core.common.SymbolType;
import exchange.core2.core.common.api.ApiPlaceOrder;
import exchange.core2.core.common.cmd.CommandResultCode;
import exchange.core2.core.common.config.PerformanceConfiguration;
import exchange.core2.core.processors.liquidation.LiquidationEngine;
import exchange.core2.tests.util.ExchangeTestContainer;
import org.eclipse.collections.impl.map.sorted.mutable.TreeSortedMap;
import org.junit.jupiter.api.Test;

import static exchange.core2.tests.util.TestConstants.CURRENECY_USD;
import static exchange.core2.tests.util.TestConstants.UID_1;
import static exchange.core2.tests.util.TestConstants.UID_2;
import static exchange.core2.tests.util.TestConstants.UID_3;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.core.Is.is;

/**
 * 锁定 audit 报告 H1 / H2 两处 fee 多扣 bug 的回归测试。
 *
 * H1: 动态费率下强平 fee 被 takerSize 倍放大（RiskEngine.java:1346 collectLiquidationFee）。
 * H2: FOK_BUDGET 在 actualMatched &lt; budget 时按 budget 估算的 takerFee 扣账，差额蒸发
 *     （RiskEngine.java:1726 handleMatcherEventsExchangeBuy BUDGET 分支）。
 *
 * 验证手段：git stash 修复，跑这两测必须红；stash pop，必须绿。
 */
public final class ITFeeAuditRegression {

    private final int quoteId = CURRENECY_USD;

    // ─────────────────────────────────────────────────────────────────────
    // H1 — 动态费率强平 fee 不能被 takerSize 倍放大
    //
    //   修复前：notional = ceil(takerSize × Σ(size×price) × liqFee / feeScaleK)
    //   修复后：notional = ceil(Σ(size×price) × liqFee / feeScaleK)
    //   差异 = takerSize 倍（本例 10×）
    // ─────────────────────────────────────────────────────────────────────
    @Test
    public void h1_liquidationFeeDynamicRate_notAmplifiedByTakerSize() throws Exception {
        try (final ExchangeTestContainer container = ExchangeTestContainer.create(PerformanceConfiguration.DEFAULT)) {

            final int baseId = 999;
            // takerFee / makerFee / liquidationFee 都用动态费率，feeScaleK = 10000
            //   takerFee = 100 → 1%
            //   makerFee = 50  → 0.5%
            //   liquidationFee = 100 → 1%
            CoreSymbolSpecification spec = CoreSymbolSpecification.builder()
                    .symbolId(60001)
                    .type(SymbolType.FUTURES_CONTRACT_PERPETUAL)
                    .baseCurrency(baseId)
                    .quoteCurrency(quoteId)
                    .baseScaleK(1)
                    .quoteScaleK(1)
                    .takerFee(100)
                    .makerFee(50)
                    .liquidationFee(100)
                    .feeScaleK(10000)                  // 关键：> 0 走动态费率分支
                    .initMargin(1).initMarginScaleK(100)
                    .maintenanceMargin(TreeSortedMap.newMapWith(1000L, 5L, 1_000_000L, 10L))
                    .maintenanceMarginScaleK(10)
                    .maxLeverage(TreeSortedMap.newMapWith(2000L, 5L, 1_000_000L, 10L))
                    .build();

            container.addCurrency(baseId, 0);
            container.addCurrency(quoteId, 0);
            container.addSymbol(spec);
            container.initMarkPrice(spec.symbolId, 10000);
            // 关掉自动 scanner，下面手动 triggerOnce 触发
            container.getExchangeCore().getLiquidationEngines().forEach(LiquidationEngine::stop);

            final int userSize = 10;
            final long openPrice = 10000;
            final long liquidationPrice = 9900;
            // 动态费率：BP = (openPriceSum − marginBase) × feeScaleK / (Q × (feeScaleK − takerFee − liquidationFee))
            //         = (100000 − 1000) × 10000 / (10 × (10000 − 100 − 100)) = 99000×10000/98000 = 10102.04 → ceil 10103
            final long bpFillPrice = 10103L;

            // 押金给足，避免 RISK_NSF 影响断言
            container.createUserWithSpecificMoney(UID_1, 200_000L, quoteId);
            container.createUserWithSpecificMoney(UID_2, 2_000_000L, quoteId);
            container.createUserWithSpecificMoney(UID_3, 2_000_000L, quoteId);

            // UID_1 多头开仓（BID 先挂 → loser 是 maker）
            container.createBidWithOrderId(60101L, UID_1, userSize, openPrice, spec.symbolId, MarginMode.ISOLATED);
            container.createAskWithOrderId(60102L, UID_2, userSize, openPrice, spec.symbolId, MarginMode.CROSS);

            // 跌价触发强平
            container.updateCurrentPriceTo((int) liquidationPrice, spec.symbolId, quoteId);
            container.createBidWithOrderId(60103L, UID_3, userSize + 15, bpFillPrice, spec.symbolId, MarginMode.CROSS);
            container.getExchangeCore().getLiquidationEngines().forEach(LiquidationEngine::triggerOnce);
            Thread.sleep(200);

            // 算账（BP=10103，fill 均价 10103）：
            //   开仓 makerFee     = ceil(10 × 10000 × 50 / 10000) = 500
            //   强平 PnL          = (10103 − 10000) × 10 = 1030（LONG close 在 BP 处小幅正 PnL，为 fee 预留的缓冲）
            //   强平 closeFee     = ceil(10 × 10103 × 100 / 10000) = 1011
            //   强平 liquidationFee（H1 关注点）：
            //     修复后 = ceil(10 × 10103 × 100 / 10000)         = 1011
            //     修复前 = ceil(10 × (10×10103) × 100 / 10000)    = 10103  （10× 倍）
            //   final accounts = 200_000 − 500 + 1030 − 1011 − 1011 = 198_508
            final long expectedFinal = 200_000L - 500L + 1030L - 1011L - 1011L;

            container.validateUserState(UID_1, profile -> {
                assertThat("loser 持仓应被全平", profile.getPositions().size(), is(0));
                assertThat("强平 fee 不应被 takerSize 倍放大",
                        profile.getAccounts().get(quoteId), is(expectedFinal));
            });

            assertThat(container.totalBalanceReport().isGlobalBalancesAllZero(), is(true));
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // H2 — FOK_BUDGET 在 actualMatched < budget 时，fee 差额必须退还
    //
    //   修复前：用户扣 = actualMatched + fee_held（按 budget 估算的 takerFee）
    //   修复后：用户扣 = actualMatched + actualFee（按实际成交均价算的 takerFee）
    //   差异 = fee_held − actualFee（既不退用户也不进 fees → 全局守恒破坏）
    // ─────────────────────────────────────────────────────────────────────
    @Test
    public void h2_fokBudgetActualMatchedBelowBudget_refundsFeeDelta() throws Exception {
        try (final ExchangeTestContainer container = ExchangeTestContainer.create(PerformanceConfiguration.DEFAULT)) {

            final int baseCurrencyId = 998;

            // 动态费率：takerFee = 100 → 1%
            CoreSymbolSpecification spec = CoreSymbolSpecification.builder()
                    .symbolId(60201)
                    .type(SymbolType.CURRENCY_EXCHANGE_PAIR)
                    .baseCurrency(baseCurrencyId).baseScaleK(1)
                    .quoteCurrency(quoteId).quoteScaleK(1)
                    .takerFee(100)
                    .makerFee(50)
                    .feeScaleK(10000)
                    .build();

            container.addCurrency(baseCurrencyId, 0);
            container.addCurrency(quoteId, 0);
            container.addSymbol(spec);

            final long userQuoteDeposit = 1_000_000L;
            final long makerBaseDeposit = 100L;
            container.createUserWithSpecificMoney(UID_1, userQuoteDeposit, quoteId);
            container.createUserWithMoney(UID_2, baseCurrencyId, makerBaseDeposit);

            // Maker ASK 10 @ 120（单价 120，低于 taker 隐含均价 150，
            //   也满足 isAskPriceTooLow 阈值：price >= ceilDiv(feeScaleK, takerFee) = 100）
            final int makerSize = 10;
            final long makerPrice = 120L;
            container.submitCommandSync(ApiPlaceOrder.builder()
                            .uid(UID_2).orderId(60301L)
                            .symbol(spec.symbolId)
                            .action(OrderAction.ASK).orderType(OrderType.GTC)
                            .price(makerPrice).reservePrice(makerPrice).size(makerSize).build(),
                    CommandResultCode.SUCCESS);

            // Taker FOK_BUDGET：要 10 件，budget = 1500（隐含均价 150 > maker 实际 120）
            final int takerSize = 10;
            final long budget = 1500L;
            container.submitCommandSync(ApiPlaceOrder.builder()
                            .uid(UID_1).orderId(60302L)
                            .symbol(spec.symbolId)
                            .action(OrderAction.BID).orderType(OrderType.FOK_BUDGET)
                            .price(budget).reservePrice(budget).size(takerSize).build(),
                    CommandResultCode.SUCCESS);

            // 数学：
            //   actualMatched = 10 × 120 = 1200
            //   actualFee     = ceil(10 × 120 × 100 / 10000) = ceil(12) = 12
            //   fee_held      = ceil(1500 × 100 / 10000)     = ceil(15) = 15
            //   差额          = fee_held - actualFee = 3  → 必须退给用户
            //   修复后用户实付 = 1200 + 12 = 1212
            //   修复前用户实付 = 1200 + 15 = 1215（断言失败）
            final long expectedPaid = 1200L + 12L;
            final long expectedAccounts = userQuoteDeposit - expectedPaid;

            container.validateUserState(UID_1, profile -> {
                assertThat("FOK_BUDGET 全成后 exchangeLocked 必须归零",
                        profile.getExchangeLocked().get(quoteId), is(0L));
                assertThat("用户实付 = actualMatched + actualFee（按成交均价），不按 budget 估算",
                        profile.getAccounts().get(quoteId), is(expectedAccounts));
                assertThat("base 收到 takerSize", profile.getAccounts().get(baseCurrencyId), is((long) takerSize));
            });

            assertThat(container.totalBalanceReport().isGlobalBalancesAllZero(), is(true));
        }
    }
}
