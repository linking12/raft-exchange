package com.binance.raftexchange.server.raft;

import java.util.Arrays;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.alipay.sofa.jraft.Closure;
import com.alipay.sofa.jraft.Iterator;
import com.alipay.sofa.jraft.Status;
import com.alipay.sofa.jraft.core.StateMachineAdapter;
import com.alipay.sofa.jraft.error.RaftError;
import com.alipay.sofa.jraft.storage.snapshot.SnapshotReader;
import com.alipay.sofa.jraft.storage.snapshot.SnapshotWriter;
import com.binance.raftexchange.server.exchange.ExchangeApiInstance;
import com.binance.raftexchange.server.exchange.SyncAdminApiAccountsController;
import com.binance.raftexchange.server.exchange.SyncAdminApiSymbolsController;
import com.binance.raftexchange.server.exchange.SyncNoOpApiController;
import com.binance.raftexchange.server.exchange.SyncTradeMiscApiController;
import com.binance.raftexchange.server.exchange.SyncTradeOrdersApiController;
import com.binance.raftexchange.server.raft.RaftClusterContainer.ReturnableClosure;
import com.binance.raftexchange.server.util.SerializeHelper;
import com.binance.raftexchange.stubs.request.ApiBinaryDataCommand;
import com.binance.raftexchange.stubs.request.ApiCommand;
import com.binance.raftexchange.stubs.request.BinaryDataCommand;
import com.google.protobuf.GeneratedMessageV3;

import exchange.core2.core.ExchangeApi;
import exchange.core2.core.common.api.ApiPersistState;
import exchange.core2.core.common.api.ApiRecoverState;
import exchange.core2.core.common.cmd.OrderCommand;

import java.util.function.Supplier;

public class ExchangeStateMachine extends StateMachineAdapter {
    private static final Logger LOG = LoggerFactory.getLogger(ExchangeStateMachine.class);

    private final AtomicLong leaderTerm = new AtomicLong(-1L);
    private final SnapshotHelper snapshotHelper = new SnapshotHelper();

    // ---- batch apply pre-allocated arrays (FSMCaller is single-threaded, safe to reuse) ----
    private static final int BATCH_CAPACITY = 1024;
    private static final Consumer<OrderCommand> NOOP_CALLBACK = cmd -> {};
    private static final Status STATUS_OK = Status.OK();

    private final exchange.core2.core.common.api.ApiCommand[] batchCommands = new exchange.core2.core.common.api.ApiCommand[BATCH_CAPACITY];
    @SuppressWarnings("unchecked")
    private final Consumer<OrderCommand>[] batchCallbacks = new Consumer[BATCH_CAPACITY];
    private final Closure[] batchClosures = new Closure[BATCH_CAPACITY];

    @Override
    public void onApply(Iterator iter) {
        int batchSize = 0;

        while (iter.hasNext()) {
            Closure done = iter.done();
            ReturnableClosure rc = (done instanceof ReturnableClosure) ? (ReturnableClosure) done : null;
            long applyTime = (rc != null) ? System.nanoTime() : 0L;

            try {
                GeneratedMessageV3 msg = (rc != null) ?
                        rc.message() : // Leader fast path: 已解析 protobuf
                        SerializeHelper.deserializeWithType(iter.getData()); // Follower path: 解析 ByteBuffer

                // Try to resolve as a batchable command
                exchange.core2.core.common.api.ApiCommand exchangeCmd = convertToExchangeCommand(msg);
                if (exchangeCmd != null) {
                    // Batchable: accumulate
                    batchCommands[batchSize] = exchangeCmd;
                    if (rc != null) {
                        rc.setApplyTime(applyTime);
                        batchCallbacks[batchSize] = rc; // ReturnableClosure IS the Consumer<OrderCommand>
                    } else {
                        batchCallbacks[batchSize] = NOOP_CALLBACK; // Follower: discard result
                    }
                    batchClosures[batchSize] = done;
                    batchSize++;
                    // Flush if batch is full
                    if (batchSize >= BATCH_CAPACITY) {
                        flushBatch(batchSize);
                        batchSize = 0;
                    }
                    iter.next();
                    continue;
                }

                // Non-batchable: flush pending batch first, then process individually
                if (batchSize > 0) {
                    flushBatch(batchSize);
                    batchSize = 0;
                }
                CompletableFuture<Supplier<byte[]>> result = apply(msg);
                if (done != null) {
                    if (rc != null) {
                        rc.setApplyTime(applyTime);
                        rc.accept(result);
                    }
                    done.run(STATUS_OK);
                }
            } catch (Throwable e) {
                LOG.error("Fail to apply", e);
                // Flush pending batch before handling error
                if (batchSize > 0) {
                    flushBatch(batchSize);
                    batchSize = 0;
                }
                if (done != null) {
                    if (rc != null) {
                        rc.completeExceptionally(e);
                    }
                    done.run(STATUS_OK);
                }
            }
            iter.next();
        }

        // ---- Flush remaining batch ----
        if (batchSize > 0) {
            flushBatch(batchSize);
        }
    }

