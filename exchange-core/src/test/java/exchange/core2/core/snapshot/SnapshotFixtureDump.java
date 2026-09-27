package exchange.core2.core.snapshot;

import java.nio.file.Files;
import java.nio.file.Paths;

import net.openhft.chronicle.bytes.Bytes;
import org.junit.jupiter.api.Test;

import exchange.core2.core.common.CoreCurrencySpecification;
import exchange.core2.core.processors.LastPriceCacheRecord;

/**
 * 仅测试侧:dump 已知值对象的 Chronicle 序列化字节(hex),供 Rust Chronicle 读取器反推精确编码。
 * 跑:mvn -pl exchange-core -Dtest=SnapshotFixtureDump test；产物 /tmp/cw_fixtures/*.hex。
 */
public class SnapshotFixtureDump {

    private static String hex(Bytes<?> b) {
        StringBuilder sb = new StringBuilder();
        long lim = b.readLimit();
        for (long i = b.readPosition(); i < lim; i++) {
            sb.append(String.format("%02x", b.readByte(i) & 0xff));
        }
        return sb.toString();
    }

    @Test
    public void dumpFixtures() throws Exception {
        Paths.get("/tmp/cw_fixtures").toFile().mkdirs();

        // 1) 四个已知 long:askPrice=1,bidPrice=2,markPrice=3,markPriceTs=4
        Bytes<?> b1 = Bytes.allocateElasticOnHeap();
        LastPriceCacheRecord r = new LastPriceCacheRecord(1L, 2L, 3L);
        r.markPriceTs = 4L;
        r.writeMarshallable(b1);
        Files.write(Paths.get("/tmp/cw_fixtures/lpcr.hex"), hex(b1).getBytes());

        // 2) int + utf8 + int + int:id=7,name="BTC",digit=8,collateralWeightBps=9000
        Bytes<?> b2 = Bytes.allocateElasticOnHeap();
        CoreCurrencySpecification cur = CoreCurrencySpecification.builder()
                .id(7).name("BTC").digit(8).collateralWeightBps(9000).build();
        cur.writeMarshallable(b2);
        Files.write(Paths.get("/tmp/cw_fixtures/currency.hex"), hex(b2).getBytes());

        // 3) 单纯 int / long / utf8 三个原语,分别验证
        Bytes<?> b3 = Bytes.allocateElasticOnHeap();
        b3.writeInt(0x01020304);
        Files.write(Paths.get("/tmp/cw_fixtures/int.hex"), hex(b3).getBytes());

        Bytes<?> b4 = Bytes.allocateElasticOnHeap();
        b4.writeLong(0x0102030405060708L);
        Files.write(Paths.get("/tmp/cw_fixtures/long.hex"), hex(b4).getBytes());

        Bytes<?> b5 = Bytes.allocateElasticOnHeap();
        b5.writeUtf8("BTC");
        Files.write(Paths.get("/tmp/cw_fixtures/utf8.hex"), hex(b5).getBytes());

        // 6) 帧:WireType.RAW 的 wire.writeBytes(obj)——复刻 storeData 的外层文档帧。
        //    inner = LastPriceCacheRecord(1,2,3,4) 已知 32 字节;看外层加了什么头。
        net.openhft.chronicle.wire.Wire wire =
                net.openhft.chronicle.wire.WireType.RAW.apply(Bytes.allocateElasticOnHeap());
        LastPriceCacheRecord r2 = new LastPriceCacheRecord(1L, 2L, 3L);
        r2.markPriceTs = 4L;
        wire.writeBytes(r2);
        Files.write(Paths.get("/tmp/cw_fixtures/framed_lpcr.hex"), hex(wire.bytes()).getBytes());

        // 7) stop-bit 大值:长度 200(>127,验证多字节 stop-bit)
        Bytes<?> b7 = Bytes.allocateElasticOnHeap();
        StringBuilder big = new StringBuilder();
        for (int i = 0; i < 200; i++) big.append('x');
        b7.writeUtf8(big.toString());
        Files.write(Paths.get("/tmp/cw_fixtures/utf8_big.hex"), hex(b7).substring(0, 8).getBytes());
    }
}
