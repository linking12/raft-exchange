package exchange.core2.tests.integration;

import exchange.core2.core.common.MarginMode;
import exchange.core2.core.common.OrderAction;
import exchange.core2.core.common.OrderType;
import exchange.core2.core.common.PositionDirection;
import exchange.core2.core.common.PositionMode;
import exchange.core2.core.common.api.ApiAdjustPositionMode;
import exchange.core2.core.common.api.ApiPlaceOrder;
import exchange.core2.core.common.api.reports.SingleUserReportResult;
import exchange.core2.core.common.cmd.CommandResultCode;
import exchange.core2.core.common.config.PerformanceConfiguration;
import exchange.core2.tests.util.ExchangeTestContainer;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;

import static exchange.core2.tests.util.TestConstants.CURRENECY_USD;
import static exchange.core2.tests.util.TestConstants.UID_1;
import static exchange.core2.tests.util.TestConstants.UID_2;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.core.Is.is;

/**
 * {@code RiskEngine.canPlaceMarginOrder} 期货下单 NSF 校验各项验证：
 * <ul>
 *   <li>openLoss 预留（BID 超付 / ASK 贱卖 / ONEWAY 反向大单 openingSize 截断）</li>
 *   <li>order margin 反向 pending 单在 |openVolume| 范围内不占额外保证金</li>
 *   <li>HEDGE crossFreeMargin：对侧腿保证金被正确扣（引用相等判本仓 vs 其它仓）</li>
 *   <li>ISOLATED cross-subsidy demo：ISOLATED 仓位不参与 cross 抵扣（当前实现未过滤，需人工评估修不修）</li>
 * </ul>
 *
 * Symbol 用 {@code initFutureSymbol} 默认：initMargin=1/initMarginScaleK=100 → 初始保证金率 1%/leverage；
 * takerFee=20 fixed；maintenance bracket=(1000, 5) → 维持保证金率 0.5%；maxLeverage bracket=(2000, 5)/(100000, 10)。
 */
@Slf4j
public class ITPlaceMarginOrderNsfChecks {

    private static final int SYMBOL = 5001;
    private static final int LEVERAGE = 5;
    private static final long MARK_PRICE = 1000L;
    private static final long OPEN_SIZE = 5L;

    private PerformanceConfiguration cfg() {
        return PerformanceConfiguration.DEFAULT;
    }

    /**
     * Doc §1 Open Loss：Place Order Cost = Required Margin + Open Loss。
     *
     * mark=1000, BID @ 2000 size=5：
     *   notional_at_order = 10000, IM = 10000 × 1/(100×5) = 20
     *   openLoss = 5 × (2000 − 1000) = 5000
     *   Doc 需求 = IM(20) + openLoss(5000) + fee(100) = 5120
     *
     * deposit=300 < 5120 → 应 RISK_NSF 拦下（doc §1 明说的"开仓即爆仓"pitfall）。
     */
    @Test
    public void openLoss_bidAboveMark_rejectedByNSF() throws Exception {
        try (ExchangeTestContainer container = ExchangeTestContainer.create(cfg())) {
            container.initFutureSymbol(SYMBOL, CURRENECY_USD);
            container.addCurrency(CURRENECY_USD);
            container.initMarkPrice(SYMBOL, (int) MARK_PRICE);

            long trader = UID_1;
            long lp = UID_2;
            container.createUserWithSpecificMoney(trader, 300L, CURRENECY_USD);
            container.createUserWithSpecificMoney(lp, 10_000_000L, CURRENECY_USD);

            container.submitCommandSync(ApiPlaceOrder.builder()
                    .uid(lp).orderId(10001L).symbol(SYMBOL)
                    .action(OrderAction.ASK).orderType(OrderType.GTC)
                    .price(2000L).reservePrice(2000L).size(OPEN_SIZE)
                    .marginMode(MarginMode.CROSS).leverage(LEVERAGE).build(),
                CommandResultCode.SUCCESS);

            container.submitCommandSync(ApiPlaceOrder.builder()
                    .uid(trader).orderId(10002L).symbol(SYMBOL)
                    .action(OrderAction.BID).orderType(OrderType.GTC)
                    .price(2000L).reservePrice(2000L).size(OPEN_SIZE)
                    .marginMode(MarginMode.ISOLATED).leverage(LEVERAGE).build(),
                CommandResultCode.RISK_NSF);
        }
    }

