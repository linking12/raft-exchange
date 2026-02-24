package com.binance.raftexchange.server.exchange;

import com.binance.raftexchange.server.util.SerializeHelper;
import com.binance.raftexchange.stubs.request.ApiAdjustLeverage;
import com.binance.raftexchange.stubs.request.ApiCancelOrder;
import com.binance.raftexchange.stubs.request.ApiClosePosition;
import com.binance.raftexchange.stubs.request.ApiCommand;
import com.binance.raftexchange.stubs.request.ApiMoveOrder;
import com.binance.raftexchange.stubs.request.ApiOrderBookRequest;
import com.binance.raftexchange.stubs.request.ApiPlaceOrder;
import com.binance.raftexchange.stubs.request.ApiReduceOrder;
import com.binance.raftexchange.stubs.response.CommandResult;

import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

public class SyncTradeOrdersApiController extends AbstractApiController {

    /**
     * 获取OrderBook
     */
    public static CompletableFuture<Supplier<byte[]>> getOrderBook(ApiCommand apiCommand) {
        return callExchange(convertOrderBookRequest(apiCommand));
    }

    public static exchange.core2.core.common.api.ApiOrderBookRequest convertOrderBookRequest(ApiCommand apiCommand) {
        ApiOrderBookRequest grpcApiOrderBookRequest = apiCommand.getOrderBookRequest();
        exchange.core2.core.common.api.ApiOrderBookRequest apiOrderBookRequest = exchange.core2.core.common.api.ApiOrderBookRequest.builder()
            .symbol(grpcApiOrderBookRequest.getSymbol()).size(grpcApiOrderBookRequest.getSize()).build();
        apiOrderBookRequest.updateTimestamp(apiCommand.getTimestamp());
        return apiOrderBookRequest;
    }

    public static CompletableFuture<CommandResult> getOrderBookAsync(ApiOrderBookRequest grpcApiOrderBookRequest) {
        exchange.core2.core.common.api.ApiOrderBookRequest apiOrderBookRequest =
                exchange.core2.core.common.api.ApiOrderBookRequest.builder().symbol(grpcApiOrderBookRequest.getSymbol())
                        .size(grpcApiOrderBookRequest.getSize()).build();
        return callExchangeAsync(apiOrderBookRequest)
                .thenApply(SerializeHelper::orderCommandToResult);
    }

    /**
     * 下单
     */
    public static CompletableFuture<Supplier<byte[]>> placeOrder(ApiCommand apiCommand) {
        return callExchange(convertPlaceOrder(apiCommand));
    }

    public static exchange.core2.core.common.api.ApiPlaceOrder convertPlaceOrder(ApiCommand apiCommand) {
        ApiPlaceOrder grpcApiPlaceOrder = apiCommand.getPlaceOrder();
        exchange.core2.core.common.api.ApiPlaceOrder apiPlaceOrder =
            exchange.core2.core.common.api.ApiPlaceOrder.builder().price(grpcApiPlaceOrder.getPrice()).size(grpcApiPlaceOrder.getSize())
                .orderId(grpcApiPlaceOrder.getOrderId()).action(exchange.core2.core.common.OrderAction.of((byte)grpcApiPlaceOrder.getAction().getNumber()))
                .orderType(exchange.core2.core.common.OrderType.of((byte)grpcApiPlaceOrder.getOrderType().getNumber())).uid(grpcApiPlaceOrder.getUid())
                .symbol(grpcApiPlaceOrder.getSymbol()).userCookie(grpcApiPlaceOrder.getUserCookie()).leverage(grpcApiPlaceOrder.getLeverage())
                .marginMode(exchange.core2.core.common.MarginMode.of((byte)grpcApiPlaceOrder.getMarginMode().getNumber()))
                .reservePrice(grpcApiPlaceOrder.getReservePrice()).reduceOnly(grpcApiPlaceOrder.getReduceOnly()).build();
        apiPlaceOrder.updateTimestamp(apiCommand.getTimestamp());
        return apiPlaceOrder;
    }

    /**
     * 修改订单
     */
    public static CompletableFuture<Supplier<byte[]>> moveOrder(ApiCommand apiCommand) {
        return callExchange(convertMoveOrder(apiCommand));
    }

