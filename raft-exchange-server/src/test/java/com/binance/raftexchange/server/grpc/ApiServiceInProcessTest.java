package com.binance.raftexchange.server.grpc;

import com.binance.raftexchange.server.raft.RaftClusterContainer;
import com.binance.raftexchange.server.raft.RaftResponse;
import com.binance.raftexchange.server.raft.RaftNode;
import com.binance.raftexchange.stubs.api.ApiCommandServiceGrpc;
import com.binance.raftexchange.stubs.request.ApiAddUser;
import com.binance.raftexchange.stubs.request.ApiCommand;
import com.binance.raftexchange.stubs.response.CommandResult;
import com.binance.raftexchange.stubs.response.CommandResultCode;
import io.grpc.ManagedChannel;
import io.grpc.Server;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * ApiService 走真 gRPC 栈 + UniversalInterceptor 端到端： 客户端 stub 发 ApiCommand → grpc marshalling →
 * UniversalInterceptor.onMessage → handle → callback → sendMessage 回客户端。 验证 ApiService.transform() 把 interceptor
 * 装上了；如果忘装 interceptor，客户端会永远收不到响应。
 */
class ApiServiceInProcessTest {

    private Server server;
    private ManagedChannel channel;
    private RaftClusterContainer raftContainer;
    private java.util.concurrent.atomic.AtomicBoolean leaderFlag;

    @BeforeEach
    void setUp() throws Exception {
        leaderFlag = new java.util.concurrent.atomic.AtomicBoolean(false);
        raftContainer = mock(RaftClusterContainer.class);
        when(raftContainer.isLeader()).thenAnswer(inv -> leaderFlag.get());

        String name = InProcessServerBuilder.generateName();
        server = InProcessServerBuilder.forName(name).directExecutor()
            .addService(new ApiService(raftContainer).transform()).build().start();
        channel = InProcessChannelBuilder.forName(name).directExecutor().build();
    }

    @AfterEach
    void tearDown() throws Exception {
        if (channel != null)
            channel.shutdownNow().awaitTermination(2, TimeUnit.SECONDS);
        if (server != null)
            server.shutdownNow().awaitTermination(2, TimeUnit.SECONDS);
    }

    @Test
    void writeCommand_asLeader_invokesRaftConsensus_andResponseFlowsBackToClient() throws Exception {
        leaderFlag.set(true);

        // 模拟 raft 共识：直接 callback SUCCESS
        doAnswer(inv -> {
            @SuppressWarnings("unchecked")
            BiConsumer<RaftResponse, Throwable> cb = inv.getArgument(1);
            byte[] success = CommandResult.newBuilder().setResultCode(CommandResultCode.SUCCESS).build().toByteArray();
            cb.accept(new RaftResponse(() -> success, 0, 0, 0), null);
            return null;
        }).when(raftContainer).requestConsensus(any(byte[].class), any());

        CommandResult got = sendAndWait(addUser(42L));

        assertEquals(CommandResultCode.SUCCESS, got.getResultCode(), "leader 路径上 raft callback 必须穿透 interceptor 回到客户端");
    }

    @Test
    void writeCommand_asFollowerWithoutLeader_clientReceivesNO_LEADER() throws Exception {
        // bus 默认 FOLLOWER
        when(raftContainer.leaderNode()).thenReturn(null);

        CommandResult got = sendAndWait(addUser(42L));

        assertEquals(CommandResultCode.NO_LEADER, got.getResultCode(),
            "follower + 无 leader 必须经 grpc 栈传 NO_LEADER 给客户端");
    }

    @Test
    void writeCommand_asFollowerWithLeader_clientReceivesNEED_MOVE() throws Exception {
        when(raftContainer.leaderNode()).thenReturn(new RaftNode("10.0.0.7", 6001, RaftNode.NodeType.LEADER));

        CommandResult got = sendAndWait(addUser(42L));

        assertEquals(CommandResultCode.NEED_MOVE, got.getResultCode());
        assertEquals("10.0.0.7", got.getLeaderNode().getHost());
        assertEquals(6001, got.getLeaderNode().getPort());
    }

    // ---- helpers ----

    private CommandResult sendAndWait(ApiCommand cmd) throws Exception {
        CompletableFuture<CommandResult> future = new CompletableFuture<>();
        StreamObserver<ApiCommand> reqObs =
            ApiCommandServiceGrpc.newStub(channel).execApiCommand(new StreamObserver<>() {
                @Override
                public void onNext(CommandResult v) {
                    future.complete(v);
                }

                @Override
                public void onError(Throwable t) {
                    future.completeExceptionally(t);
                }

                @Override
                public void onCompleted() {}
            });
        reqObs.onNext(cmd);
        return future.get(5, TimeUnit.SECONDS);
    }

    private static ApiCommand addUser(long uid) {
        return ApiCommand.newBuilder().setTimestamp(System.currentTimeMillis())
            .setAddUser(ApiAddUser.newBuilder().setUid(uid)).build();
    }
}
