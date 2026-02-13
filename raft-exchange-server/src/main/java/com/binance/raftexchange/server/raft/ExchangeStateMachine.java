package com.binance.raftexchange.server.raft;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.IntStream;

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

import java.util.function.Supplier;

public class ExchangeStateMachine extends StateMachineAdapter {
    private static final Logger LOG = LoggerFactory.getLogger(ExchangeStateMachine.class);
    private static final int PARALLEL_THRESHOLD = 128;

    private final AtomicLong leaderTerm = new AtomicLong(-1L);
    private final SnapshotHelper snapshotHelper = new SnapshotHelper();

    private ByteBuffer[] dataArr;
    private Closure[] closureArr;
    private GeneratedMessageV3[] msgArr;

    public ExchangeStateMachine(int applyBatch) {
        dataArr = new ByteBuffer[applyBatch];
        closureArr = new Closure[applyBatch];
        msgArr = new GeneratedMessageV3[applyBatch];
    }

    @Override
    public void onApply(Iterator iter) {
        // Phase 1: collect all entries into pre-allocated arrays
        int size = 0;
        while (iter.hasNext()) {
            if (size == dataArr.length) {
                // Extremely rare: grow once (1.5x), keep arrays in sync
                final int newCap = dataArr.length + (dataArr.length >> 1);
                dataArr = Arrays.copyOf(dataArr, newCap);
                closureArr = Arrays.copyOf(closureArr, newCap);
                msgArr = Arrays.copyOf(msgArr, newCap);
                LOG.warn("onApply batch size exceeded {}, grew to {}", size, newCap);
            }
            dataArr[size] = iter.getData();
            closureArr[size] = iter.done();
            size++;
            iter.next();
        }
        if (size == 0) {
            return;
        }

        // Phase 2: deserialize (parallel if batch is large enough)
        if (size >= PARALLEL_THRESHOLD) {
            IntStream.range(0, size).parallel().forEach(i -> msgArr[i] = deserialize(dataArr[i]));
        } else {
            for (int i = 0; i < size; i++) {
                msgArr[i] = deserialize(dataArr[i]);
            }
        }

        // Phase 3: sequential apply + setResult
        for (int i = 0; i < size; i++) {
            Closure closure = closureArr[i];
            GeneratedMessageV3 msg = msgArr[i];
            long startTime = System.nanoTime();
            CompletableFuture<Supplier<byte[]>> result = null;
            if (msg != null) {
                try {
                    result = apply(msg);
                } catch (Throwable e) {
                    LOG.error("Fail to apply", e);
                }
            }
            if (closure != null) {
                if (result != null && closure instanceof ReturnableClosure returnableClosure) {
                    returnableClosure.setResult(startTime, result);
                }
                closure.run(Status.OK());
            }
        }

        // Clear references to help GC
        Arrays.fill(dataArr, 0, size, null);
        Arrays.fill(closureArr, 0, size, null);
        Arrays.fill(msgArr, 0, size, null);
    }

    private GeneratedMessageV3 deserialize(ByteBuffer data) {
        try {
            return SerializeHelper.deserializeWithType(data);
        } catch (Exception e) {
            LOG.error("Fail to deserialize", e);
            return null;
        }
    }

    private CompletableFuture<Supplier<byte[]>> apply(GeneratedMessageV3 grpcMessage) {
        CompletableFuture<Supplier<byte[]>> result = null;
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
                    LOG.warn("Unsupported ApiCommand: {}", commandCase);
            }
        }
        return result;
    }

    private CompletableFuture<Supplier<byte[]>> processBinaryDataCommand(BinaryDataCommand binaryDataCommand) {
        CompletableFuture<Supplier<byte[]>> result = null;
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
                LOG.warn("Unsupported BinaryDataCommand: {}", commandCase);
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
