package com.binance.raftexchange.server.exchange;

import com.binance.raftexchange.stubs.request.ApiAdjustLeverage;
import com.binance.raftexchange.stubs.request.ApiAdjustMargin;
import com.binance.raftexchange.stubs.request.ApiAdjustMarkPrice;
import com.binance.raftexchange.stubs.request.ApiAdjustPositionMode;
import com.binance.raftexchange.stubs.request.ApiAdjustUserBalance;
import com.binance.raftexchange.stubs.request.ApiAddUser;
import com.binance.raftexchange.stubs.request.ApiCancelOrder;
import com.binance.raftexchange.stubs.request.ApiClosePosition;
import com.binance.raftexchange.stubs.request.ApiCommand;
import com.binance.raftexchange.stubs.request.ApiMoveOrder;
import com.binance.raftexchange.stubs.request.ApiOrderBookRequest;
import com.binance.raftexchange.stubs.request.ApiPlaceOrder;
import com.binance.raftexchange.stubs.request.ApiReduceOrder;
import com.binance.raftexchange.stubs.request.ApiResumeUser;
import com.binance.raftexchange.stubs.request.ApiSettleFundingFees;
import com.binance.raftexchange.stubs.request.ApiSettlePNL;
import com.binance.raftexchange.stubs.request.ApiSuspendUser;
import com.google.protobuf.GeneratedMessageV3;

import exchange.core2.core.common.MarginMode;
import exchange.core2.core.common.OrderAction;
import exchange.core2.core.common.OrderType;
import exchange.core2.core.common.PositionMode;

/**
 * Converts gRPC protobuf ApiCommand to exchange-core ApiCommand DTO.
 * Returns null for command types that cannot be batch-published (e.g. BinaryDataCommand).
 *
 * This class only does DTO conversion — no callExchange, no side effects.
 */
public final class GrpcCommandConverter {

    private GrpcCommandConverter() {}

    /**
     * @return exchange-core ApiCommand, or null if not batchable
     */
    public static exchange.core2.core.common.api.ApiCommand convert(GeneratedMessageV3 grpcMessage) {
        if (!(grpcMessage instanceof ApiCommand apiCommand)) {
            return null;
        }
        return switch (apiCommand.getCommandCase()) {
            case PLACE_ORDER -> convertPlaceOrder(apiCommand);
            case MOVE_ORDER -> convertMoveOrder(apiCommand);
            case CANCEL_ORDER -> convertCancelOrder(apiCommand);
            case REDUCE_ORDER -> convertReduceOrder(apiCommand);
            case CLOSE_POSITION -> convertClosePosition(apiCommand);
            case ORDER_BOOK_REQUEST -> convertOrderBookRequest(apiCommand);
            case ADJUST_LEVERAGE -> convertAdjustLeverage(apiCommand);
            case ADJUST_BALANCE -> convertAdjustBalance(apiCommand);
            case ADD_USER -> convertAddUser(apiCommand);
            case SUSPEND_USER -> convertSuspendUser(apiCommand);
            case RESUME_USER -> convertResumeUser(apiCommand);
            case ADJUST_POSITION_MODE -> convertAdjustPositionMode(apiCommand);
            case ADJUST_MARGIN -> convertAdjustMargin(apiCommand);
            case ADJUST_MARKPRICE -> convertAdjustMarkPrice(apiCommand);
            case SETTLE_FUNDING_FEES -> convertSettleFundingFees(apiCommand);
            case SETTLE_PNL -> convertSettlePNL(apiCommand);
            case RESET_FEE -> convertResetFee(apiCommand);
            case NOP -> convertNop(apiCommand);
            default -> null;
        };
    }

    private static exchange.core2.core.common.api.ApiCommand convertPlaceOrder(ApiCommand apiCommand) {
        ApiPlaceOrder g = apiCommand.getPlaceOrder();
        var cmd = exchange.core2.core.common.api.ApiPlaceOrder.builder()
                .price(g.getPrice()).size(g.getSize()).orderId(g.getOrderId())
                .action(OrderAction.of((byte) g.getAction().getNumber()))
                .orderType(OrderType.of((byte) g.getOrderType().getNumber()))
                .uid(g.getUid()).symbol(g.getSymbol()).userCookie(g.getUserCookie())
                .leverage(g.getLeverage())
                .marginMode(MarginMode.values()[g.getMarginMode().getNumber()])
                .reservePrice(g.getReservePrice()).reduceOnly(g.getReduceOnly())
                .build();
        cmd.updateTimestamp(apiCommand.getTimestamp());
        return cmd;
    }

