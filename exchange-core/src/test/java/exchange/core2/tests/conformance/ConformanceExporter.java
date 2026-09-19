package exchange.core2.tests.conformance;

import exchange.core2.core.ExchangeApi;
import exchange.core2.core.IFundEventsHandler;
import exchange.core2.core.ITradeEventsHandler;
import exchange.core2.core.common.CoreCurrencySpecification;
import exchange.core2.core.common.CoreSymbolSpecification;
import exchange.core2.core.common.MarginMode;
import exchange.core2.core.common.OrderAction;
import exchange.core2.core.common.OrderType;
import exchange.core2.core.common.PositionMode;
import exchange.core2.core.common.SymbolLoanSpecification;
import exchange.core2.core.common.SymbolType;
import exchange.core2.core.common.api.ApiAddUser;
import exchange.core2.core.common.api.ApiAdjustMargin;
import exchange.core2.core.common.api.ApiAdjustMarkPrice;
import exchange.core2.core.common.api.ApiAdjustPositionMode;
import exchange.core2.core.common.api.ApiAdjustUserBalance;
import exchange.core2.core.common.api.ApiCancelOrder;
import exchange.core2.core.common.api.ApiInsuranceFundDeposit;
import exchange.core2.core.common.api.ApiInternalTransfer;
import exchange.core2.core.common.api.ApiMoveOrder;
import exchange.core2.core.common.api.ApiLoanCreate;
import exchange.core2.core.common.api.ApiLoanCrossAddCollateral;
import exchange.core2.core.common.api.ApiLoanCrossBorrow;
import exchange.core2.core.common.api.ApiLoanCrossRepay;
import exchange.core2.core.common.api.ApiLoanCrossWithdrawCollateral;
import exchange.core2.core.common.api.ApiLoanIfDeposit;
import exchange.core2.core.common.api.ApiLoanRepay;
import exchange.core2.core.common.api.ApiPlaceOrder;
import exchange.core2.core.common.api.ApiPoolDeposit;
import exchange.core2.core.common.api.ApiReduceOrder;
import exchange.core2.core.common.api.binary.BatchAddLoanCommand;
import exchange.core2.core.common.api.ApiSettleFundingFees;
import exchange.core2.core.common.api.ApiSettlePNL;
import exchange.core2.core.common.api.reports.SingleUserReportResult;
import exchange.core2.core.common.cmd.CommandResultCode;
import exchange.core2.core.common.config.PerformanceConfiguration;
import exchange.core2.core.event.IEventsHandler4Test;
import exchange.core2.core.event.SimpleEventsProcessor4Test;
import exchange.core2.tests.util.ExchangeTestContainer;
import org.eclipse.collections.impl.map.sorted.mutable.TreeSortedMap;
import org.junit.jupiter.api.Test;

import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Java↔Rust 一致性黄金向量**导出器**(Java 当 oracle)。读 exchange-core-rs/tests/conformance_vectors/*.stream,
 * 喂 exchange-core 实际引擎,写同名 *.golden(每命令 result_code + 状态摘要 + fund event 多重集)。
 * Rust 侧 tests/conformance.rs replay 同流断言 == golden。
 * v2:fund event 只导出**结算类白名单**(排除记账/锁类粒度差异 + alert);`#!events=off` 的向量只导 result+state。
 * 运行:mvn -q -Dtest=ConformanceExporter test
 */
public class ConformanceExporter {

    /** 结算类白名单(与 Rust fe_allowed 一致)。 */
    private static final Set<String> ALLOWED = Set.of(
            "LIQUIDATION_CLOSE", "LIQUIDATION_FEE",
            "FUNDINGFEE_SETTLEMENT", "PNL_SETTLEMENT", "MARGIN_ADJUST", "MARGIN_REFUND",
            "IF_POSITION_CLOSE", "ADL_ORIGIN_CLOSE", "ADL_POSITION_CLOSE",
            "LOAN_BORROW", "LOAN_REPAY", "LOAN_LIQUIDATED", "INTERNAL_TRANSFER",
            "MARGIN_ALERT", "LIQUIDATION_ALERT", "LOAN_MARGIN_CALL",
            "OPEN_POSITION", "CLOSE_POSITION", "LOCKED", "UNLOCKED",
            "LOCK_PENDING", "UNLOCK_PENDING", "DEPOSIT", "WITHDRAW");

