package com.binance.raftexchange.server.raft;

import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;

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

    private final AtomicLong leaderTerm = new AtomicLong(-1L);
    private final SnapshotHelper snapshotHelper = new SnapshotHelper();

    @Override
    public void onApply(Iterator iter) {
        while (iter.hasNext()) {
            Closure done = iter.done();
            ReturnableClosure rc = (done instanceof ReturnableClosure) ? (ReturnableClosure) done : null;
            long startTime = (rc != null) ? System.nanoTime() : 0L;
            CompletableFuture<Supplier<byte[]>> result;
            try {
                GeneratedMessageV3 msg = (rc != null) ?
                        rc.message() : // Leader fast path: 已解析 protobuf
                        SerializeHelper.deserializeWithType(iter.getData()); // Follower path: 解析 ByteBuffer
                result = apply(msg);
            } catch (Throwable e) {
                LOG.error("Fail to apply", e);
                result = CompletableFuture.failedFuture(e);
            }
            if (done != null) {
                if (rc != null) {
                    rc.setResult(startTime, result);
                }
                done.run(Status.OK());
            }
            iter.next();
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
