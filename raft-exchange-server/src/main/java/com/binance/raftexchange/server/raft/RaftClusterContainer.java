package com.binance.raftexchange.server.raft;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.binance.raftexchange.server.exchange.ExchangeCalls;
import com.binance.raftexchange.server.exchange.ExchangeRuntime;
import com.binance.raftexchange.server.raft.aeron.AeronClusterContainer;
import com.binance.raftexchange.server.raft.jraft.JraftClusterContainer;

/**
 * 共识层抽象。{@link JraftClusterContainer} / {@link AeronClusterContainer} 按 sysprop
 * {@code raftexchange.consensus=jraft|aeron} 切换。Aeron 复用 raft.port 作 base，实占 base..base+5 共 6 个 UDP 端口。
 */
public interface RaftClusterContainer {

    Logger LOGGER = LoggerFactory.getLogger(RaftClusterContainer.class);

    String CONSENSUS_PROP = "raftexchange.consensus";
    String CONSENSUS_DEFAULT = "jraft";

    static RaftClusterContainer create(RaftClusterDiscovery discovery, ExchangeRuntime exchangeRuntime) {
        String consensus = System.getProperty(CONSENSUS_PROP, CONSENSUS_DEFAULT).trim().toLowerCase();
        LOGGER.info("consensus={}", consensus);
        return switch (consensus) {
            case "jraft" -> new JraftClusterContainer(discovery, exchangeRuntime);
            case "aeron" -> new AeronClusterContainer(discovery, exchangeRuntime);
            default -> throw new IllegalArgumentException(
                "unknown " + CONSENSUS_PROP + "=" + consensus + " (expected jraft|aeron)");
        };
    }

    void doStart() throws Exception;

    void doStop() throws Exception;

    boolean isLeader();

    void addRoleChangeListener(Consumer<RaftNode.NodeType> listener);

    ExchangeCalls exchangeCalls();

    void requestConsensus(byte[] cmdBytes, BiConsumer<RaftResponse, Throwable> callback);

    RaftNode leaderNode();

    List<RaftNode> listNodes();

    CompletableFuture<AdminResult> triggerSnapshot();

    AdminResult stepDownLeadership();

    default Path snapshotsRoot() {
        return null;
    }

    default CompletableFuture<Void> readConsistencyBarrier() {
        return CompletableFuture.completedFuture(null);
    }
}
