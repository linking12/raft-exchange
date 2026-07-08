package com.binance.raftexchange.client;

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
import lombok.extern.slf4j.Slf4j;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static com.binance.raftexchange.client.ExchangeApiHelper.longToBigDecimal;
import static com.binance.raftexchange.client.ExchangeApiHelper.multiplyScaleExact;
import static com.binance.raftexchange.client.ExchangeApiHelper.pow10;

@Getter
@Slf4j
public class SingleUserReportResultView {

    private final long userId;
    private final UserStatus userStatus;
    private final Map<Integer, BigDecimal> accounts;
    private final Map<Integer, BigDecimal> exchangeLocked;
    private final Map<Integer, List<PositionView>> positions;
    private final Map<Integer, List<OrderView>> orders;
    private final QueryExecutionStatus queryExecutionStatus;

    private SingleUserReportResultView(long userId, UserStatus userStatus, QueryExecutionStatus queryExecutionStatus) {
        this.userId = userId;
        this.userStatus = userStatus;
        this.accounts = new HashMap<>();
        this.exchangeLocked = new HashMap<>();
        this.positions = new HashMap<>();
        this.orders = new HashMap<>();
        this.queryExecutionStatus = queryExecutionStatus;
    }

    public static SingleUserReportResultView build(SingleUserReportResult reportResult,
        ExchangeMetadataManager metadataManager) {
        SingleUserReportResultView view = new SingleUserReportResultView(reportResult.getUserId(),
            reportResult.getUserStatus(), reportResult.getQueryExecutionStatus());

        // 单个 currency / symbol spec 缺失时跳过该项，不让整份报告作废
        reportResult.getAccountsMap().forEach((currency, amount) -> {
            try {
                CoreCurrencySpecification currencySpec = metadataManager.getCurrencySpec(currency);
                long currencyScaleK = pow10(currencySpec.getDigit());
                view.accounts.put(currency, longToBigDecimal(amount, currencyScaleK));
            } catch (RuntimeException ex) {
                log.warn("skip account restore: currency={}, err={}", currency, ex.toString());
            }
        });

        reportResult.getExchangeLockedMap().forEach((currency, amount) -> {
            try {
                CoreCurrencySpecification currencySpec = metadataManager.getCurrencySpec(currency);
                long currencyScaleK = pow10(currencySpec.getDigit());
                view.exchangeLocked.put(currency, longToBigDecimal(amount, currencyScaleK));
            } catch (RuntimeException ex) {
                log.warn("skip exchangeLocked restore: currency={}, err={}", currency, ex.toString());
            }
        });

        reportResult.getPositionsMap().forEach((symbol, positions) -> {
            try {
                CoreSymbolSpecification spec = metadataManager.getSymbolSpec(symbol);
                List<PositionView> positionViews = positions.getPositionsList().stream()
                    .map(pos -> toPositionView(pos, spec)).collect(Collectors.toList());
                view.positions.put(symbol, positionViews);
            } catch (RuntimeException ex) {
                log.warn("skip position restore: symbol={}, err={}", symbol, ex.toString());
            }
        });

        reportResult.getOrdersMap().forEach((symbol, orders) -> {
            try {
                CoreSymbolSpecification spec = metadataManager.getSymbolSpec(symbol);
                List<OrderView> orderViews =
                    orders.getOrdersList().stream().map(order -> toOrderView(order, spec)).collect(Collectors.toList());
                view.orders.put(symbol, orderViews);
            } catch (RuntimeException ex) {
                log.warn("skip order restore: symbol={}, err={}", symbol, ex.toString());
            }
        });
        return view;
    }

