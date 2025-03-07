package com.binance.raftexchange.server.grpc;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.binance.raftexchange.server.raft.RaftClusterContainer;
import com.binance.raftexchange.server.raft.RaftNode;
import com.binance.raftexchange.server.util.SerializeHelper;
import com.binance.raftexchange.server.util.ThrowableFunction;
import com.binance.raftexchange.stubs.request.ApiCommand;
import com.binance.raftexchange.stubs.response.CommandResult;
import com.binance.raftexchange.stubs.response.CommandResultCode;
import com.binance.raftexchange.stubs.response.ServerNode;

import io.grpc.ForwardingServerCallListener;
import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.Status;

class UniversalInterceptor<ReqT, RespT> extends ForwardingServerCallListener.SimpleForwardingServerCallListener<ReqT> {
    private static final Logger LOGGER = LoggerFactory.getLogger(UniversalInterceptor.class);
    protected final ServerCall<ReqT, RespT> call;
    protected final RaftClusterContainer raftClusterContainer;

    protected final ConcurrentHashMap<ReqT, CompletableFuture<CommandResult>> commandOnTheWay =
        new ConcurrentHashMap<>();

    public UniversalInterceptor(RaftClusterContainer raftClusterContainer, ServerCall.Listener<ReqT> delegate,
        ServerCall<ReqT, RespT> call) {
        super(delegate);
        this.raftClusterContainer = raftClusterContainer;
        this.call = call;
    }

    @Override
    public void onMessage(ReqT message) {
        try (InputStream stream = (InputStream)message) {
            /**
             * @formatter off
             */
            CompletableFuture<CommandResult> complete = handle(readAll(stream)).whenComplete((result, err) -> {
                commandOnTheWay.remove(message);
                if (result != null) {
                    call.sendMessage((RespT)new ByteArrayInputStream(result.toByteArray()));
                    return;
                }
                if (err instanceof CancellationException) {
                    return;
                }
                LOGGER.error("exchange core error!", err);
                call.close(Status.INTERNAL.withCause(err), new Metadata());
            });
            /**
             * @formatter on
             */
            commandOnTheWay.put(message, complete);
        } catch (Exception e) {
            //不应该到这里
            throw new RuntimeException(e);
        }
    }

    private byte[] readAll(InputStream inputStream) throws IOException {
        int available = inputStream.available();
        byte[] bytes = new byte[available];
        inputStream.read(bytes);
        return bytes;
    }

    @Override
    public void onComplete() {
        LOGGER.info("client onComplete");
        for (CompletableFuture<CommandResult> future : commandOnTheWay.values()) {
            future.cancel(false);
        }
        delegate().onComplete();
    }

    private CompletableFuture<CommandResult> handle(byte[] apiCommand) {
        if (!raftClusterContainer.isLeader()) {
            RaftNode raftNode = raftClusterContainer.leaderNode();

            if(raftNode == null) {
                return CompletableFuture.completedFuture(
                        CommandResult.newBuilder()
                                .setResultCode(CommandResultCode.NO_LEADER)
                                .build()
                );
            }

            ServerNode leaderNode = Transformer.raftNodeTransform(raftNode);
            return CompletableFuture.completedFuture(
                    CommandResult.newBuilder()
                            .setResultCode(CommandResultCode.NEED_MOVE)
                            .setLeaderNode(leaderNode)
                            .build()
            );
        }

        byte[] raftLog = SerializeHelper.serializeWithType(ApiCommand.class, apiCommand);
        return raftClusterContainer.requestConsensus(raftLog)
            .thenApply(ThrowableFunction.warp(b -> SerializeHelper.bytesToEnumProto(b, CommandResultCode.class))) // 把状态机应用结果给下面
            .thenApply(v -> CommandResult.newBuilder().setResultCode(v).build());
    }

    protected boolean allowFollowExecute(byte[] command) {
        return false;
    }
}
