package com.binance.raftexchange.server.grpc;

import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.CancellationException;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.binance.raftexchange.server.raft.RaftClusterContainer;
import com.binance.raftexchange.server.metrics.RaftExchangeMetrics;
import com.binance.raftexchange.server.raft.RaftResponse;
import com.binance.raftexchange.server.raft.RaftNode;
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
import io.netty.util.internal.ThreadExecutorMap;

/**
 * grpc bidi-stream 入口拦截器，4 合 1 中间件：
 *
 * <ol>
 * <li><b>路由</b>：leader 走 raft 共识；follower 写命令直接回 NEED_MOVE；follower 读 (ORDER_BOOK_REQUEST) 本地执行</li>
 * <li><b>反压</b>：滑动窗口（{@link #WINDOW_SIZE}）+ 半窗补充，防 server 被打爆</li>
 * <li><b>异步协调</b>：raft callback 在 ResultsHandler 线程，需 offload 回 grpc worker 才能 sendMessage</li>
 * <li><b>可观测</b>：分段记 raft / matching / grpc latency + inflight 反压指标</li>
 * </ol>
 *
 * <p>
 * 命令处理路径：
 * 
 * <pre>
 *   onMessage(grpc thread)
 *     ├─ tryFastReply 命中 (NEED_MOVE/NO_LEADER) → handleComplete 同步 sendMessage
 *     └─ 走 raft 共识 → OffloadCallback.accept (ResultsHandler thread)
 *                          → offloadWorker.execute (grpc worker thread)
 *                          → handleComplete → sendMessage
 * </pre>
 */
class UniversalInterceptor<ReqT, RespT> extends ForwardingServerCallListener.SimpleForwardingServerCallListener<ReqT> {
    private static final Logger LOGGER = LoggerFactory.getLogger(UniversalInterceptor.class);

    /**
     * 单 grpc stream 最大 inflight 命令数。jraft ringbuffer 8M，理论支持 ~60k 并发 client × 128 inflight = 7.6M < 8M 安全水位。
     *
     * @see com.alipay.sofa.jraft.option.RaftOptions#disruptorBufferSize
     */
    private static final int WINDOW_SIZE = 128;

    /** 只读命令：follower 本地执行允许的唯一类型；其它写命令必须 leader 走 raft */
    private static final int READ_ONLY_COMMAND_FIELD_NUMBER = ApiCommand.ORDER_BOOK_REQUEST_FIELD_NUMBER;

    private final ServerCall<ReqT, RespT> call;
    private final RaftClusterContainer raftClusterContainer;
    private final Executor offloadWorker;
    private final AtomicBoolean halfClose = new AtomicBoolean(false);
    private final AtomicInteger inflight = new AtomicInteger(0);

    public UniversalInterceptor(RaftClusterContainer raftClusterContainer, ServerCall.Listener<ReqT> delegate,
        ServerCall<ReqT, RespT> call) {
        super(delegate);
        this.raftClusterContainer = raftClusterContainer;
        this.call = call;
        Executor nettyExec = ThreadExecutorMap.currentExecutor();
        this.offloadWorker = nettyExec != null ? nettyExec : Runnable::run;
    }

    // ============================== grpc lifecycle ==============================

    @Override
    public void onReady() {
        super.onReady();
        maybeRequestMore();
    }

    @Override
    public void onMessage(ReqT message) {
        byte[] bytes = null;
        try (InputStream stream = (InputStream)message) {
            bytes = stream.readAllBytes();
            long start = System.nanoTime();
            byte[] fastReply = tryFastReply(bytes);
            if (fastReply != null) {
                // NEED_MOVE / NO_LEADER：grpc 线程同步 sendMessage，省一次线程切换
                handleComplete(new RaftResponse(() -> fastReply, 0, 0, 0), start, null);
                return;
            }
            // 走 raft 共识：callback 在 ResultsHandler 线程触发，需 offload 回 grpc worker
            byte[] raftLog = SerializeHelper.serializeWithType(ApiCommand.class, bytes);
            raftClusterContainer.requestConsensus(raftLog, new OffloadCallback(this, start));
        } catch (Exception e) {
            LOGGER.error("Unexpected error in onMessage, closing stream, commandFieldNumber={}, payloadSize={}",
                bytes != null ? commandFieldNumber(bytes) : -1, bytes != null ? bytes.length : -1, e);
            halfClose.set(true);
            call.close(Status.INTERNAL.withDescription(e.getMessage()).withCause(e), new Metadata());
        }
    }

    @Override
    public void onComplete() {
        drainInflight();
    }

    @Override
    public void onCancel() {
        drainInflight();
    }

    @Override
    public void onHalfClose() {
        halfClose.set(true);
    }