    public static exchange.core2.core.common.api.ApiMoveOrder convertMoveOrder(ApiCommand apiCommand) {
        ApiMoveOrder grpcApiMoveOrder = apiCommand.getMoveOrder();
        exchange.core2.core.common.api.ApiMoveOrder apiMoveOrder = exchange.core2.core.common.api.ApiMoveOrder.builder().orderId(grpcApiMoveOrder.getOrderId())
            .newPrice(grpcApiMoveOrder.getNewPrice()).uid(grpcApiMoveOrder.getUid()).symbol(grpcApiMoveOrder.getSymbol()).build();
        apiMoveOrder.updateTimestamp(apiCommand.getTimestamp());
        return apiMoveOrder;
    }

    /**
     * 撤单
     */
    public static CompletableFuture<Supplier<byte[]>> cancelOrder(ApiCommand apiCommand) {
        return callExchange(convertCancelOrder(apiCommand));
    }

    public static exchange.core2.core.common.api.ApiCancelOrder convertCancelOrder(ApiCommand apiCommand) {
        ApiCancelOrder grpcApiCancelOrder = apiCommand.getCancelOrder();
        exchange.core2.core.common.api.ApiCancelOrder apiCancelOrder = exchange.core2.core.common.api.ApiCancelOrder.builder()
            .orderId(grpcApiCancelOrder.getOrderId()).uid(grpcApiCancelOrder.getUid()).symbol(grpcApiCancelOrder.getSymbol()).build();
        apiCancelOrder.updateTimestamp(apiCommand.getTimestamp());
        return apiCancelOrder;
    }

    /**
     * 改单
     */
    public static CompletableFuture<Supplier<byte[]>> reduceOrder(ApiCommand apiCommand) {
        return callExchange(convertReduceOrder(apiCommand));
    }

    public static exchange.core2.core.common.api.ApiReduceOrder convertReduceOrder(ApiCommand apiCommand) {
        ApiReduceOrder grpcApiReduceOrder = apiCommand.getReduceOrder();
        exchange.core2.core.common.api.ApiReduceOrder apiReduceOrder =
            exchange.core2.core.common.api.ApiReduceOrder.builder().orderId(grpcApiReduceOrder.getOrderId()).uid(grpcApiReduceOrder.getUid())
                .symbol(grpcApiReduceOrder.getSymbol()).reduceSize(grpcApiReduceOrder.getReduceSize()).build();
        apiReduceOrder.updateTimestamp(apiCommand.getTimestamp());
        return apiReduceOrder;
    }

    /**
     * 调整杠杆
     */
    public static CompletableFuture<Supplier<byte[]>> adjustLeverage(ApiCommand apiCommand) {
        return callExchange(convertAdjustLeverage(apiCommand));
    }

    public static exchange.core2.core.common.api.ApiAdjustLeverage convertAdjustLeverage(ApiCommand apiCommand) {
        ApiAdjustLeverage grpcApiAdjustLeverage = apiCommand.getAdjustLeverage();
        exchange.core2.core.common.api.ApiAdjustLeverage apiAdjustLeverage =
            exchange.core2.core.common.api.ApiAdjustLeverage.builder().uid(grpcApiAdjustLeverage.getUid())
                .symbol(grpcApiAdjustLeverage.getSymbol()).leverage(grpcApiAdjustLeverage.getLeverage()).build();
        apiAdjustLeverage.updateTimestamp(apiCommand.getTimestamp());
        return apiAdjustLeverage;
    }

    /**
     * 关仓
     */
    public static CompletableFuture<Supplier<byte[]>> closePosition(ApiCommand apiCommand) {
        return callExchange(convertClosePosition(apiCommand));
    }

    public static exchange.core2.core.common.api.ApiClosePosition convertClosePosition(ApiCommand apiCommand) {
        ApiClosePosition grpcApiClosePosition = apiCommand.getClosePosition();
        exchange.core2.core.common.api.ApiClosePosition apiClosePosition =
                exchange.core2.core.common.api.ApiClosePosition.builder().price(grpcApiClosePosition.getPrice()).size(grpcApiClosePosition.getSize())
                        .orderId(grpcApiClosePosition.getOrderId()).action(exchange.core2.core.common.OrderAction.of((byte) grpcApiClosePosition.getAction().getNumber()))
                        .uid(grpcApiClosePosition.getUid()).symbol(grpcApiClosePosition.getSymbol()).build();
        apiClosePosition.updateTimestamp(apiCommand.getTimestamp());
        return apiClosePosition;
    }
}
