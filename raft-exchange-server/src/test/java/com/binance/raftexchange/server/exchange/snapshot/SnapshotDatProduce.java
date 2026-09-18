package com.binance.raftexchange.server.exchange.snapshot;

import java.io.BufferedOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PipedInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.eclipse.collections.impl.map.sorted.mutable.TreeSortedMap;
import org.junit.jupiter.api.Test;

import com.binance.raftexchange.server.raft.SnapshotHelper;

import exchange.core2.core.ExchangeApi;
import exchange.core2.core.ExchangeCore;
import exchange.core2.core.common.CoreCurrencySpecification;
import exchange.core2.core.common.CoreSymbolSpecification;
import exchange.core2.core.common.MarginMode;
import exchange.core2.core.common.OrderAction;
import exchange.core2.core.common.OrderType;
import exchange.core2.core.common.SymbolType;
import exchange.core2.core.common.api.ApiAddUser;
import exchange.core2.core.common.api.ApiAdjustMarkPrice;
import exchange.core2.core.common.api.ApiCommand;
import exchange.core2.core.common.api.ApiAdjustUserBalance;
import exchange.core2.core.common.api.ApiLoanCreate;
import exchange.core2.core.common.api.ApiLoanCrossAddCollateral;
import exchange.core2.core.common.api.ApiLoanCrossBorrow;
import exchange.core2.core.common.api.ApiPersistState;
import exchange.core2.core.common.api.ApiPlaceOrder;
import exchange.core2.core.common.api.ApiPoolDeposit;
import exchange.core2.core.common.api.binary.BatchAddCurrenciesCommand;
import exchange.core2.core.common.api.binary.BatchAddLoanCommand;
import exchange.core2.core.common.api.binary.BatchAddSymbolsCommand;
import exchange.core2.core.common.api.binary.BinaryDataCommand;
import exchange.core2.core.common.cmd.CommandResultCode;
import exchange.core2.core.common.config.ExchangeConfiguration;
import exchange.core2.core.common.config.InitialStateConfiguration;
import exchange.core2.core.common.config.OrdersProcessingConfiguration;
import exchange.core2.core.common.config.OrdersProcessingConfiguration.MarginTradingMode;
import exchange.core2.core.common.config.OrdersProcessingConfiguration.RiskProcessingMode;
import exchange.core2.core.common.config.PerformanceConfiguration;
import exchange.core2.core.common.config.SerializationConfiguration;
import exchange.core2.core.orderbook.OrderBookDirectImpl;
import exchange.core2.core.processors.journaling.ISerializationProcessor.SerializedModuleType;

/**
 * 仅测试侧:用生产 snapshot 落盘链路(server {@link MemorySerializationProcessor} + {@link StreamManager} 管道,
 * 不压缩,与真实 raft 集群节点写盘字节完全同框)产出**真实富状态 Java 快照 {@code .dat}**,供 Rust Chronicle
 * 读取器单分片加载验证正确性(route B)。跑:{@code mvn -pl raft-exchange-server -Dtest=SnapshotDatProduce test}。
 *
 * <p>与 exchange-core 的 {@code SnapshotProduce}(DiskSerializationProcessor,LZ4 {@code .ecs},极小状态)互补:
 * 这里走 server 的 {@code MemorySerializationProcessor}(不压缩 {@code .dat}=BE 分块长度 + Chronicle Wire RAW),
 * 且状态更富:4 币种(带 name)+ 2 现货 symbol + 1 永续期货 symbol + 4 用户 + 余额/锁定 + 挂单 + 期货持仓。
 * 单分片(baseBuilder=1 ME + 1 RE,instanceId=0),对齐"只加载某一分片验证正确性"。
 *
 * <p>产物:{@code /tmp/rust_snapshot_dat_fixture/snapshot_{dumpId}_{RE|ME}_0.dat} + meta.txt。
 */
public class SnapshotDatProduce {

    private static final String FOLDER = "/tmp/rust_snapshot_dat_fixture";
    private static final long DUMP_ID = 88888L;

    // 币种
    private static final int USD = 1;
    private static final int USDT = 2;
    private static final int BTC = 3;
    private static final int ETH = 4;
    // 现货 symbol
    private static final int SYM_BTC_USDT = 100;
    private static final int SYM_ETH_USDT = 101;
    // 永续期货 symbol(USD 计保证金)
    private static final int SYM_BTC_PERP = 200;

