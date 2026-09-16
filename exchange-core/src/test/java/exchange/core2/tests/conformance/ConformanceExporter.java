package exchange.core2.tests.conformance;

import exchange.core2.core.ExchangeApi;
import exchange.core2.core.IFundEventsHandler;
import exchange.core2.core.ITradeEventsHandler;
import exchange.core2.core.common.CoreCurrencySpecification;
import exchange.core2.core.common.CoreSymbolSpecification;
import exchange.core2.core.common.MarginMode;
import exchange.core2.core.common.OrderAction;
import exchange.core2.core.common.OrderType;
import exchange.core2.core.common.SymbolType;
import exchange.core2.core.common.api.ApiAddUser;
import exchange.core2.core.common.api.ApiAdjustMarkPrice;
import exchange.core2.core.common.api.ApiAdjustUserBalance;
import exchange.core2.core.common.api.ApiInsuranceFundDeposit;
import exchange.core2.core.common.api.ApiPlaceOrder;
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
            "LOAN_BORROW", "LOAN_REPAY", "LOAN_LIQUIDATED", "INTERNAL_TRANSFER");

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
        Path dir = Paths.get(System.getProperty("user.dir"), "..", "exchange-core-rs", "tests", "conformance_vectors")
                .normalize();
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
        StringBuilder out = new StringBuilder();
        List<Long> uids = new ArrayList<>();
        final List<String> feAccum = new ArrayList<>();

        IEventsHandler4Test handler = new IEventsHandler4Test() {
            @Override public void process(FundEventReport r) { fundEventReport(r); }
            @Override public void process(SpotExecutionReport r) {}
            @Override public void process(FuturesExecutionReport r) {}
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
                    case "SYM_SPOT":
                        c.addSymbol(CoreSymbolSpecification.builder()
                                .symbolId(pi(kv, "id")).type(SymbolType.CURRENCY_EXCHANGE_PAIR)
                                .baseCurrency(pi(kv, "base")).quoteCurrency(pi(kv, "quote"))
                                .baseScaleK(pl(kv, "baseScale")).quoteScaleK(pl(kv, "quoteScale"))
                                .takerFee(pl(kv, "taker")).makerFee(pl(kv, "maker")).build());
                        break;
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
                                .marginMode("CROSS".equals(kv.get("margin")) ? MarginMode.CROSS : MarginMode.ISOLATED).build()).join();
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
                    default:
                        throw new IllegalArgumentException("未支持 verb: " + verb);
                }
                if (rc != null) {
                    out.append("R ").append(seq).append(' ').append(rc.name()).append('\n');
                }
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
                    TreeMap<Integer, SingleUserReportResult.Position> byS = new TreeMap<>();
                    p.getPositions().forEachKeyValue((sym, plist) -> {
                        for (SingleUserReportResult.Position pos : plist) {
                            if (pos.openVolume != 0) byS.put(sym, pos);
                        }
                    });
                    byS.forEach((sym, pos) -> out.append("POS ").append(uid).append(' ').append(sym).append(' ')
                            .append(pos.direction.name()).append(' ').append(pos.openVolume).append(' ').append(pos.openPriceSum).append('\n'));
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
        }
        return out.toString();
    }
}
