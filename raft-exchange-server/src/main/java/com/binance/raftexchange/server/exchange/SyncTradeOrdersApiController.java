package com.binance.raftexchange.server.exchange;

import com.binance.raftexchange.server.util.SerializeHelper;
import com.binance.raftexchange.stubs.request.ApiCancelOrder;
import com.binance.raftexchange.stubs.request.ApiMoveOrder;
import com.binance.raftexchange.stubs.request.ApiOrderBookRequest;
import com.binance.raftexchange.stubs.request.ApiPlaceOrder;
import com.binance.raftexchange.stubs.request.ApiReduceOrder;
import com.binance.raftexchange.stubs.response.CommandResult;

import java.util.concurrent.CompletableFuture;

public class SyncTradeOrdersApiController extends AbstractApiController {

    /**
     * 获取OrderBook
     */
    public static byte[] getOrderBook(ApiOrderBookRequest grpcApiOrderBookRequest) throws Exception {
        exchange.core2.core.common.api.ApiOrderBookRequest apiOrderBookRequest =
            exchange.core2.core.common.api.ApiOrderBookRequest.builder().symbol(grpcApiOrderBookRequest.getSymbol())
                .size(grpcApiOrderBookRequest.getSize()).build();

        return callExchange(apiOrderBookRequest);
    }

    public static CompletableFuture<CommandResult> getOrderBookAsync(ApiOrderBookRequest grpcApiOrderBookRequest) throws Exception {
        exchange.core2.core.common.api.ApiOrderBookRequest apiOrderBookRequest =
                exchange.core2.core.common.api.ApiOrderBookRequest.builder().symbol(grpcApiOrderBookRequest.getSymbol())
                        .size(grpcApiOrderBookRequest.getSize()).build();
        return callExchangeAsync(apiOrderBookRequest)
                .thenApply(SerializeHelper::orderCommandToResult);
    }

    /**
     * 下单
     */
    public static byte[] placeOrder(ApiPlaceOrder grpcApiPlaceOrder) throws Exception {
        exchange.core2.core.common.api.ApiPlaceOrder apiPlaceOrder = exchange.core2.core.common.api.ApiPlaceOrder
            .builder().price(grpcApiPlaceOrder.getPrice()).size(grpcApiPlaceOrder.getSize())
            .orderId(grpcApiPlaceOrder.getOrderId())
            .action(exchange.core2.core.common.OrderAction.of((byte)grpcApiPlaceOrder.getAction().getNumber()))
            .orderType(exchange.core2.core.common.OrderType.of((byte)grpcApiPlaceOrder.getOrderType().getNumber()))
            .uid(grpcApiPlaceOrder.getUid()).symbol(grpcApiPlaceOrder.getSymbol())
            .userCookie(grpcApiPlaceOrder.getUserCookie()).reservePrice(grpcApiPlaceOrder.getReservePrice()).build();

        return callExchange(apiPlaceOrder);
    }

    /**
     * 修改订单
     */
    public static byte[] moveOrder(ApiMoveOrder grpcApiMoveOrder) throws Exception {
        exchange.core2.core.common.api.ApiMoveOrder apiMoveOrder = exchange.core2.core.common.api.ApiMoveOrder.builder()
            .orderId(grpcApiMoveOrder.getOrderId()).newPrice(grpcApiMoveOrder.getNewPrice())
            .uid(grpcApiMoveOrder.getUid()).symbol(grpcApiMoveOrder.getSymbol()).build();

        return callExchange(apiMoveOrder);
    }

    /**
     * 撤单
     */
    public static byte[] cancelOrder(ApiCancelOrder grpcApiCancelOrder) throws Exception {
        exchange.core2.core.common.api.ApiCancelOrder apiCancelOrder =
            exchange.core2.core.common.api.ApiCancelOrder.builder().orderId(grpcApiCancelOrder.getOrderId())
                .uid(grpcApiCancelOrder.getUid()).symbol(grpcApiCancelOrder.getSymbol()).build();

        return callExchange(apiCancelOrder);
    }

    /**
     * 改单
     */
    public static byte[] reduceOrder(ApiReduceOrder grpcApiReduceOrder) throws Exception {
        exchange.core2.core.common.api.ApiReduceOrder apiReduceOrder = exchange.core2.core.common.api.ApiReduceOrder
            .builder().orderId(grpcApiReduceOrder.getOrderId()).uid(grpcApiReduceOrder.getUid())
            .symbol(grpcApiReduceOrder.getSymbol()).reduceSize(grpcApiReduceOrder.getReduceSize()).build();

        return callExchange(apiReduceOrder);
    }
}