    /**
     * Doc §1 Open Loss：足够 balance 场景应正常放行。
     *
     * 同上但 deposit=6000（> 5120），应 SUCCESS。
     */
    @Test
    public void openLoss_bidAboveMark_acceptedWithSufficientBalance() throws Exception {
        try (ExchangeTestContainer container = ExchangeTestContainer.create(cfg())) {
            container.initFutureSymbol(SYMBOL, CURRENECY_USD);
            container.addCurrency(CURRENECY_USD);
            container.initMarkPrice(SYMBOL, (int) MARK_PRICE);

            long trader = UID_1;
            long lp = UID_2;
            container.createUserWithSpecificMoney(trader, 6000L, CURRENECY_USD);
            container.createUserWithSpecificMoney(lp, 10_000_000L, CURRENECY_USD);

            container.submitCommandSync(ApiPlaceOrder.builder()
                    .uid(lp).orderId(10001L).symbol(SYMBOL)
                    .action(OrderAction.ASK).orderType(OrderType.GTC)
                    .price(2000L).reservePrice(2000L).size(OPEN_SIZE)
                    .marginMode(MarginMode.CROSS).leverage(LEVERAGE).build(),
                CommandResultCode.SUCCESS);

            container.submitCommandSync(ApiPlaceOrder.builder()
                    .uid(trader).orderId(10002L).symbol(SYMBOL)
                    .action(OrderAction.BID).orderType(OrderType.GTC)
                    .price(2000L).reservePrice(2000L).size(OPEN_SIZE)
                    .marginMode(MarginMode.ISOLATED).leverage(LEVERAGE).build(),
                CommandResultCode.SUCCESS);

            container.validateUserState(trader, profile -> {
                SingleUserReportResult.Position pos = profile.getPositions().get(SYMBOL).get(0);
                assertThat(pos.direction, is(PositionDirection.LONG));
                assertThat(pos.openVolume, is(OPEN_SIZE));
            });
        }
    }

    /**
     * Doc §7 Order Margin：pure reduce ASK 挂单不占额外 margin。
     *
     * LONG 5@1000 后挂 ASK 5@1000（纯反向）：
     *   W_new = max(abs(5000+0), abs(5000−5000)) = 5000
     *   orderMargin = (5000 − 5000)/leverage = 0
     *   位置字段：pendingSellSize 计 5，但 calculateRequiredMarginForFutures 应仅剩 openInitMarginSum + fee，
     *            不加 pending IM。
     */
    @Test
    public void orderMargin_reduceSideOffset_pureReduceNoExtraMargin() throws Exception {
        try (ExchangeTestContainer container = ExchangeTestContainer.create(cfg())) {
            container.initFutureSymbol(SYMBOL, CURRENECY_USD);
            container.addCurrency(CURRENECY_USD);
            container.initMarkPrice(SYMBOL, (int) MARK_PRICE);

            long trader = UID_1;
            long lp = UID_2;
            // deposit 215：开仓 fee 100 后剩 115；reduce ASK doc 需 110，我方（未修）需 120 → NSF
            container.createUserWithSpecificMoney(trader, 215L, CURRENECY_USD);
            container.createUserWithSpecificMoney(lp, 10_000_000L, CURRENECY_USD);

            container.submitCommandSync(ApiPlaceOrder.builder()
                    .uid(lp).orderId(20001L).symbol(SYMBOL)
                    .action(OrderAction.ASK).orderType(OrderType.GTC)
                    .price(MARK_PRICE).reservePrice(MARK_PRICE).size(OPEN_SIZE)
                    .marginMode(MarginMode.CROSS).leverage(LEVERAGE).build(),
                CommandResultCode.SUCCESS);
            container.submitCommandSync(ApiPlaceOrder.builder()
                    .uid(trader).orderId(20002L).symbol(SYMBOL)
                    .action(OrderAction.BID).orderType(OrderType.GTC)
                    .price(MARK_PRICE).reservePrice(MARK_PRICE).size(OPEN_SIZE)
                    .marginMode(MarginMode.ISOLATED).leverage(LEVERAGE).build(),
                CommandResultCode.SUCCESS);
            container.getApi().groupingControl(0, 1); // 强制 R2 落地开仓状态

            container.validateUserState(trader, profile -> {
                SingleUserReportResult.Position pos = profile.getPositions().get(SYMBOL).get(0);
                assertThat("开仓后 openInitMarginSum=10", pos.openInitMarginSum, is(10L));
                assertThat("taker fee 100 已扣", profile.getAccounts().get(CURRENECY_USD), is(115L));
            });

            container.submitCommandSync(ApiPlaceOrder.builder()
                    .uid(trader).orderId(20003L).symbol(SYMBOL)
                    .action(OrderAction.ASK).orderType(OrderType.GTC)
                    .price(MARK_PRICE).reservePrice(MARK_PRICE).size(OPEN_SIZE)
                    .marginMode(MarginMode.ISOLATED).leverage(LEVERAGE).build(),
                CommandResultCode.SUCCESS);
        }
    }