    @Test
    public void produce() throws Exception {
        new java.io.File(FOLDER).mkdirs();
        // 落盘根目录 = SnapshotHelper.getSnapshotPath();MemorySerializationProcessor / drain 都用它。
        SnapshotHelper.setSnapshotPath(FOLDER);
        // 生产默认不压缩;显式清零,让 MemorySerializationProcessor 构造期决策走 raw 路径。
        System.clearProperty("raftexchange.snapshot.compression");

        // 单分片 + Direct 订单簿(与 Rust OrderBookDirectImpl 一致;生产用 Direct)。
        final PerformanceConfiguration perfCfg = PerformanceConfiguration.baseBuilder()
            .orderBookFactory(OrderBookDirectImpl::new)
            .build();
        // server 生产序列化器 = MemorySerializationProcessor(不压缩 .dat)。
        final SerializationConfiguration serCfg = SerializationConfiguration.builder()
            .enableJournaling(false)
            .serializationProcessorFactory(MemorySerializationProcessor::new)
            .build();
        // 期货需显式开启 margin trading(默认关,且 DEFAULT 类加载期定型)。
        final OrdersProcessingConfiguration ordersCfg = OrdersProcessingConfiguration.builder()
            .riskProcessingMode(RiskProcessingMode.FULL_PER_CURRENCY)
            .marginTradingMode(MarginTradingMode.MARGIN_TRADING_ENABLED)
            .build();

        final ExchangeConfiguration exCfg = ExchangeConfiguration.defaultBuilder()
            .performanceCfg(perfCfg)
            .initStateCfg(InitialStateConfiguration.cleanStart("SNAPDAT0001"))
            .serializationCfg(serCfg)
            .ordersProcessingCfg(ordersCfg)
            .build();

        final ExchangeCore core = ExchangeCore.builder()
            .resultsConsumer((cmd, seq) -> {})
            .exchangeConfiguration(exCfg).build();
        core.startup();
        final ExchangeApi api = core.getApi();

        try {
            // ===== 币种(带 name,进 stateHash) =====
            api.submitBinaryDataAsync(new BatchAddCurrenciesCommand(Arrays.asList(
                CoreCurrencySpecification.builder().id(USD).name("USD").digit(0).collateralWeightBps(0).build(),
                CoreCurrencySpecification.builder().id(USDT).name("USDT").digit(0).collateralWeightBps(0).build(),
                CoreCurrencySpecification.builder().id(BTC).name("BTC").digit(0).collateralWeightBps(8000).build(),
                CoreCurrencySpecification.builder().id(ETH).name("ETH").digit(0).collateralWeightBps(6000).build())))
                .get();

            // ===== 现货 symbol(2 个,空 margin 表) =====
            api.submitBinaryDataAsync(new BatchAddSymbolsCommand(Arrays.asList(
                CoreSymbolSpecification.builder().symbolId(SYM_BTC_USDT).type(SymbolType.CURRENCY_EXCHANGE_PAIR)
                    .baseCurrency(BTC).quoteCurrency(USDT).baseScaleK(1).quoteScaleK(1).takerFee(0).makerFee(0).build(),
                CoreSymbolSpecification.builder().symbolId(SYM_ETH_USDT).type(SymbolType.CURRENCY_EXCHANGE_PAIR)
                    .baseCurrency(ETH).quoteCurrency(USDT).baseScaleK(1).quoteScaleK(1).takerFee(0).makerFee(0).build())))
                .get();

            // ===== 永续期货 symbol(USD 计保证金,含 maintenance/leverage stop-bit treemap) =====
            api.submitBinaryDataAsync(new BatchAddSymbolsCommand(
                CoreSymbolSpecification.builder().symbolId(SYM_BTC_PERP).type(SymbolType.FUTURES_CONTRACT_PERPETUAL)
                    .baseCurrency(BTC).quoteCurrency(USD).baseScaleK(1).quoteScaleK(1).takerFee(0).makerFee(0)
                    .maintenanceMargin(TreeSortedMap.newMapWith(1000L, 5L, 100000L, 10L)).maintenanceMarginScaleK(1000)
                    .maxLeverage(TreeSortedMap.newMapWith(2000L, 5L, 100000L, 10L)).initMargin(1).initMarginScaleK(100)
                    .build()))
                .get();

            api.submitCommandAsync(ApiAdjustMarkPrice.builder().transactionId(1L).symbol(SYM_BTC_PERP).markPrice(1000L)
                .build()).get();

            // ===== 用户 + 余额(10-13 现货/期货;20/22 loan) =====
            for (long uid : new long[] {10L, 11L, 12L, 13L, 20L, 22L}) {
                api.submitCommandAsync(ApiAddUser.builder().uid(uid).build()).get();
            }
            deposit(api, 10L, USDT, 1_000_000L, 101);
            deposit(api, 10L, USD, 100_000L, 102);
            deposit(api, 11L, USDT, 1_000_000L, 111);
            deposit(api, 11L, BTC, 100L, 112);
            deposit(api, 12L, ETH, 100L, 121);
            deposit(api, 13L, USD, 100_000L, 131);
            deposit(api, 20L, BTC, 1000L, 201); // isolated 借款人抵押
            deposit(api, 22L, BTC, 300L, 221);  // cross 借款人抵押

            // ===== 现货成交 + 挂单(ME 订单簿非空 + RE 余额/锁定变动) =====
            // sym100 BTC/USDT:u11 卖 → u10 买(成交 10@50000)。
            place(api, 1001L, 11L, SYM_BTC_USDT, 50000L, 10, OrderAction.ASK, OrderType.GTC);
            placeBid(api, 1002L, 10L, SYM_BTC_USDT, 50000L, 10);
            // u11 再挂一个更高的卖单(不成交,留在簿上 → 锁定 5 BTC)。
            place(api, 1003L, 11L, SYM_BTC_USDT, 60000L, 5, OrderAction.ASK, OrderType.GTC);
            // sym101 ETH/USDT:u12 挂卖 20@3000(无对手,留簿 → 锁定 20 ETH)。
            place(api, 1004L, 12L, SYM_ETH_USDT, 3000L, 20, OrderAction.ASK, OrderType.GTC);

            // ===== 期货持仓(sym200,mark=1000):u10 LONG / u13 SHORT 各 3@1000 ISOLATED lev1 =====
            placeFut(api, 2001L, 13L, SYM_BTC_PERP, 1000L, 3, OrderAction.ASK);
            placeFutBid(api, 2002L, 10L, SYM_BTC_PERP, 1000L, 3);

            // ===== loan 子系统(RiskEngine.loanService → 进 RE_0.dat) =====
            // 全局:numeraire=USDT(2) + cross LTV 阈值。
            reqBinary(api, BatchAddLoanCommand.ofGlobal(USDT, 8500, 7500, 0, 0, 0, 0));
            // per-symbol(sym100 BTC/USDT):抵押币=base(BTC),借出币=quote(USDT),collateralWeight→BTC。
            reqBinary(api, BatchAddLoanCommand.ofSymbol(SYM_BTC_USDT, 6000, 8000, 7500, 0L, 365, 10000));
            // loan 抵押估值用 mark price(非成交价),须显式就绪。
            reqCmd(api, ApiAdjustMarkPrice.builder().transactionId(900L).symbol(SYM_BTC_USDT).markPrice(50000L).build());
            // USDT 借贷池注资(shard 0),供本金放出。
            reqCmd(api, ApiPoolDeposit.builder().shardId(0).currency(USDT).amount(1_000_000L).build());
            // isolated 借款:u20 抵押 1000 BTC 借 1 USDT(极度超额抵押,LTV 稳过);留 outstanding。
            reqCmd(api, ApiLoanCreate.builder().transactionId(902L).uid(20L).loanId(9001L).symbol(SYM_BTC_USDT)
                .collateralAmount(1000L).principal(1L).rateMode((byte) 0).build());
            // cross 借款:u22 注 300 BTC 抵押 → 借 1 USDT;留 outstanding。
            reqCmd(api, ApiLoanCrossAddCollateral.builder().transactionId(903L).uid(22L).currency(BTC).amount(300L)
                .build());
            reqCmd(api, ApiLoanCrossBorrow.builder().transactionId(904L).uid(22L).loanId(9002L).symbolId(SYM_BTC_USDT)
                .principal(1L).build());

            // ===== persist → 落 RE_0.dat / ME_0.dat(drain 先起,消费管道写盘) =====
            CompletableFuture<Void> drainRe = startDrain(DUMP_ID, SerializedModuleType.RISK_ENGINE, 0);
            CompletableFuture<Void> drainMe = startDrain(DUMP_ID, SerializedModuleType.MATCHING_ENGINE_ROUTER, 0);

            final CommandResultCode rc =
                api.submitCommandAsync(ApiPersistState.builder().dumpId(DUMP_ID).build()).get();
            System.out.println("[SNAPDAT] persist rc=" + rc + " dumpId=" + DUMP_ID);

            drainRe.get(30, TimeUnit.SECONDS);
            drainMe.get(30, TimeUnit.SECONDS);

            Files.write(Paths.get(FOLDER, "meta.txt"),
                ("dumpId=" + DUMP_ID + "\nRE=" + fileName(SerializedModuleType.RISK_ENGINE, 0) + "\nME="
                    + fileName(SerializedModuleType.MATCHING_ENGINE_ROUTER, 0) + "\n").getBytes());
        } finally {
            core.shutdown();
        }

        for (java.io.File f : new java.io.File(FOLDER).listFiles()) {
            System.out.println("[SNAPDAT] file: " + f.getName() + " size=" + f.length());
        }
    }

