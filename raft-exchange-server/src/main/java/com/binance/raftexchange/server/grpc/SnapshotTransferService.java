package com.binance.raftexchange.server.grpc;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Supplier;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.binance.raftexchange.server.metrics.RaftExchangeMetrics;
import com.binance.raftexchange.stubs.snapshot.SnapshotChunk;
import com.binance.raftexchange.stubs.snapshot.SnapshotFetchRequest;
import com.binance.raftexchange.stubs.snapshot.SnapshotTransferServiceGrpc;
import com.google.protobuf.ByteString;

import io.grpc.Status;
import io.grpc.stub.StreamObserver;

/**
 * Sidecar gRPC：把本节点 {@code snapshot_<id>_*.dat} 流式吐给请求方，仅 aeron backend 启用。
 *
 * <p>snapshotsRoot 用 {@link Supplier} 懒解析，避开 gRPC server 启动早于 cluster doStart 的顺序依赖。
 */
public class SnapshotTransferService extends SnapshotTransferServiceGrpc.SnapshotTransferServiceImplBase {

    private static final Logger LOGGER = LoggerFactory.getLogger(SnapshotTransferService.class);
    private static final int CHUNK_SIZE = 256 * 1024;

    private final Supplier<Path> snapshotsRoot;

    public SnapshotTransferService(Supplier<Path> snapshotsRoot) {
        this.snapshotsRoot = snapshotsRoot;
    }

    @Override
    public void fetch(SnapshotFetchRequest request, StreamObserver<SnapshotChunk> responseStream) {
        Path root = snapshotsRoot.get();
        if (root == null || !Files.isDirectory(root)) {
            RaftExchangeMetrics.Sidecar.recordServeFailure();
            responseStream.onError(Status.FAILED_PRECONDITION.withDescription("snapshots root not ready").asException());
            return;
        }
        long snapshotId = request.getSnapshotId();
        String filePrefix = "snapshot_" + snapshotId + "_";
        List<Path> files;
        try (Stream<Path> paths = Files.list(root)) {
            files = paths.filter(path -> {
                String filename = path.getFileName().toString();
                return filename.startsWith(filePrefix) && filename.endsWith(".dat");
            }).toList();
        } catch (IOException e) {
            RaftExchangeMetrics.Sidecar.recordServeFailure();
            responseStream.onError(Status.INTERNAL.withCause(e).withDescription("list failed").asException());
            return;
        }
        if (files.isEmpty()) {
            RaftExchangeMetrics.Sidecar.recordServeFailure();
            responseStream
                .onError(Status.NOT_FOUND.withDescription("snapshot " + snapshotId + " not found locally").asException());
            return;
        }
        try {
            byte[] buffer = new byte[CHUNK_SIZE];
            long totalBytes = 0;
            for (Path file : files) {
                String filename = file.getFileName().toString();
                try (InputStream input = Files.newInputStream(file)) {
                    int bytesRead;
                    while ((bytesRead = input.read(buffer)) > 0) {
                        responseStream.onNext(SnapshotChunk.newBuilder().setFilename(filename)
                            .setData(ByteString.copyFrom(buffer, 0, bytesRead)).build());
                        totalBytes += bytesRead;
                    }
                }
                responseStream.onNext(SnapshotChunk.newBuilder().setFilename(filename).setEof(true).build());
            }
            responseStream.onCompleted();
            RaftExchangeMetrics.Sidecar.recordServeSuccess(totalBytes);
            LOGGER.info("snapshot id={} served, files={}", snapshotId, files.size());
        } catch (IOException e) {
            RaftExchangeMetrics.Sidecar.recordServeFailure();
            LOGGER.warn("snapshot transfer id={} failed", snapshotId, e);
            responseStream.onError(Status.INTERNAL.withCause(e).asException());
        }
    }
}
