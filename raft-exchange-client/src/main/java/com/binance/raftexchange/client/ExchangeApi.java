package com.binance.raftexchange.client;

import com.binance.raftexchange.client.grpc.ApiStream;
import com.binance.raftexchange.client.grpc.ExchangeClient;
import com.binance.raftexchange.stubs.BalanceAdjustmentType;
import com.binance.raftexchange.stubs.CoreCurrencySpecification;
import com.binance.raftexchange.stubs.CoreSymbolSpecification;
import com.binance.raftexchange.stubs.MarginMode;
import com.binance.raftexchange.stubs.OrderAction;
import com.binance.raftexchange.stubs.OrderType;
import com.binance.raftexchange.stubs.PositionMode;
import com.binance.raftexchange.stubs.SymbolType;
import com.binance.raftexchange.stubs.request.ApiAddUser;
import com.binance.raftexchange.stubs.request.ApiAdjustLeverage;
import com.binance.raftexchange.stubs.request.ApiAdjustMargin;
import com.binance.raftexchange.stubs.request.ApiAdjustMarkPrice;
import com.binance.raftexchange.stubs.request.ApiAdjustPositionMode;
import com.binance.raftexchange.stubs.request.ApiAdjustUserBalance;
import com.binance.raftexchange.stubs.request.ApiBinaryDataCommand;
import com.binance.raftexchange.stubs.request.ApiCancelOrder;
import com.binance.raftexchange.stubs.request.ApiClosePosition;
import com.binance.raftexchange.stubs.request.ApiCommand;
import com.binance.raftexchange.stubs.request.ApiMoveOrder;
import com.binance.raftexchange.stubs.request.ApiPlaceOrder;
import com.binance.raftexchange.stubs.request.ApiReduceOrder;
import com.binance.raftexchange.stubs.request.ApiResumeUser;
import com.binance.raftexchange.stubs.request.ApiSettleFundingFees;
import com.binance.raftexchange.stubs.request.ApiSettlePNL;
import com.binance.raftexchange.stubs.request.ApiSuspendUser;
import com.binance.raftexchange.stubs.request.BatchAddCurrenciesCommand;
import com.binance.raftexchange.stubs.request.BatchAddSymbolsCommand;
import com.binance.raftexchange.stubs.request.BinaryDataCommand;
import com.binance.raftexchange.stubs.response.CommandResult;
import com.binance.raftexchange.stubs.response.CommandResultCode;
import io.grpc.stub.StreamObserver;
import io.micrometer.core.instrument.Metrics;
import io.micrometer.core.instrument.Timer;
import lombok.Getter;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static com.binance.raftexchange.client.ExchangeApiHelper.doubleToLong;
import static com.binance.raftexchange.client.ExchangeApiHelper.getFloorValue;

public class ExchangeApi implements AutoCloseable {

    private static final Timer latencyTimer = Timer.builder("client.latency").publishPercentiles(0.9, 0.99).publishPercentileHistogram().register(Metrics.globalRegistry);

    private final ExchangeClient client;
    @Getter
    private final ExchangeMetadataManager metadataManager;
    private final AtomicInteger reqIdGen = new AtomicInteger(1);

    private ExchangeApi(String host, int port) {
        this.client = new ExchangeClient(host, port);
        this.metadataManager = new ExchangeMetadataManager(client, reqIdGen);
    }

    public static ExchangeApi connect(String host, int port) {
        return new ExchangeApi(host, port);
    }

    public CompletableFuture<CommandResultView> sendAsync(ApiCommand cmd) {
        CompletableFuture<CommandResult> f = new CompletableFuture<>();
        ApiStream commandStream = client.createStream(new StreamObserver<CommandResult>() {
            private final long start = System.nanoTime();

            @Override
            public void onNext(CommandResult result) {
                latencyTimer.record(System.nanoTime() - start, TimeUnit.NANOSECONDS);
                f.complete(result);
            }

            @Override
            public void onError(Throwable t) {
                f.completeExceptionally(t);
            }

            @Override
            public void onCompleted() {
                f.completeExceptionally(new IllegalStateException("empty response"));
            }
        });
        commandStream.onNext(cmd);
        return f.whenComplete((res, err) -> commandStream.onCompleted())
                .thenApply(result -> CommandResultView.build(result, metadataManager));
    }