    // ---- helpers ----

    private static void deposit(ExchangeApi api, long uid, int cur, long amount, long txId) throws Exception {
        CommandResultCode rc = api.submitCommandAsync(ApiAdjustUserBalance.builder().uid(uid).currency(cur)
            .amount(amount).transactionId(txId).build()).get();
        if (rc != CommandResultCode.SUCCESS)
            throw new IllegalStateException("deposit failed uid=" + uid + " cur=" + cur + " rc=" + rc);
    }

    private static void reqCmd(ExchangeApi api, ApiCommand cmd) throws Exception {
        CommandResultCode rc = api.submitCommandAsync(cmd).get();
        if (rc != CommandResultCode.SUCCESS)
            throw new IllegalStateException("command failed rc=" + rc + " cmd=" + cmd);
    }

    private static void reqBinary(ExchangeApi api, BinaryDataCommand cmd) throws Exception {
        CommandResultCode rc = api.submitBinaryDataAsync(cmd).get();
        if (rc != CommandResultCode.SUCCESS)
            throw new IllegalStateException("binary command failed rc=" + rc + " cmd=" + cmd);
    }

    private static void place(ExchangeApi api, long orderId, long uid, int sym, long price, int size,
        OrderAction action, OrderType type) throws Exception {
        CommandResultCode rc = api.submitCommandAsync(ApiPlaceOrder.builder().uid(uid).orderId(orderId).symbol(sym)
            .price(price).reservePrice(price).size(size).action(action).orderType(type)
            .marginMode(MarginMode.ISOLATED).build()).get();
        if (rc != CommandResultCode.SUCCESS)
            throw new IllegalStateException("place failed order=" + orderId + " rc=" + rc);
    }

