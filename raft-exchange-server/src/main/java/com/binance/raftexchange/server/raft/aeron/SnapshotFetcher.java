package com.binance.raftexchange.server.raft.aeron;

import java.nio.file.Path;

/**
 * 跨节点拉取 snapshot 文件的抽象。
 *
 * <p>
 * Aeron snapshot 只在 publication 写 8 字节 snapshotId marker，实际 .dat 文件留本地 {@code clusterDir/snapshots/}；新节点或本地 dat
 * 丢失的节点 {@code onStart} 时通过此接口去其他 peer 同步。默认实现 {@link SnapshotFetcherImpl} 走 gRPC
 * {@link com.binance.raftexchange.server.grpc.SnapshotTransferService}。
 */
@FunctionalInterface
interface SnapshotFetcher {
    void fetch(long snapshotId, Path targetDir) throws Exception;
}
