package com.binance.raftexchange.client.sdk;

import com.binance.raftexchange.stubs.CoreCurrencySpecification;
import com.binance.raftexchange.stubs.CoreSymbolSpecification;
import com.binance.raftexchange.stubs.MarginMode;
import com.binance.raftexchange.stubs.OrderAction;
import com.binance.raftexchange.stubs.PositionDirection;
import com.binance.raftexchange.stubs.report.Order;
import com.binance.raftexchange.stubs.report.Position;
import com.binance.raftexchange.stubs.report.QueryExecutionStatus;
import com.binance.raftexchange.stubs.report.SingleUserReportResult;
import com.binance.raftexchange.stubs.report.UserStatus;
import lombok.Getter;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static com.binance.raftexchange.client.sdk.ExchangeSdkHelper.longToDouble;

@Getter
public class SingleUserReportResultView {

    private final long userId;
    private final UserStatus userStatus;
    private final Map<Integer, Double> accounts;
    private final Map<Integer, List<PositionView>> positions;
    private final Map<Integer, List<OrderView>> orders;
    private final QueryExecutionStatus queryExecutionStatus;

    private SingleUserReportResultView(long userId, UserStatus userStatus, QueryExecutionStatus queryExecutionStatus) {
        this.userId = userId;
        this.userStatus = userStatus;
        this.accounts = new HashMap<>();
        this.positions = new HashMap<>();
        this.orders = new HashMap<>();
        this.queryExecutionStatus = queryExecutionStatus;
    }


    public static SingleUserReportResultView build(SingleUserReportResult reportResult, ExchangeMetadataManager metadataManager) {
        SingleUserReportResultView view = new SingleUserReportResultView(reportResult.getUserId(), reportResult.getUserStatus(), reportResult.getQueryExecutionStatus());

        reportResult.getAccountsMap().forEach((currency, amount) -> {
            CoreCurrencySpecification currencySpec = metadataManager.getCurrencySpec(currency);
            long currencyScaleK = (long) Math.pow(10, currencySpec.getDigit());
            view.accounts.put(currency, longToDouble(amount, currencyScaleK));
        });

        reportResult.getPositionsMap().forEach((symbol, positions) -> {
            CoreSymbolSpecification spec = metadataManager.getSymbolSpec(symbol);
            List<PositionView> positionViews = positions.getPositionsList().stream().map(pos -> toPositionView(pos, spec)).collect(Collectors.toList());
            view.positions.put(symbol, positionViews);
        });

        reportResult.getOrdersMap().forEach((symbol, orders) -> {
            CoreSymbolSpecification spec = metadataManager.getSymbolSpec(symbol);
            List<OrderView> orderViews = orders.getOrdersList().stream().map(order -> toOrderView(order, spec)).collect(Collectors.toList());
            view.orders.put(symbol, orderViews);
        });
        return view;
    }

    private static OrderView toOrderView(Order order, CoreSymbolSpecification spec) {
        long sizeScale = spec.getBaseScaleK();
        long priceScale = spec.getQuoteScaleK();
        OrderView orderView = new OrderView();
        orderView.orderId = order.getOrderId();
        orderView.price = longToDouble(order.getPrice(), priceScale);
        orderView.size = longToDouble(order.getSize(), sizeScale);
        orderView.filled = longToDouble(order.getFilled(), sizeScale);
        orderView.reserveBidPrice = longToDouble(order.getReserveBidPrice(), priceScale);
        orderView.action = order.getAction();
        orderView.uid = order.getUid();
        orderView.timestamp = order.getTimestamp();
        return orderView;
    }

    private static PositionView toPositionView(Position pos, CoreSymbolSpecification spec) {
        long sizeScale = spec.getBaseScaleK();
        long priceScale = spec.getQuoteScaleK();
        long sizePriceScale = sizeScale * priceScale;
        long maintenanceMarginScale = spec.getMaintenanceMarginScaleK();
        PositionView view = new PositionView();
        view.quoteCurrency = pos.getQuoteCurrency();
        view.direction = pos.getDirection();
        view.openVolume = pos.getOpenVolume();
        view.openInitMarginSum = longToDouble(pos.getOpenInitMarginSum(), sizePriceScale);
        view.openPriceSum = longToDouble(pos.getOpenPriceSum(), sizePriceScale);
        view.profit = longToDouble(pos.getProfit(), sizePriceScale);
        view.pendingSellSize = longToDouble(pos.getPendingSellSize(), sizeScale);
        view.pendingBuySize = longToDouble(pos.getPendingBuySize(), sizeScale);
        view.pendingSellAvgPrice = longToDouble(pos.getPendingSellAvgPrice(), priceScale);
        view.pendingBuyAvgPrice = longToDouble(pos.getPendingBuyAvgPrice(), priceScale);
        view.leverage = pos.getLeverage();
        view.marginMode = pos.getMarginMode();
        view.extraMargin = longToDouble(pos.getExtraMargin(), priceScale);
        view.unrealizedProfit = longToDouble(pos.getUnrealizedProfit(), sizePriceScale);
        view.liquidationPrice = longToDouble(pos.getLiquidationPrice(), priceScale);
        view.marginRatio = longToDouble(pos.getMarginRatioScaleK(), maintenanceMarginScale);
        view.markPrice = longToDouble(pos.getMarkPrice(), priceScale);
        return view;
    }

    @Getter
    public static class PositionView {
        private int quoteCurrency;
        private PositionDirection direction;
        private long openVolume;
        private double openInitMarginSum;
        private double openPriceSum;
        private double profit;
        private double pendingSellSize;
        private double pendingBuySize;
        private double pendingSellAvgPrice;
        private double pendingBuyAvgPrice;
        private int leverage;
        private MarginMode marginMode;
        private double extraMargin;
        private double unrealizedProfit;
        private double liquidationPrice;
        private double marginRatio;
        private double markPrice;
    }

    @Getter
    public static class OrderView {
        private long orderId;
        private double price;
        private double size;
        private double filled;
        private double reserveBidPrice;
        private OrderAction action;
        private long uid;
        private long timestamp;
    }
}