    private static final TreeSortedMap<Long, Long> MM = TreeSortedMap.newMapWith(1000L, 5L, 100000L, 10L);
    private static final TreeSortedMap<Long, Long> LEV = TreeSortedMap.newMapWith(2000L, 5L, 100000L, 10L);

    private static int pi(Map<String, String> kv, String k) { return Integer.parseInt(kv.get(k)); }
    private static long pl(Map<String, String> kv, String k) { return Long.parseLong(kv.get(k)); }
    private static long pl(Map<String, String> kv, String k, long d) { return kv.containsKey(k) ? Long.parseLong(kv.get(k)) : d; }

    private static OrderType orderType(String s) {
        switch (s) {
            case "IOC": return OrderType.IOC;
            case "FOK": return OrderType.FOK;
            case "FOK_BUDGET": return OrderType.FOK_BUDGET;
            case "IOC_BUDGET": return OrderType.IOC_BUDGET;
            default: return OrderType.GTC;
        }
    }

    @Test
    public void exportGoldenVectors() throws Exception {
        // 默认入库向量目录;live-diff 编排(conformance_live_diff.sh)用 -Dconformance.vectors.dir=<临时目录> 覆盖跑新鲜随机流。
        String overrideDir = System.getProperty("conformance.vectors.dir");
        Path dir = overrideDir != null
                ? Paths.get(overrideDir).normalize()
                : Paths.get(System.getProperty("user.dir"), "..", "exchange-core-rs", "tests", "conformance_vectors").normalize();
        int exported = 0;
        try (DirectoryStream<Path> files = Files.newDirectoryStream(dir, "*.stream")) {
            for (Path stream : files) {
                List<String> lines = Files.readAllLines(stream);
                String golden = runVector(lines);
                Path gpath = dir.resolve(stream.getFileName().toString().replace(".stream", ".golden"));
                Files.writeString(gpath, golden);
                exported++;
                System.out.println("[conformance] exported: " + gpath);
            }
        }
        if (exported == 0) {
            throw new IllegalStateException("conformance_vectors 无 .stream: " + dir);
        }
    }

