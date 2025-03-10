package com.binance.raftexchange.server.raft;

import com.alipay.sofa.jraft.Closure;
import com.alipay.sofa.jraft.Iterator;
import com.alipay.sofa.jraft.Status;
import com.alipay.sofa.jraft.core.StateMachineAdapter;
import com.binance.raftexchange.server.exchange.SyncNoOpApiController;
import com.binance.raftexchange.server.raft.RaftClusterContainer.ReturnableClosure;

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

public class ExchangeStateMachine extends StateMachineAdapter {

    private static final Logger LOG = LoggerFactory.getLogger(ExchangeStateMachine.class);

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
    public void onLeaderStart(long term) {
        RaftChangeEventbus.INSTANCE.publish(RaftNode.NodeType.LEADER);
    }

    @Override
    public void onLeaderStop(Status status) {
        RaftChangeEventbus.INSTANCE.publish(RaftNode.NodeType.FOLLOWER);
    }
}
