package com.binance.raftexchange.server.grpc;

import com.binance.raftexchange.client.ExchangeApi;
import com.binance.raftexchange.server.raft.jraft.SingleNodeRaftHarness;
import com.binance.raftexchange.stubs.api.ServerNodeServiceGrpc;
import com.binance.raftexchange.stubs.request.NodeListCommand;
import com.binance.raftexchange.stubs.response.NodeList;
import com.binance.raftexchange.stubs.response.NodeType;
import com.binance.raftexchange.stubs.response.ServerNode;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * 端到端：SingleNodeRaftHarness（真 Raft + 真 gRPC Netty）+ ExchangeApi 客户端。
 *
 * <p>
 * 验证 client ↔ server 的 listNodes 全链路：
 * <ul>
 * <li>server：RaftClusterContainer.listNodes → SevererNodeService → gRPC marshaller → Netty</li>
 * <li>client：bootstrapLeaderChannel (bootstrap) 消费 listNodes 响应定位 leader</li>
 * </ul>
 *
 * <p>
 * 单节点 Raft leader election 约 1–3s，整体 ~5s，默认跳过。手动运行：
 * 
 * <pre>
 * mvn -pl raft-exchange-server -am test -Dtest=ExchangeClientListNodesE2ETest \
 *     -Djunit.jupiter.conditions.deactivate='org.junit.*DisabledCondition'
 * </pre>
 */
@Disabled("E2E (~5s): 单节点 jraft leader election。手动运行回归 listNodes 全链路。")
class ExchangeClientListNodesE2ETest {

    private static SingleNodeRaftHarness harness;
    private static ManagedChannel channel;

    @BeforeAll
    static void startServer() throws Exception {
        harness = SingleNodeRaftHarness.start();
        channel = ManagedChannelBuilder.forAddress("127.0.0.1", harness.grpcPort()).usePlaintext().build();
    }

    @AfterAll
    static void stopServer() throws Exception {
        if (channel != null)
            channel.shutdownNow();
        if (harness != null)
            harness.close();
    }

    // -----------------------------------------------------------------------

    @Test
    @DisplayName("listNodes: 单节点集群只有一个 LEADER，gRPC port 与 harness 实际端口一致")
    void listNodes_returnsSingleLeader() {
        NodeList nodes = stub().listNodes(NodeListCommand.getDefaultInstance());

        assertEquals(1, nodes.getNodesCount(), "单节点集群应只有一个节点");
        ServerNode leader = nodes.getNodes(0);
        assertEquals(NodeType.LEADER, leader.getType());
        assertEquals(harness.grpcPort(), leader.getPort(), "listNodes 返回的 gRPC port 必须与 harness 实际端口一致");
    }

    @Test
    @DisplayName("ExchangeApi.connect: bootstrapLeaderChannel 通过 listNodes 定位并连接 leader，不抛异常")
    void exchangeApiConnect_bootstrapViaSniffLeaderChannel() throws Exception {
        // ExchangeApi.connect → new ExchangeClient(host, port)
        // → bootstrapLeaderChannel → gRPC listNodes → 找 LEADER → 建连
        // 若 listNodes 失败 / 无 LEADER，connect 会抛 IllegalStateException
        try (ExchangeApi client = ExchangeApi.connect("127.0.0.1", harness.grpcPort())) {
            assertNotNull(client);
        }
    }

    @Test
    @DisplayName("listNodes 多次调用结果稳定（幂等）")
    void listNodes_idempotent() {
        ServerNodeServiceGrpc.ServerNodeServiceBlockingStub s = stub();
        NodeList first = s.listNodes(NodeListCommand.getDefaultInstance());
        NodeList second = s.listNodes(NodeListCommand.getDefaultInstance());

        assertEquals(first.getNodesCount(), second.getNodesCount());
        assertEquals(first.getNodes(0).getType(), second.getNodes(0).getType());
        assertEquals(first.getNodes(0).getPort(), second.getNodes(0).getPort());
    }

    // -----------------------------------------------------------------------

    private static ServerNodeServiceGrpc.ServerNodeServiceBlockingStub stub() {
        return ServerNodeServiceGrpc.newBlockingStub(channel).withDeadlineAfter(5, TimeUnit.SECONDS);
    }
}
