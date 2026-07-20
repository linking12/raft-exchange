package com.binance.raftexchange.server.grpc;

import com.binance.raftexchange.server.raft.RaftClusterContainer;
import com.binance.raftexchange.server.raft.RaftNode;
import com.binance.raftexchange.stubs.api.ServerNodeServiceGrpc;
import com.binance.raftexchange.stubs.request.NodeListCommand;
import com.binance.raftexchange.stubs.response.NodeList;
import com.binance.raftexchange.stubs.response.NodeType;
import io.grpc.ManagedChannel;
import io.grpc.Server;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * SevererNodeService 走真 gRPC stack 的集成测试（in-process channel — 无端口/网络开销）： client → grpc-marshaller →
 * ServerNodeService.listNodes → grpc-marshaller → client。 比 fork JVM 的 E2E 快 100 倍，可作为 gRPC plumbing smoke test。
 */
class SevererNodeServiceInProcessTest {

    private Server server;
    private ManagedChannel channel;
    private RaftClusterContainer raftContainer;

    @BeforeEach
    void setUp() throws Exception {
        raftContainer = mock(RaftClusterContainer.class);
        String name = InProcessServerBuilder.generateName();
        server = InProcessServerBuilder.forName(name).directExecutor().addService(new SevererNodeService(raftContainer))
            .build().start();
        channel = InProcessChannelBuilder.forName(name).directExecutor().build();
    }

    @AfterEach
    void tearDown() throws Exception {
        channel.shutdownNow();
        server.shutdownNow();
    }

    @Test
    void listNodes_returnsAllRaftNodesViaGrpc() {
        when(raftContainer.listNodes()).thenReturn(List.of(new RaftNode("10.0.0.1", 9090, RaftNode.NodeType.LEADER),
            new RaftNode("10.0.0.2", 9091, RaftNode.NodeType.FOLLOWER)));

        NodeList result =
            ServerNodeServiceGrpc.newBlockingStub(channel).listNodes(NodeListCommand.getDefaultInstance());

        assertEquals(2, result.getNodesCount());
        assertEquals(NodeType.LEADER, result.getNodes(0).getType());
        assertEquals("10.0.0.1", result.getNodes(0).getHost());
        assertEquals(9090, result.getNodes(0).getPort());
        assertEquals(NodeType.FOLLOW, result.getNodes(1).getType());
    }

    @Test
    void listNodes_emptyCluster_returnsEmptyList() {
        when(raftContainer.listNodes()).thenReturn(List.of());

        NodeList result =
            ServerNodeServiceGrpc.newBlockingStub(channel).listNodes(NodeListCommand.getDefaultInstance());

        assertEquals(0, result.getNodesCount());
    }
}
