package com.binance.raftexchange.server.raft;

import java.util.Objects;

/**
 * 代表了raft层的对象 不要使用grpc的
 * 这里代表了raft真实提供出去的交互接口
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
        if (obj == this) return true;
        if (obj == null || obj.getClass() != this.getClass()) return false;
        RaftNode that = (RaftNode) obj;
        return Objects.equals(this.host, that.host) && this.port == that.port && Objects.equals(this.nodeType, that.nodeType);
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
        LEADER,
        FOLLOWER
    }
}