    public CommandResultView send(ApiCommand cmd, Duration timeout) {
        try {
            return sendAsync(cmd).get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            throw new RuntimeException("cmd exec failed. cmd: " + cmd, e);
        }
    }

    public CommandResultView send(ApiCommand cmd) {
        return send(cmd, Duration.ofSeconds(2));
    }

    // ———————— 业务方法 ————————

    public CommandResultView addUser(long uid) {
        return send(buildAddUserCommand(uid));
    }

    public CompletableFuture<CommandResultView> addUserAsync(long uid) {
        return sendAsync(buildAddUserCommand(uid));
    }

    private ApiCommand buildAddUserCommand(long uid) {
        return ApiCommand.newBuilder().setTimestamp(System.currentTimeMillis()).setAddUser(ApiAddUser.newBuilder().setUid(uid)).build();
    }

    public CommandResultView addCurrency(int id, String name, int digit) {
        ApiCommand cmd = buildAddCurrencyCommand(id, name, digit);
        CommandResultView result = send(cmd);
        if (result.getResultCode() == CommandResultCode.SUCCESS) {
            CoreCurrencySpecification currencySpec = cmd.getBinaryData().getData().getAddCurrencies().getCurrenciesMap().get(id);
            metadataManager.addCurrencySpec(currencySpec);
        }
        return result;
    }

    public CompletableFuture<CommandResultView> addCurrencyAsync(int id, String name, int digit) {
        final ApiCommand cmd;
        try {
            cmd = buildAddCurrencyCommand(id, name, digit);
        } catch (Exception ex) {
            return CompletableFuture.failedFuture(ex);
        }
        return sendAsync(cmd).thenApply(result -> {
            if (result.getResultCode() == CommandResultCode.SUCCESS) {
                CoreCurrencySpecification currencySpec = cmd.getBinaryData().getData().getAddCurrencies().getCurrenciesMap().get(id);
                metadataManager.addCurrencySpec(currencySpec);
            }
            return result;
        });
    }

    private ApiCommand buildAddCurrencyCommand(int id, String name, int digit) {
        if (metadataManager.currencyExists(id)) {
            throw new IllegalArgumentException("Currency with id " + id + " already exists.");
        }
        CoreCurrencySpecification currencyToAdd = CoreCurrencySpecification.newBuilder()
                .setId(id).setName(name).setDigit(digit).build();
        return ApiCommand.newBuilder().setTimestamp(System.currentTimeMillis())
                .setBinaryData(ApiBinaryDataCommand.newBuilder().setTransferId(reqIdGen.getAndIncrement())
                        .setData(BinaryDataCommand.newBuilder()
                                .setAddCurrencies(BatchAddCurrenciesCommand.newBuilder().putCurrencies(id, currencyToAdd))))
                .build();
    }

    public CommandResultView addSymbol(int id, SymbolType symbolType, int baseCurrency, int quoteCurrency,
                                   long baseScaleK, long quoteScaleK, long takerFee, long makerFee, long feeScaleK,
                                   long initMargin, long initMarginScaleK, Map<Long, Long> maintenanceMargin,
                                   long maintenanceMarginScaleK, Map<Long, Long> maxLeverage) {
        ApiCommand cmd = buildAddSymbolCommand(id, symbolType, baseCurrency, quoteCurrency,
                baseScaleK, quoteScaleK, takerFee, makerFee, feeScaleK, initMargin, initMarginScaleK,
                maintenanceMargin, maintenanceMarginScaleK, maxLeverage);
        CommandResultView result = send(cmd);
        if (result.getResultCode() == CommandResultCode.SUCCESS) {
            CoreSymbolSpecification spec = cmd.getBinaryData().getData().getAddSymbols().getSymbolsMap().get(id);
            metadataManager.addSymbolSpec(spec);
        }
        return result;
    }

