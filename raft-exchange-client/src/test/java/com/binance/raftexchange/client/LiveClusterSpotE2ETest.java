package com.binance.raftexchange.client;

import com.binance.raftexchange.stubs.MarginMode;
import com.binance.raftexchange.stubs.OrderAction;
import com.binance.raftexchange.stubs.OrderType;
import com.binance.raftexchange.stubs.response.CommandResultCode;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;

import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 现货在真集群上的端到端流程验证。
 *
 * <p>按业务顺序：建市场 → 撮合成交 → 撤单解冻 → 部分成交 → IOC 无流动性 → 余额不足拒单 →
 * 提现 → 多交易对隔离 → 三节点余额一致。
 *
 * <p>BTC/USDT 与 ETH/USDT 两个 pair 并行，验证 orderbook 之间互不串味。
 */
@EnabledIfSystemProperty(named = "livecluster", matches = "true") // 需本地三节点集群；@Disabled 在本模块被 junit-platform.properties 关掉了，用系统属性 gate
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LiveClusterSpotE2ETest extends LiveClusterE2EBase {

    private long buyer;
    private long seller;
    private int btcUsdt;
    private int ethUsdt;

    /** 本 run 独占价位，避开前次 run 留在 book 上的残留挂单。 */
    private double price;

    @Test
    @Order(1)
    void setup_market_users_balance() {
        log("=== spot E2E setup ===");
        ensureAllCurrencies();

        buyer = uid(1);
        seller = uid(2);
        btcUsdt = SHARED_BTC_USDT;
        ethUsdt = SHARED_ETH_USDT;
        price = 20_000.0 + (runId % 5_000);

        ok(api.addUser(buyer), "addUser buyer=" + buyer);
        ok(api.addUser(seller), "addUser seller=" + seller);

        addSpotSymbol(btcUsdt, BTC_ID, USDT_ID, "BTC/USDT");
        addSpotSymbol(ethUsdt, ETH_ID, USDT_ID, "ETH/USDT");

        ok(api.adjustUserBalance(buyer, nextTxId(), USDT_ID, +200_000.0), "buyer +200000 USDT");
        ok(api.adjustUserBalance(seller, nextTxId(), BTC_ID, +5.0), "seller +5 BTC");
        ok(api.adjustUserBalance(seller, nextTxId(), ETH_ID, +50.0), "seller +50 ETH");
    }

    @Test
    @Order(2)
    void placeOrder_match_balancesUpdated() throws Exception {
        log("=== 撮合成交：双方余额对向变动 ===");
        BigDecimal buyerUsdtBefore = balanceOf(buyer, USDT_ID);
        BigDecimal sellerBtcBefore = balanceOf(seller, BTC_ID);

        ok(api.placeOrder(seller, nextOrderId(), btcUsdt, OrderAction.ASK, OrderType.GTC,
            price, 0.0, 1.0, MarginMode.ISOLATED, 1, false), "seller ASK 1 BTC");
        ok(api.placeOrder(buyer, nextOrderId(), btcUsdt, OrderAction.BID, OrderType.GTC,
            price, price, 1.0, MarginMode.ISOLATED, 1, false), "buyer BID 1 BTC");
        awaitApplied();

        assertTrue(balanceOf(buyer, BTC_ID).compareTo(BigDecimal.ZERO) > 0, "买方应收到 BTC");
        assertTrue(balanceOf(buyer, USDT_ID).compareTo(buyerUsdtBefore) < 0, "买方 USDT 应减少");
        assertTrue(balanceOf(seller, BTC_ID).compareTo(sellerBtcBefore) < 0, "卖方 BTC 应减少");
        assertTrue(balanceOf(seller, USDT_ID).compareTo(BigDecimal.ZERO) > 0, "卖方应收到 USDT");
    }

    @Test
    @Order(3)
    void cancelOrder_restoresLockedFunds() throws Exception {
        log("=== 撤单解冻 ===");
        // 现货冻结进 exchangeLocked，accounts 原样不动（accounts 是含冻结的总额），
        // 故解冻必须断言 exchangeLocked——断在 accounts 上恒真，等于什么都没测。
        BigDecimal lockedBefore = lockedOf(buyer, USDT_ID);
        long orderId = nextOrderId();

        // 挂远离市价的买单，不会成交，资金被冻结
        ok(api.placeOrder(buyer, orderId, btcUsdt, OrderAction.BID, OrderType.GTC,
            price * 0.5, price * 0.5, 1.0, MarginMode.ISOLATED, 1, false), "buyer BID 远离市价");
        awaitApplied();
        assertTrue(lockedOf(buyer, USDT_ID).compareTo(lockedBefore) > 0, "挂单后 quote 应被冻结");

        ok(api.cancelOrder(buyer, orderId, btcUsdt), "撤单");
        awaitApplied();

        assertEquals(0, lockedBefore.compareTo(lockedOf(buyer, USDT_ID)), "撤单后冻结应完全释放");
    }

    @Test
    @Order(4)
    void partialFill_cancelLeftover_unlocksRemainder() throws Exception {
        log("=== 部分成交后撤余量 ===");
        long bigBid = nextOrderId();
        // 卖方只挂 0.5，买方要 2.0 → 成交 0.5，剩 1.5 挂在簿上
        ok(api.placeOrder(seller, nextOrderId(), btcUsdt, OrderAction.ASK, OrderType.GTC,
            price, 0.0, 0.5, MarginMode.ISOLATED, 1, false), "seller ASK 0.5");
        ok(api.placeOrder(buyer, bigBid, btcUsdt, OrderAction.BID, OrderType.GTC,
            price, price, 2.0, MarginMode.ISOLATED, 1, false), "buyer BID 2.0");
        awaitApplied();

        BigDecimal lockedBeforeCancel = lockedOf(buyer, USDT_ID);
        assertTrue(lockedBeforeCancel.compareTo(BigDecimal.ZERO) > 0, "剩余 1.5 应仍在冻结中");
        ok(api.cancelOrder(buyer, bigBid, btcUsdt), "撤销剩余 1.5");
        awaitApplied();

        assertTrue(lockedOf(buyer, USDT_ID).compareTo(lockedBeforeCancel) < 0, "撤余量应释放冻结");
    }

    @Test
    @Order(5)
    void iocOrder_noLiquidity_cancelsImmediately() throws Exception {
        log("=== IOC 无流动性即撤 ===");
        BigDecimal before = balanceOf(buyer, USDT_ID);
        BigDecimal lockedBefore = lockedOf(buyer, USDT_ID);

        // 极低价 IOC 买单，簿上无对手 → 立即取消，不占资金
        ok(api.placeOrder(buyer, nextOrderId(), btcUsdt, OrderAction.BID, OrderType.IOC,
            price * 0.1, price * 0.1, 1.0, MarginMode.ISOLATED, 1, false), "buyer IOC 无对手");
        awaitApplied();

        assertEquals(0, before.compareTo(balanceOf(buyer, USDT_ID)), "IOC 未成交不应动余额");
        assertEquals(0, lockedBefore.compareTo(lockedOf(buyer, USDT_ID)), "IOC 未成交不应留下冻结");
    }

    @Test
    @Order(6)
    void insufficientFunds_rejected() {
        log("=== 余额不足拒单 ===");
        var result = api.placeOrder(buyer, nextOrderId(), btcUsdt, OrderAction.BID, OrderType.GTC,
            price, price, 1_000_000.0, MarginMode.ISOLATED, 1, false);
        assertEquals(CommandResultCode.RISK_NSF, result.getResultCode(), "超出余额应返回 RISK_NSF");
    }

    @Test
    @Order(7)
    void withdraw_decreasesBalance() throws Exception {
        log("=== 提现减少余额 ===");
        BigDecimal before = balanceOf(buyer, USDT_ID);
        ok(api.adjustUserBalance(buyer, nextTxId(), USDT_ID, -1_000.0), "buyer -1000 USDT");
        awaitApplied();

        assertEquals(0, before.subtract(new BigDecimal("1000")).compareTo(balanceOf(buyer, USDT_ID)),
            "提现后余额应精确减少 1000");
    }

    @Test
    @Order(8)
    void secondSymbol_ethUsdt_isolatedFromBtc() throws Exception {
        log("=== 第二个交易对独立撮合 ===");
        BigDecimal btcBefore = balanceOf(buyer, BTC_ID);

        double ethPrice = 1_500.0 + (runId % 500);
        ok(api.placeOrder(seller, nextOrderId(), ethUsdt, OrderAction.ASK, OrderType.GTC,
            ethPrice, 0.0, 2.0, MarginMode.ISOLATED, 1, false), "seller ASK 2 ETH");
        ok(api.placeOrder(buyer, nextOrderId(), ethUsdt, OrderAction.BID, OrderType.GTC,
            ethPrice, ethPrice, 2.0, MarginMode.ISOLATED, 1, false), "buyer BID 2 ETH");
        awaitApplied();

        assertTrue(balanceOf(buyer, ETH_ID).compareTo(BigDecimal.ZERO) > 0, "买方应收到 ETH");
        assertEquals(0, btcBefore.compareTo(balanceOf(buyer, BTC_ID)), "ETH 撮合不应影响 BTC 余额");
    }

    @Test
    @Order(9)
    void moveOrder_repricing_adjustsLock() throws Exception {
        log("=== 改价：reserve 以内放行，超出被风控挡 ===");
        // 现货买单冻结的是 size × reserveBidPrice，不是 size × price——改价只要不越过 reserve，
        // 冻结额一分不变；越过就必须被拒，否则等于凭空放大敞口。
        long orderId = nextOrderId();
        ok(api.placeOrder(buyer, orderId, btcUsdt, OrderAction.BID, OrderType.GTC,
            price * 0.5, price * 0.7, 1.0, MarginMode.ISOLATED, 1, false), "挂 BID price=0.5p reserve=0.7p");
        awaitApplied();
        BigDecimal lockedAtPlace = lockedOf(buyer, USDT_ID);
        assertTrue(lockedAtPlace.compareTo(BigDecimal.ZERO) > 0, "挂单应产生冻结");

        ok(api.moveOrder(buyer, orderId, btcUsdt, price * 0.6), "改价到 0.6p（reserve 以内）");
        awaitApplied();
        assertEquals(0, lockedAtPlace.compareTo(lockedOf(buyer, USDT_ID)),
            "reserve 以内改价不应改变冻结额——冻结基准是 reserveBidPrice");

        var overLimit = api.moveOrder(buyer, orderId, btcUsdt, price * 0.9);
        assertEquals(CommandResultCode.MATCHING_MOVE_FAILED_PRICE_OVER_RISK_LIMIT, overLimit.getResultCode(),
            "改到 reserve 之上应被拒，实际 " + overLimit.getResultCode());

        ok(api.cancelOrder(buyer, orderId, btcUsdt), "收尾撤单");
        awaitApplied();
    }

    @Test
    @Order(10)
    void reduceOrder_releasesProportionalLock() throws Exception {
        log("=== 减量：按比例释放冻结 ===");
        long orderId = nextOrderId();
        ok(api.placeOrder(buyer, orderId, btcUsdt, OrderAction.BID, OrderType.GTC,
            price * 0.5, price * 0.5, 2.0, MarginMode.ISOLATED, 1, false), "挂 BID 2.0");
        awaitApplied();
        BigDecimal lockedFull = lockedOf(buyer, USDT_ID);

        ok(api.reduceOrder(buyer, orderId, btcUsdt, 1.5), "减量 1.5，剩 0.5");
        awaitApplied();
        assertTrue(lockedOf(buyer, USDT_ID).compareTo(lockedFull) < 0, "减量应释放对应冻结");

        ok(api.cancelOrder(buyer, orderId, btcUsdt), "收尾撤单");
        awaitApplied();
    }

    @Test
    @Order(11)
    void fokOrder_insufficientLiquidity_rejectedEntirely() throws Exception {
        log("=== FOK 不能全额成交则整单拒绝，不留残单也不留冻结 ===");
        BigDecimal btcBefore = balanceOf(buyer, BTC_ID);
        BigDecimal lockedBefore = lockedOf(buyer, USDT_ID);

        // size 必须买得起，否则先撞 RISK_NSF，测不到"流动性不足全撤"这条路径
        ok(api.placeOrder(buyer, nextOrderId(), btcUsdt, OrderAction.BID, OrderType.FOK,
            price, price, 3.0, MarginMode.ISOLATED, 1, false), "FOK BID 3 BTC（簿上无足量卖盘）");
        awaitApplied();

        assertEquals(0, btcBefore.compareTo(balanceOf(buyer, BTC_ID)), "FOK 未全额成交不应有任何成交");
        assertEquals(0, lockedBefore.compareTo(lockedOf(buyer, USDT_ID)), "FOK 拒绝后不应残留冻结");
    }

    @Test
    @Order(12)
    void iocBudgetOrder_spendsWithinBudget() throws Exception {
        log("=== IOC_BUDGET：price 是总预算而非单价，走 pendingHoldBudget 分支 ===");
        ok(api.placeOrder(seller, nextOrderId(), btcUsdt, OrderAction.ASK, OrderType.GTC,
            price, 0.0, 1.0, MarginMode.ISOLATED, 1, false), "seller ASK 1 BTC");
        awaitApplied();

        BigDecimal btcBefore = balanceOf(buyer, BTC_ID);
        BigDecimal usdtBefore = balanceOf(buyer, USDT_ID);
        double budget = price * 1.1; // 留出手续费与滑点余量
        // BUDGET 单要求 reserveBidPrice == price（RiskEngine.placeExchangeOrder 会校验）
        ok(api.placeOrder(buyer, nextOrderId(), btcUsdt, OrderAction.BID, OrderType.IOC_BUDGET,
            budget, budget, 1.0, MarginMode.ISOLATED, 1, false), "IOC_BUDGET 买 1 BTC");
        awaitApplied();

        assertTrue(balanceOf(buyer, BTC_ID).compareTo(btcBefore) > 0, "预算单应买到 BTC");
        BigDecimal spent = usdtBefore.subtract(balanceOf(buyer, USDT_ID));
        assertTrue(spent.compareTo(BigDecimal.valueOf(budget)) <= 0,
            "花费不得超过预算：spent=" + spent + " budget=" + budget);
    }

    @Test
    @Order(13)
    void takerFee_chargedOnTopOfNotional() throws Exception {
        log("=== taker 手续费：买方作为 taker 支出应超过 notional ===");
        ok(api.placeOrder(seller, nextOrderId(), btcUsdt, OrderAction.ASK, OrderType.GTC,
            price, 0.0, 1.0, MarginMode.ISOLATED, 1, false), "seller 先挂 ASK（maker）");
        awaitApplied();

        BigDecimal usdtBefore = balanceOf(buyer, USDT_ID);
        ok(api.placeOrder(buyer, nextOrderId(), btcUsdt, OrderAction.BID, OrderType.GTC,
            price, price, 1.0, MarginMode.ISOLATED, 1, false), "buyer 吃单（taker）");
        awaitApplied();

        BigDecimal spent = usdtBefore.subtract(balanceOf(buyer, USDT_ID));
        BigDecimal notional = BigDecimal.valueOf(price);
        assertTrue(spent.compareTo(notional) > 0,
            "taker 应在 notional 之上多付手续费：spent=" + spent + " notional=" + notional);
    }

    @Test
    @Order(14)
    void transfer_movesFundsBetweenUsers() throws Exception {
        log("=== 用户间转账 ===");
        BigDecimal fromBefore = balanceOf(buyer, USDT_ID);
        BigDecimal toBefore = balanceOf(seller, USDT_ID);

        ok(api.transfer(buyer, seller, USDT_ID, 500.0, nextTxId()), "buyer → seller 500 USDT");
        awaitApplied();

        assertEquals(0, fromBefore.subtract(new BigDecimal("500")).compareTo(balanceOf(buyer, USDT_ID)),
            "付款方应精确减少 500");
        assertEquals(0, toBefore.add(new BigDecimal("500")).compareTo(balanceOf(seller, USDT_ID)),
            "收款方应精确增加 500");
    }

    @Test
    @Order(15)
    void suspendUser_blocksTrading_resumeRestores() throws Exception {
        log("=== 暂停用户：有余额不可暂停，清空后放行 ===");
        long tmpUser = uid(3);
        ok(api.addUser(tmpUser), "addUser tmpUser");
        ok(api.adjustUserBalance(tmpUser, nextTxId(), USDT_ID, +50_000.0), "tmpUser +50000 USDT");
        awaitApplied();

        // 有余额时暂停会把这笔钱冻在一个不可交易的账户里，引擎不允许
        var withBalance = api.suspendUser(tmpUser);
        assertEquals(CommandResultCode.USER_MGMT_USER_NOT_SUSPENDABLE_NON_EMPTY_ACCOUNTS,
            withBalance.getResultCode(), "有余额时暂停应被拒，实际 " + withBalance.getResultCode());

        ok(api.adjustUserBalance(tmpUser, nextTxId(), USDT_ID, -50_000.0), "清空余额");
        awaitApplied();
        ok(api.suspendUser(tmpUser), "清空后暂停应成功");
        awaitApplied();
        ok(api.resumeUser(tmpUser), "恢复应成功");
    }

    @Test
    @Order(16)
    void spot_3nodesReportSameBalance() throws Exception {
        log("=== 三节点余额一致性 ===");
        List<String[]> nodes = resolveAllNodes();
        assertTrue(nodes.size() >= 3, "应至少 3 节点");

        BigDecimal expected = null;
        for (String[] node : nodes) {
            try (ExchangeApi nodeApi = ExchangeApi.connect(node[0], Integer.parseInt(node[1]),
                ExchangeApiOptions.builder().sendTimeout(java.time.Duration.ofSeconds(10)).build())) {
                BigDecimal usdt = nodeApi.queryUserReport(buyer).get(10, TimeUnit.SECONDS)
                    .getAccounts().get(USDT_ID);
                log("  node " + node[0] + ":" + node[1] + "(" + node[2] + ") buyer USDT=" + usdt);
                if (expected == null) {
                    expected = usdt;
                } else {
                    assertEquals(0, expected.compareTo(usdt), "各节点余额应一致");
                }
            }
        }
        log("✓ spot E2E 全流程通过");
    }

    // ================================================================
    // helpers
    // ================================================================

    /** 现货挂单冻结额。accounts 是含冻结的总额，冻结变化只能在这里看到。 */
    private BigDecimal lockedOf(long uid, int currency) throws Exception {
        BigDecimal v = api.queryUserReport(uid).get(10, TimeUnit.SECONDS).getExchangeLocked().get(currency);
        return v == null ? BigDecimal.ZERO : v;
    }

    private BigDecimal balanceOf(long uid, int currency) throws Exception {
        BigDecimal v = api.queryUserReport(uid).get(10, TimeUnit.SECONDS).getAccounts().get(currency);
        return v == null ? BigDecimal.ZERO : v;
    }

    private void awaitApplied() throws Exception {
        TimeUnit.MILLISECONDS.sleep(300L);
    }
}
