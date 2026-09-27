package com.binance.raftexchange.client;

import com.binance.raftexchange.stubs.CoreCurrencySpecification;
import com.binance.raftexchange.stubs.CoreSymbolSpecification;
import com.binance.raftexchange.stubs.MarginMode;
import com.binance.raftexchange.stubs.OrderAction;
import com.binance.raftexchange.stubs.PositionDirection;
import com.binance.raftexchange.stubs.report.Order;
import com.binance.raftexchange.stubs.report.Position;
import com.binance.raftexchange.stubs.report.QueryExecutionStatus;
import com.binance.raftexchange.stubs.report.IsolatedLoan;
import com.binance.raftexchange.stubs.report.CrossLoan;
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
    private final List<IsolatedLoanView> isolatedLoans;
    private final List<CrossLoanView> crossLoans;
    /** Cross 账户级抵押池：currency → 数量，按各币种 scale 还原 */
    private final Map<Integer, BigDecimal> crossLoanCollateral;
    /** Cross 账户级 LTV（bps）；numeraire 未配或无 cross loan 时为 0 */
    private final long crossAccountLtvBps;
    private final QueryExecutionStatus queryExecutionStatus;

    private SingleUserReportResultView(long userId, UserStatus userStatus, long crossAccountLtvBps,
        QueryExecutionStatus queryExecutionStatus) {
        this.userId = userId;
        this.userStatus = userStatus;
        this.accounts = new HashMap<>();
        this.exchangeLocked = new HashMap<>();
        this.positions = new HashMap<>();
        this.orders = new HashMap<>();
        this.isolatedLoans = new java.util.ArrayList<>();
        this.crossLoans = new java.util.ArrayList<>();
        this.crossLoanCollateral = new HashMap<>();
        this.crossAccountLtvBps = crossAccountLtvBps;
        this.queryExecutionStatus = queryExecutionStatus;
    }

    public static SingleUserReportResultView build(SingleUserReportResult reportResult,
        ExchangeMetadataManager metadataManager) {
        SingleUserReportResultView view = new SingleUserReportResultView(reportResult.getUserId(),
            reportResult.getUserStatus(), reportResult.getCrossAccountLtvBps(),
            reportResult.getQueryExecutionStatus());

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

        reportResult.getCrossLoanCollateralMap().forEach((currency, amount) -> {
            try {
                view.crossLoanCollateral.put(currency,
                    longToBigDecimal(amount, pow10(metadataManager.getCurrencySpec(currency).getDigit())));
            } catch (RuntimeException ex) {
                log.warn("skip crossLoanCollateral restore: currency={}, err={}", currency, ex.toString());
            }
        });

        // 抵押侧与借款侧是两个币种、两套精度，各自还原
        reportResult.getIsolatedLoansList().forEach(loan -> {
            try {
                view.isolatedLoans.add(toIsolatedLoanView(loan, metadataManager));
            } catch (RuntimeException ex) {
                log.warn("skip isolatedLoan restore: loanId={}, err={}", loan.getLoanId(), ex.toString());
            }
        });

        reportResult.getCrossLoansList().forEach(loan -> {
            try {
                view.crossLoans.add(toCrossLoanView(loan, metadataManager));
            } catch (RuntimeException ex) {
                log.warn("skip crossLoan restore: loanId={}, err={}", loan.getLoanId(), ex.toString());
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

    private static IsolatedLoanView toIsolatedLoanView(IsolatedLoan loan, ExchangeMetadataManager metadataManager) {
        long collateralScale = pow10(metadataManager.getCurrencySpec(loan.getCollateralCurrency()).getDigit());
        long loanScale = pow10(metadataManager.getCurrencySpec(loan.getLoanCurrency()).getDigit());
        IsolatedLoanView view = new IsolatedLoanView();
        view.loanId = loan.getLoanId();
        view.symbolId = loan.getSymbolId();
        view.collateralCurrency = loan.getCollateralCurrency();
        view.loanCurrency = loan.getLoanCurrency();
        view.rateBps = loan.getRateBps();
        view.openedAtTs = loan.getOpenedAtTs();
        view.collateralAmount = longToBigDecimal(loan.getCollateralAmount(), collateralScale);
        view.outstandingPrincipal = longToBigDecimal(loan.getOutstandingPrincipal(), loanScale);
        view.accumulatedInterest = longToBigDecimal(loan.getAccumulatedInterest(), loanScale);
        view.displayInterest = longToBigDecimal(loan.getDisplayInterest(), loanScale);
        view.ltvBps = loan.getLtvBps();
        view.markPrice = loan.getMarkPrice();
        return view;
    }

    private static CrossLoanView toCrossLoanView(CrossLoan loan, ExchangeMetadataManager metadataManager) {
        long loanScale = pow10(metadataManager.getCurrencySpec(loan.getLoanCurrency()).getDigit());
        CrossLoanView view = new CrossLoanView();
        view.loanId = loan.getLoanId();
        view.symbolId = loan.getSymbolId();
        view.loanCurrency = loan.getLoanCurrency();
        view.rateBps = loan.getRateBps();
        view.openedAtTs = loan.getOpenedAtTs();
        view.outstandingPrincipal = longToBigDecimal(loan.getOutstandingPrincipal(), loanScale);
        view.accumulatedInterest = longToBigDecimal(loan.getAccumulatedInterest(), loanScale);
        view.displayInterest = longToBigDecimal(loan.getDisplayInterest(), loanScale);
        return view;
    }

    /** Isolated 单笔：抵押量按抵押币精度、债务按借款币精度还原；ltvBps / markPrice 为 0 表示喂价缺失、不可信。 */
    @Getter
    public static class IsolatedLoanView {
        private long loanId;
        private int symbolId;
        private int collateralCurrency;
        private int loanCurrency;
        private int rateBps;
        private long openedAtTs;
        private BigDecimal collateralAmount;
        private BigDecimal outstandingPrincipal;
        private BigDecimal accumulatedInterest;
        private BigDecimal displayInterest;
        private long ltvBps;
        private long markPrice;
    }

    /** Cross 单笔：抵押是账户级共享的，见 {@code crossLoanCollateral} / {@code crossAccountLtvBps}，单笔无 LTV。 */
    @Getter
    public static class CrossLoanView {
        private long loanId;
        private int symbolId;
        private int loanCurrency;
        private int rateBps;
        private long openedAtTs;
        private BigDecimal outstandingPrincipal;
        private BigDecimal accumulatedInterest;
        private BigDecimal displayInterest;
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
