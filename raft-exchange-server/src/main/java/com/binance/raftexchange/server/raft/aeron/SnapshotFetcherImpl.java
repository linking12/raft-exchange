package com.binance.raftexchange.server.raft.aeron;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.binance.raftexchange.server.metrics.RaftExchangeMetrics;
import com.binance.raftexchange.server.raft.RaftClusterDiscovery;
import com.binance.raftexchange.stubs.snapshot.SnapshotChunk;
import com.binance.raftexchange.stubs.snapshot.SnapshotFetchRequest;
import com.binance.raftexchange.stubs.snapshot.SnapshotTransferServiceGrpc;

import io.grpc.ManagedChannel;
import io.grpc.netty.NettyChannelBuilder;

/**
 * gRPC 拉取 snapshot：从 Eureka 拿 peers 遍历，第一个成功就返回；全失败抛 IllegalStateException 让上层 halt。
 * channel 用完即关——snapshot 同步是低频事件。
 */
final class SnapshotFetcherImpl implements SnapshotFetcher {

    private static final Logger LOGGER = LoggerFactory.getLogger(SnapshotFetcherImpl.class);
    private static final long FETCH_TIMEOUT_SEC = 600L;

    private final RaftClusterDiscovery discovery;

    SnapshotFetcherImpl(RaftClusterDiscovery discovery) {
        this.discovery = discovery;
    }

    @Override
    public void fetch(long snapshotId, Path targetDir) throws Exception {
        long startNanos = System.nanoTime();
        try {
            Files.createDirectories(targetDir);
            Map<String, String> peers = discovery.raftToGrpcPeerMap();
            if (peers.isEmpty()) {
                throw new IllegalStateException("no peers known from discovery");
            }
            String selfRaftMember = discovery.raftCurrentMember();
            Throwable lastErr = null;
            for (Map.Entry<String, String> peer : peers.entrySet()) {
                if (peer.getKey().equals(selfRaftMember)) {
                    continue;
                }
                String peerGrpc = peer.getValue();
                try {
                    LOGGER.info("fetching snapshot id={} from {}", snapshotId, peerGrpc);
                    long bytes = fetchFromPeer(peerGrpc, snapshotId, targetDir);
                    RaftExchangeMetrics.Sidecar.recordFetchSuccess(startNanos, bytes);
                    return;
                } catch (Throwable t) {
                    LOGGER.warn("fetch snapshot id={} from {} failed: {}", snapshotId, peerGrpc, t.toString());
                    lastErr = t;
                }
            }
            throw new IllegalStateException("no peer could serve snapshot " + snapshotId, lastErr);
        } catch (Throwable t) {
            RaftExchangeMetrics.Sidecar.recordFetchFailure(startNanos);
            throw t;
        }
    }

    private long fetchFromPeer(String grpcPeer, long snapshotId, Path targetDir) throws IOException {
        int colon = grpcPeer.indexOf(':');
        String host = grpcPeer.substring(0, colon);
        int port = Integer.parseInt(grpcPeer.substring(colon + 1));
        ManagedChannel channel = NettyChannelBuilder.forAddress(host, port).usePlaintext().build();
        try {
            SnapshotTransferServiceGrpc.SnapshotTransferServiceBlockingStub stub = SnapshotTransferServiceGrpc
                .newBlockingStub(channel).withDeadlineAfter(FETCH_TIMEOUT_SEC, TimeUnit.SECONDS);
            Iterator<SnapshotChunk> chunks =
                stub.fetch(SnapshotFetchRequest.newBuilder().setSnapshotId(snapshotId).build());
            Map<String, OutputStream> openFiles = new HashMap<>();
            long totalBytes = 0;
            try {
                while (chunks.hasNext()) {
                    SnapshotChunk chunk = chunks.next();
                    String filename = chunk.getFilename();
                    OutputStream output = openFiles.get(filename);
                    if (output == null) {
                        output = Files.newOutputStream(targetDir.resolve(filename), StandardOpenOption.CREATE,
                            StandardOpenOption.TRUNCATE_EXISTING);
                        openFiles.put(filename, output);
                    }
                    if (!chunk.getData().isEmpty()) {
                        chunk.getData().writeTo(output);
                        totalBytes += chunk.getData().size();
                    }
                    if (chunk.getEof()) {
                        output.close();
                        openFiles.remove(filename);
                    }
                }
                return totalBytes;
            } finally {
                for (OutputStream output : openFiles.values()) {
                    try {
                        output.close();
                    } catch (IOException ignored) {
                    }
                }
            }
        } finally {
            channel.shutdownNow();
        }
    }
}