    private static void placeBid(ExchangeApi api, long orderId, long uid, int sym, long price, int size)
        throws Exception {
        place(api, orderId, uid, sym, price, size, OrderAction.BID, OrderType.GTC);
    }

    private static void placeFut(ExchangeApi api, long orderId, long uid, int sym, long price, int size,
        OrderAction action) throws Exception {
        CommandResultCode rc = api.submitCommandAsync(ApiPlaceOrder.builder().uid(uid).orderId(orderId).symbol(sym)
            .price(price).reservePrice(price).size(size).action(action).orderType(OrderType.GTC).leverage(1)
            .marginMode(MarginMode.ISOLATED).build()).get();
        if (rc != CommandResultCode.SUCCESS)
            throw new IllegalStateException("placeFut failed order=" + orderId + " rc=" + rc);
    }

    private static void placeFutBid(ExchangeApi api, long orderId, long uid, int sym, long price, int size)
        throws Exception {
        placeFut(api, orderId, uid, sym, price, size, OrderAction.BID);
    }

    private static String fileName(SerializedModuleType type, int instanceId) {
        return SnapshotHelper.genSnapshotFileName(DUMP_ID, type, instanceId);
    }

    private static Path snapshotFile(SerializedModuleType type, int instanceId) {
        return Paths.get(FOLDER, SnapshotHelper.genSnapshotFileName(DUMP_ID, type, instanceId));
    }

    /** 模拟 saveSnapshot:把 StreamManager 给的 PipedInputStream 写到 .dat 磁盘文件。 */
    private static CompletableFuture<Void> startDrain(long snapshotId, SerializedModuleType type, int instanceId) {
        return CompletableFuture.runAsync(() -> {
            try {
                PipedInputStream pis = StreamManager.get(snapshotId, type, instanceId);
                try (OutputStream fos = Files.newOutputStream(snapshotFile(type, instanceId),
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
                    OutputStream bos = new BufferedOutputStream(fos)) {
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = pis.read(buf)) != -1)
                        bos.write(buf, 0, n);
                    bos.flush();
                }
                StreamManager.close(snapshotId, type, instanceId);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
    }
}
