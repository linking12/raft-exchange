package com.binance.raftexchange.server.exchange;

import com.binance.raftexchange.stubs.api.ApiCancelOrder;
import com.binance.raftexchange.stubs.api.ApiMoveOrder;
import com.binance.raftexchange.stubs.api.ApiOrderBookRequest;
import com.binance.raftexchange.stubs.api.ApiPlaceOrder;

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

}