    private static OrderView toOrderView(Order order, CoreSymbolSpecification spec) {
        long sizeScale = spec.getBaseScaleK();
        long priceScale = spec.getQuoteScaleK();
        OrderView orderView = new OrderView();
        orderView.orderId = order.getOrderId();
        orderView.price = longToBigDecimal(order.getPrice(), priceScale);
        orderView.size = longToBigDecimal(order.getSize(), sizeScale);
        orderView.filled = longToBigDecimal(order.getFilled(), sizeScale);
        orderView.reserveBidPrice = longToBigDecimal(order.getReserveBidPrice(), priceScale);
        orderView.action = order.getAction();
        orderView.uid = order.getUid();
        orderView.timestamp = order.getTimestamp();
        return orderView;
    }

    private static PositionView toPositionView(Position pos, CoreSymbolSpecification spec) {
        long sizeScale = spec.getBaseScaleK();
        long priceScale = spec.getQuoteScaleK();
        long sizePriceScale = multiplyScaleExact(sizeScale, priceScale);
        long maintenanceMarginScale = spec.getMaintenanceMarginScaleK();
        PositionView view = new PositionView();
        view.quoteCurrency = pos.getQuoteCurrency();
        view.direction = pos.getDirection();
        view.openVolume = longToBigDecimal(pos.getOpenVolume(), sizeScale);
        view.openInitMarginSum = longToBigDecimal(pos.getOpenInitMarginSum(), sizePriceScale);
        view.openPriceSum = longToBigDecimal(pos.getOpenPriceSum(), sizePriceScale);
        view.profit = longToBigDecimal(pos.getProfit(), sizePriceScale);
        view.pendingSellSize = longToBigDecimal(pos.getPendingSellSize(), sizeScale);
        view.pendingBuySize = longToBigDecimal(pos.getPendingBuySize(), sizeScale);
        view.pendingSellAvgPrice = longToBigDecimal(pos.getPendingSellAvgPrice(), priceScale);
        view.pendingBuyAvgPrice = longToBigDecimal(pos.getPendingBuyAvgPrice(), priceScale);
        view.leverage = pos.getLeverage();
        view.marginMode = pos.getMarginMode();
        view.extraMargin = longToBigDecimal(pos.getExtraMargin(), sizePriceScale);
        view.unrealizedProfit = longToBigDecimal(pos.getUnrealizedProfit(), sizePriceScale);
        view.liquidationPrice = longToBigDecimal(pos.getLiquidationPrice(), priceScale);
        view.marginRatio = longToBigDecimal(pos.getMarginRatioScaleK(), maintenanceMarginScale);
        view.markPrice = longToBigDecimal(pos.getMarkPrice(), priceScale);
        return view;
    }

    @Getter
    public static class PositionView {
        private int quoteCurrency;
        private PositionDirection direction;
        private BigDecimal openVolume = BigDecimal.ZERO;
        private BigDecimal openInitMarginSum = BigDecimal.ZERO;
        private BigDecimal openPriceSum = BigDecimal.ZERO;
        private BigDecimal profit = BigDecimal.ZERO;
        private BigDecimal pendingSellSize = BigDecimal.ZERO;
        private BigDecimal pendingBuySize = BigDecimal.ZERO;
        private BigDecimal pendingSellAvgPrice = BigDecimal.ZERO;
        private BigDecimal pendingBuyAvgPrice = BigDecimal.ZERO;
        private int leverage;
        private MarginMode marginMode;
        private BigDecimal extraMargin = BigDecimal.ZERO;
        private BigDecimal unrealizedProfit = BigDecimal.ZERO;
        private BigDecimal liquidationPrice = BigDecimal.ZERO;
        private BigDecimal marginRatio = BigDecimal.ZERO;
        private BigDecimal markPrice = BigDecimal.ZERO;
    }

    @Getter
    public static class OrderView {
        private long orderId;
        private BigDecimal price = BigDecimal.ZERO;
        private BigDecimal size = BigDecimal.ZERO;
        private BigDecimal filled = BigDecimal.ZERO;
        private BigDecimal reserveBidPrice = BigDecimal.ZERO;
        private OrderAction action;
        private long uid;
        private long timestamp;
    }
}