    private static exchange.core2.core.common.api.ApiCommand convertMoveOrder(ApiCommand apiCommand) {
        ApiMoveOrder g = apiCommand.getMoveOrder();
        var cmd = exchange.core2.core.common.api.ApiMoveOrder.builder()
                .orderId(g.getOrderId()).newPrice(g.getNewPrice())
                .uid(g.getUid()).symbol(g.getSymbol())
                .build();
        cmd.updateTimestamp(apiCommand.getTimestamp());
        return cmd;
    }

    private static exchange.core2.core.common.api.ApiCommand convertCancelOrder(ApiCommand apiCommand) {
        ApiCancelOrder g = apiCommand.getCancelOrder();
        var cmd = exchange.core2.core.common.api.ApiCancelOrder.builder()
                .orderId(g.getOrderId()).uid(g.getUid()).symbol(g.getSymbol())
                .build();
        cmd.updateTimestamp(apiCommand.getTimestamp());
        return cmd;
    }

    private static exchange.core2.core.common.api.ApiCommand convertReduceOrder(ApiCommand apiCommand) {
        ApiReduceOrder g = apiCommand.getReduceOrder();
        var cmd = exchange.core2.core.common.api.ApiReduceOrder.builder()
                .orderId(g.getOrderId()).uid(g.getUid()).symbol(g.getSymbol())
                .reduceSize(g.getReduceSize())
                .build();
        cmd.updateTimestamp(apiCommand.getTimestamp());
        return cmd;
    }

    private static exchange.core2.core.common.api.ApiCommand convertClosePosition(ApiCommand apiCommand) {
        ApiClosePosition g = apiCommand.getClosePosition();
        var cmd = exchange.core2.core.common.api.ApiClosePosition.builder()
                .price(g.getPrice()).size(g.getSize()).orderId(g.getOrderId())
                .action(OrderAction.of((byte) g.getAction().getNumber()))
                .uid(g.getUid()).symbol(g.getSymbol())
                .build();
        cmd.updateTimestamp(apiCommand.getTimestamp());
        return cmd;
    }

    private static exchange.core2.core.common.api.ApiCommand convertOrderBookRequest(ApiCommand apiCommand) {
        ApiOrderBookRequest g = apiCommand.getOrderBookRequest();
        var cmd = exchange.core2.core.common.api.ApiOrderBookRequest.builder()
                .symbol(g.getSymbol()).size(g.getSize())
                .build();
        cmd.updateTimestamp(apiCommand.getTimestamp());
        return cmd;
    }

    private static exchange.core2.core.common.api.ApiCommand convertAdjustLeverage(ApiCommand apiCommand) {
        ApiAdjustLeverage g = apiCommand.getAdjustLeverage();
        var cmd = exchange.core2.core.common.api.ApiAdjustLeverage.builder()
                .uid(g.getUid()).symbol(g.getSymbol()).leverage(g.getLeverage())
                .build();
        cmd.updateTimestamp(apiCommand.getTimestamp());
        return cmd;
    }

    private static exchange.core2.core.common.api.ApiCommand convertAdjustBalance(ApiCommand apiCommand) {
        ApiAdjustUserBalance g = apiCommand.getAdjustBalance();
        var cmd = exchange.core2.core.common.api.ApiAdjustUserBalance.builder()
                .uid(g.getUid()).currency(g.getCurrency())
                .amount(g.getAmount()).transactionId(g.getTransactionId())
                .build();
        cmd.updateTimestamp(apiCommand.getTimestamp());
        return cmd;
    }

    private static exchange.core2.core.common.api.ApiCommand convertAddUser(ApiCommand apiCommand) {
        ApiAddUser g = apiCommand.getAddUser();
        var cmd = exchange.core2.core.common.api.ApiAddUser.builder().uid(g.getUid()).build();
        cmd.updateTimestamp(apiCommand.getTimestamp());
        return cmd;
    }