    public CompletableFuture<CommandResultView> addSymbolAsync(int id, SymbolType symbolType, int baseCurrency, int quoteCurrency,
                                                           long baseScaleK, long quoteScaleK, long takerFee, long makerFee, long feeScaleK,
                                                           long initMargin, long initMarginScaleK, Map<Long, Long> maintenanceMargin,
                                                           long maintenanceMarginScaleK, Map<Long, Long> maxLeverage) {
        final ApiCommand cmd;
        try {
            cmd = buildAddSymbolCommand(id, symbolType, baseCurrency, quoteCurrency,
                    baseScaleK, quoteScaleK, takerFee, makerFee, feeScaleK, initMargin, initMarginScaleK,
                    maintenanceMargin, maintenanceMarginScaleK, maxLeverage);
        } catch (Exception ex) {
            return CompletableFuture.failedFuture(ex);
        }
        return sendAsync(cmd).thenApply(result -> {
            if (result.getResultCode() == CommandResultCode.SUCCESS) {
                CoreSymbolSpecification spec = cmd.getBinaryData().getData().getAddSymbols().getSymbolsMap().get(id);
                metadataManager.addSymbolSpec(spec);
            }
            return result;
        });
    }

    private ApiCommand buildAddSymbolCommand(int id, SymbolType symbolType, int baseCurrency, int quoteCurrency,
                                             long baseScaleK, long quoteScaleK, long takerFee, long makerFee, long feeScaleK,
                                             long initMargin, long initMarginScaleK, Map<Long, Long> maintenanceMargin,
                                             long maintenanceMarginScaleK, Map<Long, Long> maxLeverage) {
        if (metadataManager.symbolExists(id)) {
            throw new IllegalArgumentException("Symbol with id " + id + " already exists.");
        }
        if (!metadataManager.currencyExists(baseCurrency)) {
            throw new IllegalArgumentException("Currency with id " + baseCurrency + " does not exist.");
        }
        if (!metadataManager.currencyExists(quoteCurrency)) {
            throw new IllegalArgumentException("Currency with id " + quoteCurrency + " does not exist.");
        }
        CoreSymbolSpecification.Builder symbolToAdd = CoreSymbolSpecification.newBuilder()
                .setSymbolId(id).setType(symbolType)
                .setBaseCurrency(baseCurrency).setQuoteCurrency(quoteCurrency)
                .setBaseScaleK(baseScaleK).setQuoteScaleK(quoteScaleK)
                .setTakerFee(takerFee).setMakerFee(makerFee).setFeeScaleK(feeScaleK);
        if (symbolType != SymbolType.CURRENCY_EXCHANGE_PAIR) {
            symbolToAdd.setInitMargin(initMargin).setInitMarginScaleK(initMarginScaleK)
                    .putAllMaintenanceMargin(maintenanceMargin).setMaintenanceMarginScaleK(maintenanceMarginScaleK)
                    .putAllMaxLeverage(maxLeverage);
        }
        return ApiCommand.newBuilder().setTimestamp(System.currentTimeMillis())
                .setBinaryData(ApiBinaryDataCommand.newBuilder().setTransferId(reqIdGen.getAndIncrement())
                        .setData(BinaryDataCommand.newBuilder()
                                .setAddSymbols(BatchAddSymbolsCommand.newBuilder().putSymbols(id, symbolToAdd.build()))))
                .build();
    }

    public CommandResultView adjustUserBalance(long uid, long transactionId, int currency, double amount) {
        return send(buildAdjustUserBalanceCommand(uid, transactionId, currency, amount));
    }