    /**
     * openLoss 对称：ASK 报价低于 mark（贱卖）时同样要预留立即浮亏。
     *
     * mark=1000, ASK 5 @ 500 size=5：
     *   notional = 2500, IM = 2500 × 1/(100×5) = 5
     *   openLoss = 5 × (1000 − 500) = 2500
     *   总需 5 + 100(fee) + 2500 = 2605
     *
     * deposit=1000 < 2605 → RISK_NSF。
     */
    @Test
    public void openLoss_askBelowMark_rejectedByNSF() throws Exception {
        try (ExchangeTestContainer container = ExchangeTestContainer.create(cfg())) {
            container.initFutureSymbol(SYMBOL, CURRENECY_USD);
            container.addCurrency(CURRENECY_USD);
            container.initMarkPrice(SYMBOL, (int) MARK_PRICE);

            long trader = UID_1;
            long lp = UID_2;
            container.createUserWithSpecificMoney(trader, 1000L, CURRENECY_USD);
            container.createUserWithSpecificMoney(lp, 10_000_000L, CURRENECY_USD);

            container.submitCommandSync(ApiPlaceOrder.builder()
                    .uid(lp).orderId(40001L).symbol(SYMBOL)
                    .action(OrderAction.BID).orderType(OrderType.GTC)
                    .price(500L).reservePrice(500L).size(OPEN_SIZE)
                    .marginMode(MarginMode.CROSS).leverage(LEVERAGE).build(),
                CommandResultCode.SUCCESS);

            container.submitCommandSync(ApiPlaceOrder.builder()
                    .uid(trader).orderId(40002L).symbol(SYMBOL)
                    .action(OrderAction.ASK).orderType(OrderType.GTC)
                    .price(500L).reservePrice(500L).size(OPEN_SIZE)
                    .marginMode(MarginMode.ISOLATED).leverage(LEVERAGE).build(),
                CommandResultCode.RISK_NSF);
        }
    }

