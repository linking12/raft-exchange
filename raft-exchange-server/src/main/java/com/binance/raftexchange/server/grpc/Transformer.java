package com.binance.raftexchange.server.grpc;

import com.binance.raftexchange.server.raft.RaftNode;
import com.binance.raftexchange.stubs.response.NodeType;
import com.binance.raftexchange.stubs.response.ServerNode;

class Transformer {

    static ServerNode raftNodeTransform(RaftNode raftNode) {
        return ServerNode.newBuilder()
                .setHost(raftNode.host())
                .setPort(raftNode.port())
                .setType(transformType(raftNode.nodeType()))
                .build();
    }

    private static NodeType transformType(RaftNode.NodeType nodeType) {
        switch (nodeType) {
            case LEADER:
                return NodeType.LEADER;
            case FOLLOWER:
                return NodeType.FOLLOW;
            default:
                return NodeType.UNRECOGNIZED;
        }
    }
}
