package com.binance.raftexchange.server.raft;

import com.alipay.sofa.jraft.Closure;
import com.alipay.sofa.jraft.Iterator;
import com.alipay.sofa.jraft.Status;
import com.alipay.sofa.jraft.core.StateMachineAdapter;
import com.alipay.sofa.jraft.error.RaftError;
import com.alipay.sofa.jraft.storage.snapshot.SnapshotReader;
import com.alipay.sofa.jraft.storage.snapshot.SnapshotWriter;
import com.binance.raftexchange.server.exchange.ExchangeApiInstance;
import com.binance.raftexchange.server.exchange.SyncNoOpApiController;
import com.binance.raftexchange.server.raft.RaftClusterContainer.ReturnableClosure;
import exchange.core2.core.ExchangeApi;
import exchange.core2.core.common.api.ApiPersistState;
import exchange.core2.core.common.api.ApiRecoverState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.binance.raftexchange.server.exchange.SyncAdminApiAccountsController;
import com.binance.raftexchange.server.exchange.SyncAdminApiSymbolsController;
import com.binance.raftexchange.server.exchange.SyncTradeOrdersApiController;
import com.binance.raftexchange.server.exchange.events.RaftChangeEventbus;
import com.binance.raftexchange.server.util.SerializeHelper;
import com.binance.raftexchange.stubs.request.ApiBinaryDataCommand;
import com.binance.raftexchange.stubs.request.ApiCommand;
import com.binance.raftexchange.stubs.request.BinaryDataCommand;
import com.google.protobuf.GeneratedMessageV3;

import java.nio.ByteBuffer;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

public class ExchangeStateMachine extends StateMachineAdapter {

    private static final Logger LOG = LoggerFactory.getLogger(ExchangeStateMachine.class);

    private final AtomicLong leaderTerm = new AtomicLong(-1L);
    private final SnapshotHelper snapshotHelper = new SnapshotHelper();

    @Override
    public void onApply(Iterator iter) {
        while (iter.hasNext()) {
            ByteBuffer data = iter.getData();
            Closure closure = iter.done();
            byte[] result = null;
            try {
                result = apply(data.array(), data.arrayOffset() + data.position(), data.remaining());
            } catch (Exception e) {
                LOG.error("Fail to apply", e);
            }
            if (closure != null) {
                if (closure instanceof ReturnableClosure) {
                    ((ReturnableClosure)closure).setResult(result);
                }
                closure.run(Status.OK());
            }
            iter.next();
        }
    }

    private byte[] apply(byte[] data, int offset, int length) throws Exception {
        GeneratedMessageV3 grpcMessage = SerializeHelper.deserializeWithType(data, offset, length);
        byte[] result = null;
        if (grpcMessage instanceof ApiCommand) {
            ApiCommand.CommandCase commandCase = ((ApiCommand)grpcMessage).getCommandCase();
            switch (commandCase) {
                case BINARY_DATA:
                    ApiBinaryDataCommand apiBinaryDataCommand = ((ApiCommand)grpcMessage).getBinaryData();
                    result = processBinaryDataCommand(apiBinaryDataCommand.getData());
                    break;
                case PLACE_ORDER:
                    result = SyncTradeOrdersApiController.placeOrder(((ApiCommand)grpcMessage).getPlaceOrder());
                    break;
                case ADJUST_BALANCE:
                    result = SyncAdminApiAccountsController.adjustBalance(((ApiCommand)grpcMessage).getAdjustBalance());
                    break;
                case ORDER_BOOK_REQUEST:
                    result = SyncTradeOrdersApiController.getOrderBook(((ApiCommand)grpcMessage).getOrderBookRequest());
                    break;
                case MOVE_ORDER:
                    result = SyncTradeOrdersApiController.moveOrder(((ApiCommand)grpcMessage).getMoveOrder());
                    break;
                case CANCEL_ORDER:
                    result = SyncTradeOrdersApiController.cancelOrder(((ApiCommand)grpcMessage).getCancelOrder());
                    break;
                case ADD_USER:
                    result = SyncAdminApiAccountsController.createUser(((ApiCommand)grpcMessage).getAddUser());
                    break;
                case REDUCE_ORDER:
                    result = SyncTradeOrdersApiController.reduceOrder(((ApiCommand)grpcMessage).getReduceOrder());
                    break;
                case SUSPEND_USER:
                    result = SyncAdminApiAccountsController.suspendUser(((ApiCommand)grpcMessage).getSuspendUser());
                    break;
                case RESUME_USER:
                    result = SyncAdminApiAccountsController.resumeUser(((ApiCommand)grpcMessage).getResumeUser());
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

    private byte[] processBinaryDataCommand(BinaryDataCommand binaryDataCommand) throws Exception {
        byte[] result = null;
        BinaryDataCommand.CommandCase commandCase = binaryDataCommand.getCommandCase();
        switch (commandCase) {
            case ADD_ACCOUNTS:
                result = SyncAdminApiAccountsController.batchAddAccounts(binaryDataCommand.getAddAccounts());
                break;
            case ADD_SYMBOLS:
                result = SyncAdminApiSymbolsController.batchAddSymbols(binaryDataCommand.getAddSymbols());
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
            LOG.error("Snapshot shard count mismatch! Update PerformanceConfiguration.DEFAULT config to match snapshot files or delete existing snapshot files.");
            return false;
        }
        try {
            // 触发exchange的恢复快照
            long snapshotId = SnapshotHelper.getSnapshotId(files);
            ExchangeApi api = ExchangeApiInstance.exchangeApi();
            ApiRecoverState apiRecoverState = ApiRecoverState.builder().snapshotId(snapshotId).build();
            api.submitCommand(apiRecoverState);
        } catch (Exception e) {
            LOG.error("Failed to load snapshot", e);
            return false;
        }
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
        try {
            for (String fileName : snapshotHelper.saveSnapshot(snapshotId, root)) {
                if (!writer.addFile(fileName)) {
                    throw new RuntimeException("Fail to add file[" + fileName + "] to writer");
                }
            }
        } catch (Exception e) {
            LOG.error("Fail to save snapshot", e);
            SnapshotHelper.cleanSnapshots(root, snapshotId);
            done.run(new Status(RaftError.EIO, "Fail to save snapshot"));
        }
        done.run(Status.OK());
    }


    @Override
    public void onLeaderStart(long term) {
        this.leaderTerm.set(term);
        RaftChangeEventbus.INSTANCE.publish(RaftNode.NodeType.LEADER);
    }

    @Override
    public void onLeaderStop(Status status) {
        this.leaderTerm.set(-1L);
        RaftChangeEventbus.INSTANCE.publish(RaftNode.NodeType.FOLLOWER);
    }

    public boolean isLeader() {
        return this.leaderTerm.get() > 0;
    }
}
