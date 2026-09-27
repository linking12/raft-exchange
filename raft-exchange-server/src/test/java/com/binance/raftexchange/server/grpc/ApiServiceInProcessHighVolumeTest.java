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

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 在 in-process gRPC 上打 1000 个请求，验证 UniversalInterceptor 的流控窗口 (WINDOW_SIZE)、 OffloadCallback 复用、Closure 链路在高并发下不丢消息 /
 * 不死锁。
 */
class ApiServiceInProcessHighVolumeTest {

    private Server server;
    private ManagedChannel channel;
    private RaftClusterContainer raftContainer;
    private java.util.concurrent.atomic.AtomicBoolean leaderFlag;

    @BeforeEach
    void setUp() throws Exception {
        leaderFlag = new java.util.concurrent.atomic.AtomicBoolean(false);
        leaderFlag.set(true); // 走 leader 路径，触发 requestConsensus

        raftContainer = mock(RaftClusterContainer.class);
        when(raftContainer.isLeader()).thenAnswer(inv -> leaderFlag.get());

        // 模拟 raft 共识：每个 requestConsensus 立刻 callback SUCCESS（无 latency）
        doAnswer(inv -> {
            @SuppressWarnings("unchecked")
            BiConsumer<RaftResponse, Throwable> cb = inv.getArgument(1);
            byte[] success = CommandResult.newBuilder().setResultCode(CommandResultCode.SUCCESS).build().toByteArray();
            cb.accept(new RaftResponse(() -> success, 0, 0, 0), null);
            return null;
        }).when(raftContainer).requestConsensus(any(byte[].class), any());

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
    void thousandRequests_concurrentClients_allReceiveSUCCESS() throws Exception {
        int clients = 8;
        int perClient = 200;
        int total = clients * perClient;

        AtomicInteger ok = new AtomicInteger();
        AtomicInteger fail = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(clients);
        java.util.concurrent.CountDownLatch allDone = new java.util.concurrent.CountDownLatch(total);

        for (int c = 0; c < clients; c++) {
            final int clientId = c;
            pool.submit(() -> {
                ApiCommandServiceGrpc.ApiCommandServiceStub stub = ApiCommandServiceGrpc.newStub(channel);
                StreamObserver<ApiCommand> reqObs = stub.execApiCommand(new StreamObserver<>() {
                    @Override
                    public void onNext(CommandResult v) {
                        if (v.getResultCode() == CommandResultCode.SUCCESS)
                            ok.incrementAndGet();
                        else
                            fail.incrementAndGet();
                        allDone.countDown();
                    }

                    @Override
                    public void onError(Throwable t) {
                        fail.incrementAndGet();
                        allDone.countDown();
                    }

                    @Override
                    public void onCompleted() {}
                });
                for (int i = 0; i < perClient; i++) {
                    reqObs.onNext(ApiCommand.newBuilder().setTimestamp(System.currentTimeMillis())
                        .setAddUser(ApiAddUser.newBuilder().setUid(clientId * 1_000L + i)).build());
                }
            });
        }

        assertTrue(allDone.await(30, TimeUnit.SECONDS),
            "30s 内 " + total + " 个请求必须全部回复；实际收到 " + (total - allDone.getCount()));
        assertEquals(total, ok.get(), "所有请求必须返回 SUCCESS（不能丢、不能错路由）");
        assertEquals(0, fail.get());

        pool.shutdown();
    }
}
