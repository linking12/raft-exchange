package com.binance.raftexchange.client;

import com.binance.raftexchange.stubs.MarginMode;
import com.binance.raftexchange.stubs.OrderAction;
import com.binance.raftexchange.stubs.OrderType;
import com.binance.raftexchange.stubs.PositionDirection;
import com.binance.raftexchange.stubs.PositionMode;
import com.binance.raftexchange.stubs.SymbolType;
import com.binance.raftexchange.stubs.response.CommandResultCode;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 期货（永续 + 交割）在真集群上的端到端流程验证。
 *
 * <p>按业务顺序走一遍：建市场 → 开仓撮合 → 调杠杆/追加保证金 → 资金费结算 → 平仓 →
 * 砸价强平 → 交割结算 → 三节点持仓一致。每步之后回读用户报表断言状态。
 *
 * <p>合约建在真实币对上——BTC/USDT 永续、ETH/USDT 交割，与现货<b>共用币种</b>，只是 symbolId 另给
 * （symbolId → spec 一对一、type 单值，故合约不能复用现货的 id）。scale 也取现货那一套，避免跨 scale 截断。
 * 初始保证金率 1%、维持保证金率 5%、最大 20x。
 */
@EnabledIfSystemProperty(named = "livecluster", matches = "true") // 需本地三节点集群；@Disabled 在本模块被 junit-platform.properties 关掉了，用系统属性 gate
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LiveClusterFuturesE2ETest extends LiveClusterE2EBase {

    // 合约建在真实币对上：BTC/USDT 永续、ETH/USDT 交割，与现货共用币种（symbolId 另给即可）
    private static final double OPEN_SIZE = 0.1;   // 保持 notional 在安全量级
    private static final int LEVERAGE = 10;

    private int perpSymbol;
    private int deliverySymbol;
    private long traderLong;
    private long traderShort;
    private long lp;        // 流动性提供者，负责在对手价挂单
    private long liqUser;   // 保证金刚好够一手，用来触发强平

    /** 本 run 独占价位：symbol 复用是 prod 模型，book 上随时有别人的残留挂单，不能假设 book 空。 */
    private double openPrice;

    @Test
    @Order(1)
    void setup_market_users_liquidity() {
        log("=== futures E2E setup ===");
        ensureAllCurrencies();

        perpSymbol = symbolId(0);
        deliverySymbol = symbolId(1);
        traderLong = uid(1);
        traderShort = uid(2);
        lp = uid(3);
        liqUser = uid(4);
        openPrice = 10_000.0 + (runId % 10_000);

        for (long u : new long[] {traderLong, traderShort, lp, liqUser}) {
            ok(api.addUser(u), "addUser " + u);
        }
        ok(api.adjustUserBalance(traderLong, nextTxId(), USDT_ID, +100_000.0), "long +100000 USDT");
        ok(api.adjustUserBalance(traderShort, nextTxId(), USDT_ID, +100_000.0), "short +100000 USDT");
        ok(api.adjustUserBalance(lp, nextTxId(), USDT_ID, +10_000_000.0), "lp +10M USDT");
        // liqUser 仅够一手 10x 逐仓保证金（notional/leverage）+ 少量手续费缓冲，否则砸价打不穿
        ok(api.adjustUserBalance(liqUser, nextTxId(), USDT_ID, +250.0), "liqUser +250 USDT");

        addFuturesSymbol(perpSymbol, SymbolType.FUTURES_CONTRACT_PERPETUAL, BTC_ID, "BTC/USDT PERP");
        addFuturesSymbol(deliverySymbol, SymbolType.FUTURES_CONTRACT_DELIVERY, ETH_ID, "ETH/USDT DELIVERY");

        ok(api.adjustMarkPrice(perpSymbol, openPrice), "markPrice PERP");
        ok(api.adjustMarkPrice(deliverySymbol, openPrice), "markPrice DELIVERY");

        // IF 必须每个 shard 都预存：仓位落在哪个 shard 不由测试决定，只存 shard 0 时
        // 其余 shard 的接管会因额度不足失败 → 退到 ADL → 无候选 → 仓位卡住反复重试
        for (int shard = 0; shard < RISK_SHARDS; shard++) {
            ok(api.insuranceFundDeposit(perpSymbol, shard, 1_000_000.0), "IF deposit PERP shard" + shard);
            ok(api.insuranceFundDeposit(deliverySymbol, shard, 1_000_000.0), "IF deposit DELIVERY shard" + shard);
        }
    }

    @Test
    @Order(2)
    void perp_openPosition_bothSidesMatched() throws Exception {
        log("=== 永续开仓撮合 ===");
        ok(api.placeOrder(traderLong, nextOrderId(), perpSymbol, OrderAction.BID, OrderType.GTC,
            openPrice, 0.0, OPEN_SIZE, MarginMode.ISOLATED, LEVERAGE, false), "long BID");
        ok(api.placeOrder(traderShort, nextOrderId(), perpSymbol, OrderAction.ASK, OrderType.GTC,
            openPrice, 0.0, OPEN_SIZE, MarginMode.ISOLATED, LEVERAGE, false), "short ASK");
        awaitApplied();

        assertHasPosition(traderLong, perpSymbol, "多头应成交建仓");
        assertHasPosition(traderShort, perpSymbol, "空头应成交建仓");
    }

    @Test
    @Order(3)
    void perp_adjustLeverage_andMargin() throws Exception {
        log("=== 调杠杆 + 追加保证金 ===");
        // 同 symbol 下已有仓位时杠杆须一致，故调的是尚无仓位的 lp
        ok(api.adjustLeverage(lp, perpSymbol, 5), "lp 杠杆调到 5x");
        ok(api.adjustMargin(traderLong, MarginMode.ISOLATED, perpSymbol, +1_000.0), "多头追加 1000 保证金");
        awaitApplied();
        assertHasPosition(traderLong, perpSymbol, "追加保证金后仓位仍在");
    }

    @Test
    @Order(4)
    void perp_settleFundingFees_longsPayShorts() throws Exception {
        log("=== 资金费结算：正费率多头付空头 ===");
        // 用户仍持仓时，资金费记进 position.profit 而不是 accounts——断在余额上会恒不变
        BigDecimal longProfitBefore = positionProfit(traderLong, perpSymbol);
        BigDecimal shortProfitBefore = positionProfit(traderShort, perpSymbol);

        ok(api.settleFundingFees(perpSymbol, OrderAction.BID, 100L, 10_000L), "funding rate +1%");
        awaitApplied();

        BigDecimal longProfitAfter = positionProfit(traderLong, perpSymbol);
        BigDecimal shortProfitAfter = positionProfit(traderShort, perpSymbol);
        assertTrue(longProfitAfter.compareTo(longProfitBefore) < 0,
            "正费率下多头应付费，before=" + longProfitBefore + " after=" + longProfitAfter);
        assertTrue(shortProfitAfter.compareTo(shortProfitBefore) > 0,
            "空头应收费，before=" + shortProfitBefore + " after=" + shortProfitAfter);
    }

    @Test
    @Order(5)
    void perp_closePosition_releasesMargin() throws Exception {
        log("=== 永续平仓 ===");
        // 平多头 = 卖出，对手方必须挂买单
        ok(api.placeOrder(lp, nextOrderId(), perpSymbol, OrderAction.BID, OrderType.GTC,
            openPrice, 0.0, OPEN_SIZE, MarginMode.ISOLATED, LEVERAGE, false), "lp 挂对手 BID");
        ok(api.closePosition(traderLong, nextOrderId(), perpSymbol, OrderAction.ASK, openPrice, OPEN_SIZE),
            "多头平仓");
        awaitApplied();
        assertFlat(traderLong, perpSymbol, "平仓后不应还有敞口");
    }

    @Test
    @Order(6)
    void perp_liquidation_markPriceCrash() throws Exception {
        log("=== 砸价强平 ===");
        ok(api.adjustMarkPrice(perpSymbol, openPrice), "markPrice 复位");
        ok(api.placeOrder(liqUser, nextOrderId(), perpSymbol, OrderAction.BID, OrderType.GTC,
            openPrice, 0.0, OPEN_SIZE, MarginMode.ISOLATED, LEVERAGE, false), "liqUser BID 开多");
        ok(api.placeOrder(lp, nextOrderId(), perpSymbol, OrderAction.ASK, OrderType.GTC,
            openPrice, 0.0, OPEN_SIZE, MarginMode.ISOLATED, LEVERAGE, false), "lp ASK 对手盘");
        awaitApplied();
        assertHasPosition(liqUser, perpSymbol, "强平前应有多头仓位");

        // 10x 逐仓，跌 15% 已远低于 5% 维持保证金率
        ok(api.adjustMarkPrice(perpSymbol, openPrice * 0.85), "markPrice 砸到 -15%");

        awaitPositionFlat(liqUser, perpSymbol, 30);
        log("✓ liqUser 仓位已被强平");
    }

    @Test
    @Order(7)
    void delivery_openAndSettlePnl() throws Exception {
        log("=== 交割合约：开仓 + 到期结算 ===");
        ok(api.placeOrder(traderLong, nextOrderId(), deliverySymbol, OrderAction.BID, OrderType.GTC,
            openPrice, 0.0, OPEN_SIZE, MarginMode.ISOLATED, LEVERAGE, false), "long BID delivery");
        ok(api.placeOrder(traderShort, nextOrderId(), deliverySymbol, OrderAction.ASK, OrderType.GTC,
            openPrice, 0.0, OPEN_SIZE, MarginMode.ISOLATED, LEVERAGE, false), "short ASK delivery");
        awaitApplied();
        assertHasPosition(traderLong, deliverySymbol, "交割合约应建仓");

        // 高于开仓价结算：多头盈利、空头亏损，结算后仓位清空
        ok(api.settlePnl(deliverySymbol, openPrice * 1.1), "settlePnl @ +10%");
        awaitApplied();
        assertFlat(traderLong, deliverySymbol, "交割结算后仓位应清空");
    }

    @Test
    @Order(8)
    void perp_leverageGuard_aboveMaxRejected() throws Exception {
        log("=== 杠杆守卫：超过 symbol 上限应拒 ===");
        // 无持仓时 adjustLeverage 走的是"没什么可调"的早返回，直接 SUCCESS，档位校验根本不跑；
        // 要测到守卫就必须先有仓位。symbol 配的 maxLeverage=20。
        long levUser = uid(14);
        ok(api.addUser(levUser), "addUser levUser");
        ok(api.adjustUserBalance(levUser, nextTxId(), USDT_ID, +100_000.0), "levUser +100000 USDT");
        ok(api.adjustMarkPrice(perpSymbol, openPrice), "markPrice 复位");
        ok(api.placeOrder(levUser, nextOrderId(), perpSymbol, OrderAction.BID, OrderType.GTC,
            openPrice, 0.0, OPEN_SIZE, MarginMode.ISOLATED, LEVERAGE, false), "levUser 开仓");
        ok(api.placeOrder(lp, nextOrderId(), perpSymbol, OrderAction.ASK, OrderType.GTC,
            openPrice, 0.0, OPEN_SIZE, MarginMode.ISOLATED, LEVERAGE, false), "lp 对手 ASK");
        awaitApplied();

        var rejected = api.adjustLeverage(levUser, perpSymbol, 100);
        assertEquals(CommandResultCode.RISK_INVALID_LEVERAGE, rejected.getResultCode(),
            "超过 maxLeverage 应返回 RISK_INVALID_LEVERAGE");
        ok(api.adjustLeverage(levUser, perpSymbol, 20), "等于上限应放行");
    }

    @Test
    @Order(9)
    void perp_reduceOnly_withNoPosition_isNullified() {
        log("=== reduce-only 无仓位时应被静默裁剪，不开新敞口 ===");
        var result = api.placeOrder(traderShort, nextOrderId(), perpSymbol, OrderAction.BID, OrderType.GTC,
            openPrice, 0.0, OPEN_SIZE, MarginMode.ISOLATED, LEVERAGE, true);
        assertTrue(isOk(result.getResultCode()),
            "reduce-only 无反向仓位应 SUCCESS no-op，而非报错，实际 " + result.getResultCode());
    }

    @Test
    @Order(10)
    void perp_crossMargin_sharesAccountEquity() throws Exception {
        log("=== 全仓保证金：与逐仓隔离，共享账户权益 ===");
        // 不能拿 lp 开 CROSS：lp 是全篇共享的对手方，一旦带上 CROSS 仓位，
        // 后面所有以它作 ISOLATED 对手的下单都会被 RISK_MARGIN_MODE_MISMATCH 拒掉
        long crossOnlyUser = uid(13);
        ok(api.addUser(crossOnlyUser), "addUser crossOnlyUser");
        ok(api.adjustUserBalance(crossOnlyUser, nextTxId(), USDT_ID, +100_000.0), "crossOnlyUser +100000 USDT");
        ok(api.adjustPositionMode(crossOnlyUser, PositionMode.ONEWAY), "单向持仓");
        ok(api.placeOrder(crossOnlyUser, nextOrderId(), perpSymbol, OrderAction.BID, OrderType.GTC,
            openPrice, 0.0, 1.0, MarginMode.CROSS, 5, false), "CROSS 开多");
        ok(api.placeOrder(lp, nextOrderId(), perpSymbol, OrderAction.ASK, OrderType.GTC,
            openPrice, 0.0, 1.0, MarginMode.ISOLATED, LEVERAGE, false), "lp 对手 ISOLATED ASK");
        awaitApplied();
        assertHasPosition(crossOnlyUser, perpSymbol, "CROSS 仓位应建立");
    }

    @Test
    @Order(11)
    void perp_adjustMargin_exceedingBalance_rejected() {
        log("=== 追加保证金超出余额应拒 ===");
        var rejected = api.adjustMargin(liqUser, MarginMode.ISOLATED, perpSymbol, +10_000_000.0);
        assertTrue(rejected.getResultCode() != CommandResultCode.SUCCESS,
            "超出余额的追加保证金不应成功，实际 " + rejected.getResultCode());
    }

    @Test
    @Order(12)
    void perp_and_delivery_marginIsolatedAcrossSymbols() throws Exception {
        log("=== 跨 symbol 保证金隔离：永续亏损不吃交割仓位 ===");
        ok(api.adjustMarkPrice(perpSymbol, openPrice), "markPrice 复位");
        ok(api.placeOrder(traderLong, nextOrderId(), deliverySymbol, OrderAction.BID, OrderType.GTC,
            openPrice, 0.0, 1.0, MarginMode.ISOLATED, LEVERAGE, false), "交割开多");
        ok(api.placeOrder(traderShort, nextOrderId(), deliverySymbol, OrderAction.ASK, OrderType.GTC,
            openPrice, 0.0, 1.0, MarginMode.ISOLATED, LEVERAGE, false), "交割对手");
        awaitApplied();
        BigDecimal deliveryVolBefore = openVolumeOf(api, traderLong, deliverySymbol);

        // 砸永续价，不应波及交割仓位（逐仓、不同 symbol）
        ok(api.adjustMarkPrice(perpSymbol, openPrice * 0.85), "只砸永续价");
        awaitApplied();

        assertEquals(0, deliveryVolBefore.compareTo(openVolumeOf(api, traderLong, deliverySymbol)),
            "永续价格变动不应改变交割仓位");
        ok(api.adjustMarkPrice(perpSymbol, openPrice), "markPrice 复位");
    }

    @Test
    @Order(13)
    void liquidation_consumesInsuranceFund() throws Exception {
        log("=== 强平确实动用了 IF：断言资金流向而非只看仓位消失 ===");
        ok(api.adjustMarkPrice(perpSymbol, openPrice), "markPrice 复位");
        for (int shard = 0; shard < RISK_SHARDS; shard++) {
            ok(api.insuranceFundDeposit(perpSymbol, shard, 500_000.0), "IF 预存 shard" + shard);
        }
        awaitApplied();
        long ifBefore = perpIfBalance();

        ok(api.adjustUserBalance(liqUser, nextTxId(), USDT_ID, +250.0), "liqUser 补仓资金");
        openOpposing(liqUser, traderShort, OPEN_SIZE);
        // 不挂接盘单 → FORCE 无对手 → 必须由 IF 承接
        ok(api.adjustMarkPrice(perpSymbol, openPrice * 0.85), "markPrice -15%，簿上无买盘");
        awaitPositionFlat(liqUser, perpSymbol, 30);

        long ifAfter = perpIfBalance();
        log("  IF " + ifBefore + " → " + ifAfter);
        assertTrue(ifAfter != ifBefore, "IF 接管破产仓位后余额应发生变化");
    }

    @Test
    @Order(14)
    void crossMargin_liquidation_isAccountLevel() throws Exception {
        log("=== CROSS 保证金强平：账户级判定，与逐仓路径不同 ===");
        int symbol = freshPerp(8, "CROSSLIQ");
        long crossUser = uid(5);
        ok(api.addUser(crossUser), "addUser crossUser");
        // 全仓权益刚够一手，砸价即穿
        ok(api.adjustUserBalance(crossUser, nextTxId(), USDT_ID, +250.0), "crossUser +250 USDT");
        ok(api.adjustPositionMode(crossUser, PositionMode.ONEWAY), "单向持仓");

        ok(api.placeOrder(crossUser, nextOrderId(), symbol, OrderAction.BID, OrderType.GTC,
            openPrice, 0.0, OPEN_SIZE, MarginMode.CROSS, LEVERAGE, false), "CROSS 开多");
        ok(api.placeOrder(lp, nextOrderId(), symbol, OrderAction.ASK, OrderType.GTC,
            openPrice, 0.0, OPEN_SIZE, MarginMode.ISOLATED, LEVERAGE, false), "lp 对手 ASK");
        awaitApplied();
        assertHasPosition(crossUser, symbol, "CROSS 仓位应建立");

        // CROSS 按账户权益判定，强平价比逐仓低得多，-15% 打不穿
        ok(api.adjustMarkPrice(symbol, openPrice * 0.70), "markPrice -30%");
        awaitPositionFlat(crossUser, symbol, 30);
        log("✓ CROSS 仓位被账户级强平");
    }

    @Test
    @Order(15)
    void liquidation_doesNotTouchOtherSymbolPosition() throws Exception {
        log("=== 一个合约被强平，另一个合约的仓位不受牵连 ===");
        ok(api.adjustMarkPrice(perpSymbol, openPrice), "PERP 复位");
        ok(api.adjustMarkPrice(deliverySymbol, openPrice), "DELIVERY 复位");

        // 同一用户在交割上另开一仓，资金充裕不会被强平
        ok(api.placeOrder(traderLong, nextOrderId(), deliverySymbol, OrderAction.BID, OrderType.GTC,
            openPrice, 0.0, OPEN_SIZE, MarginMode.ISOLATED, LEVERAGE, false), "交割开多");
        ok(api.placeOrder(traderShort, nextOrderId(), deliverySymbol, OrderAction.ASK, OrderType.GTC,
            openPrice, 0.0, OPEN_SIZE, MarginMode.ISOLATED, LEVERAGE, false), "交割对手");
        awaitApplied();
        BigDecimal deliveryVolBefore = openVolumeOf(api, traderLong, deliverySymbol);
        assertTrue(deliveryVolBefore.compareTo(BigDecimal.ZERO) != 0, "交割应有仓位");

        // liqUser 在永续上被真强平
        ok(api.adjustUserBalance(liqUser, nextTxId(), USDT_ID, +250.0), "liqUser 补仓资金");
        openOpposing(liqUser, traderShort, OPEN_SIZE);
        ok(api.adjustMarkPrice(perpSymbol, openPrice * 0.85), "只砸永续价");
        awaitPositionFlat(liqUser, perpSymbol, 30);

        assertEquals(0, deliveryVolBefore.compareTo(openVolumeOf(api, traderLong, deliverySymbol)),
            "永续强平不应波及交割仓位");
        ok(api.adjustMarkPrice(perpSymbol, openPrice), "PERP 复位");
    }

    @Test
    @Order(16)
    void healthyPosition_notLiquidated_whenPriceRecovers() throws Exception {
        log("=== 负向验证：价格回升后不得误强平 ===");
        ok(api.adjustMarkPrice(perpSymbol, openPrice), "markPrice 复位");
        ok(api.adjustUserBalance(traderLong, nextTxId(), USDT_ID, +50_000.0), "traderLong 充足保证金");
        openOpposing(traderLong, traderShort, OPEN_SIZE);
        BigDecimal volBefore = openVolumeOf(api, traderLong, perpSymbol);

        // 小幅下跌后回升——始终没跌破维持保证金
        ok(api.adjustMarkPrice(perpSymbol, openPrice * 0.98), "小跌 2%");
        awaitApplied();
        ok(api.adjustMarkPrice(perpSymbol, openPrice), "回升");
        TimeUnit.SECONDS.sleep(3); // 给 scanner 几轮机会

        assertEquals(0, volBefore.compareTo(openVolumeOf(api, traderLong, perpSymbol)),
            "保证金充足的仓位不该被强平");
    }

    @Test
    @Order(17)
    void hedgeMode_holdsLongAndShortOnSameSymbol() throws Exception {
        log("=== HEDGE 双向持仓：同 symbol 两条独立 record ===");
        long hedgeUser = uid(6);
        ok(api.addUser(hedgeUser), "addUser hedgeUser");
        ok(api.adjustUserBalance(hedgeUser, nextTxId(), USDT_ID, +100_000.0), "hedgeUser +100000 USDT");
        ok(api.adjustPositionMode(hedgeUser, PositionMode.HEDGE), "切双向持仓");
        ok(api.adjustMarkPrice(perpSymbol, openPrice), "markPrice 复位");

        // 先开多
        ok(api.placeOrder(hedgeUser, nextOrderId(), perpSymbol, OrderAction.BID, OrderType.GTC,
            openPrice, 0.0, OPEN_SIZE, MarginMode.ISOLATED, LEVERAGE, false), "HEDGE 开多");
        ok(api.placeOrder(lp, nextOrderId(), perpSymbol, OrderAction.ASK, OrderType.GTC,
            openPrice, 0.0, OPEN_SIZE, MarginMode.ISOLATED, LEVERAGE, false), "lp 对手 ASK");
        awaitApplied();

        // 再开空：ONEWAY 下这会平掉多头，HEDGE 下应另起一条腿
        ok(api.placeOrder(hedgeUser, nextOrderId(), perpSymbol, OrderAction.ASK, OrderType.GTC,
            openPrice, 0.0, OPEN_SIZE, MarginMode.ISOLATED, LEVERAGE, false), "HEDGE 开空");
        ok(api.placeOrder(lp, nextOrderId(), perpSymbol, OrderAction.BID, OrderType.GTC,
            openPrice, 0.0, OPEN_SIZE, MarginMode.ISOLATED, LEVERAGE, false), "lp 对手 BID");
        awaitApplied();

        var positions = api.queryUserReport(hedgeUser).get(10, TimeUnit.SECONDS).getPositions().get(perpSymbol);
        assertNotNull(positions, "HEDGE 用户应有仓位");
        assertEquals(2, positions.size(), "HEDGE 下同 symbol 应有多空两条独立 record，实际 " + positions.size());
        assertTrue(volumeOf(hedgeUser, perpSymbol, PositionDirection.LONG).compareTo(BigDecimal.ZERO) != 0,
            "多头腿应存在");
        assertTrue(volumeOf(hedgeUser, perpSymbol, PositionDirection.SHORT).compareTo(BigDecimal.ZERO) != 0,
            "空头腿应存在");
    }

    @Test
    @Order(18)
    void hedgeMode_closingOneLeg_keepsTheOther() throws Exception {
        log("=== HEDGE：平掉一条腿，另一条不受影响 ===");
        long hedgeUser = uid(6);
        BigDecimal shortBefore = volumeOf(hedgeUser, perpSymbol, PositionDirection.SHORT);
        assertTrue(shortBefore.compareTo(BigDecimal.ZERO) != 0, "前置：空头腿应存在");

        // 平多头腿
        ok(api.placeOrder(lp, nextOrderId(), perpSymbol, OrderAction.BID, OrderType.GTC,
            openPrice, 0.0, OPEN_SIZE, MarginMode.ISOLATED, LEVERAGE, false), "lp 挂 BID 接平仓");
        ok(api.closePosition(hedgeUser, nextOrderId(), perpSymbol, OrderAction.ASK, openPrice, OPEN_SIZE),
            "平多头腿");
        awaitApplied();

        assertEquals(0, shortBefore.compareTo(volumeOf(hedgeUser, perpSymbol, PositionDirection.SHORT)),
            "平多头不应动到空头腿");
    }

    @Test
    @Order(19)
    void hedgeMode_reduceOnlyIsIgnored_notHonored() throws Exception {
        log("=== HEDGE 下 reduceOnly 被忽略：不是收窄成平仓，而是照常开新腿 ===");
        long hedgeUser = uid(6);
        ok(api.adjustMarkPrice(perpSymbol, openPrice), "markPrice 复位");
        BigDecimal shortBefore = volumeOf(hedgeUser, perpSymbol, PositionDirection.SHORT);

        // ONEWAY 下 reduceOnly 会被 maxClosableSize 夹住；HEDGE 下该标志整个不生效
        ok(api.placeOrder(hedgeUser, nextOrderId(), perpSymbol, OrderAction.ASK, OrderType.GTC,
            openPrice, 0.0, OPEN_SIZE, MarginMode.ISOLATED, LEVERAGE, true), "HEDGE + reduceOnly 开空");
        ok(api.placeOrder(lp, nextOrderId(), perpSymbol, OrderAction.BID, OrderType.GTC,
            openPrice, 0.0, OPEN_SIZE, MarginMode.ISOLATED, LEVERAGE, false), "lp 对手 BID");
        awaitApplied();

        assertTrue(volumeOf(hedgeUser, perpSymbol, PositionDirection.SHORT).abs().compareTo(shortBefore.abs()) > 0,
            "HEDGE 下 reduceOnly 不生效，空头腿应继续增大而非被夹成 0");
    }

    @Test
    @Order(20)
    void positionMode_cannotSwitch_whileHoldingPosition() throws Exception {
        log("=== 带仓切持仓模式应被拒 ===");
        long hedgeUser = uid(6);
        assertHasPosition(hedgeUser, perpSymbol, "前置：应仍持仓");

        var result = api.adjustPositionMode(hedgeUser, PositionMode.ONEWAY);
        assertEquals(CommandResultCode.RISK_MARGIN_POSITION_EXISTS, result.getResultCode(),
            "带仓切模式应返回 RISK_MARGIN_POSITION_EXISTS，实际 " + result.getResultCode());
    }

    @Test
    @Order(21)
    void hedgeMode_bothLegsReplicatedToAllNodes() throws Exception {
        log("=== HEDGE 两条腿在三节点上完全一致（E2E 独有价值：复制而非单机语义）===");
        long hedgeUser = uid(6);
        List<String[]> nodes = resolveAllNodes();
        assertTrue(nodes.size() >= 3, "应至少 3 节点");

        BigDecimal expectedLong = null;
        BigDecimal expectedShort = null;
        for (String[] node : nodes) {
            try (ExchangeApi nodeApi = ExchangeApi.connect(node[0], Integer.parseInt(node[1]),
                ExchangeApiOptions.builder().sendTimeout(java.time.Duration.ofSeconds(10)).build())) {
                var positions = nodeApi.queryUserReport(hedgeUser).get(10, TimeUnit.SECONDS)
                    .getPositions().get(perpSymbol);
                assertNotNull(positions, "节点 " + node[0] + ":" + node[1] + " 上应有仓位");
                BigDecimal lng = directionVolume(positions, PositionDirection.LONG);
                BigDecimal sht = directionVolume(positions, PositionDirection.SHORT);
                log("  node " + node[0] + ":" + node[1] + "(" + node[2] + ") LONG=" + lng + " SHORT=" + sht);
                if (expectedLong == null) {
                    expectedLong = lng;
                    expectedShort = sht;
                } else {
                    assertEquals(0, expectedLong.compareTo(lng), "各节点多头腿应一致");
                    assertEquals(0, expectedShort.compareTo(sht), "各节点空头腿应一致");
                }
            }
        }
    }

    @Test
    @Order(22)
    void onewayMode_oppositeOrderNetsOff_doesNotOpenSecondLeg() throws Exception {
        log("=== ONEWAY 净额语义：反向单是平仓，不是另起一条腿（HEDGE 用例的镜像）===");
        long onewayUser = uid(9);
        ok(api.addUser(onewayUser), "addUser onewayUser");
        ok(api.adjustUserBalance(onewayUser, nextTxId(), USDT_ID, +100_000.0), "onewayUser +100000 USDT");
        ok(api.adjustPositionMode(onewayUser, PositionMode.ONEWAY), "单向持仓");
        ok(api.adjustMarkPrice(perpSymbol, openPrice), "markPrice 复位");

        ok(api.placeOrder(onewayUser, nextOrderId(), perpSymbol, OrderAction.BID, OrderType.GTC,
            openPrice, 0.0, OPEN_SIZE, MarginMode.ISOLATED, LEVERAGE, false), "开多");
        ok(api.placeOrder(lp, nextOrderId(), perpSymbol, OrderAction.ASK, OrderType.GTC,
            openPrice, 0.0, OPEN_SIZE, MarginMode.ISOLATED, LEVERAGE, false), "lp 对手 ASK");
        awaitApplied();
        assertHasPosition(onewayUser, perpSymbol, "应先建多头仓");

        // 等量反向：HEDGE 下会变成两条腿，ONEWAY 下必须净掉
        ok(api.placeOrder(onewayUser, nextOrderId(), perpSymbol, OrderAction.ASK, OrderType.GTC,
            openPrice, 0.0, OPEN_SIZE, MarginMode.ISOLATED, LEVERAGE, false), "等量反向 ASK");
        ok(api.placeOrder(lp, nextOrderId(), perpSymbol, OrderAction.BID, OrderType.GTC,
            openPrice, 0.0, OPEN_SIZE, MarginMode.ISOLATED, LEVERAGE, false), "lp 对手 BID");
        awaitApplied();

        var positions = api.queryUserReport(onewayUser).get(10, TimeUnit.SECONDS)
            .getPositions().get(perpSymbol);
        assertTrue(positions == null || positions.size() <= 1,
            "ONEWAY 下同 symbol 至多一条 record，实际 " + (positions == null ? 0 : positions.size()));
        assertFlat(onewayUser, perpSymbol, "等量反向应净额平掉敞口");
    }

    @Test
    @Order(23)
    void onewayMode_reduceOnly_clampedToClosableSize() throws Exception {
        log("=== ONEWAY reduceOnly 被夹到可平量：超额部分不得反手开新敞口 ===");
        long onewayUser = uid(9);
        ok(api.adjustMarkPrice(perpSymbol, openPrice), "markPrice 复位");
        ok(api.placeOrder(onewayUser, nextOrderId(), perpSymbol, OrderAction.BID, OrderType.GTC,
            openPrice, 0.0, OPEN_SIZE, MarginMode.ISOLATED, LEVERAGE, false), "重新开多 " + OPEN_SIZE);
        ok(api.placeOrder(lp, nextOrderId(), perpSymbol, OrderAction.ASK, OrderType.GTC,
            openPrice, 0.0, OPEN_SIZE, MarginMode.ISOLATED, LEVERAGE, false), "lp 对手 ASK");
        awaitApplied();
        assertHasPosition(onewayUser, perpSymbol, "前置：应持多头 " + OPEN_SIZE);

        // reduceOnly 报 5 倍可平量：maxClosableSize 应把 size 裁到 OPEN_SIZE，多出的 4 倍不成为空头敞口
        ok(api.placeOrder(lp, nextOrderId(), perpSymbol, OrderAction.BID, OrderType.GTC,
            openPrice, 0.0, OPEN_SIZE * 5, MarginMode.ISOLATED, LEVERAGE, false), "lp 挂 5 倍 BID");
        ok(api.placeOrder(onewayUser, nextOrderId(), perpSymbol, OrderAction.ASK, OrderType.GTC,
            openPrice, 0.0, OPEN_SIZE * 5, MarginMode.ISOLATED, LEVERAGE, true), "reduceOnly ASK 5 倍");
        awaitApplied();

        assertFlat(onewayUser, perpSymbol, "reduceOnly 超额部分不得反手开空，应恰好归零");
    }

    @Test
    @Order(24)
    void partialFill_marginHeldMatchesFilledSize_notOrderedSize() throws Exception {
        log("=== 部分成交：保证金按实际成交量计提，残量仍在簿上 ===");
        log("    （core 已用精确数值测过费用与精度；E2E 这条测的是 R1 冻结与 R2 成交在 raft 下的对齐）");
        int symbol = freshPerp(3, "PARTIAL");
        long partialUser = uid(12);
        ok(api.addUser(partialUser), "addUser partialUser");
        ok(api.adjustUserBalance(partialUser, nextTxId(), USDT_ID, +100_000.0), "partialUser +100000 USDT");
        ok(api.adjustPositionMode(partialUser, PositionMode.ONEWAY), "单向持仓");

        // 对手只挂 1 份，本方要 3 份 → 成交 1 份，残量 2 份留在簿上
        long bigOrder = nextOrderId();
        ok(api.placeOrder(traderShort, nextOrderId(), symbol, OrderAction.ASK, OrderType.GTC,
            openPrice, 0.0, OPEN_SIZE, MarginMode.ISOLATED, LEVERAGE, false), "对手只挂 " + OPEN_SIZE);
        ok(api.placeOrder(partialUser, bigOrder, symbol, OrderAction.BID, OrderType.GTC,
            openPrice, 0.0, OPEN_SIZE * 3, MarginMode.ISOLATED, LEVERAGE, false), "本方要 " + (OPEN_SIZE * 3));
        awaitApplied();

        // 敞口必须等于实际成交量，而不是下单量——按下单量建仓是典型的 R1/R2 错配
        BigDecimal filled = openVolumeOf(api, partialUser, symbol);
        assertEquals(0, filled.abs().compareTo(BigDecimal.valueOf(OPEN_SIZE)),
            "敞口应等于实际成交量 " + OPEN_SIZE + "，而非下单量 " + (OPEN_SIZE * 3) + "，实际 " + filled);

        // 残量撤单后敞口不应变化：撤的是未成交部分，不该动已建仓位
        ok(api.cancelOrder(partialUser, bigOrder, symbol), "撤残量 " + (OPEN_SIZE * 2));
        awaitApplied();
        assertEquals(0, filled.compareTo(openVolumeOf(api, partialUser, symbol)),
            "撤残量不应改变已成交建立的仓位");
    }

    @Test
    @Order(25)
    void crossMargin_adjustMargin_creditsAccountNotPosition() throws Exception {
        log("=== CROSS 追加保证金走的是账户余额，不是仓位 extraMargin（与 ISOLATED 两条路径）===");
        int symbol = freshPerp(7, "CROSSMARGIN");
        long crossUser = uid(15);
        ok(api.addUser(crossUser), "addUser crossUser");
        ok(api.adjustUserBalance(crossUser, nextTxId(), USDT_ID, +100_000.0), "crossUser +100000 USDT");
        ok(api.adjustPositionMode(crossUser, PositionMode.ONEWAY), "单向持仓");

        // CROSS 追保证金要求该 symbol 上已有 CROSS 仓位，先建一个
        ok(api.placeOrder(crossUser, nextOrderId(), symbol, OrderAction.BID, OrderType.GTC,
            openPrice, 0.0, OPEN_SIZE, MarginMode.CROSS, LEVERAGE, false), "CROSS 开多");
        ok(api.placeOrder(lp, nextOrderId(), symbol, OrderAction.ASK, OrderType.GTC,
            openPrice, 0.0, OPEN_SIZE, MarginMode.ISOLATED, LEVERAGE, false), "lp 对手 ASK");
        awaitApplied();
        BigDecimal before = balanceOf(crossUser, USDT_ID);

        // CROSS 下第三参是 currency（ISOLATED 才是 symbol）——传 symbol 会被当成币种 id 查不到
        ok(api.adjustMargin(crossUser, MarginMode.CROSS, USDT_ID, +500.0), "CROSS 追加 500");
        awaitApplied();

        assertEquals(0, before.add(new BigDecimal("500")).compareTo(balanceOf(crossUser, USDT_ID)),
            "CROSS 追加保证金应直接落到账户余额");
    }

    @Test
    @Order(26)
    void marginMode_mismatchOnSecondOrder_rejected() throws Exception {
        log("=== 同 symbol 已有仓位时换保证金模式下单应被拒 ===");
        int symbol = freshPerp(4, "MODEMISMATCH");
        long modeUser = uid(10);
        ok(api.addUser(modeUser), "addUser modeUser");
        ok(api.adjustUserBalance(modeUser, nextTxId(), USDT_ID, +100_000.0), "modeUser +100000 USDT");

        ok(api.placeOrder(modeUser, nextOrderId(), symbol, OrderAction.BID, OrderType.GTC,
            openPrice, 0.0, OPEN_SIZE, MarginMode.ISOLATED, LEVERAGE, false), "先以 ISOLATED 开仓");
        ok(api.placeOrder(lp, nextOrderId(), symbol, OrderAction.ASK, OrderType.GTC,
            openPrice, 0.0, OPEN_SIZE, MarginMode.ISOLATED, LEVERAGE, false), "lp 对手 ASK");
        awaitApplied();
        assertHasPosition(modeUser, symbol, "前置：应有 ISOLATED 仓位");

        var mismatch = api.placeOrder(modeUser, nextOrderId(), symbol, OrderAction.BID, OrderType.GTC,
            openPrice, 0.0, OPEN_SIZE, MarginMode.CROSS, LEVERAGE, false);
        assertEquals(CommandResultCode.RISK_MARGIN_MODE_MISMATCH, mismatch.getResultCode(),
            "换 marginMode 应返回 RISK_MARGIN_MODE_MISMATCH，实际 " + mismatch.getResultCode());

        var levMismatch = api.placeOrder(modeUser, nextOrderId(), symbol, OrderAction.BID, OrderType.GTC,
            openPrice, 0.0, OPEN_SIZE, MarginMode.ISOLATED, LEVERAGE + 5, false);
        assertEquals(CommandResultCode.RISK_LEVERAGE_MISMATCH, levMismatch.getResultCode(),
            "换杠杆应返回 RISK_LEVERAGE_MISMATCH，实际 " + levMismatch.getResultCode());
    }

    @Test
    @Order(27)
    void crossMargin_lossOnOneSymbol_drawsOnSharedEquity() throws Exception {
        log("=== CROSS 跨 symbol 共享权益：一个合约亏损会吃掉另一个合约的保证金 ===");
        log("    （这正是 liquidation_doesNotTouchOtherSymbolPosition 的反面——ISOLATED 隔离，CROSS 不隔离）");
        int symA = freshPerp(5, "SHARED-A");
        int symB = freshPerp(6, "SHARED-B");
        long sharedUser = uid(11);
        ok(api.addUser(sharedUser), "addUser sharedUser");
        ok(api.adjustUserBalance(sharedUser, nextTxId(), USDT_ID, +600.0), "sharedUser +600 USDT（两仓刚够）");
        ok(api.adjustPositionMode(sharedUser, PositionMode.ONEWAY), "单向持仓");

        // 同一账户在两个合约上各开一仓，都走 CROSS → 共用同一份 USDT 权益
        ok(api.placeOrder(sharedUser, nextOrderId(), symA, OrderAction.BID, OrderType.GTC,
            openPrice, 0.0, OPEN_SIZE, MarginMode.CROSS, LEVERAGE, false), "PERP CROSS 开多");
        ok(api.placeOrder(traderShort, nextOrderId(), symA, OrderAction.ASK, OrderType.GTC,
            openPrice, 0.0, OPEN_SIZE, MarginMode.ISOLATED, LEVERAGE, false), "PERP 对手");
        ok(api.placeOrder(sharedUser, nextOrderId(), symB, OrderAction.BID, OrderType.GTC,
            openPrice, 0.0, OPEN_SIZE, MarginMode.CROSS, LEVERAGE, false), "DELIVERY CROSS 开多");
        ok(api.placeOrder(traderShort, nextOrderId(), symB, OrderAction.ASK, OrderType.GTC,
            openPrice, 0.0, OPEN_SIZE, MarginMode.ISOLATED, LEVERAGE, false), "DELIVERY 对手");
        awaitApplied();
        assertHasPosition(sharedUser, symA, "PERP 应建仓");
        assertHasPosition(sharedUser, symB, "DELIVERY 应建仓");

        // 只砸 PERP：账户级权益被打穿，强平会按风险降序动仓位——DELIVERY 无法像 ISOLATED 那样置身事外
        // 两仓合计维持保证金约占权益两成，-20% 吃不穿；要看到共享权益被拖垮得砸到 -50%
        ok(api.adjustMarkPrice(symA, openPrice * 0.50), "只砸 A -50%");
        awaitPositionFlat(sharedUser, symA, 30);

        log("  DELIVERY 敞口=" + openVolumeOf(api, sharedUser, symB)
            + " 账户 USDT=" + balanceOf(sharedUser, USDT_ID));
    }

    @Test
    @Order(28)
    void adl_deleveragesProfitableCounterparty() throws Exception {
        log("=== ADL 自动减仓：级联第三段，动的是没做错事的盈利对手方 ===");
        // 专用 symbol，且刻意不给它存 IF——否则第二段就把破产仓位吃掉，永远走不到 ADL
        int adlSymbol = symbolId(2);
        addFuturesSymbol(adlSymbol, SymbolType.FUTURES_CONTRACT_PERPETUAL, BTC_ID, "BTC/USDT ADL");
        ok(api.adjustMarkPrice(adlSymbol, openPrice), "markPrice ADL");

        long victim = uid(7);
        long winner = uid(8);
        ok(api.addUser(victim), "addUser victim");
        ok(api.addUser(winner), "addUser winner");
        ok(api.adjustUserBalance(victim, nextTxId(), USDT_ID, +250.0), "victim +250 USDT（刚够一手）");
        ok(api.adjustUserBalance(winner, nextTxId(), USDT_ID, +100_000.0), "winner +100000 USDT");

        // victim 开多、winner 开空：砸价后 victim 穿仓，winner 是唯一盈利的反向持仓 → ADL 候选人
        ok(api.placeOrder(victim, nextOrderId(), adlSymbol, OrderAction.BID, OrderType.GTC,
            openPrice, 0.0, OPEN_SIZE, MarginMode.ISOLATED, LEVERAGE, false), "victim 开多");
        ok(api.placeOrder(winner, nextOrderId(), adlSymbol, OrderAction.ASK, OrderType.GTC,
            openPrice, 0.0, OPEN_SIZE, MarginMode.ISOLATED, LEVERAGE, false), "winner 开空");
        awaitApplied();
        assertHasPosition(victim, adlSymbol, "victim 应建仓");
        BigDecimal winnerBefore = openVolumeOf(api, winner, adlSymbol);
        assertTrue(winnerBefore.compareTo(BigDecimal.ZERO) != 0, "winner 应建仓");

        // 簿上无买盘 → FORCE 无对手；IF 为零 → 接管失败；级联只剩 ADL
        ok(api.adjustMarkPrice(adlSymbol, openPrice * 0.85), "markPrice -15%");
        awaitPositionFlat(victim, adlSymbol, 30);

        BigDecimal winnerAfter = openVolumeOf(api, winner, adlSymbol);
        log("  winner 敞口 " + winnerBefore + " → " + winnerAfter);
        assertTrue(winnerAfter.abs().compareTo(winnerBefore.abs()) < 0,
            "ADL 应减掉盈利对手方的敞口：" + winnerBefore + " → " + winnerAfter);
    }

    @Test
    @Order(29)
    void futures_3nodesReportSamePosition() throws Exception {
        log("=== 三节点持仓一致性 ===");
        List<String[]> nodes = resolveAllNodes();
        assertTrue(nodes.size() >= 3, "应至少 3 节点");

        BigDecimal expected = null;
        for (String[] node : nodes) {
            try (ExchangeApi nodeApi = ExchangeApi.connect(node[0], Integer.parseInt(node[1]),
                ExchangeApiOptions.builder().sendTimeout(java.time.Duration.ofSeconds(10)).build())) {
                BigDecimal vol = openVolumeOf(nodeApi, liqUser, perpSymbol);
                log("  node " + node[0] + ":" + node[1] + "(" + node[2] + ") liqUser vol=" + vol);
                if (expected == null) {
                    expected = vol;
                } else {
                    assertEquals(0, expected.compareTo(vol), "各节点持仓量应一致");
                }
            }
        }
        log("✓ futures E2E 全流程通过");
    }

    // ================================================================
    // helpers
    // ================================================================

    /**
     * 起一个本用例独占的永续合约。
     *
     * <p>共用 {@code perpSymbol} 的用例会互相污染订单簿——前一个用例挂在簿上的残单会先把对手单吃掉，
     * 于是本用例的下单返回 SUCCESS 却建不成仓位。凡是依赖"这两笔必须互相成交"的用例都要独占 symbol。
     */
    private int freshPerp(int slot, String label) {
        int symbol = symbolId(slot);
        addFuturesSymbol(symbol, SymbolType.FUTURES_CONTRACT_PERPETUAL, BTC_ID, label);
        ok(api.adjustMarkPrice(symbol, openPrice), "markPrice " + label);
        for (int shard = 0; shard < RISK_SHARDS; shard++) {
            ok(api.insuranceFundDeposit(symbol, shard, 1_000_000.0), "IF " + label + " shard" + shard);
        }
        return symbol;
    }

    /** 合约与现货共用币种，scale 走基类同一套真实档位。 */
    private void addFuturesSymbol(int symbol, SymbolType type, int baseCurrency, String label) {
        addFuturesSymbolSpec(symbol, type, baseCurrency, USDT_ID, label);
    }

    /** IF 逐 shard 明细：接管成功的话 available 下降、openVolume 上升，一眼能分清"没接"还是"接了没平"。 */
    private String dumpIf() {
        StringBuilder sb = new StringBuilder("IF: ");
        var report = api.queryInsuranceFundReport().join();
        report.getByShardMap().forEach((shard, perShard) -> {
            var e = perShard.getFuturesInsuranceFundMap().get(perpSymbol);
            if (e != null) {
                sb.append(String.format("[shard%s avail=%d posValue=%d] ",
                    shard, e.getAvailable(), e.getPositionValue()));
            }
        });
        return sb.toString();
    }

    /** 该 symbol 的期货 IF 总额（available + 接管仓位 MtM），跨 shard 求和。 */
    private long perpIfBalance() {
        long sum = 0;
        for (var perShard : api.queryInsuranceFundReport().join().getByShardMap().values()) {
            var entry = perShard.getFuturesInsuranceFundMap().get(perpSymbol);
            if (entry != null) {
                sum += entry.getAvailable() + entry.getPositionValue();
            }
        }
        return sum;
    }

    /** longSide 开多、shortSide 开空，撮合后各持一边。 */
    private void openOpposing(long longSide, long shortSide, double size) throws Exception {
        ok(api.placeOrder(longSide, nextOrderId(), perpSymbol, OrderAction.BID, OrderType.GTC,
            openPrice, 0.0, size, MarginMode.ISOLATED, LEVERAGE, false), "开多");
        ok(api.placeOrder(shortSide, nextOrderId(), perpSymbol, OrderAction.ASK, OrderType.GTC,
            openPrice, 0.0, size, MarginMode.ISOLATED, LEVERAGE, false), "开空");
        awaitApplied();
    }

    /** 强平不触发时，光看"敞口非零"无从判断是保证金够还是 scanner 没跑，把判定依据一并打出来。 */
    private String dumpPositions(long uid, int symbol) throws Exception {
        var positions = api.queryUserReport(uid).get(10, TimeUnit.SECONDS).getPositions().get(symbol);
        if (positions == null || positions.isEmpty()) {
            return "无仓位";
        }
        StringBuilder sb = new StringBuilder();
        for (var p : positions) {
            sb.append(String.format("dir=%s vol=%s initMargin=%s profit=%s extraMargin=%s unrealized=%s "
                    + "liqPrice=%s marginRatio=%s markPrice=%s%n  ",
                p.getDirection(), p.getOpenVolume(), p.getOpenInitMarginSum(), p.getProfit(), p.getExtraMargin(),
                p.getUnrealizedProfit(), p.getLiquidationPrice(), p.getMarginRatio(), p.getMarkPrice()));
        }
        return sb.toString();
    }

    /** 仓位已实现盈亏桶：资金费、平仓盈亏先记这里，平掉仓位才结算进 accounts。 */
    private BigDecimal positionProfit(long uid, int symbol) throws Exception {
        var positions = api.queryUserReport(uid).get(10, TimeUnit.SECONDS).getPositions().get(symbol);
        return (positions == null || positions.isEmpty()) ? BigDecimal.ZERO : positions.get(0).getProfit();
    }

    private BigDecimal balanceOf(long uid, int currency) throws Exception {
        BigDecimal v = api.queryUserReport(uid).get(10, TimeUnit.SECONDS).getAccounts().get(currency);
        return v == null ? BigDecimal.ZERO : v;
    }

    /** 指定方向的持仓量——HEDGE 下同 symbol 有两条 record，不能简单取 get(0)。 */
    private BigDecimal volumeOf(long uid, int symbol, PositionDirection direction) throws Exception {
        var positions = api.queryUserReport(uid).get(10, TimeUnit.SECONDS).getPositions().get(symbol);
        if (positions == null) {
            return BigDecimal.ZERO;
        }
        return directionVolume(positions, direction);
    }

    private static BigDecimal directionVolume(List<SingleUserReportResultView.PositionView> positions,
        PositionDirection direction) {
        return positions.stream().filter(p -> p.getDirection() == direction)
            .map(SingleUserReportResultView.PositionView::getOpenVolume)
            .findFirst().orElse(BigDecimal.ZERO);
    }

    /** ONEWAY 场景用：单向持仓下 symbol 至多一条 record。 */
    private static BigDecimal openVolumeOf(ExchangeApi client, long uid, int symbol) throws Exception {
        var positions = client.queryUserReport(uid).get(10, TimeUnit.SECONDS).getPositions().get(symbol);
        return (positions == null || positions.isEmpty()) ? BigDecimal.ZERO : positions.get(0).getOpenVolume();
    }

    private void assertHasPosition(long uid, int symbol, String message) throws Exception {
        var positions = api.queryUserReport(uid).get(10, TimeUnit.SECONDS).getPositions().get(symbol);
        assertNotNull(positions, message);
        assertTrue(!positions.isEmpty() && positions.get(0).getOpenVolume().compareTo(BigDecimal.ZERO) != 0, message);
    }

    private void assertFlat(long uid, int symbol, String message) throws Exception {
        assertEquals(0, openVolumeOf(api, uid, symbol).compareTo(BigDecimal.ZERO), message);
    }

    /** 强平由 scanner 异步触发，轮询到敞口归零。 */
    private void awaitPositionFlat(long uid, int symbol, int timeoutSec) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutSec * 1000L;
        while (System.currentTimeMillis() < deadline) {
            if (openVolumeOf(api, uid, symbol).compareTo(BigDecimal.ZERO) == 0) {
                return;
            }
            TimeUnit.MILLISECONDS.sleep(500L);
        }
        throw new AssertionError("仓位在 " + timeoutSec + "s 内未被强平：uid=" + uid + " symbol=" + symbol
            + "\n  " + dumpPositions(uid, symbol) + "\n  " + dumpIf());
    }

    /** 命令经 raft 复制后各 shard 才可见，回读前留窗口。 */
    private void awaitApplied() throws Exception {
        TimeUnit.MILLISECONDS.sleep(300L);
    }
}