    public CompletableFuture<CommandResultView> adjustUserBalanceAsync(long uid, long transactionId, int currency, double amount) {
        final ApiCommand cmd;
        try {
            cmd = buildAdjustUserBalanceCommand(uid, transactionId, currency, amount);
        } catch (Exception ex) {
            return CompletableFuture.failedFuture(ex);
        }
        return sendAsync(cmd);
    }

    private ApiCommand buildAdjustUserBalanceCommand(long uid, long transactionId, int currency, double amount) {
        CoreCurrencySpecification currencySpec = metadataManager.getCurrencySpec(currency);
        long currencyScaleK = (long) Math.pow(10, currencySpec.getDigit());
        return ApiCommand.newBuilder().setTimestamp(System.currentTimeMillis())
                .setAdjustBalance(ApiAdjustUserBalance.newBuilder().setTransactionId(transactionId)
                        .setUid(uid).setCurrency(currency).setAmount(doubleToLong(amount, currencyScaleK))
                        .setAdjustmentType(BalanceAdjustmentType.ADJUSTMENT))
                .build();
    }

    public CommandResultView placeOrder(long uid, long orderId, int symbol, OrderAction action, OrderType type,
                                    double price, double reversePrice, double size, MarginMode marginMode, int leverage) {
        return send(buildPlaceOrderCommand(uid, orderId, symbol, action, type, price, reversePrice, size, marginMode, leverage));
    }

    public CompletableFuture<CommandResultView> placeOrderAsync(long uid, long orderId, int symbol, OrderAction action, OrderType type,
                                                            double price, double reversePrice, double size, MarginMode marginMode, int leverage) {
        final ApiCommand cmd;
        try {
            cmd = buildPlaceOrderCommand(uid, orderId, symbol, action, type, price, reversePrice, size, marginMode, leverage);
        } catch (Exception ex) {
            return CompletableFuture.failedFuture(ex);
        }
        return sendAsync(cmd);
    }

    public ApiCommand buildPlaceOrderCommand(long uid, long orderId, int symbol, OrderAction action, OrderType type,
                                             double price, double reversePrice, double size, MarginMode marginMode, int leverage) {
        CoreSymbolSpecification spec = metadataManager.getSymbolSpec(symbol);
        boolean exchangeSymbol = spec.getType() == SymbolType.CURRENCY_EXCHANGE_PAIR;
        if (!exchangeSymbol && marginMode == null) {
            throw new IllegalArgumentException("MarginMode must be specified for non-exchange symbols.");
        }
        long scaledPrice = doubleToLong(price, spec.getQuoteScaleK());
        long scaledReversePrice = doubleToLong(reversePrice, spec.getQuoteScaleK());
        long scaledSize = doubleToLong(size, spec.getBaseScaleK());
        long scaledNotional = (action == OrderAction.BID ? scaledReversePrice : scaledPrice) * scaledSize;
        if (!exchangeSymbol) {
            long maxLeverage = getFloorValue(spec.getMaxLeverageMap(), scaledNotional);
            if (leverage <= 0 || leverage > maxLeverage) {
                throw new IllegalArgumentException("Leverage illegal: " + leverage);
            }
        }
        ApiPlaceOrder.Builder builder = ApiPlaceOrder.newBuilder().setUid(uid).setOrderId(orderId).setSymbol(symbol)
                .setAction(action).setOrderType(type).setPrice(scaledPrice).setReservePrice(scaledReversePrice).setSize(scaledSize);
        if (!exchangeSymbol) {
            builder.setMarginMode(marginMode).setLeverage(leverage);
        }
        return ApiCommand.newBuilder().setTimestamp(System.currentTimeMillis()).setPlaceOrder(builder).build();
    }

    public CommandResultView closePosition(long uid, long orderId, int symbol, OrderAction action, double price, double size) {
        return send(buildClosePositionCommand(uid, orderId, symbol, action, price, size));
    }

    public CompletableFuture<CommandResultView> closePositionAsync(long uid, long orderId, int symbol, OrderAction action, double price, double size) {
        return sendAsync(buildClosePositionCommand(uid, orderId, symbol, action, price, size));
    }