    /**
     * 路由判定：
     * <ul>
     * <li>leader 收到任何命令 / follower 收到只读命令 → 返 null（应走 raft 共识）</li>
     * <li>follower 收到写命令 + 有 leader → 返 NEED_MOVE 字节（client 应 retry）</li>
     * <li>follower 收到写命令 + 无 leader → 返 NO_LEADER 字节（client 应重连）</li>
     * </ul>
     */
    private byte[] tryFastReply(byte[] apiCommand) {
        if (raftClusterContainer.isLeader() || commandFieldNumber(apiCommand) == READ_ONLY_COMMAND_FIELD_NUMBER) {
            return null;
        }
        RaftNode raftNode = raftClusterContainer.leaderNode();
        if (raftNode == null) {
            return CommandResult.newBuilder().setResultCode(CommandResultCode.NO_LEADER).build().toByteArray();
        }
        ServerNode leaderNode = SevererNodeService.toServerNode(raftNode);
        return CommandResult.newBuilder().setResultCode(CommandResultCode.NEED_MOVE).setLeaderNode(leaderNode).build()
            .toByteArray();
    }

    /**
     * 从 ApiCommand 字节流里提取命令 oneof 的 proto field number；解析失败返 -1（当成写命令处理）。
     *
     * <p>
     * <b>schema 依赖</b>：proto 第一字段必须是 timestamp（被 skip），第二字段是命令 oneof。
     */
    private static int commandFieldNumber(byte[] data) {
        try {
            CodedInputStream input = CodedInputStream.newInstance(data);
            input.skipField(input.readTag());
            return input.readTag() >>> 3;
        } catch (IOException e) {
            return -1;
        }
    }

    // ============================== 完成回调（grpc worker thread）==============================

    /**
     * 单对象同时实现 BiConsumer + Runnable，省一次 lambda 分配： {@link #accept} 在 ResultsHandler 线程接 raft 回调 → {@link #run} 在 grpc
     * worker 线程完成 sendMessage（grpc 线程模型禁止其它线程直接 sendMessage）。
     */
    private static final class OffloadCallback implements BiConsumer<RaftResponse, Throwable>, Runnable {

        private final UniversalInterceptor<?, ?> parent;
        private final long start;
        private RaftResponse result;
        private Throwable err;

        OffloadCallback(UniversalInterceptor<?, ?> parent, long start) {
            this.parent = parent;
            this.start = start;
        }

        @Override
        public void accept(RaftResponse result, Throwable err) {
            this.result = result;
            this.err = err;
            parent.offloadWorker.execute(this);
        }

        @Override
        public void run() {
            parent.handleComplete(result, start, err);
        }
    }

    private void handleComplete(RaftResponse result, long start, Throwable err) {
        // 配额/窗口必须先释放——否则 cancelled / 异常返回时 inflight 不归零，client 永远收不到新 credit
        RaftExchangeMetrics.Grpc.inflightRelease();
        if (inflight.decrementAndGet() <= WINDOW_SIZE / 2) {
            maybeRequestMore();
        }
        // cancelled 不计入 latency / qps 指标（避免污染 p99）
        if (call.isCancelled() || halfClose.get()) {
            return;
        }
        try {
            RaftExchangeMetrics.Grpc.recordRequest();
            if (result != null) {
                if (result.raftLatencyNanos() > 0) {
                    RaftExchangeMetrics.Grpc.recordRaftLatency(result.raftLatencyNanos());
                    RaftExchangeMetrics.Grpc.recordMatchingLatency(result.exchangeLatencyNanos());
                }
                // 序列化在 gRPC offloadWorker 线程执行
                call.sendMessage((RespT)SerializeHelper.wrapKnownBytes(result.serializer().get()));
                return;
            }
            if (err instanceof CancellationException) {
                return;
            }
            LOGGER.error("exchange core error!", err);
            call.close(Status.INTERNAL.withDescription(err.getMessage()).withCause(err), new Metadata());
        } finally {
            RaftExchangeMetrics.Grpc.recordGrpcLatency(System.nanoTime() - start);
        }
    }

    // ============================== inflight 反压 ==============================

    /** 半窗补充：累计有 ≥ WINDOW_SIZE/2 缺口才向 client 补 credit，避免高频小批量 grpc 通知噪音 */
    private void maybeRequestMore() {
        while (true) {
            int current = inflight.get();
            int needed = WINDOW_SIZE - current;
            if (needed < WINDOW_SIZE / 2) {
                return;
            }
            if (inflight.compareAndSet(current, current + needed)) {
                RaftExchangeMetrics.Grpc.inflightAcquire(needed);
                call.request(needed);
                return;
            }
        }
    }

    /** stream 结束时把残余 inflight 还回去，避免全局 INFLIGHT 计数器漂移 */
    private void drainInflight() {
        int remaining = inflight.getAndSet(0);
        if (remaining > 0) {
            RaftExchangeMetrics.Grpc.inflightRelease(remaining);
        }
    }

}
