package com.binance.raftexchange.server.grpc;

import com.binance.raftexchange.server.raft.RaftClusterContainer;
import com.binance.raftexchange.server.raft.RaftNode;
import com.binance.raftexchange.stubs.api.ServerNodeServiceGrpc;
import com.binance.raftexchange.stubs.request.NodeListCommand;
import com.binance.raftexchange.stubs.response.NodeList;
import com.binance.raftexchange.stubs.response.NodeType;
import com.binance.raftexchange.stubs.response.ServerNode;
import io.grpc.stub.StreamObserver;

import java.util.List;

public class SevererNodeService extends ServerNodeServiceGrpc.ServerNodeServiceImplBase {

    private final RaftClusterContainer raftClusterContainer;

    public SevererNodeService(RaftClusterContainer raftClusterContainer) {
        this.raftClusterContainer = raftClusterContainer;
    }

    @Override
    public void listNodes(NodeListCommand request, StreamObserver<NodeList> responseObserver) {
        List<RaftNode> raftNodes = raftClusterContainer.listNodes();
        NodeList.Builder resultBuilder = NodeList.newBuilder();
        for (RaftNode raftNode : raftNodes) {
            resultBuilder = resultBuilder.addNodes(Transformer.raftNodeTransform(raftNode));
        }
        NodeList nodeList = resultBuilder.build();
        responseObserver.onNext(nodeList);
        responseObserver.onCompleted();
    }

}
