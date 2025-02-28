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
        return switch (nodeType) {
            case LEADER -> NodeType.LEADER;
            case FOLLOWER -> NodeType.FOLLOW;
            default -> NodeType.UNRECOGNIZED;
        };
    }
}