    /**
     * Zero-alloc batch flush: ReturnableClosure objects (already allocated per-request) are registered
     * directly in PromiseBuffer as Consumer&lt;OrderCommand&gt; — no intermediate Futures.
     */
    private void flushBatch(int size) {
        ExchangeApi api = ExchangeApiInstance.exchangeApi();
        api.submitBatchAsync(batchCommands, batchCallbacks, size);

        for (int i = 0; i < size; i++) {
            if (batchClosures[i] != null) {
                batchClosures[i].run(STATUS_OK);
            }
        }

        // Clear references for GC
        Arrays.fill(batchCommands, 0, size, null);
        Arrays.fill(batchCallbacks, 0, size, null);
        Arrays.fill(batchClosures, 0, size, null);
    }

    /**
     * grpc对象转成exchange对象，方便批量提交；返回null则不走批量提交
     * 对BINARY_DATA类型，因为它每次的slot都是动态的，不适合批量
     */
    private static exchange.core2.core.common.api.ApiCommand convertToExchangeCommand(GeneratedMessageV3 grpcMessage) {
        if (!(grpcMessage instanceof ApiCommand apiCommand)) {
            return null;
        }
        return switch (apiCommand.getCommandCase()) {
            case PLACE_ORDER -> SyncTradeOrdersApiController.convertPlaceOrder(apiCommand);
            case MOVE_ORDER -> SyncTradeOrdersApiController.convertMoveOrder(apiCommand);
            case CANCEL_ORDER -> SyncTradeOrdersApiController.convertCancelOrder(apiCommand);
            case REDUCE_ORDER -> SyncTradeOrdersApiController.convertReduceOrder(apiCommand);
            case CLOSE_POSITION -> SyncTradeOrdersApiController.convertClosePosition(apiCommand);
            case ORDER_BOOK_REQUEST -> SyncTradeOrdersApiController.convertOrderBookRequest(apiCommand);
            case ADJUST_LEVERAGE -> SyncTradeOrdersApiController.convertAdjustLeverage(apiCommand);
            case ADJUST_BALANCE -> SyncAdminApiAccountsController.convertAdjustBalance(apiCommand);
            case ADD_USER -> SyncAdminApiAccountsController.convertAddUser(apiCommand);
            case SUSPEND_USER -> SyncAdminApiAccountsController.convertSuspendUser(apiCommand);
            case RESUME_USER -> SyncAdminApiAccountsController.convertResumeUser(apiCommand);
            case ADJUST_POSITION_MODE -> SyncAdminApiAccountsController.convertAdjustPositionMode(apiCommand);
            case ADJUST_MARGIN -> SyncAdminApiAccountsController.convertAdjustMargin(apiCommand);
            case ADJUST_MARKPRICE -> SyncAdminApiSymbolsController.convertAdjustMarkPrice(apiCommand);
            case SETTLE_FUNDING_FEES -> SyncAdminApiSymbolsController.convertSettleFundingFees(apiCommand);
            case SETTLE_PNL -> SyncAdminApiSymbolsController.convertSettlePNL(apiCommand);
            case RESET_FEE -> SyncTradeMiscApiController.convertResetFee(apiCommand);
            case NOP -> SyncTradeMiscApiController.convertNop(apiCommand);
            default -> null;
        };
    }