    private static exchange.core2.core.common.api.ApiCommand convertSuspendUser(ApiCommand apiCommand) {
        ApiSuspendUser g = apiCommand.getSuspendUser();
        var cmd = exchange.core2.core.common.api.ApiSuspendUser.builder().uid(g.getUid()).build();
        cmd.updateTimestamp(apiCommand.getTimestamp());
        return cmd;
    }

    private static exchange.core2.core.common.api.ApiCommand convertResumeUser(ApiCommand apiCommand) {
        ApiResumeUser g = apiCommand.getResumeUser();
        var cmd = exchange.core2.core.common.api.ApiResumeUser.builder().uid(g.getUid()).build();
        cmd.updateTimestamp(apiCommand.getTimestamp());
        return cmd;
    }

    private static exchange.core2.core.common.api.ApiCommand convertAdjustPositionMode(ApiCommand apiCommand) {
        ApiAdjustPositionMode g = apiCommand.getAdjustPositionMode();
        var cmd = exchange.core2.core.common.api.ApiAdjustPositionMode.builder()
                .uid(g.getUid())
                .positionMode(PositionMode.values()[g.getPositionMode().getNumber()])
                .build();
        cmd.updateTimestamp(apiCommand.getTimestamp());
        return cmd;
    }

    private static exchange.core2.core.common.api.ApiCommand convertAdjustMargin(ApiCommand apiCommand) {
        ApiAdjustMargin g = apiCommand.getAdjustMargin();
        var cmd = exchange.core2.core.common.api.ApiAdjustMargin.builder()
                .transactionId(g.getTransactionId()).uid(g.getUid())
                .symbol(g.getSymbol()).currency(g.getCurrency())
                .amount(g.getAmount())
                .marginMode(MarginMode.values()[g.getMarginMode().getNumber()])
                .build();
        cmd.updateTimestamp(apiCommand.getTimestamp());
        return cmd;
    }

    private static exchange.core2.core.common.api.ApiCommand convertAdjustMarkPrice(ApiCommand apiCommand) {
        ApiAdjustMarkPrice g = apiCommand.getAdjustMarkprice();
        var cmd = exchange.core2.core.common.api.ApiAdjustMarkPrice.builder()
                .transactionId(g.getTransactionId()).symbol(g.getSymbol())
                .markPrice(g.getMarkPrice())
                .build();
        cmd.updateTimestamp(apiCommand.getTimestamp());
        return cmd;
    }

    private static exchange.core2.core.common.api.ApiCommand convertSettleFundingFees(ApiCommand apiCommand) {
        ApiSettleFundingFees g = apiCommand.getSettleFundingFees();
        var cmd = exchange.core2.core.common.api.ApiSettleFundingFees.builder()
                .transactionId(g.getTransactionId()).symbol(g.getSymbol())
                .action(OrderAction.of((byte) g.getAction().getNumber()))
                .fundingRate(g.getFundingRate()).rateScaleK(g.getRateScaleK())
                .build();
        cmd.updateTimestamp(apiCommand.getTimestamp());
        return cmd;
    }

    private static exchange.core2.core.common.api.ApiCommand convertSettlePNL(ApiCommand apiCommand) {
        ApiSettlePNL g = apiCommand.getSettlePnl();
        var cmd = exchange.core2.core.common.api.ApiSettlePNL.builder()
                .transactionId(g.getTransactionId()).symbol(g.getSymbol())
                .settlePrice(g.getSettlePrice())
                .build();
        cmd.updateTimestamp(apiCommand.getTimestamp());
        return cmd;
    }

    private static exchange.core2.core.common.api.ApiCommand convertResetFee(ApiCommand apiCommand) {
        var cmd = exchange.core2.core.common.api.ApiResetFee.builder().build();
        cmd.updateTimestamp(apiCommand.getTimestamp());
        return cmd;
    }

    private static exchange.core2.core.common.api.ApiCommand convertNop(ApiCommand apiCommand) {
        var cmd = exchange.core2.core.common.api.ApiNop.builder().build();
        cmd.updateTimestamp(apiCommand.getTimestamp());
        return cmd;
    }
}