    private String runVector(List<String> lines) throws Exception {
        boolean eventsOn = lines.stream().noneMatch(l -> l.replaceFirst("^#+", "").trim().equals("!events=off"));
        boolean matchOn = lines.stream().anyMatch(l -> l.replaceFirst("^#+", "").trim().equals("!match=on"));
        StringBuilder out = new StringBuilder();
        List<Long> uids = new ArrayList<>();
        final List<String> matchAccum = new ArrayList<>();
        // 线程安全:同步/join 路径由主线程 append,但异步清算(FORCE→IF→ADL)的 fund event 由 disruptor
        // 结果线程(E 阶段)append、由主线程 SCAN 循环读——裸 ArrayList 跨线程会漏读/串读(曾致 LIQUIDATION_FEE
        // 被读成两条)。用 synchronizedList 保证可见性与原子性。
        final List<String> feAccum = Collections.synchronizedList(new ArrayList<>());

        IEventsHandler4Test handler = new IEventsHandler4Test() {
            @Override public void process(FundEventReport r) { fundEventReport(r); }
            @Override public void process(SpotExecutionReport r) {
                if (!matchOn) return;
                matchAccum.add("ER " + r.executionType.name() + ' ' + r.orderStatus.name()
                        + " uid=" + r.accountId + " oid=" + r.orderId + " side=" + r.side.name()
                        + " maker=" + (r.isMaker ? 1 : 0) + " px=" + r.price + " lastQty=" + r.lastQty
                        + " lastPx=" + r.lastPrice + " cumQty=" + r.cumulativeQty + " cumQ=" + r.cumulativeQuoteQty
                        + " comm=" + r.commission);
            }
            @Override public void process(FuturesExecutionReport r) {
                if (!matchOn) return;
                matchAccum.add("ERF " + r.executionType.name() + ' ' + r.orderStatus.name()
                        + " uid=" + r.userId + " oid=" + r.orderId + " side=" + r.side.name()
                        + " maker=" + (r.isMaker ? 1 : 0) + " pos=" + r.positionSide.name() + " cp=" + r.counterpartyId
                        + " px=" + r.price + " lastQty=" + r.lastQty + " lastPx=" + r.lastPx
                        + " cumQty=" + r.cumQty + " cumQ=" + r.cumQuoteQty + " avgPx=" + r.avgPx
                        + " fee=" + r.fee);
            }
            @Override public void orderBook(ITradeEventsHandler.OrderBook o) {}
            @Override public void spotExecutionReport(ITradeEventsHandler.SpotExecutionReport r) {}
            @Override public void futuresExecutionReport(ITradeEventsHandler.FuturesExecutionReport r) {}
            @Override public void fundEventReport(IFundEventsHandler.FundEventReport r) {
                String type = r.getEventType().name();
                if (ALLOWED.contains(type)) {
                    IFundEventsHandler.FundEventReport.BalanceSnapshot b = r.getBalances();
                    feAccum.add("FE " + type + " uid=" + r.getAccountId() + " cur=" + b.getCurrency()
                            + " free=" + b.getFree() + " locked=" + b.getLocked());
                }
            }
        };

        // 清算/ADL 事件级对拍需捕获确定:把后台定时扫描线程间隔拉到 24h,使其本次导出期间永不触发——唯一扫描来自
        // SCAN 循环的显式 triggerLiquidation,消除后台线程注入的非确定扫描。纯测试侧;LiquidationEngine 仅构造时读一次,
        // 须在 create() 前设;非清算向量不开清算引擎、不受影响。
        System.setProperty("raftexchange.liquidation.interval", "86400");
        try (ExchangeTestContainer c = ExchangeTestContainer.create(
                PerformanceConfiguration.DEFAULT, new SimpleEventsProcessor4Test(handler))) {
            ExchangeApi api = c.getApi();
            long seq = 0;
            for (String raw : lines) {
                String line = raw.trim();
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }
                String[] toks = line.split("\\s+");
                String verb = toks[0];
                Map<String, String> kv = new HashMap<>();
                for (int i = 1; i < toks.length; i++) {
                    String[] p = toks[i].split("=", 2);
                    if (p.length == 2) {
                        kv.put(p[0], p[1]);
                    }
                }
                CommandResultCode rc = null;
                switch (verb) {
                    case "CUR":
                        c.addCurrency(CoreCurrencySpecification.builder().id(pi(kv, "id")).digit(pi(kv, "digit")).build());
                        break;
                    case "SYM_SPOT": {
                        // 可选 loan 配置(5 字段与 Rust SymbolLoanSpecification 逐一对齐;未给字段两侧默认 0)。
                        SymbolLoanSpecification loanCfg = new SymbolLoanSpecification(
                                (int) pl(kv, "initialLtv", 0), (int) pl(kv, "liqLtv", 0),
                                (int) pl(kv, "marginCallLtv", 0), pl(kv, "maxAmount", 0), (int) pl(kv, "maxTermDays", 0));
                        c.addSymbol(CoreSymbolSpecification.builder()
                                .symbolId(pi(kv, "id")).type(SymbolType.CURRENCY_EXCHANGE_PAIR)
                                .baseCurrency(pi(kv, "base")).quoteCurrency(pi(kv, "quote"))
                                .baseScaleK(pl(kv, "baseScale")).quoteScaleK(pl(kv, "quoteScale"))
                                .takerFee(pl(kv, "taker")).makerFee(pl(kv, "maker"))
                                .loanConfig(loanCfg).build());
                        break;
                    }
                    case "SYM_FUT":
                        c.addSymbol(CoreSymbolSpecification.builder()
                                .symbolId(pi(kv, "id"))
                                .type("DELIVERY".equals(kv.get("kind")) ? SymbolType.FUTURES_CONTRACT_DELIVERY : SymbolType.FUTURES_CONTRACT_PERPETUAL)
                                .baseCurrency(pi(kv, "base")).quoteCurrency(pi(kv, "quote"))
                                .baseScaleK(pl(kv, "baseScale")).quoteScaleK(pl(kv, "quoteScale"))
                                .takerFee(pl(kv, "taker")).makerFee(pl(kv, "maker")).feeScaleK(pl(kv, "feeScale", 0))
                                .initMargin(pl(kv, "initMargin", 1)).initMarginScaleK(pl(kv, "initMarginScaleK", 100))
                                .maintenanceMargin(MM.clone()).maintenanceMarginScaleK(1000).maxLeverage(LEV.clone())
                                .build());
                        break;
                    case "MARK":
                    case "MARK_AT":
                        rc = api.submitCommandAsync(ApiAdjustMarkPrice.builder()
                                .transactionId(pl(kv, "ts", seq)).symbol(pi(kv, "sym")).markPrice(pl(kv, "price")).build()).join();
                        rc = null; // MARK 是 setup,不发 R 行(对齐 Rust)
                        break;
                    case "ENABLE_LIQ":
                        c.enableLiquidationEngines();
                        break;
                    case "USER": {
                        long uid = pl(kv, "uid");
                        rc = api.submitCommandAsync(ApiAddUser.builder().uid(uid).build()).join();
                        uids.add(uid);
                        break;
                    }
                    case "BAL":
                        rc = api.submitCommandAsync(ApiAdjustUserBalance.builder()
                                .uid(pl(kv, "uid")).currency(pi(kv, "cur")).amount(pl(kv, "amount")).transactionId(pl(kv, "txid")).build()).join();
                        break;
                    case "PLACE":
                        rc = api.submitCommandAsync(ApiPlaceOrder.builder()
                                .uid(pl(kv, "uid")).orderId(pl(kv, "oid")).symbol(pi(kv, "sym"))
                                .price(pl(kv, "price")).size(pl(kv, "size")).reservePrice(pl(kv, "reserve", 0))
                                .action("ASK".equals(kv.get("action")) ? OrderAction.ASK : OrderAction.BID)
                                .orderType(orderType(kv.getOrDefault("type", "GTC"))).marginMode(MarginMode.ISOLATED).build()).join();
                        break;
                    case "PLACE_FUT":
                        rc = api.submitCommandAsync(ApiPlaceOrder.builder()
                                .uid(pl(kv, "uid")).orderId(pl(kv, "oid")).symbol(pi(kv, "sym"))
                                .price(pl(kv, "price")).size(pl(kv, "size"))
                                .action("ASK".equals(kv.get("action")) ? OrderAction.ASK : OrderAction.BID)
                                .orderType(orderType(kv.getOrDefault("type", "GTC")))
                                .leverage((int) pl(kv, "leverage", 1))
                                .reduceOnly(pl(kv, "reduceOnly", 0) != 0)
                                .marginMode("CROSS".equals(kv.get("margin")) ? MarginMode.CROSS : MarginMode.ISOLATED).build()).join();
                        break;
                    case "CANCEL":
                        rc = api.submitCommandAsync(ApiCancelOrder.builder()
                                .uid(pl(kv, "uid")).orderId(pl(kv, "oid")).symbol(pi(kv, "sym")).build()).join();
                        break;
                    case "REDUCE":
                        rc = api.submitCommandAsync(ApiReduceOrder.builder()
                                .uid(pl(kv, "uid")).orderId(pl(kv, "oid")).symbol(pi(kv, "sym")).reduceSize(pl(kv, "size")).build()).join();
                        break;
                    case "MOVE":
                        rc = api.submitCommandAsync(ApiMoveOrder.builder()
                                .uid(pl(kv, "uid")).orderId(pl(kv, "oid")).symbol(pi(kv, "sym")).newPrice(pl(kv, "price")).build()).join();
                        break;
                    case "SCAN":
                        // 强平/ADL 走异步引擎、FORCE→IF→ADL 级联需多轮 scan(对齐 Java testADL 的
                        // waitForCondition 循环 triggerLiquidation)。循环 triggerLiquidation 直到状态**连续 6 轮稳定**或封顶。
                        {
                            String prev = null;
                            int stable = 0;
                            for (int iter = 0; iter < 120 && stable < 6; iter++) {
                                c.triggerLiquidation();
                                api.groupingControl(0, 1);
                                try { Thread.sleep(25L); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
                                StringBuilder snap = new StringBuilder();
                                for (long u : uids) {
                                    SingleUserReportResult pr = c.getUserProfile(u);
                                    snap.append(u).append(':');
                                    if (pr.getPositions() != null) {
                                        pr.getPositions().forEachKeyValue((sym, pl) -> {
                                            for (SingleUserReportResult.Position po : pl) snap.append(sym).append('=').append(po.openVolume).append(',');
                                        });
                                    }
                                }
                                // 稳定判据同时纳入已捕获事件数:级联最后一批命令的 fund event 可能晚于仓位定格才到达,
                                // 只看仓位会提前判稳、漏掉迟到事件。事件数不再变化才算真静默 → 捕获确定。
                                snap.append("|fe=").append(feAccum.size());
                                String cur = snap.toString();
                                stable = cur.equals(prev) ? stable + 1 : 0;
                                prev = cur;
                            }
                        }
                        break;
                    case "IF_DEPOSIT":
                        rc = api.submitCommandAsync(ApiInsuranceFundDeposit.builder()
                                .transactionId(pl(kv, "txid")).shardId(0).symbol(pi(kv, "sym")).currencyAmount(pl(kv, "amount")).build()).join();
                        rc = null; // 与 Rust 对齐:IF_DEPOSIT 结果不入 R(Rust 走 submit 但此处按 setup 处理)
                        break;
                    case "SETTLE_PNL":
                        rc = api.submitCommandAsync(ApiSettlePNL.builder()
                                .transactionId(pl(kv, "txid", seq)).symbol(pi(kv, "sym")).settlePrice(pl(kv, "price")).build()).join();
                        break;
                    case "SETTLE_FUNDING":
                        rc = api.submitCommandAsync(ApiSettleFundingFees.builder()
                                .transactionId(pl(kv, "txid", seq)).symbol(pi(kv, "sym"))
                                .action("ASK".equals(kv.get("action")) ? OrderAction.ASK : OrderAction.BID)
                                .fundingRate(pl(kv, "rate")).rateScaleK(pl(kv, "rateScaleK")).build()).join();
                        break;
                    case "MARGIN_ADJUST":
                        rc = api.submitCommandAsync(ApiAdjustMargin.builder()
                                .transactionId(pl(kv, "txid", seq)).uid(pl(kv, "uid")).symbol(pi(kv, "sym"))
                                .action("ASK".equals(kv.get("action")) ? OrderAction.ASK : OrderAction.BID)
                                .currency(pi(kv, "sym"))
                                .amount(pl(kv, "amount"))
                                .marginMode("CROSS".equals(kv.get("margin")) ? MarginMode.CROSS : MarginMode.ISOLATED).build()).join();
                        break;
                    case "POS_MODE":
                        rc = api.submitCommandAsync(ApiAdjustPositionMode.builder()
                                .uid(pl(kv, "uid"))
                                .positionMode(pl(kv, "hedge") != 0 ? PositionMode.HEDGE : PositionMode.ONEWAY).build()).join();
                        break;
                    case "POOL_DEPOSIT":
                        rc = api.submitCommandAsync(ApiPoolDeposit.builder()
                                .shardId(0).currency(pi(kv, "cur")).amount(pl(kv, "amount")).build()).join();
                        break;
                    case "LOAN_CREATE":
                        rc = api.submitCommandAsync(ApiLoanCreate.builder()
                                .transactionId(pl(kv, "txid", seq)).uid(pl(kv, "uid")).loanId(pl(kv, "loanId"))
                                .symbol(pi(kv, "sym")).collateralAmount(pl(kv, "collateral")).principal(pl(kv, "principal"))
                                .rateMode((byte) pl(kv, "rateMode", 0)).build()).join();
                        break;
                    case "LOAN_REPAY":
                        rc = api.submitCommandAsync(ApiLoanRepay.builder()
                                .transactionId(pl(kv, "txid", seq)).uid(pl(kv, "uid"))
                                .loanId(pl(kv, "loanId")).repayAmount(pl(kv, "repay")).build()).join();
                        break;
                    case "LOAN_GLOBAL":
                        // 全局 loan 运行时配置(numeraire/cross LTV/池上限/清算费/派生缓冲),走 binary 命令
                        // (对应 Rust api.add_loan 直接 facade);setup,不发 R。7 参顺序 == GlobalLoanConfig 字段顺序。
                        c.sendBinaryDataCommandSync(BatchAddLoanCommand.ofGlobal(
                                (int) pl(kv, "numeraire", 0), (int) pl(kv, "crossLiqLtv", 0),
                                (int) pl(kv, "crossMcLtv", 0), (int) pl(kv, "poolCap", 0),
                                (int) pl(kv, "liqFee", 0), (int) pl(kv, "liqBuf", 0),
                                (int) pl(kv, "mcBuf", 0)), 5000);
                        break;
                    case "LOAN_SYMBOL":
                        // per-symbol loan 配置(含 collateralWeight → 落到 base 币),对应 Rust LOAN_SYMBOL。setup,不发 R。
                        c.sendBinaryDataCommandSync(BatchAddLoanCommand.ofSymbol(
                                pi(kv, "sym"), (int) pl(kv, "initialLtv", 0), (int) pl(kv, "liqLtv", -1),
                                (int) pl(kv, "marginCallLtv", -1), pl(kv, "maxAmount", -1),
                                (int) pl(kv, "maxTermDays", -1), (int) pl(kv, "collateralWeight", -1)), 5000);
                        break;
                    case "LOAN_CROSS_ADD_COLLATERAL":
                        rc = api.submitCommandAsync(ApiLoanCrossAddCollateral.builder()
                                .transactionId(pl(kv, "txid", seq)).uid(pl(kv, "uid"))
                                .currency(pi(kv, "cur")).amount(pl(kv, "amount")).build()).join();
                        break;
                    case "LOAN_CROSS_WITHDRAW_COLLATERAL":
                        rc = api.submitCommandAsync(ApiLoanCrossWithdrawCollateral.builder()
                                .transactionId(pl(kv, "txid", seq)).uid(pl(kv, "uid"))
                                .currency(pi(kv, "cur")).amount(pl(kv, "amount")).build()).join();
                        break;
                    case "LOAN_CROSS_BORROW":
                        rc = api.submitCommandAsync(ApiLoanCrossBorrow.builder()
                                .transactionId(pl(kv, "txid", seq)).uid(pl(kv, "uid"))
                                .loanId(pl(kv, "loanId")).symbolId(pi(kv, "sym")).principal(pl(kv, "principal")).build()).join();
                        break;
                    case "LOAN_CROSS_REPAY":
                        rc = api.submitCommandAsync(ApiLoanCrossRepay.builder()
                                .transactionId(pl(kv, "txid", seq)).uid(pl(kv, "uid"))
                                .loanId(pl(kv, "loanId")).repayAmount(pl(kv, "repay")).build()).join();
                        break;
                    case "LIF_DEPOSIT":
                        rc = api.submitCommandAsync(ApiLoanIfDeposit.builder()
                                .shardId(0).currency(pi(kv, "cur")).amount(pl(kv, "amount")).build()).join();
                        rc = null; // 与 Rust 对齐:LIF_DEPOSIT 运维 setup,不入 R
                        break;
                    case "TRANSFER":
                        rc = api.submitCommandAsync(ApiInternalTransfer.builder()
                                .transactionId(pl(kv, "txid", seq)).fromUid(pl(kv, "from")).toUid(pl(kv, "to"))
                                .currency(pi(kv, "cur")).amount(pl(kv, "amount")).build()).join();
                        break;
                    default:
                        throw new IllegalArgumentException("未支持 verb: " + verb);
                }
                if (rc != null) {
                    out.append("R ").append(seq).append(' ').append(rc.name()).append('\n');
                }
                // 每条命令后 flush 管线:report query 走完整管线,强制把上一条的 R2(如未成交 IOC ASK 的锁释放)
                // 落地,再进下一条 R1。否则 Java 批处理 R1/R2 lag 会让下一条读到未 settle 的 exchangeLocked
                // (spurious RISK_NSF)——这是 Java 已知的批处理时序 hazard(reprice-r2-r1 同类),Rust 单管线无此问题。
                // conformance 要比的是两侧 settled 语义,故这里显式 settle。
                c.totalBalanceReport();
                seq++;
            }

            out.append("STATE\n");
            Collections.sort(uids);
            for (long uid : uids) {
                SingleUserReportResult p = c.getUserProfile(uid);
                if (p.getAccounts() != null) {
                    TreeMap<Integer, Long> accts = new TreeMap<>();
                    p.getAccounts().forEachKeyValue((k, v) -> { if (v != 0) accts.put(k, v); });
                    accts.forEach((k, v) -> out.append("A ").append(uid).append(' ').append(k).append(' ').append(v).append('\n'));
                }
                if (p.getPositions() != null) {
                    // HEDGE 同 symbol 可有 LONG/SHORT 两腿,不能按 sym 折叠成一条。收集全部非空腿,
                    // 按 (sym, direction, openVolume, openPriceSum) 排序——与 Rust state_digest 的
                    // (syms 升序 + legs.sort()) 口径逐字一致。
                    List<long[]> legs = new ArrayList<>();
                    List<String> dirs = new ArrayList<>();
                    p.getPositions().forEachKeyValue((sym, plist) -> {
                        for (SingleUserReportResult.Position pos : plist) {
                            if (pos.openVolume != 0) {
                                dirs.add(pos.direction.name());
                                legs.add(new long[]{sym, dirs.size() - 1, pos.openVolume, pos.openPriceSum, pos.openInitMarginSum, pos.extraMargin});
                            }
                        }
                    });
                    legs.sort((a, b) -> {
                        if (a[0] != b[0]) return Long.compare(a[0], b[0]);
                        int d = dirs.get((int) a[1]).compareTo(dirs.get((int) b[1]));
                        if (d != 0) return d;
                        if (a[2] != b[2]) return Long.compare(a[2], b[2]);
                        return Long.compare(a[3], b[3]);
                    });
                    for (long[] leg : legs) {
                        // 含初始保证金(受杠杆决定)+ 追加保证金,让 leverage/margin 在状态里可观测。
                        out.append("POS ").append(uid).append(' ').append(leg[0]).append(' ')
                                .append(dirs.get((int) leg[1])).append(' ').append(leg[2]).append(' ').append(leg[3])
                                .append(' ').append(leg[4]).append(' ').append(leg[5]).append('\n');
                    }
                }
            }
            TreeMap<Integer, Long> fees = new TreeMap<>();
            c.totalBalanceReport().getFees().forEachKeyValue((k, v) -> { if (v != 0) fees.put(k, v); });
            fees.forEach((k, v) -> out.append("FEE ").append(k).append(' ').append(v).append('\n'));

            if (eventsOn) {
                out.append("EVENTS\n");
                Collections.sort(feAccum);
                for (String fe : feAccum) {
                    out.append(fe).append('\n');
                }
            }
            if (matchOn) {
                out.append("MATCH\n");
                for (String m : matchAccum) {
                    out.append(m).append('\n');
                }
            }
        }
        return out.toString();
    }
}
