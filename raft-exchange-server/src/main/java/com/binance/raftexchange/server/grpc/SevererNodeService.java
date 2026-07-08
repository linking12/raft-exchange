package com.binance.raftexchange.server.grpc;

import com.binance.raftexchange.server.raft.RaftClusterContainer;
import com.binance.raftexchange.server.raft.RaftNode;
import com.binance.raftexchange.stubs.api.ServerNodeServiceGrpc;
import com.binance.raftexchange.stubs.request.NodeListCommand;
import com.binance.raftexchange.stubs.response.NodeList;
import com.binance.raftexchange.stubs.response.NodeType;
import com.binance.raftexchange.stubs.response.ServerNode;
import io.grpc.stub.StreamObserver;

public class SevererNodeService extends ServerNodeServiceGrpc.ServerNodeServiceImplBase {
    private final RaftClusterContainer raftClusterContainer;

    public SevererNodeService(RaftClusterContainer raftClusterContainer) {
        this.raftClusterContainer = raftClusterContainer;
    }

    @Override
    public void listNodes(NodeListCommand request, StreamObserver<NodeList> responseObserver) {
        NodeList.Builder resultBuilder = NodeList.newBuilder();
        for (RaftNode raftNode : raftClusterContainer.listNodes()) {
            resultBuilder.addNodes(toServerNode(raftNode));
        }
        responseObserver.onNext(resultBuilder.build());
        responseObserver.onCompleted();
    }

    static ServerNode toServerNode(RaftNode raftNode) {
        return ServerNode.newBuilder().setHost(raftNode.host()).setPort(raftNode.port())
            .setType(toNodeType(raftNode.nodeType())).build();
    }

    private static NodeType toNodeType(RaftNode.NodeType type) {
        return switch (type) {
            case LEADER -> NodeType.LEADER;
            case FOLLOWER -> NodeType.FOLLOW;
            default -> NodeType.UNRECOGNIZED;
        };
    }
}