    private CompletableFuture<Supplier<byte[]>> apply(GeneratedMessageV3 grpcMessage) {
        CompletableFuture<Supplier<byte[]>> result;
        if (grpcMessage instanceof ApiCommand apiCommand) {
            ApiCommand.CommandCase commandCase = apiCommand.getCommandCase();
            switch (commandCase) {
                case BINARY_DATA:
                    ApiBinaryDataCommand apiBinaryDataCommand = apiCommand.getBinaryData();
                    result = processBinaryDataCommand(apiBinaryDataCommand.getData());
                    break;
                case PLACE_ORDER:
                    result = SyncTradeOrdersApiController.placeOrder(apiCommand);
                    break;
                case ADJUST_BALANCE:
                    result = SyncAdminApiAccountsController.adjustBalance(apiCommand);
                    break;
                case CLOSE_POSITION:
                    result = SyncTradeOrdersApiController.closePosition(apiCommand);
                    break;
                case ORDER_BOOK_REQUEST:
                    result = SyncTradeOrdersApiController.getOrderBook(apiCommand);
                    break;
                case MOVE_ORDER:
                    result = SyncTradeOrdersApiController.moveOrder(apiCommand);
                    break;
                case CANCEL_ORDER:
                    result = SyncTradeOrdersApiController.cancelOrder(apiCommand);
                    break;
                case ADD_USER:
                    result = SyncAdminApiAccountsController.createUser(apiCommand);
                    break;
                case REDUCE_ORDER:
                    result = SyncTradeOrdersApiController.reduceOrder(apiCommand);
                    break;
                case SUSPEND_USER:
                    result = SyncAdminApiAccountsController.suspendUser(apiCommand);
                    break;
                case RESUME_USER:
                    result = SyncAdminApiAccountsController.resumeUser(apiCommand);
                    break;
                case ADJUST_LEVERAGE:
                    result = SyncTradeOrdersApiController.adjustLeverage(apiCommand);
                    break;
                case ADJUST_POSITION_MODE:
                    result = SyncAdminApiAccountsController.adjustPositionMode(apiCommand);
                    break;
                case ADJUST_MARGIN:
                    result = SyncAdminApiAccountsController.adjustMargin(apiCommand);
                    break;
                case ADJUST_MARKPRICE:
                    result = SyncAdminApiSymbolsController.adjustMarkPrice(apiCommand);
                    break;
                case SETTLE_FUNDING_FEES:
                    result = SyncAdminApiSymbolsController.settleFundingFees(apiCommand);
                    break;
                case SETTLE_PNL:
                    result = SyncAdminApiSymbolsController.settlePNL(apiCommand);
                    break;
                case RESET_FEE:
                    result = SyncTradeMiscApiController.resetFee(apiCommand);
                    break;
                case NOP:
                    LOG.info("NOP Command received, no action taken.");
                    result = SyncNoOpApiController.handleNoOp();
                    break;
                default:
                    result = CompletableFuture.failedFuture(new IllegalArgumentException("Unsupported ApiCommand: " + commandCase));
            }
        } else {
            result = CompletableFuture.failedFuture(
                    new IllegalArgumentException("Unsupported grpcMessage: " + (grpcMessage == null ? "null" : grpcMessage.getClass().getName())));
        }
        return result;
    }

    private CompletableFuture<Supplier<byte[]>> processBinaryDataCommand(BinaryDataCommand binaryDataCommand) {
        CompletableFuture<Supplier<byte[]>> result;
        BinaryDataCommand.CommandCase commandCase = binaryDataCommand.getCommandCase();
        switch (commandCase) {
            case ADD_ACCOUNTS:
                result = SyncAdminApiAccountsController.batchAddAccounts(binaryDataCommand.getAddAccounts());
                break;
            case ADD_SYMBOLS:
                result = SyncAdminApiSymbolsController.batchAddSymbols(binaryDataCommand.getAddSymbols());
                break;
            case ADD_CURRENCIES:
                result = SyncAdminApiSymbolsController.batchAddCurrencies(binaryDataCommand.getAddCurrencies());
                break;
            default:
                result = CompletableFuture.failedFuture(new IllegalArgumentException("Unsupported BinaryDataCommand: " + commandCase));
        }
        return result;
    }

    @Override
    public boolean onSnapshotLoad(SnapshotReader reader) {
        if (isLeader()) {
            LOG.warn("Leader is not supposed to load snapshot");
            return false;
        }
        SnapshotHelper.setSnapshotPath(reader.getPath());
        Set<String> files = reader.listFiles();
        if (!snapshotHelper.checkSnapshotIntegrity(files)) {
            LOG.error(
                "Snapshot shard count mismatch! Update PerformanceConfiguration.DEFAULT config to match snapshot files or delete existing snapshot files.");
            return false;
        }
        long snapshotId = SnapshotHelper.getSnapshotId(files);
        ExchangeApi api = ExchangeApiInstance.exchangeApi();
        ApiRecoverState apiRecoverState = ApiRecoverState.builder().snapshotId(snapshotId).build();
        api.submitCommand(apiRecoverState);
        return true;
    }

    @Override
    public void onSnapshotSave(SnapshotWriter writer, Closure done) {
        String root = writer.getPath();
        // 触发exchange的快照
        long snapshotId = SnapshotHelper.genSnapshotId();
        ExchangeApi api = ExchangeApiInstance.exchangeApi();
        ApiPersistState apiPersist = ApiPersistState.builder().dumpId(snapshotId).build();
        api.submitCommand(apiPersist);
        // 保存快照
        for (String fileName : snapshotHelper.saveSnapshot(snapshotId, root)) {
            if (!writer.addFile(fileName)) {
                SnapshotHelper.cleanSnapshots(root, snapshotId);
                done.run(new Status(RaftError.EIO, "Fail to save snapshot"));
                return;
            }
        }
        done.run(Status.OK());
    }

    @Override
    public void onLeaderStart(long term) {
        this.leaderTerm.set(term);
        RoleChangeEventbus.INSTANCE.publish(RaftNode.NodeType.LEADER);
    }

    @Override
    public void onLeaderStop(Status status) {
        this.leaderTerm.set(-1L);
        RoleChangeEventbus.INSTANCE.publish(RaftNode.NodeType.FOLLOWER);
    }

    public boolean isLeader() {
        return this.leaderTerm.get() > 0;
    }
}