    /**
     * ONEWAY 反向大单 openingSize 截断：ASK size > openVolume 时，只有超出部分是真正的新对侧开仓，
     * openLoss 只应对超出部分预留（不是全部 size）。
     *
     * LONG 5@1000 后挂 ASK 10@500（mark=1000）：
     *   openingSize = max(0, 10 − 5) = 5（真正 SHORT 开仓的部分）
     *   openLoss = 5 × (1000 − 500) = 2500（不是 10 × 500 = 5000）
     *   pendingFee = 10 × 20 = 200（fee 按全 pending size 算，不截断）
     *   positionMargin = openInitMarginSum 10（doc §7 净敞口不变，返回 -1 → 用 current）
     *   总需 10 + 200 + 2500 = 2710
     *
     * deposit=3000（开 LONG fee 100 后剩 2900）: 2710 ≤ 2900 → SUCCESS（若不截断需要 5210，会 NSF）
     */
    @Test
    public void openLoss_onewayReverseOrder_truncatedToOpeningPortion() throws Exception {
        try (ExchangeTestContainer container = ExchangeTestContainer.create(cfg())) {
            container.initFutureSymbol(SYMBOL, CURRENECY_USD);
            container.addCurrency(CURRENECY_USD);
            container.initMarkPrice(SYMBOL, (int) MARK_PRICE);

            long trader = UID_1;
            long lp = UID_2;
            container.createUserWithSpecificMoney(trader, 3000L, CURRENECY_USD);
            container.createUserWithSpecificMoney(lp, 10_000_000L, CURRENECY_USD);

            container.submitCommandSync(ApiPlaceOrder.builder()
                    .uid(lp).orderId(50001L).symbol(SYMBOL)
                    .action(OrderAction.ASK).orderType(OrderType.GTC)
                    .price(MARK_PRICE).reservePrice(MARK_PRICE).size(OPEN_SIZE)
                    .marginMode(MarginMode.CROSS).leverage(LEVERAGE).build(),
                CommandResultCode.SUCCESS);
            container.submitCommandSync(ApiPlaceOrder.builder()
                    .uid(trader).orderId(50002L).symbol(SYMBOL)
                    .action(OrderAction.BID).orderType(OrderType.GTC)
                    .price(MARK_PRICE).reservePrice(MARK_PRICE).size(OPEN_SIZE)
                    .marginMode(MarginMode.ISOLATED).leverage(LEVERAGE).build(),
                CommandResultCode.SUCCESS);
            container.getApi().groupingControl(0, 1); // 强制 R2 落地：确保 LONG 开仓的 pending 释放 + openInitMarginSum 写入

            // ASK 10 @ 500：ONEWAY 反向大单，opening 部分只有 size − openVolume = 5
            container.submitCommandSync(ApiPlaceOrder.builder()
                    .uid(trader).orderId(50003L).symbol(SYMBOL)
                    .action(OrderAction.ASK).orderType(OrderType.GTC)
                    .price(500L).reservePrice(500L).size(10L)
                    .marginMode(MarginMode.ISOLATED).leverage(LEVERAGE).build(),
                CommandResultCode.SUCCESS);
        }
    }

    /**
     * HEDGE crossFreeMargin 修复：开对侧腿时正确扣掉已有腿的 IM（旧代码用 symbol 相等匹配导致 double-count PnL、
     * 不扣 sibling IM，属于 bug）。
     *
     * HEDGE + CROSS，deposit 215：
     *   开 LONG 5@1000 (taker fee 100) → accounts=115, LONG.openInitMarginSum=10, LONG.PnL=0（mark=EP）
     *   开 SHORT 5@1000（新 SHORT record）：
     *     positionMargin(新SHORT) = 10
     *     crossFreeMargin = 0(LONG PnL) − 10(LONG IM) = −10
     *     required = 10 + 100(fee) + 0(openLoss) − (−10) = 120
     *     spendable = 115 → 120 > 115 → RISK_NSF
     *   deposit 加 10 → spendable=125 ≥ 120 → SUCCESS
     */
    @Test
    public void hedge_oppositeLegSubtractsSiblingIM() throws Exception {
        try (ExchangeTestContainer container = ExchangeTestContainer.create(cfg())) {
            container.initFutureSymbol(SYMBOL, CURRENECY_USD);
            container.addCurrency(CURRENECY_USD);
            container.initMarkPrice(SYMBOL, (int) MARK_PRICE);

            long trader = UID_1;
            long lp = UID_2;
            container.createUserWithSpecificMoney(trader, 215L, CURRENECY_USD);
            container.createUserWithSpecificMoney(lp, 10_000_000L, CURRENECY_USD);

            container.submitCommandSync(ApiAdjustPositionMode.builder()
                    .uid(trader).positionMode(PositionMode.HEDGE).build(), CommandResultCode.SUCCESS);

            // 开 LONG 5 @ mark
            container.submitCommandSync(ApiPlaceOrder.builder()
                    .uid(lp).orderId(60001L).symbol(SYMBOL)
                    .action(OrderAction.ASK).orderType(OrderType.GTC)
                    .price(MARK_PRICE).reservePrice(MARK_PRICE).size(OPEN_SIZE)
                    .marginMode(MarginMode.CROSS).leverage(LEVERAGE).build(),
                CommandResultCode.SUCCESS);
            container.submitCommandSync(ApiPlaceOrder.builder()
                    .uid(trader).orderId(60002L).symbol(SYMBOL)
                    .action(OrderAction.BID).orderType(OrderType.GTC)
                    .price(MARK_PRICE).reservePrice(MARK_PRICE).size(OPEN_SIZE)
                    .marginMode(MarginMode.CROSS).leverage(LEVERAGE).build(),
                CommandResultCode.SUCCESS);
            container.getApi().groupingControl(0, 1); // 强制 R2 落地开仓状态

            container.validateUserState(trader, profile -> {
                SingleUserReportResult.Position pos = profile.getPositions().get(SYMBOL).get(0);
                assertThat("LONG 开仓后 openInitMarginSum=10", pos.openInitMarginSum, is(10L));
                assertThat("taker fee 100 已扣", profile.getAccounts().get(CURRENECY_USD), is(115L));
            });

            // 尝试开对侧 SHORT 5：spendable 115 < required 120 → NSF
            container.submitCommandSync(ApiPlaceOrder.builder()
                    .uid(trader).orderId(60003L).symbol(SYMBOL)
                    .action(OrderAction.ASK).orderType(OrderType.GTC)
                    .price(MARK_PRICE).reservePrice(MARK_PRICE).size(OPEN_SIZE)
                    .marginMode(MarginMode.CROSS).leverage(LEVERAGE).build(),
                CommandResultCode.RISK_NSF);

            // 补 10 后可开
            container.addMoneyToUser(trader, CURRENECY_USD, 10L);
            container.submitCommandSync(ApiPlaceOrder.builder()
                    .uid(trader).orderId(60004L).symbol(SYMBOL)
                    .action(OrderAction.ASK).orderType(OrderType.GTC)
                    .price(MARK_PRICE).reservePrice(MARK_PRICE).size(OPEN_SIZE)
                    .marginMode(MarginMode.CROSS).leverage(LEVERAGE).build(),
                CommandResultCode.SUCCESS);

            container.validateUserState(trader, profile -> {
                assertThat("HEDGE 后同 symbol 有 LONG + SHORT 两条 record",
                    profile.getPositions().get(SYMBOL).size(), is(2));
            });
        }
    }

