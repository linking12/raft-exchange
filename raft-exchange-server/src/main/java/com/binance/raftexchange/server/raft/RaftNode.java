package com.binance.raftexchange.server.raft;

import java.util.Objects;

/**
 * 共识层对节点的统一抽象。两个 backend 共享：
 * <ul>
 * <li>jraft：JraftClusterContainer 把 PeerId → RaftNode
 * <li>aeron：AeronClusterContainer 把 ClusterMember → RaftNode
 * </ul>
 *
 * <p>
 * port 是 grpc port（客户端通信端口）。
 */
public final class RaftNode {
    private final String host;
    private final int port;
    private final NodeType nodeType;

    public RaftNode(String host, int port, NodeType nodeType) {
        this.host = host;
        this.port = port;
        this.nodeType = nodeType;
    }

    public String host() {
        return host;
    }

    public int port() {
        return port;
    }

    public NodeType nodeType() {
        return nodeType;
    }

    @Override
    public boolean equals(Object obj) {
        if (obj == this)
            return true;
        if (obj == null || obj.getClass() != this.getClass())
            return false;
        RaftNode that = (RaftNode)obj;
        return Objects.equals(this.host, that.host) && this.port == that.port
            && Objects.equals(this.nodeType, that.nodeType);
    }

    @Override
    public int hashCode() {
        return Objects.hash(host, port, nodeType);
    }

    @Override
    public String toString() {
        return "RaftNode[" + "host=" + host + ", " + "port=" + port + ", " + "nodeType=" + nodeType + ']';
    }

    public enum NodeType {
        LEADER, FOLLOWER
    }
}
