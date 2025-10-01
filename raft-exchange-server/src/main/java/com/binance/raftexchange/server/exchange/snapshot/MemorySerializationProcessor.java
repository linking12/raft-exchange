package com.binance.raftexchange.server.exchange.snapshot;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.NavigableMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import com.binance.raftexchange.server.raft.SnapshotHelper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import exchange.core2.core.ExchangeApi;
import exchange.core2.core.common.cmd.OrderCommand;
import exchange.core2.core.common.config.ExchangeConfiguration;
import exchange.core2.core.common.config.InitialStateConfiguration;
import exchange.core2.core.processors.journaling.ISerializationProcessor;
import exchange.core2.core.processors.journaling.SnapshotDescriptor;
import net.jpountz.lz4.LZ4Compressor;
import net.jpountz.lz4.LZ4Factory;
import net.jpountz.lz4.LZ4FrameInputStream;
import net.jpountz.lz4.LZ4FrameOutputStream;
import net.jpountz.xxhash.XXHashFactory;
import net.openhft.chronicle.bytes.Bytes;
import net.openhft.chronicle.bytes.BytesIn;
import net.openhft.chronicle.bytes.WriteBytesMarshallable;
import net.openhft.chronicle.wire.InputStreamToWire;
import net.openhft.chronicle.wire.Wire;
import net.openhft.chronicle.wire.WireType;

public class MemorySerializationProcessor implements ISerializationProcessor {
    private static final Logger LOG = LoggerFactory.getLogger(MemorySerializationProcessor.class);

    private final boolean enableCompression;
    private LZ4Compressor lz4Compressor;

    public MemorySerializationProcessor(ExchangeConfiguration configuration) {
        enableCompression = Boolean.parseBoolean(System.getProperty("raft-exchange.snapshot.compression", "false"));
        if (enableCompression) {
            lz4Compressor = LZ4Factory.fastestInstance().fastCompressor();
        }
    }

    @Override
    public boolean storeData(long snapshotId, long seq, long timestampNs, SerializedModuleType type, int instanceId, WriteBytesMarshallable obj) {
        LOG.debug("Writing state into memory stream... (Compression: {})", enableCompression);
        try (OutputStream bos = new BufferedOutputStream(StreamManager.build(snapshotId, type, instanceId));
            OutputStream os = enableCompression ? new LZ4FrameOutputStream(bos, LZ4FrameOutputStream.BLOCKSIZE.SIZE_4MB, -1, lz4Compressor,
                XXHashFactory.fastestInstance().hash32(), LZ4FrameOutputStream.FLG.Bits.BLOCK_INDEPENDENCE) : bos;
            WireToOutputStream wireToOutputStream = new WireToOutputStream(WireType.RAW, os)) {
            Wire wire = wireToOutputStream.getWire();
            wire.writeBytes(obj);
            wireToOutputStream.flush();
            os.flush();
            LOG.debug("Completed writing to memory stream.");
        } catch (IOException ex) {
            LOG.error("Failed to write to memory stream: ", ex);
            return false;
        }
        return true;
    }

    @Override
    public <T> T loadData(long snapshotId, SerializedModuleType type, int instanceId, Function<BytesIn, T> initFunc) {
        Path path = resolveSnapshotPath(snapshotId, type, instanceId);
        LOG.debug("Loading state from {}", path);
        try (final InputStream is = Files.newInputStream(path, StandardOpenOption.READ); final InputStream bis = new BufferedInputStream(is);
            final InputStream in = autoDetectInputStream(bis)) {
            final InputStreamToWire inputStreamToWire = new InputStreamToWire(WireType.RAW, in);
            final Wire wire = inputStreamToWire.readOne();
            LOG.debug("start de-serializing...");
            AtomicReference<T> ref = new AtomicReference<>();
            wire.readBytes(bytes -> ref.set(initFunc.apply(bytes)));
            return ref.get();
        } catch (final IOException ex) {
            LOG.error("Can not read snapshot file: ", ex);
            throw new IllegalStateException(ex);
        }
    }

    @Override
    public void writeToJournal(OrderCommand cmd, long dSeq, boolean eob) throws IOException {
        throw new UnsupportedOperationException();
    }

    @Override
    public void enableJournaling(long afterSeq, ExchangeApi api) {
        throw new UnsupportedOperationException();
    }

    @Override
    public NavigableMap<Long, SnapshotDescriptor> findAllSnapshotPoints() {
        throw new UnsupportedOperationException();
    }

    @Override
    public void replayJournalStep(long snapshotId, long seqFrom, long seqTo, ExchangeApi api) {
        throw new UnsupportedOperationException();
    }

    @Override
    public long replayJournalFull(InitialStateConfiguration initialStateConfiguration, ExchangeApi api) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void replayJournalFullAndThenEnableJouraling(InitialStateConfiguration initialStateConfiguration, ExchangeApi exchangeApi) {}

    @Override
    public Path resolveSnapshotPath(long snapshotId, SerializedModuleType type, int instanceId) {
        String root = SnapshotHelper.getSnapshotPath();
        String fileName = SnapshotHelper.genSnapshotFileName(snapshotId, type, instanceId);
        return Paths.get(root, fileName);
    }

    private static final int LZ4_MAGIC_INT = 0x04224D18; // LZ4文件头标识

    /**
     * 自动判断读入的是否是lz4压缩的流
     * 
     * @param inputStream
     * @return
     */
    private static InputStream autoDetectInputStream(InputStream inputStream) {
        try {
            // 读取前4字节，判断是否是LZ4
            inputStream.mark(4);
            byte[] header = new byte[4];
            int bytesRead = inputStream.read(header);
            inputStream.reset(); // 重置流，回到起始位置
            if (bytesRead == 4 && ByteBuffer.wrap(header).getInt() == LZ4_MAGIC_INT) {
                return new LZ4FrameInputStream(inputStream);
            }
        } catch (IOException e) {
            LOG.error("Failed to read stream from file", e);
        }
        return inputStream;
    }

    private static class WireToOutputStream implements AutoCloseable {
        private final Bytes<ByteBuffer> bytes = Bytes.elasticByteBuffer(128 * 1024 * 1024);
        private final Wire wire;
        private final DataOutputStream dos;

        public WireToOutputStream(WireType wireType, OutputStream os) {
            wire = wireType.apply(bytes);
            dos = new DataOutputStream(os);
        }

        public Wire getWire() {
            wire.clear();
            return wire;
        }

        public void flush() throws IOException {
            int length = Math.toIntExact(bytes.readRemaining());
            dos.writeInt(length);

            final byte[] buf = new byte[1024 * 1024];

            while (bytes.readPosition() < bytes.readLimit()) {
                int read = bytes.read(buf);
                dos.write(buf, 0, read);
            }
        }

        @Override
        public void close() {
            bytes.releaseLast();
        }
    }
}