    private ApiCommand buildClosePositionCommand(long uid, long orderId, int symbol, OrderAction action, double price, double size) {
        CoreSymbolSpecification spec = metadataManager.getSymbolSpec(symbol);
        long scaledPrice = doubleToLong(price, spec.getQuoteScaleK());
        long scaledSize = doubleToLong(size, spec.getBaseScaleK());
        return ApiCommand.newBuilder().setTimestamp(System.currentTimeMillis())
                .setClosePosition(ApiClosePosition.newBuilder().setUid(uid).setOrderId(orderId).setSymbol(symbol)
                        .setAction(action).setPrice(scaledPrice).setSize(scaledSize)).build();
    }

    public CommandResultView moveOrder(long uid, long orderId, int symbol, double newPrice) {
        return send(buildMoveOrderCommand(uid, orderId, symbol, newPrice));
    }

    public CompletableFuture<CommandResultView> moveOrderAsync(long uid, long orderId, int symbol, double newPrice) {
        final ApiCommand cmd;
        try {
            cmd = buildMoveOrderCommand(uid, orderId, symbol, newPrice);
        } catch (Exception ex) {
            return CompletableFuture.failedFuture(ex);
        }
        return sendAsync(cmd);
    }

    public ApiCommand buildMoveOrderCommand(long uid, long orderId, int symbol, double newPrice) {
        CoreSymbolSpecification spec = metadataManager.getSymbolSpec(symbol);
        long scaledNewPrice = doubleToLong(newPrice, spec.getQuoteScaleK());
        return ApiCommand.newBuilder().setTimestamp(System.currentTimeMillis())
                .setMoveOrder(ApiMoveOrder.newBuilder().setUid(uid).setOrderId(orderId).setSymbol(symbol)
                        .setNewPrice(scaledNewPrice)).build();
    }

    public CommandResultView cancelOrder(long uid, long orderId, int symbol) {
        return send(buildCancelOrderCommand(uid, orderId, symbol));
    }

    public CompletableFuture<CommandResultView> cancelOrderAsync(long uid, long orderId, int symbol) {
        return sendAsync(buildCancelOrderCommand(uid, orderId, symbol));
    }

    public ApiCommand buildCancelOrderCommand(long uid, long orderId, int symbol) {
        return ApiCommand.newBuilder().setTimestamp(System.currentTimeMillis())
                .setCancelOrder(ApiCancelOrder.newBuilder().setUid(uid).setOrderId(orderId).setSymbol(symbol)).build();
    }

    public CommandResultView reduceOrder(long uid, long orderId, int symbol, double size) {
        return send(buildReduceOrderCommand(uid, orderId, symbol, size));
    }

    public CompletableFuture<CommandResultView> reduceOrderAsync(long uid, long orderId, int symbol, double size) {
        final ApiCommand cmd;
        try {
            cmd = buildReduceOrderCommand(uid, orderId, symbol, size);
        } catch (Exception ex) {
            return CompletableFuture.failedFuture(ex);
        }
        return sendAsync(cmd);
    }

    public ApiCommand buildReduceOrderCommand(long uid, long orderId, int symbol, double size) {
        CoreSymbolSpecification spec = metadataManager.getSymbolSpec(symbol);
        long scaledSize = doubleToLong(size, spec.getBaseScaleK());
        return ApiCommand.newBuilder().setTimestamp(System.currentTimeMillis())
                .setReduceOrder(ApiReduceOrder.newBuilder().setUid(uid).setOrderId(orderId).setSymbol(symbol)
                        .setReduceSize(scaledSize)).build();
    }

    public CommandResultView suspendUser(long uid) {
        return send(buildSuspendUserCommand(uid));
    }

    public CompletableFuture<CommandResultView> suspendUserAsync(long uid) {
        return sendAsync(buildSuspendUserCommand(uid));
    }

