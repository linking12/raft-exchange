package com.binance.raftexchange.server.raft.jraft;

import com.binance.raftexchange.client.CommandResultView;
import com.binance.raftexchange.client.ExchangeApi;
import com.binance.raftexchange.stubs.FundEventReportPB;
import com.binance.raftexchange.stubs.FuturesExecutionReportPB;
import com.binance.raftexchange.stubs.MarginMode;
import com.binance.raftexchange.stubs.OrderAction;
import com.binance.raftexchange.stubs.OrderType;
import com.binance.raftexchange.stubs.SpotExecutionReportPB;
import com.binance.raftexchange.stubs.SymbolType;
import com.binance.raftexchange.stubs.response.CommandResultCode;
import com.binance.raftexchange.spi.restore.FundEventRestorer;
import com.binance.raftexchange.spi.restore.TradeEventRestorer;
import com.binance.raftexchange.spi.restore.model.RestoredFundEvent;
import com.binance.raftexchange.spi.restore.model.RestoredFuturesExecution;
import com.binance.raftexchange.spi.restore.model.RestoredSpotExecution;
import exchange.core2.core.common.config.OrdersProcessingConfiguration;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 真·全链路 e2e：客户端 gRPC → server gRPC interceptor → RaftClusterContainer.requestConsensus → jraft Task.apply →
 * JraftExchangeStateMachine.onApply → ExchangeRuntime.exchangeApi().submitBatchAsync → ExchangeCore →
 * SimpleEventsProcessor → IEventsHandlerByKafka.{fundEventReport, spotExecutionReport} → mockKafkaProducer.send →
 * {FundEventReportPB, SpotExecutionReportPB} bytes → {FundEventRestorer, TradeEventRestorer}.restore → BigDecimal
 *
 * 缩放 round-trip 断言：客户端输入 == 引擎编码 == 还原值。
 *
 * Phase 1: 充值/提现 → FundEventRestorer 还原 free 余额 Phase 2: 现货 GTC 限价成交 → TradeEventRestorer 还原成交量/价/notional/commission
 * Phase 3: 现货 FOK_BUDGET 全成 → 验证 quoteOrderQty 按 product scale 还原 + 引擎闭环 Phase 4: 现货 IOC_BUDGET 部分成交 → 验证新的
 * newOrderMatchIocBudget 撮合路径 Phase 5: 期货 FOK_BUDGET → 验证 pendingHoldBudget + futures result.orderQty 重载还原 Phase 6: 期货
 * IOC_BUDGET 部分成交 → 验证 newOrderMatchIocBudget 在 margin 路径下的撮合 + reject 报表
 *
 * 默认跳过 (~13s 太重)，需要手动跑： mvn -pl raft-exchange-server -am test -Dtest=RaftFullE2ETest \
 * -Djunit.jupiter.conditions.deactivate='org.junit.*DisabledCondition'
 *
 * Phase 5/6（期货）需要 MARGIN_TRADING_ENABLED；本类 @BeforeAll 里直接改写 {@code OrdersProcessingConfiguration.DEFAULT} 强开，@AfterAll
 * 恢复，避免污染同 JVM 内别的测试。
 */
@Disabled("Slow e2e (~13s): 单节点 jraft + gRPC + leader election。手动运行用于回归 raft/gRPC 全链路。")
class RaftFullE2ETest {

    private static OrdersProcessingConfiguration originalOrdersProcCfg;

    @BeforeAll
    static void enableMarginTrading() {
        originalOrdersProcCfg = OrdersProcessingConfiguration.DEFAULT;
        OrdersProcessingConfiguration.DEFAULT = OrdersProcessingConfiguration.builder()
            .riskProcessingMode(OrdersProcessingConfiguration.RiskProcessingMode.FULL_PER_CURRENCY)
            .marginTradingMode(OrdersProcessingConfiguration.MarginTradingMode.MARGIN_TRADING_ENABLED).build();
    }

    @AfterAll
    static void restoreOrdersProcCfg() {
        if (originalOrdersProcCfg != null) {
            OrdersProcessingConfiguration.DEFAULT = originalOrdersProcCfg;
        }
    }