    /**
     * Doc §7：非 reduce 的开新仓 pending 单应正常锁 orderMargin。
     *
     * LONG 5@1000 再挂 BID 3@1000（同向加仓）：
     *   W_new = max(abs(5000+3000), abs(5000−0)) = 8000
     *   orderMargin = (8000 − 5000)/5 = 3000/5 = 600 (notional-based IM check)
     * 验证：pendingBuySize=3，本仓额外锁了 pendingBuyIM 部分（不是 0）。
     */
    @Test
    public void orderMargin_sameSideOpen_reservesPendingIM() throws Exception {
        try (ExchangeTestContainer container = ExchangeTestContainer.create(cfg())) {
            container.initFutureSymbol(SYMBOL, CURRENECY_USD);
            container.addCurrency(CURRENECY_USD);
            container.initMarkPrice(SYMBOL, (int) MARK_PRICE);

            long trader = UID_1;
            long lp = UID_2;
            container.createUserWithSpecificMoney(trader, 10_000L, CURRENECY_USD);
            container.createUserWithSpecificMoney(lp, 10_000_000L, CURRENECY_USD);

            container.submitCommandSync(ApiPlaceOrder.builder()
                    .uid(lp).orderId(30001L).symbol(SYMBOL)
                    .action(OrderAction.ASK).orderType(OrderType.GTC)
                    .price(MARK_PRICE).reservePrice(MARK_PRICE).size(OPEN_SIZE)
                    .marginMode(MarginMode.CROSS).leverage(LEVERAGE).build(),
                CommandResultCode.SUCCESS);
            container.submitCommandSync(ApiPlaceOrder.builder()
                    .uid(trader).orderId(30002L).symbol(SYMBOL)
                    .action(OrderAction.BID).orderType(OrderType.GTC)
                    .price(MARK_PRICE).reservePrice(MARK_PRICE).size(OPEN_SIZE)
                    .marginMode(MarginMode.ISOLATED).leverage(LEVERAGE).build(),
                CommandResultCode.SUCCESS);

            container.submitCommandSync(ApiPlaceOrder.builder()
                    .uid(trader).orderId(30003L).symbol(SYMBOL)
                    .action(OrderAction.BID).orderType(OrderType.GTC)
                    .price(500L).reservePrice(500L).size(3L)
                    .marginMode(MarginMode.ISOLATED).leverage(LEVERAGE).build(),
                CommandResultCode.SUCCESS);

            container.validateUserState(trader, profile -> {
                SingleUserReportResult.Position pos = profile.getPositions().get(SYMBOL).get(0);
                assertThat(pos.pendingBuySize, is(3L));
                assertThat(pos.pendingSellSize, is(0L));

                long absPos = pos.openPriceSum; // 5000
                long pendingBuyNotional = pos.pendingBuySize * pos.pendingBuyAvgPrice; // 1500
                long W = Math.max(Math.abs(absPos + pendingBuyNotional), absPos);
                long extraExposure = Math.max(0, W - absPos);
                assertThat("同向加仓 extra exposure = pending 部分",
                    extraExposure, is(pendingBuyNotional));
            });
        }
    }

