package com.binance.raftexchange.server.grpc;

import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.binance.raftexchange.server.raft.RaftClusterContainer;
import com.binance.raftexchange.server.raft.RaftNode;
import com.binance.raftexchange.server.raft.RoleChangeEventbus;
import com.binance.raftexchange.server.util.SerializeHelper;
import com.binance.raftexchange.stubs.request.ApiCommand;
import com.binance.raftexchange.stubs.response.CommandResult;
import com.binance.raftexchange.stubs.response.CommandResultCode;
import com.binance.raftexchange.stubs.response.ServerNode;
import com.google.protobuf.CodedInputStream;

import io.grpc.ForwardingServerCallListener;
import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.Status;

class UniversalInterceptor<ReqT, RespT> extends ForwardingServerCallListener.SimpleForwardingServerCallListener<ReqT> {
    private static final Logger LOGGER = LoggerFactory.getLogger(UniversalInterceptor.class);
    /**
     * jraft处理buffer是8M 如果一次拉取4k个，同一时刻可以支持2k个client的并发
     * 
     * @see com.alipay.sofa.jraft.option.RaftOptions#disruptorBufferSize
     */
    private static final int WINDOW_SIZE = Integer.parseInt(System.getProperty("raft-exchange.grpc.windowSize", "4096"));

    protected final ServerCall<ReqT, RespT> call;
    protected final RaftClusterContainer raftClusterContainer;
    protected final ExecutorService offloadWorker;

    protected final ConcurrentHashMap<ReqT, CompletableFuture<byte[]>> commandOnTheWay = new ConcurrentHashMap<>();

    private final AtomicBoolean halfClose;

    private final int READ_TYPE_COMMAND_NUMBER = ApiCommand.ORDER_BOOK_REQUEST_FIELD_NUMBER;

    private final AtomicInteger inflight = new AtomicInteger(0);

    public UniversalInterceptor(RaftClusterContainer raftClusterContainer, ExecutorService offloadWorker, ServerCall.Listener<ReqT> delegate,
        ServerCall<ReqT, RespT> call) {
        super(delegate);
        this.raftClusterContainer = raftClusterContainer;
        this.offloadWorker = offloadWorker;
        this.call = call;
        this.halfClose = new AtomicBoolean(false);
    }

    @Override
    public void onReady() {
        super.onReady();
        maybeRequestMore();
    }

    @Override
    public void onMessage(ReqT message) {
        try (InputStream stream = (InputStream)message) {
            /**
             * @formatter off
             */
            @SuppressWarnings("unchecked")
            CompletableFuture<byte[]> complete = handle(readAll(stream)).whenCompleteAsync((result, err) -> {
                commandOnTheWay.remove(message);
                if (inflight.decrementAndGet() <= WINDOW_SIZE / 2) {
                    maybeRequestMore();
                }
                if (call.isCancelled() || halfClose.get()) {
                    cancelAll();
                    return;
                }
                if (result != null) {
                    call.sendMessage((RespT)SerializeHelper.wrapKnownBytes(result));
                    return;
                }
                if (err instanceof CancellationException) {
                    return;
                }
                LOGGER.error("exchange core error!", err);
                call.close(Status.INTERNAL.withCause(err), new Metadata());
            }, offloadWorker);
            /**
             * @formatter on
             */
            // 某些情况下实际上并不是异步操作 所以上面会立刻执行回调然后才到这里
            // 比如说no leader这种
            if (!complete.isDone()) {
                commandOnTheWay.put(message, complete);
            }
        } catch (Exception e) {
            // 不应该到这里
            throw new RuntimeException(e);
        }
    }

    private void maybeRequestMore() {
        int needed = WINDOW_SIZE - inflight.get();
        if (needed >= WINDOW_SIZE / 2) { // 只有缺了一半，才补
            inflight.addAndGet(needed);
            call.request(needed);
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
    }

    @Override
    public void onCancel() {

    }

    @Override
    public void onHalfClose() {
        // grpc stream是保序的
        if (commandOnTheWay.isEmpty()) {
            cancelAll();
        } else {
            halfClose.set(true);
        }
    }

    private void cancelAll() {
        for (CompletableFuture<byte[]> future : commandOnTheWay.values()) {
            future.cancel(false);
        }
        call.close(Status.OK, new Metadata());
    }

    /**
     * 这里返回的结果就是撮合后的结果 请看AbstractApiController::callExchange中的序列化转换 这样可以节约一次序列化和反序列化开销 grpc直通exchange-core
     * 
     * @param apiCommand
     * @return
     */
    private CompletableFuture<byte[]> handle(byte[] apiCommand) {
        if (!RoleChangeEventbus.isLeader() && !allowFollowExecute(apiCommand)) {
            RaftNode raftNode = raftClusterContainer.leaderNode();
            if (raftNode == null) {
                return CompletableFuture.completedFuture(CommandResult.newBuilder().setResultCode(CommandResultCode.NO_LEADER).build().toByteArray());
            }
            ServerNode leaderNode = Transformer.raftNodeTransform(raftNode);
            return CompletableFuture
                .completedFuture(CommandResult.newBuilder().setResultCode(CommandResultCode.NEED_MOVE).setLeaderNode(leaderNode).build().toByteArray());
        }
        byte[] raftLog = SerializeHelper.serializeWithType(ApiCommand.class, apiCommand);
        return raftClusterContainer.requestConsensus(raftLog);
    }

    protected boolean allowFollowExecute(byte[] command) {
        int fieldNumber = commandFieldNumber(command);
        if (fieldNumber == -1) {
            return false;
        }
        return fieldNumber != READ_TYPE_COMMAND_NUMBER;
    }

    public static int commandFieldNumber(byte[] data) {
        try {
            CodedInputStream input = CodedInputStream.newInstance(data);
            // 跳过timestamp
            int timeStampTag = input.readTag();
            input.skipField(timeStampTag);
            return input.readTag() >>> 3;
        } catch (IOException e) {
            LOGGER.warn("parse commandFieldNumber");
            return -1;
        }
    }

}