    private static final int USDT_ID = 2;
    private static final int USDT_DIGIT = 6;
    private static final int BNB_ID = 1;
    private static final int BNB_DIGIT = 8;
    private static final int SYMBOL_ID = 11;
    private static final int FUTURES_SYMBOL_ID = 12; // BNB/USDT 永续合约

    // 现货合约规格（百分比费率模式，刻意避开 BaseTest 用的 feeScaleK=0 固定费率，
    // 这样能验证 TradeEventRestorer 假设：commission/lastQuoteQty 落在 product scale）
    private static final long BASE_SCALE_K = 1_000L; // 3 位小数（BNB-like）
    private static final long QUOTE_SCALE_K = 100_000L; // 5 位小数（USDT-like）
    private static final long PRODUCT_SCALE = BASE_SCALE_K * QUOTE_SCALE_K; // 10^8
    private static final long TAKER_FEE = 1_000L; // 0.1%
    private static final long MAKER_FEE = 500L; // 0.05%
    private static final long FEE_SCALE_K = 1_000_000L; // 10^6

    private static final long BUYER = 42L;
    private static final long SELLER = 43L;

    @Test
    void clientToServerRoundTripThroughRaftAndGrpc() throws Exception {
        try (SingleNodeRaftHarness harness = SingleNodeRaftHarness.start()) {
            ExchangeApi client = ExchangeApi.connect("127.0.0.1", harness.grpcPort());

            // ================ Phase 1: 充值/提现 → FundEvent ================

            ok(client.addCurrency(USDT_ID, "USDT", USDT_DIGIT), ">> addCurrency(USDT)");
            ok(client.addCurrency(BNB_ID, "BNB", BNB_DIGIT), ">> addCurrency(BNB)");
            ok(client.addUser(BUYER), ">> addUser(buyer=42)");
            ok(client.addUser(SELLER), ">> addUser(seller=43)");

            ok(client.adjustUserBalance(BUYER, 1L, USDT_ID, +1000.0), ">> deposit(buyer +1000 USDT)");
            ok(client.adjustUserBalance(BUYER, 2L, USDT_ID, -2.3), ">> withdraw(buyer -2.3 USDT)");
            ok(client.adjustUserBalance(BUYER, 3L, USDT_ID, -100.123456), ">> withdraw(buyer -100.123456)");
            ok(client.adjustUserBalance(SELLER, 4L, BNB_ID, +0.5), ">> deposit(seller +0.5 BNB)");

            Thread.sleep(500);

            List<ProducerRecord<Long, byte[]>> fundRecs = harness.fundEventRecords();
            System.out.printf("%n--- FUND topic 收到 %d 条事件 ---%n", fundRecs.size());

            // 期望 4 条：buyer DEPOSIT + 2 × buyer WITHDRAW + seller DEPOSIT
            assertEquals(4, fundRecs.size(), "phase1 期望 4 条 FundEvent");

            BigDecimal[] expFree = {new BigDecimal("1000"), // buyer +1000
                new BigDecimal("997.7"), // buyer -2.3
                new BigDecimal("897.576544"), // buyer -100.123456
                new BigDecimal("0.5") // seller +0.5 BNB
            };
            for (int i = 0; i < fundRecs.size(); i++) {
                FundEventReportPB pb = FundEventReportPB.parseFrom(fundRecs.get(i).value());
                RestoredFundEvent v = FundEventRestorer.restore(pb);
                int idx = i;

                System.out.printf("[FUND] [%d] %-9s uid=%d ccy=%d free=%s locked=%s%n", i, v.eventType, v.accountId,
                    v.currency, v.free.toPlainString(), v.locked.toPlainString());

                assertEquals(0, expFree[i].compareTo(v.free),
                    () -> "phase1 FundEvent[" + idx + "] free 不匹配, expected=" + expFree[idx] + " actual=" + v.free);
            }

            // ================ Phase 2: 现货成交 → SpotExecution ================

            ok(client.addSymbol(SYMBOL_ID, SymbolType.CURRENCY_EXCHANGE_PAIR, BNB_ID, USDT_ID, BASE_SCALE_K,
                QUOTE_SCALE_K, TAKER_FEE, MAKER_FEE, /*liquidationFee*/ 0L, FEE_SCALE_K, /*initMargin*/ 0L,
                /*initMarginScaleK*/ 0L, /*maintenanceMargin*/ null, /*maintenanceMarginScaleK*/ 0L,
                /*maxLeverage*/ null), ">> addSymbol(BNB/USDT spot, 0.1%/0.05% fee)");

            double price = 50.0;
            double size = 0.05;

            // 卖方先挂 ASK → 进 book，成为 maker
            ok(client.placeOrder(SELLER, 100L, SYMBOL_ID, OrderAction.ASK, OrderType.GTC, price, /*reverse*/ 0.0, size,
                null, 0, false), ">> seller ASK 0.05 BNB @ 50");

            // 买方 BID 同价 → 立即吃单，成为 taker
            ok(client.placeOrder(BUYER, 101L, SYMBOL_ID, OrderAction.BID, OrderType.GTC, price, /*reverse*/ price + 1,
                size, null, 0, false), ">> buyer BID 0.05 BNB @ 50 (taker)");

            Thread.sleep(500);

            List<ProducerRecord<Long, byte[]>> spotRecs = harness.spotEventRecords();
            System.out.printf("%n--- SPOT topic 收到 %d 条 ExecutionReport ---%n", spotRecs.size());
            assertTrue(spotRecs.size() >= 2, "至少应有 2 条（买卖双方各 1 条 TRADE）实际=" + spotRecs.size());

            // 找出双方的 TRADE 报告（filter 掉 NEW/其他）
            RestoredSpotExecution buyerTrade = null;
            RestoredSpotExecution sellerTrade = null;
            for (ProducerRecord<Long, byte[]> rec : spotRecs) {
                SpotExecutionReportPB pb = SpotExecutionReportPB.parseFrom(rec.value());
                RestoredSpotExecution v = TradeEventRestorer.restore(pb);
                System.out.printf(
                    "[SPOT] uid=%d type=%-7s status=%-17s side=%s qty=%s price=%s lastQty=%s lastPx=%s lastQuoteQty=%s cumQty=%s cumQuoteQty=%s commission=%s(asset=%d)%n",
                    v.accountId, v.executionType, v.orderStatus, v.side, v.qty.toPlainString(), v.price.toPlainString(),
                    v.lastQty.toPlainString(), v.lastPrice.toPlainString(), v.lastQuoteQty.toPlainString(),
                    v.cumulativeQty.toPlainString(), v.cumulativeQuoteQty.toPlainString(), v.commission.toPlainString(),
                    v.commissionAsset);

                if (v.executionType.name().equals("TRADE")) {
                    if (v.accountId == BUYER)
                        buyerTrade = v;
                    if (v.accountId == SELLER)
                        sellerTrade = v;
                }
            }

            assertNotNull(buyerTrade, "未收到买方 TRADE 报告");
            assertNotNull(sellerTrade, "未收到卖方 TRADE 报告");

            // ---- 关键金额断言（验证 scale 假设 vs 引擎实际编码） ----
            // base scale: qty
            assertBd("0.05", buyerTrade.lastQty, "buyer lastQty");
            assertBd("0.05", sellerTrade.lastQty, "seller lastQty");
            assertBd("0.05", buyerTrade.cumulativeQty, "buyer cumQty");
            assertBd("0.05", sellerTrade.cumulativeQty, "seller cumQty");

            // quote scale: price
            assertBd("50", buyerTrade.lastPrice, "buyer lastPrice");
            assertBd("50", sellerTrade.lastPrice, "seller lastPrice");

            // product scale: notional (size × price)
            assertBd("2.5", buyerTrade.lastQuoteQty, "buyer lastQuoteQty");
            assertBd("2.5", sellerTrade.lastQuoteQty, "seller lastQuoteQty");
            assertBd("2.5", buyerTrade.cumulativeQuoteQty, "buyer cumQuoteQty");
            assertBd("2.5", sellerTrade.cumulativeQuoteQty, "seller cumQuoteQty");

            // product scale: commission（百分比费率路径）
            // 买方 taker: 0.1% × 2.5 = 0.0025
            // 卖方 maker: 0.05% × 2.5 = 0.00125
            assertBd("0.0025", buyerTrade.commission, "buyer (taker) commission");
            assertBd("0.00125", sellerTrade.commission, "seller (maker) commission");

            // commissionAsset 始终是 quote currency
            assertEquals(USDT_ID, buyerTrade.commissionAsset, "buyer commissionAsset");
            assertEquals(USDT_ID, sellerTrade.commissionAsset, "seller commissionAsset");

            // 锚定 Phase 2 结束时的 spot 记录数 —— 后续 phase 用相对位置切片
            final int phase2SpotCount = spotRecs.size();

            // ================ Phase 3: FOK_BUDGET 全成 ================
            // 引擎对 BUDGET 单把 cmd.price 当作 product-scale 总预算 notional。
            // 客户端 ExchangeApi 已修复 → 直接传 budget = 2.5 USDT 即可。

            // 卖方再挂一张 ASK 0.05 BNB @ 50 USDT 当对手
            ok(client.placeOrder(SELLER, 102L, SYMBOL_ID, OrderAction.ASK, OrderType.GTC, /*price*/ 50.0,
                /*reverse*/ 0.0, /*size*/ 0.05, null, 0, false), ">> seller ASK#2 0.05 BNB @ 50");

            // 买方 FOK_BUDGET：直接传预算 2.5 USDT
            ok(client.placeOrder(BUYER, 103L, SYMBOL_ID, OrderAction.BID, OrderType.FOK_BUDGET, /*budget*/ 2.5,
                /*reverse=同*/ 2.5, /*size*/ 0.05, null, 0, false), ">> buyer FOK_BUDGET budget=2.5 USDT");

            Thread.sleep(500);

            List<ProducerRecord<Long, byte[]>> phase3Spot = harness.spotEventRecords();
            System.out.printf("%n--- Phase 3 SPOT 新增 %d 条 ---%n", phase3Spot.size() - phase2SpotCount);

            RestoredSpotExecution fokBudgetBuyerTrade = null;
            for (int i = phase2SpotCount; i < phase3Spot.size(); i++) {
                SpotExecutionReportPB pb = SpotExecutionReportPB.parseFrom(phase3Spot.get(i).value());
                RestoredSpotExecution v = TradeEventRestorer.restore(pb);
                System.out.printf(
                    "[SPOT3] uid=%d type=%-7s status=%-17s side=%s ordType=%-11s qty=%s price=%s quoteOrderQty=%s lastQty=%s lastQuoteQty=%s commission=%s%n",
                    v.accountId, v.executionType, v.orderStatus, v.side, v.orderType, v.qty.toPlainString(),
                    v.price.toPlainString(), v.quoteOrderQty.toPlainString(), v.lastQty.toPlainString(),
                    v.lastQuoteQty.toPlainString(), v.commission.toPlainString());
                if (v.executionType.name().equals("TRADE") && v.accountId == BUYER) {
                    fokBudgetBuyerTrade = v;
                }
            }

            assertNotNull(fokBudgetBuyerTrade, "未收到 FOK_BUDGET 买方 TRADE 报告");
            // ★ quoteOrderQty 按 product scale 还原 → 恰好是 budget 原值
            assertBd("2.5", fokBudgetBuyerTrade.quoteOrderQty, "FOK_BUDGET quoteOrderQty");
            assertBd("0.05", fokBudgetBuyerTrade.lastQty, "FOK_BUDGET lastQty");
            assertBd("50", fokBudgetBuyerTrade.lastPrice, "FOK_BUDGET lastPrice");
            assertBd("2.5", fokBudgetBuyerTrade.lastQuoteQty, "FOK_BUDGET lastQuoteQty");
            assertBd("0.0025", fokBudgetBuyerTrade.commission, "FOK_BUDGET taker commission");

            final int phase3SpotCount = phase3Spot.size();

            // ================ Phase 4: IOC_BUDGET 部分成交 ================
            // size=0.05, budget=1.5 → 在 50 USDT 价位最多买 1.5/50=0.03，剩 0.02 cancel。
            // 验证我新加的 newOrderMatchIocBudget + tryMatchInstantlyWithBudget 路径。

            ok(client.placeOrder(SELLER, 104L, SYMBOL_ID, OrderAction.ASK, OrderType.GTC, /*price*/ 50.0,
                /*reverse*/ 0.0, /*size*/ 0.05, null, 0, false), ">> seller ASK#3 0.05 BNB @ 50");

            ok(client.placeOrder(BUYER, 105L, SYMBOL_ID, OrderAction.BID, OrderType.IOC_BUDGET, /*budget*/ 1.5,
                /*reverse=同*/ 1.5, /*size*/ 0.05, null, 0, false), ">> buyer IOC_BUDGET budget=1.5 USDT (期望部分成交 0.03)");

            Thread.sleep(500);

            List<ProducerRecord<Long, byte[]>> phase4Spot = harness.spotEventRecords();
            System.out.printf("%n--- Phase 4 SPOT 新增 %d 条 ---%n", phase4Spot.size() - phase3SpotCount);

            RestoredSpotExecution iocBudgetBuyerTrade = null;
            for (int i = phase3SpotCount; i < phase4Spot.size(); i++) {
                SpotExecutionReportPB pb = SpotExecutionReportPB.parseFrom(phase4Spot.get(i).value());
                RestoredSpotExecution v = TradeEventRestorer.restore(pb);
                System.out.printf(
                    "[SPOT4] uid=%d type=%-7s status=%-17s side=%s ordType=%-11s qty=%s price=%s quoteOrderQty=%s lastQty=%s lastQuoteQty=%s commission=%s%n",
                    v.accountId, v.executionType, v.orderStatus, v.side, v.orderType, v.qty.toPlainString(),
                    v.price.toPlainString(), v.quoteOrderQty.toPlainString(), v.lastQty.toPlainString(),
                    v.lastQuoteQty.toPlainString(), v.commission.toPlainString());
                if (v.executionType.name().equals("TRADE") && v.accountId == BUYER) {
                    iocBudgetBuyerTrade = v;
                }
            }

            assertNotNull(iocBudgetBuyerTrade, "未收到 IOC_BUDGET 买方 TRADE 报告");
            // ★ 验证部分成交：买到 0.03 BNB（不是请求的 0.05），花掉 1.5 USDT
            assertBd("1.5", iocBudgetBuyerTrade.quoteOrderQty, "IOC_BUDGET quoteOrderQty");
            assertBd("0.03", iocBudgetBuyerTrade.lastQty, "IOC_BUDGET lastQty（部分成交）");
            assertBd("50", iocBudgetBuyerTrade.lastPrice, "IOC_BUDGET lastPrice");
            assertBd("1.5", iocBudgetBuyerTrade.lastQuoteQty, "IOC_BUDGET lastQuoteQty");
            assertBd("0.0015", iocBudgetBuyerTrade.commission, "IOC_BUDGET taker commission");

            // ================ Phase 5: 期货 FOK_BUDGET ================
            // 验证：1) RiskEngine canPlaceMarginOrder 走 budget extraNotional 分支
            // 2) pendingHoldBudget 正确登记 pending 状态
            // 3) handleMatcherEventMargin 处理 TRADE 事件 + 开新仓
            // 4) RestoredFuturesExecution 识别 orderType 还原 orderQty 重载（BUDGET→product）
            // 设计：BNB/USDT 永续 markPrice=50，seller GTC ASK 0.05 BNB @ 50 (10x leverage),
            // buyer FOK_BUDGET budget=2.5 USDT size=0.05 全成。
            ok(client.addSymbol(FUTURES_SYMBOL_ID, SymbolType.FUTURES_CONTRACT_PERPETUAL, BNB_ID, USDT_ID, BASE_SCALE_K,
                QUOTE_SCALE_K, TAKER_FEE, MAKER_FEE, /*liquidationFee*/ 0L, FEE_SCALE_K, /*initMargin*/ 10L,
                /*initMarginScaleK*/ 100L, // 10% 初始保证金
                /*maintenanceMargin*/ java.util.Map.of(0L, 5L), // 5% 维持保证金
                /*maintenanceMarginScaleK*/ 100L, /*maxLeverage*/ java.util.Map.of(0L, 10L)), // 任何 notional 最大 10x
                ">> addSymbol(BNB/USDT perpetual)");

            ok(client.adjustMarkPrice(FUTURES_SYMBOL_ID, 50.0), ">> adjustMarkPrice(perp 50 USDT)");

            // Seller GTC ASK：开空仓 0.05 BNB @ 50，10x leverage → 需 ~0.25 USDT margin
            ok(client.placeOrder(SELLER, 200L, FUTURES_SYMBOL_ID, OrderAction.ASK, OrderType.GTC, /*price*/ 50.0,
                /*reverse*/ 0.0, /*size*/ 0.05, MarginMode.ISOLATED, /*leverage*/ 10, /*reduceOnly*/ false),
                ">> seller futures ASK 0.05 BNB @ 50 (10x)");

            // Buyer FOK_BUDGET：开多仓预算 2.5 USDT，10x leverage
            ok(client.placeOrder(BUYER, 201L, FUTURES_SYMBOL_ID, OrderAction.BID, OrderType.FOK_BUDGET, /*budget*/ 2.5,
                /*reverse=同*/ 2.5, /*size*/ 0.05, MarginMode.ISOLATED, /*leverage*/ 10, /*reduceOnly*/ false),
                ">> buyer futures FOK_BUDGET budget=2.5 USDT (10x)");

            Thread.sleep(500);

            List<ProducerRecord<Long, byte[]>> perpRecs = harness.perpEventRecords();
            System.out.printf("%n--- PERP topic 收到 %d 条 ExecutionReport ---%n", perpRecs.size());
            assertTrue(perpRecs.size() >= 4, "至少 2 个 NEW + 2 个 TRADE，实际=" + perpRecs.size());

            RestoredFuturesExecution futuresBuyerTrade = null;
            RestoredFuturesExecution futuresSellerTrade = null;
            for (ProducerRecord<Long, byte[]> rec : perpRecs) {
                FuturesExecutionReportPB pb = FuturesExecutionReportPB.parseFrom(rec.value());
                RestoredFuturesExecution v = TradeEventRestorer.restore(pb);
                System.out.printf(
                    "[PERP] uid=%d type=%-7s status=%-17s side=%s ordType=%-11s orderQty=%s price=%s lastQty=%s lastPx=%s cumQuoteQty=%s avgPx=%s fee=%s%n",
                    v.userId, v.executionType, v.orderStatus, v.side, v.orderType, v.orderQty.toPlainString(),
                    v.price.toPlainString(), v.lastQty.toPlainString(), v.lastPx.toPlainString(),
                    v.cumQuoteQty.toPlainString(), v.avgPx.toPlainString(), v.fee.toPlainString());
                if (v.executionType.name().equals("TRADE") && v.userId == BUYER) {
                    futuresBuyerTrade = v;
                }
                if (v.executionType.name().equals("TRADE") && v.userId == SELLER) {
                    futuresSellerTrade = v;
                }
            }

            assertNotNull(futuresBuyerTrade, "未收到期货 FOK_BUDGET buyer TRADE");
            assertNotNull(futuresSellerTrade, "未收到期货 GTC seller TRADE");

            // ★ orderQty 在 BUDGET 下被重载为预算（product scale）；非 BUDGET 是 cmd.size（orderQty scale）
            assertBd("2.5", futuresBuyerTrade.orderQty, "futures BUDGET buyer.orderQty (重载为 budget)");
            assertBd("0.05", futuresSellerTrade.orderQty, "futures GTC seller.orderQty (= cmd.size)");

            // 成交事实：双方 lastQty / lastPx / cumQuoteQty / avgPx 应对齐
            assertBd("0.05", futuresBuyerTrade.lastQty, "futures buyer.lastQty");
            assertBd("50", futuresBuyerTrade.lastPx, "futures buyer.lastPx");
            assertBd("2.5", futuresBuyerTrade.cumQuoteQty, "futures buyer.cumQuoteQty");
            assertBd("50", futuresBuyerTrade.avgPx, "futures buyer.avgPx");

            assertBd("0.05", futuresSellerTrade.lastQty, "futures seller.lastQty");
            assertBd("50", futuresSellerTrade.lastPx, "futures seller.lastPx");

            // fee（taker 0.1%, maker 0.05% on 2.5 USDT notional）
            assertBd("0.0025", futuresBuyerTrade.fee, "futures buyer (taker) fee");
            assertBd("0.00125", futuresSellerTrade.fee, "futures seller (maker) fee");

            final int phase5PerpCount = perpRecs.size();

            // ================ Phase 6: 期货 IOC_BUDGET 部分成交 ================
            // 验证：1) newOrderMatchIocBudget 在 FUTURES_CONTRACT_PERPETUAL 路径下也走得通
            // 2) tryMatchInstantlyWithBudget 在 margin 设置（pendingHoldBudget→position 开仓）下结算正确
            // 3) REJECT execution report 在 futures 路径下也按 BUDGET orderQty 重载（product scale）还原
            // 设计：seller GTC ASK#2 0.05 BNB @ 50；buyer IOC_BUDGET budget=1.5 size=0.05
            // → 预算只够吃 0.03 BNB（1.5/50），剩 0.02 reject。
            ok(client.placeOrder(SELLER, 300L, FUTURES_SYMBOL_ID, OrderAction.ASK, OrderType.GTC, /*price*/ 50.0,
                /*reverse*/ 0.0, /*size*/ 0.05, MarginMode.ISOLATED, /*leverage*/ 10, /*reduceOnly*/ false),
                ">> seller futures ASK#2 0.05 BNB @ 50 (10x)");

            ok(client.placeOrder(BUYER, 301L, FUTURES_SYMBOL_ID, OrderAction.BID, OrderType.IOC_BUDGET, /*budget*/ 1.5,
                /*reverse=同*/ 1.5, /*size*/ 0.05, MarginMode.ISOLATED, /*leverage*/ 10, /*reduceOnly*/ false),
                ">> buyer futures IOC_BUDGET budget=1.5 USDT size=0.05 (期望部分成交 0.03)");

            Thread.sleep(500);

            List<ProducerRecord<Long, byte[]>> phase6Perp = harness.perpEventRecords();
            System.out.printf("%n--- Phase 6 PERP 新增 %d 条 ---%n", phase6Perp.size() - phase5PerpCount);
            // 至少 2 NEW + 2 TRADE + 1 REJECT = 5
            assertTrue(phase6Perp.size() - phase5PerpCount >= 5,
                "至少 2 个 NEW + 2 个 TRADE + 1 个 REJECT，实际新增=" + (phase6Perp.size() - phase5PerpCount));

            RestoredFuturesExecution iocBudgetFuturesBuyerTrade = null;
            RestoredFuturesExecution iocBudgetFuturesSellerTrade = null;
            RestoredFuturesExecution iocBudgetFuturesReject = null;
            for (int i = phase5PerpCount; i < phase6Perp.size(); i++) {
                FuturesExecutionReportPB pb = FuturesExecutionReportPB.parseFrom(phase6Perp.get(i).value());
                RestoredFuturesExecution v = TradeEventRestorer.restore(pb);
                System.out.printf(
                    "[PERP6] uid=%d type=%-7s status=%-17s side=%s ordType=%-11s orderQty=%s price=%s lastQty=%s lastPx=%s cumQuoteQty=%s avgPx=%s fee=%s%n",
                    v.userId, v.executionType, v.orderStatus, v.side, v.orderType, v.orderQty.toPlainString(),
                    v.price.toPlainString(), v.lastQty.toPlainString(), v.lastPx.toPlainString(),
                    v.cumQuoteQty.toPlainString(), v.avgPx.toPlainString(), v.fee.toPlainString());
                if (v.executionType.name().equals("TRADE") && v.userId == BUYER) {
                    iocBudgetFuturesBuyerTrade = v;
                }
                if (v.executionType.name().equals("TRADE") && v.userId == SELLER && v.orderId == 300L) {
                    iocBudgetFuturesSellerTrade = v;
                }
                if (v.executionType.name().equals("REJECT") && v.userId == BUYER) {
                    iocBudgetFuturesReject = v;
                }
            }

            assertNotNull(iocBudgetFuturesBuyerTrade, "未收到期货 IOC_BUDGET buyer TRADE");
            assertNotNull(iocBudgetFuturesSellerTrade, "未收到期货 GTC seller (orderId=300) TRADE");
            assertNotNull(iocBudgetFuturesReject, "未收到期货 IOC_BUDGET buyer REJECT（残量未拒）");

            // ★ buyer：BUDGET 部成 0.03 BNB
            assertBd("1.5", iocBudgetFuturesBuyerTrade.orderQty, "futures IOC_BUDGET buyer.orderQty (重载为 budget)");
            assertBd("0.03", iocBudgetFuturesBuyerTrade.lastQty, "futures IOC_BUDGET buyer.lastQty（部分成交）");
            assertBd("50", iocBudgetFuturesBuyerTrade.lastPx, "futures IOC_BUDGET buyer.lastPx");
            assertBd("1.5", iocBudgetFuturesBuyerTrade.cumQuoteQty, "futures IOC_BUDGET buyer.cumQuoteQty");
            assertBd("50", iocBudgetFuturesBuyerTrade.avgPx, "futures IOC_BUDGET buyer.avgPx");
            assertBd("0.0015", iocBudgetFuturesBuyerTrade.fee, "futures IOC_BUDGET buyer (taker) fee");

            // ★ seller maker 侧：lastQty 必须只有 0.03（不是挂单的 0.05），证明 maker order 部分成交后还在簿子上
            assertBd("0.03", iocBudgetFuturesSellerTrade.lastQty, "futures IOC_BUDGET seller (maker).lastQty");
            assertBd("50", iocBudgetFuturesSellerTrade.lastPx, "futures IOC_BUDGET seller (maker).lastPx");
            assertBd("0.00075", iocBudgetFuturesSellerTrade.fee, "futures IOC_BUDGET seller (maker) fee");

            // ★ REJECT 报告：BUDGET 单的 orderQty 在 PB 上是 cmd.price（=budget product scale），
            // 还原后是 1.5（USDT 总预算），不是请求的 0.05 BNB size。
            assertBd("1.5", iocBudgetFuturesReject.orderQty, "futures IOC_BUDGET reject.orderQty (= budget overload)");

            System.out.println();
            System.out.println("✓ Phase 1: FundEvent 余额还原 OK");
            System.out.println("✓ Phase 2: SpotExecution GTC 限价路径 OK");
            System.out.println("✓ Phase 3: FOK_BUDGET 全成 OK（quoteOrderQty product scale + 客户端编码 + 引擎闭环）");
            System.out.println("✓ Phase 4: IOC_BUDGET 部分成交 OK（新撮合路径 + 余额结算）");
            System.out.println("✓ Phase 5: 期货 FOK_BUDGET 全成 OK（pendingHoldBudget + futures orderQty 重载还原）");
            System.out.println("✓ Phase 6: 期货 IOC_BUDGET 部分成交 OK（margin 路径下 newOrderMatchIocBudget + REJECT 报表还原）");
        }
    }

    // ---- helpers ----

    private static void ok(CommandResultView v, String label) {
        System.out.println(label + " → " + v.getResultCode());
        assertEquals(CommandResultCode.SUCCESS, v.getResultCode(), label);
    }

    private static void assertBd(String expected, BigDecimal actual, String label) {
        assertEquals(0, actual.compareTo(new BigDecimal(expected)),
            () -> label + " expected=" + expected + " actual=" + actual);
    }
}
