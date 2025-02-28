package com.binance.raftexchange.server.raft;

/**
 * 代表了raft层的对象 不要使用grpc的
 * @param host
 * @param port
 */
public record RaftNode(String host, int port, NodeType nodeType) {


    public enum NodeType {
        LEADER,
        FOLLOWER
    }
}