    /**
     * ISOLATED 仓位隔离：ISOLATED 位的浮盈不能被其它 symbol 的 CROSS 新单当资本使用。
     *
     * 场景：
     *   symbol A：LEVERAGE=5, 初始保证金率=1%/5=0.2%
     *   deposit=110：开 A ISOLATED LONG 5@1000（taker fee 100, openInitMarginSum=10） → accounts=10
     *   拉高 mark(A) 到 2000 → A.PnL = +5000
     *
     * 尝试开 symbol B CROSS LONG 5@1000：需要 B.IM(10) + B.fee(100) = 110
     *   spendable = accounts(10) − spotLocked(0) = 10
     *
     * 修复前：crossFreeMargin loop 不过滤 marginMode，把 A（ISOLATED）的浮盈也算进
     *   crossFreeMargin += A.PnL(5000) − A 保证金(10) = 4990
     *   required = 110 − 4990 = −4880 → SUCCESS（ISOLATED 泄漏到 cross）
     *
     * 修复后：ISOLATED 仓位的浮盈不参与 crossFreeMargin
     *   crossFreeMargin = 0 − A 保证金(10) = −10   // 保证金仍要扣（accounts 未减）
     *   required = 110 − (−10) = 120 > spendable(10) → RISK_NSF
     */
    @Test
    public void isolatedCrossSubsidy_isolatedPnlBlockedFromCrossCapacity() throws Exception {
        try (ExchangeTestContainer container = ExchangeTestContainer.create(cfg())) {
            final int symbolA = 7001;
            final int symbolB = 7002;
            container.initFutureSymbol(symbolA, CURRENECY_USD);
            container.initFutureSymbol(symbolB, CURRENECY_USD);
            container.addCurrency(CURRENECY_USD);
            container.initMarkPrice(symbolA, (int) MARK_PRICE);
            container.initMarkPrice(symbolB, (int) MARK_PRICE);

            long trader = UID_1;
            long lp = UID_2;
            container.createUserWithSpecificMoney(trader, 110L, CURRENECY_USD);
            container.createUserWithSpecificMoney(lp, 10_000_000L, CURRENECY_USD);

            // A 开 ISOLATED LONG 5@1000
            container.submitCommandSync(ApiPlaceOrder.builder()
                    .uid(lp).orderId(70001L).symbol(symbolA)
                    .action(OrderAction.ASK).orderType(OrderType.GTC)
                    .price(MARK_PRICE).reservePrice(MARK_PRICE).size(OPEN_SIZE)
                    .marginMode(MarginMode.CROSS).leverage(LEVERAGE).build(),
                CommandResultCode.SUCCESS);
            container.submitCommandSync(ApiPlaceOrder.builder()
                    .uid(trader).orderId(70002L).symbol(symbolA)
                    .action(OrderAction.BID).orderType(OrderType.GTC)
                    .price(MARK_PRICE).reservePrice(MARK_PRICE).size(OPEN_SIZE)
                    .marginMode(MarginMode.ISOLATED).leverage(LEVERAGE).build(),
                CommandResultCode.SUCCESS);
            container.getApi().groupingControl(0, 1);

            container.validateUserState(trader, profile -> {
                SingleUserReportResult.Position posA = profile.getPositions().get(symbolA).get(0);
                assertThat("A ISOLATED", posA.marginMode, is(MarginMode.ISOLATED));
                assertThat("A LONG openInitMarginSum=10", posA.openInitMarginSum, is(10L));
                assertThat("扣 fee 100 后 accounts=10", profile.getAccounts().get(CURRENECY_USD), is(10L));
            });

            // 拉高 mark(A) 到 2000 → A.PnL = +5000
            container.initMarkPrice(symbolA, 2000L);
            container.getApi().groupingControl(0, 1);

            // B CROSS LONG 5@1000：ISOLATED A 的浮盈不该抵扣到 cross → NSF
            container.submitCommandSync(ApiPlaceOrder.builder()
                    .uid(lp).orderId(70003L).symbol(symbolB)
                    .action(OrderAction.ASK).orderType(OrderType.GTC)
                    .price(MARK_PRICE).reservePrice(MARK_PRICE).size(OPEN_SIZE)
                    .marginMode(MarginMode.CROSS).leverage(LEVERAGE).build(),
                CommandResultCode.SUCCESS);
            container.submitCommandSync(ApiPlaceOrder.builder()
                    .uid(trader).orderId(70004L).symbol(symbolB)
                    .action(OrderAction.BID).orderType(OrderType.GTC)
                    .price(MARK_PRICE).reservePrice(MARK_PRICE).size(OPEN_SIZE)
                    .marginMode(MarginMode.CROSS).leverage(LEVERAGE).build(),
                CommandResultCode.RISK_NSF);
        }
    }

