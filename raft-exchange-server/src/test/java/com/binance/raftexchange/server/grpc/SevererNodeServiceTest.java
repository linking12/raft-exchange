package com.binance.raftexchange.server.grpc;

import com.binance.raftexchange.server.raft.RaftClusterContainer;
import com.binance.raftexchange.server.raft.RaftNode;
import com.binance.raftexchange.stubs.request.NodeListCommand;
import com.binance.raftexchange.stubs.response.NodeList;
import com.binance.raftexchange.stubs.response.NodeType;
import com.binance.raftexchange.stubs.response.ServerNode;
import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SevererNodeServiceTest {

    @Test
    void toServerNode_leader_mapsToLEADER() {
        ServerNode node = SevererNodeService.toServerNode(new RaftNode("10.0.0.1", 9090, RaftNode.NodeType.LEADER));
        assertEquals("10.0.0.1", node.getHost());
        assertEquals(9090, node.getPort());
        assertEquals(NodeType.LEADER, node.getType());
    }

    @Test
    void toServerNode_follower_mapsToFOLLOW() {
        ServerNode node = SevererNodeService.toServerNode(new RaftNode("10.0.0.2", 9091, RaftNode.NodeType.FOLLOWER));
        assertEquals(NodeType.FOLLOW, node.getType(), "RaftNode.FOLLOWER 必须映射到 grpc NodeType.FOLLOW (proto 拼写)");
    }

    @Test
    @SuppressWarnings("unchecked")
    void listNodes_aggregatesAllRaftNodes() {
        RaftClusterContainer container = mock(RaftClusterContainer.class);
        when(container.listNodes()).thenReturn(List.of(new RaftNode("10.0.0.1", 9090, RaftNode.NodeType.LEADER),
            new RaftNode("10.0.0.2", 9091, RaftNode.NodeType.FOLLOWER),
            new RaftNode("10.0.0.3", 9092, RaftNode.NodeType.FOLLOWER)));

        StreamObserver<NodeList> observer = mock(StreamObserver.class);
        new SevererNodeService(container).listNodes(NodeListCommand.getDefaultInstance(), observer);

        ArgumentCaptor<NodeList> captor = ArgumentCaptor.forClass(NodeList.class);
        verify(observer).onNext(captor.capture());
        verify(observer).onCompleted();

        NodeList result = captor.getValue();
        assertEquals(3, result.getNodesCount());
        assertEquals(NodeType.LEADER, result.getNodes(0).getType());
        assertEquals(NodeType.FOLLOW, result.getNodes(1).getType());
        assertEquals(NodeType.FOLLOW, result.getNodes(2).getType());
    }
}