    public ApiCommand buildSuspendUserCommand(long uid) {
        return ApiCommand.newBuilder().setTimestamp(System.currentTimeMillis())
                .setSuspendUser(ApiSuspendUser.newBuilder().setUid(uid)).build();
    }

    public CommandResultView resumeUser(long uid) {
        return send(buildResumeUserCommand(uid));
    }

    public CompletableFuture<CommandResultView> resumeUserAsync(long uid) {
        return sendAsync(buildResumeUserCommand(uid));
    }

    public ApiCommand buildResumeUserCommand(long uid) {
        return ApiCommand.newBuilder().setTimestamp(System.currentTimeMillis())
                .setResumeUser(ApiResumeUser.newBuilder().setUid(uid)).build();
    }

    public CommandResultView adjustLeverage(long uid, int symbol, int leverage) {
        return send(buildAdjustLeverageCommand(uid, symbol, leverage));
    }

    public CompletableFuture<CommandResultView> adjustLeverageAsync(long uid, int symbol, int leverage) {
        return sendAsync(buildAdjustLeverageCommand(uid, symbol, leverage));
    }

    public ApiCommand buildAdjustLeverageCommand(long uid, int symbol, int leverage) {
        return ApiCommand.newBuilder().setTimestamp(System.currentTimeMillis())
                .setAdjustLeverage(ApiAdjustLeverage.newBuilder().setUid(uid).setSymbol(symbol).setLeverage(leverage)).build();
    }

    public CommandResultView adjustPositionMode(long uid, PositionMode positionMode) {
        return send(buildAdjustPositionModeCommand(uid, positionMode));
    }

    public CompletableFuture<CommandResultView> adjustPositionModeAsync(long uid, PositionMode positionMode) {
        return sendAsync(buildAdjustPositionModeCommand(uid, positionMode));
    }

    public ApiCommand buildAdjustPositionModeCommand(long uid, PositionMode positionMode) {
        return ApiCommand.newBuilder().setTimestamp(System.currentTimeMillis())
                .setAdjustPositionMode(ApiAdjustPositionMode.newBuilder().setUid(uid).setPositionMode(positionMode)).build();
    }

    public CommandResultView adjustMargin(long uid, MarginMode mode, int symbolOrCurrency, double amount) {
        return send(buildAdjustMarginCommand(uid, mode, symbolOrCurrency, amount));
    }

    public CompletableFuture<CommandResultView> adjustMarginAsync(long uid, MarginMode mode, int symbolOrCurrency, double amount) {
        final ApiCommand cmd;
        try {
            cmd = buildAdjustMarginCommand(uid, mode, symbolOrCurrency, amount);
        } catch (Exception ex) {
            return CompletableFuture.failedFuture(ex);
        }
        return sendAsync(cmd);
    }

    public ApiCommand buildAdjustMarginCommand(long uid, MarginMode mode, int symbolOrCurrency, double amount) {
        ApiAdjustMargin.Builder apiAdjustMargin;
        if (mode == MarginMode.ISOLATED) {
            CoreSymbolSpecification spec = metadataManager.getSymbolSpec(symbolOrCurrency);
            long scaledAmount = doubleToLong(amount, spec.getQuoteScaleK());
            apiAdjustMargin = ApiAdjustMargin.newBuilder().setTransactionId(reqIdGen.getAndIncrement())
                    .setUid(uid).setSymbol(symbolOrCurrency).setAmount(scaledAmount);
        } else if (mode == MarginMode.CROSS) {
            CoreCurrencySpecification currencySpec = metadataManager.getCurrencySpec(symbolOrCurrency);
            long currencyScaleK = (long) Math.pow(10, currencySpec.getDigit());
            long scaledAmount = doubleToLong(amount, currencyScaleK);
            apiAdjustMargin = ApiAdjustMargin.newBuilder().setTransactionId(reqIdGen.getAndIncrement())
                    .setUid(uid).setCurrency(symbolOrCurrency).setAmount(scaledAmount);
        } else {
            throw new IllegalArgumentException("Unsupported margin mode: " + mode);
        }
        return ApiCommand.newBuilder().setTimestamp(System.currentTimeMillis()).setAdjustMargin(apiAdjustMargin).build();
    }