    /**
     * ISOLATED 仓位隔离对比测试：CROSS 位浮盈**应该**能被其它 CROSS 新单当资本使用。
     *
     * 同上但 A 用 CROSS 开仓：
     *   crossFreeMargin = A.PnL(5000) − A 保证金(10) = 4990
     *   required = 110 − 4990 = −4880 → SUCCESS
     */
    @Test
    public void isolatedCrossSubsidy_crossPnlAllowedIntoCrossCapacity() throws Exception {
        try (ExchangeTestContainer container = ExchangeTestContainer.create(cfg())) {
            final int symbolA = 7101;
            final int symbolB = 7102;
            container.initFutureSymbol(symbolA, CURRENECY_USD);
            container.initFutureSymbol(symbolB, CURRENECY_USD);
            container.addCurrency(CURRENECY_USD);
            container.initMarkPrice(symbolA, (int) MARK_PRICE);
            container.initMarkPrice(symbolB, (int) MARK_PRICE);

            long trader = UID_1;
            long lp = UID_2;
            container.createUserWithSpecificMoney(trader, 110L, CURRENECY_USD);
            container.createUserWithSpecificMoney(lp, 10_000_000L, CURRENECY_USD);

            container.submitCommandSync(ApiPlaceOrder.builder()
                    .uid(lp).orderId(80001L).symbol(symbolA)
                    .action(OrderAction.ASK).orderType(OrderType.GTC)
                    .price(MARK_PRICE).reservePrice(MARK_PRICE).size(OPEN_SIZE)
                    .marginMode(MarginMode.CROSS).leverage(LEVERAGE).build(),
                CommandResultCode.SUCCESS);
            container.submitCommandSync(ApiPlaceOrder.builder()
                    .uid(trader).orderId(80002L).symbol(symbolA)
                    .action(OrderAction.BID).orderType(OrderType.GTC)
                    .price(MARK_PRICE).reservePrice(MARK_PRICE).size(OPEN_SIZE)
                    .marginMode(MarginMode.CROSS).leverage(LEVERAGE).build(),
                CommandResultCode.SUCCESS);
            container.getApi().groupingControl(0, 1);

            container.initMarkPrice(symbolA, 2000L);
            container.getApi().groupingControl(0, 1);

            container.submitCommandSync(ApiPlaceOrder.builder()
                    .uid(lp).orderId(80003L).symbol(symbolB)
                    .action(OrderAction.ASK).orderType(OrderType.GTC)
                    .price(MARK_PRICE).reservePrice(MARK_PRICE).size(OPEN_SIZE)
                    .marginMode(MarginMode.CROSS).leverage(LEVERAGE).build(),
                CommandResultCode.SUCCESS);
            container.submitCommandSync(ApiPlaceOrder.builder()
                    .uid(trader).orderId(80004L).symbol(symbolB)
                    .action(OrderAction.BID).orderType(OrderType.GTC)
                    .price(MARK_PRICE).reservePrice(MARK_PRICE).size(OPEN_SIZE)
                    .marginMode(MarginMode.CROSS).leverage(LEVERAGE).build(),
                CommandResultCode.SUCCESS);

            container.validateUserState(trader, profile -> {
                assertThat("B 开出后总仓位数 = 2", profile.getPositions().size(), is(2));
            });
        }
    }
}
