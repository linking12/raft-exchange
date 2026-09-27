package exchange.core2.core.snapshot;

import exchange.core2.core.common.CoreCurrencySpecification;
import exchange.core2.core.common.api.ApiAddUser;
import exchange.core2.core.common.api.ApiAdjustUserBalance;
import exchange.core2.core.common.api.ApiPersistState;
import exchange.core2.core.common.api.binary.BatchAddSymbolsCommand;
import exchange.core2.core.common.cmd.CommandResultCode;
import exchange.core2.core.common.config.InitialStateConfiguration;
import exchange.core2.core.common.config.PerformanceConfiguration;
import exchange.core2.core.common.config.SerializationConfiguration;
import exchange.core2.core.processors.journaling.DiskSerializationProcessor;
import exchange.core2.core.processors.journaling.DiskSerializationProcessorConfiguration;
import exchange.core2.tests.util.ExchangeTestContainer;
import org.junit.jupiter.api.Test;

import java.util.Collections;

/**
 * 仅测试侧:用生产 persist 流程(单分片 + DiskSerializationProcessor)产出**真实 Java 快照** `.ecs` 文件,
 * 供 Rust Chronicle 读取器逐模块对拍。跑:mvn -pl exchange-core -Dtest=SnapshotProduce test。
 * 产物:/tmp/rust_snapshot_fixture/{exchangeId}_snapshot_{dumpId}_RE0.ecs / _ME0.ecs(LZ4 帧 + Chronicle Wire RAW)。
 * exchangeId/dumpId 打印在日志,并写 /tmp/rust_snapshot_fixture/meta.txt。
 */
public class SnapshotProduce {

    private static final String FOLDER = "/tmp/rust_snapshot_fixture";

    @Test
    public void produce() throws Exception {
        new java.io.File(FOLDER).mkdirs();

        // 单分片 + Direct 订单簿(与 Rust OrderBookDirectImpl 一致;生产用 Direct)。
        final PerformanceConfiguration perfCfg = PerformanceConfiguration.baseBuilder()
                .orderBookFactory(exchange.core2.core.orderbook.OrderBookDirectImpl::new)
                .build();
        final String exchangeId = "SNAPFIX0001";
        final InitialStateConfiguration cleanStart = InitialStateConfiguration.cleanStart(exchangeId);
        final SerializationConfiguration diskCfg = SerializationConfiguration.builder()
                .enableJournaling(false)
                .serializationProcessorFactory(exchangeCfg -> new DiskSerializationProcessor(exchangeCfg,
                        DiskSerializationProcessorConfiguration.builder()
                                .storageFolder(FOLDER)
                                .snapshotLz4CompressorFactory(() -> net.jpountz.lz4.LZ4Factory.fastestInstance().fastCompressor())
                                .journalFileMaxSize(4000L * 1024 * 1024)
                                .journalBufferSize(256 * 1024)
                                .journalBatchCompressThreshold(2048)
                                .journalLz4CompressorFactory(() -> net.jpountz.lz4.LZ4Factory.fastestInstance().fastCompressor())
                                .build()))
                .build();

        final long dumpId = 777L;
        try (final ExchangeTestContainer c =
                     ExchangeTestContainer.create(perfCfg, cleanStart, diskCfg, null)) {

            // 已知小状态:两个带 name 的 currency + 一个现货 symbol + 一个用户 + 余额。
            final CoreCurrencySpecification cur1 = CoreCurrencySpecification.builder()
                    .id(1).name("BTC").digit(0).collateralWeightBps(8000).build();
            final CoreCurrencySpecification cur2 = CoreCurrencySpecification.builder()
                    .id(2).name("USDT").digit(0).collateralWeightBps(0).build();
            c.addCurrency(cur1);
            c.addCurrency(cur2);

            // 一个现货 symbol(id=100, base=1 quote=2)。复用容器的批量加 symbol。
            c.addSymbols(Collections.singletonList(
                    exchange.core2.core.common.CoreSymbolSpecification.builder()
                            .symbolId(100)
                            .type(exchange.core2.core.common.SymbolType.CURRENCY_EXCHANGE_PAIR)
                            .baseCurrency(1).quoteCurrency(2)
                            .baseScaleK(1).quoteScaleK(1)
                            .takerFee(0).makerFee(0)
                            .build()));

            // 用户 + 余额。
            c.getApi().submitCommandAsync(ApiAddUser.builder().uid(42L).build()).get();
            c.getApi().submitCommandAsync(ApiAdjustUserBalance.builder()
                    .uid(42L).transactionId(1L).currency(2).amount(1_000_000L).build()).get();

            // persist → 落 .ecs。
            final CommandResultCode rc = c.getApi()
                    .submitCommandAsync(ApiPersistState.builder().dumpId(dumpId).build()).get();
            System.out.println("[SNAPFIX] persist rc=" + rc + " exchangeId=" + exchangeId + " dumpId=" + dumpId);

            java.nio.file.Files.write(java.nio.file.Paths.get(FOLDER, "meta.txt"),
                    ("exchangeId=" + exchangeId + "\ndumpId=" + dumpId + "\n").getBytes());
        }
        // 列出产物
        for (java.io.File f : new java.io.File(FOLDER).listFiles()) {
            System.out.println("[SNAPFIX] file: " + f.getName() + " size=" + f.length());
        }
    }
}