    public CommandResultView adjustMarkPrice(int symbol, double markPrice) {
        return send(buildAdjustMarkPriceCommand(symbol, markPrice));
    }

    public CompletableFuture<CommandResultView> adjustMarkPriceAsync(int symbol, double markPrice) {
        final ApiCommand cmd;
        try {
            cmd = buildAdjustMarkPriceCommand(symbol, markPrice);
        } catch (Exception ex) {
            return CompletableFuture.failedFuture(ex);
        }
        return sendAsync(cmd);
    }

    public ApiCommand buildAdjustMarkPriceCommand(int symbol, double markPrice) {
        CoreSymbolSpecification spec = metadataManager.getSymbolSpec(symbol);
        long scaledMarkPrice = doubleToLong(markPrice, spec.getQuoteScaleK());
        return ApiCommand.newBuilder().setTimestamp(System.currentTimeMillis())
                .setAdjustMarkprice(ApiAdjustMarkPrice.newBuilder().setTransactionId(reqIdGen.getAndIncrement())
                        .setSymbol(symbol).setMarkPrice(scaledMarkPrice)).build();
    }

    public CommandResultView settleFundingFees(int symbol, long fundingRate, long rateScaleK) {
        return send(buildSettleFundingFeesCommand(symbol, fundingRate, rateScaleK));
    }

    public CompletableFuture<CommandResultView> settleFundingFeesAsync(int symbol, long fundingRate, long rateScaleK) {
        return sendAsync(buildSettleFundingFeesCommand(symbol, fundingRate, rateScaleK));
    }

    public ApiCommand buildSettleFundingFeesCommand(int symbol, long fundingRate, long rateScaleK) {
        return ApiCommand.newBuilder().setTimestamp(System.currentTimeMillis())
                .setSettleFundingFees(ApiSettleFundingFees.newBuilder().setTransactionId(reqIdGen.getAndIncrement())
                        .setSymbol(symbol).setFundingRate(fundingRate).setRateScaleK(rateScaleK)).build();
    }

    public CommandResultView settlePnl(int symbol, double price) {
        return send(buildSettlePnlCommand(symbol, price));
    }

    public CompletableFuture<CommandResultView> settlePnlAsync(int symbol, double price) {
        final ApiCommand cmd;
        try {
            cmd = buildSettlePnlCommand(symbol, price);
        } catch (Exception ex) {
            return CompletableFuture.failedFuture(ex);
        }
        return sendAsync(cmd);
    }

    public ApiCommand buildSettlePnlCommand(int symbol, double price) {
        CoreSymbolSpecification spec = metadataManager.getSymbolSpec(symbol);
        long scaledPrice = doubleToLong(price, spec.getQuoteScaleK());
        return ApiCommand.newBuilder().setTimestamp(System.currentTimeMillis())
                .setSettlePnl(ApiSettlePNL.newBuilder().setTransactionId(reqIdGen.getAndIncrement())
                        .setSymbol(symbol).setSettlePrice(scaledPrice)).build();
    }

    // ———————— 只读查询接口  ————————
    public CompletableFuture<SingleUserReportResultView> queryUserReport(long userId) {
        return client.singleUserReport(reqIdGen.getAndIncrement(), userId)
                .thenApply(result -> SingleUserReportResultView.build(result, metadataManager));
    }

    public CompletableFuture<CommandResultView> searchOrderBook(int symbol) {
        return client.searchOrderBook(symbol, 20)
                .thenApply(result -> CommandResultView.build(result, metadataManager));
    }

    @Override
    public void close() {
        try {
            metadataManager.shutdown();
            client.close();
        } catch (Exception ignore) {

        }
    }
}