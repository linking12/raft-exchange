package com.binance.raftexchange.server.raft;

import java.io.DataInput;
import java.io.DataOutput;

import com.binance.raftexchange.server.exchange.SyncNoOpApiController;
import org.jgroups.raft.StateMachine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.binance.raftexchange.server.exchange.SyncAdminApiAccountsController;
import com.binance.raftexchange.server.exchange.SyncAdminApiSymbolsController;
import com.binance.raftexchange.server.exchange.SyncTradeOrdersApiController;
import com.binance.raftexchange.server.util.SerializeHelper;
import com.binance.raftexchange.stubs.request.ApiBinaryDataCommand;
import com.binance.raftexchange.stubs.request.ApiCommand;
import com.binance.raftexchange.stubs.request.BinaryDataCommand;
import com.google.protobuf.GeneratedMessageV3;

public class ExchangeStateMachine implements StateMachine {

    private static final Logger LOG = LoggerFactory.getLogger(ExchangeStateMachine.class);

    @Override
    public byte[] apply(byte[] data, int offset, int length, boolean serialize_response) throws Exception {
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
        return serialize_response ? result : null;
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
    public void readContentFrom(DataInput in) throws Exception {

    }

    @Override
    public void writeContentTo(DataOutput out) throws Exception {

    }

}
