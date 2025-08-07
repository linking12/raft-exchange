package com.binance.raftexchange.client.sdk;

import com.binance.raftexchange.client.Api.ApiStream;
import com.binance.raftexchange.client.Api.ExchangeClient;
import com.binance.raftexchange.stubs.BalanceAdjustmentType;
import com.binance.raftexchange.stubs.CoreCurrencySpecification;
import com.binance.raftexchange.stubs.CoreSymbolSpecification;
import com.binance.raftexchange.stubs.MarginMode;
import com.binance.raftexchange.stubs.OrderAction;
import com.binance.raftexchange.stubs.OrderType;
import com.binance.raftexchange.stubs.SymbolType;
import com.binance.raftexchange.stubs.report.SingleUserReportResult;
import com.binance.raftexchange.stubs.request.ApiAddUser;
import com.binance.raftexchange.stubs.request.ApiAdjustLeverage;
import com.binance.raftexchange.stubs.request.ApiAdjustMargin;
import com.binance.raftexchange.stubs.request.ApiAdjustMarkPrice;
import com.binance.raftexchange.stubs.request.ApiAdjustUserBalance;
import com.binance.raftexchange.stubs.request.ApiBinaryDataCommand;
import com.binance.raftexchange.stubs.request.ApiCancelOrder;
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
import io.grpc.stub.StreamObserver;

import java.time.Duration;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static com.binance.raftexchange.client.sdk.ExchangeSdkHelper.doubleToLong;
import static com.binance.raftexchange.client.sdk.ExchangeSdkHelper.getFloorValue;

public class ExchangeSdk implements AutoCloseable {

    private final ExchangeClient client;
    private final ExchangeMetadataManager metadataManager;
    private final ApiStream commandStream;
    private final AtomicInteger reqIdGen = new AtomicInteger(1);
    private final Queue<CompletableFuture<CommandResult>> pendingQueue = new ConcurrentLinkedQueue<>();

    private ExchangeSdk(String host, int port) {
        this.client = new ExchangeClient(host, port);
        this.metadataManager = new ExchangeMetadataManager(client, reqIdGen);
        // 创建一个全局的 stream，用于发所有写命令
        this.commandStream = client.createStream(new StreamObserver<CommandResult>() {
            @Override
            public void onNext(CommandResult result) {
                CompletableFuture<CommandResult> f = pendingQueue.poll();
                if (f != null) {
                    f.complete(result);
                }
            }

            @Override
            public void onError(Throwable t) {
                CompletableFuture<CommandResult> f;
                while ((f = pendingQueue.poll()) != null) {
                    f.completeExceptionally(t);
                }
            }

            @Override
            public void onCompleted() { /* no-op */ }
        });
    }

    public static ExchangeSdk connect(String host, int port) {
        return new ExchangeSdk(host, port);
    }

    public CompletableFuture<CommandResult> sendAsync(ApiCommand cmd) {
        CompletableFuture<CommandResult> f = new CompletableFuture<>();
        // 先入队：保证 future 和即将发出的命令一一对应
        pendingQueue.offer(f);
        // 再发流
        commandStream.onNext(cmd);
        return f;
    }

    public CommandResult send(ApiCommand cmd, Duration timeout) {
        try {
            return sendAsync(cmd).get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            throw new RuntimeException("cmd exec failed. cmd: " + cmd, e);
        }
    }

    public CommandResult send(ApiCommand cmd) {
        return send(cmd, Duration.ofSeconds(2));
    }

    // ———————— 业务方法 ————————

    public CommandResult addUser(long uid) {
        return send(buildAddUserCommand(uid));
    }

    public CompletableFuture<CommandResult> addUserAsync(long uid) {
        return sendAsync(buildAddUserCommand(uid));
    }

    private ApiCommand buildAddUserCommand(long uid) {
        return ApiCommand.newBuilder().setTimestamp(System.currentTimeMillis()).setAddUser(ApiAddUser.newBuilder().setUid(uid)).build();
    }

    public CommandResult addCurrency(int id, String name, int digit) {
        CommandResult commandResult = send(buildAddCurrencyCommand(id, name, digit));
        metadataManager.refreshAll();
        return commandResult;
    }

    public CompletableFuture<CommandResult> addCurrencyAsync(int id, String name, int digit) {
        final ApiCommand cmd;
        try {
            cmd = buildAddCurrencyCommand(id, name, digit);
        } catch (Exception ex) {
            return CompletableFuture.failedFuture(ex);
        }
        return sendAsync(cmd).thenApply(result -> {
            metadataManager.refreshAll();
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

    public CommandResult addSymbol(int id, SymbolType symbolType, int baseCurrency, int quoteCurrency,
                                   long baseScaleK, long quoteScaleK, long takerFee, long makerFee, long feeScaleK,
                                   long initMargin, long initMarginScaleK, Map<Long, Long> maintenanceMargin,
                                   long maintenanceMarginScaleK, Map<Long, Long> maxLeverage) {
        CommandResult commandResult = send(buildAddSymbolCommand(id, symbolType, baseCurrency, quoteCurrency,
                baseScaleK, quoteScaleK, takerFee, makerFee, feeScaleK, initMargin, initMarginScaleK,
                maintenanceMargin, maintenanceMarginScaleK, maxLeverage));
        metadataManager.refreshAll();
        return commandResult;
    }

    public CompletableFuture<CommandResult> addSymbolAsync(int id, SymbolType symbolType, int baseCurrency, int quoteCurrency,
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
            metadataManager.refreshAll();
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
        CoreSymbolSpecification symbolToAdd = CoreSymbolSpecification.newBuilder()
                .setSymbolId(id).setType(symbolType)
                .setBaseCurrency(baseCurrency).setQuoteCurrency(quoteCurrency)
                .setBaseScaleK(baseScaleK).setQuoteScaleK(quoteScaleK)
                .setTakerFee(takerFee).setMakerFee(makerFee).setFeeScaleK(feeScaleK)
                .setInitMargin(initMargin).setInitMarginScaleK(initMarginScaleK)
                .putAllMaintenanceMargin(maintenanceMargin).setMaintenanceMarginScaleK(maintenanceMarginScaleK)
                .putAllMaxLeverage(maxLeverage).build();
        return ApiCommand.newBuilder().setTimestamp(System.currentTimeMillis())
                .setBinaryData(ApiBinaryDataCommand.newBuilder().setTransferId(reqIdGen.getAndIncrement())
                        .setData(BinaryDataCommand.newBuilder()
                                .setAddSymbols(BatchAddSymbolsCommand.newBuilder().putSymbols(id, symbolToAdd))))
                .build();
    }

    public CommandResult adjustUserBalance(long uid, int currency, double amount) {
        return send(buildAdjustUserBalanceCommand(uid, currency, amount));
    }

    public CompletableFuture<CommandResult> adjustUserBalanceAsync(long uid, int currency, double amount) {
        final ApiCommand cmd;
        try {
            cmd = buildAdjustUserBalanceCommand(uid, currency, amount);
        } catch (Exception ex) {
            return CompletableFuture.failedFuture(ex);
        }
        return sendAsync(cmd);
    }

    private ApiCommand buildAdjustUserBalanceCommand(long uid, int currency, double amount) {
        CoreCurrencySpecification currencySpec = metadataManager.getCurrencySpec(currency);
        long currencyScaleK = (long) Math.pow(10, currencySpec.getDigit());
        return ApiCommand.newBuilder().setTimestamp(System.currentTimeMillis())
                .setAdjustBalance(ApiAdjustUserBalance.newBuilder().setTransactionId(reqIdGen.getAndIncrement())
                        .setUid(uid).setCurrency(currency).setAmount(doubleToLong(amount, currencyScaleK))
                        .setAdjustmentType(BalanceAdjustmentType.ADJUSTMENT))
                .build();
    }

    public CommandResult placeOrder(long uid, long orderId, int symbol, OrderAction action, OrderType type,
                                    double price, double reversePrice, double size, MarginMode marginMode, int leverage) {
        return send(buildPlaceOrderCommand(uid, orderId, symbol, action, type, price, reversePrice, size, marginMode, leverage));
    }

    public CompletableFuture<CommandResult> placeOrderAsync(long uid, long orderId, int symbol, OrderAction action, OrderType type,
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
        long maxLeverage = getFloorValue(spec.getMaxLeverageMap(), scaledNotional);
        if (!exchangeSymbol && (leverage <= 0 || leverage > maxLeverage)) {
            throw new IllegalArgumentException("Leverage illegal: " + leverage);
        }
        ApiPlaceOrder.Builder builder = ApiPlaceOrder.newBuilder().setUid(uid).setOrderId(orderId).setSymbol(symbol)
                .setAction(action).setOrderType(type).setPrice(scaledPrice).setReservePrice(scaledReversePrice).setSize(scaledSize);
        if (!exchangeSymbol) {
            builder.setMarginMode(marginMode).setLeverage(leverage);
        }
        return ApiCommand.newBuilder().setTimestamp(System.currentTimeMillis()).setPlaceOrder(builder).build();
    }

    public CommandResult moveOrder(long uid, long orderId, int symbol, double newPrice) {
        return send(buildMoveOrderCommand(uid, orderId, symbol, newPrice));
    }

    public CompletableFuture<CommandResult> moveOrderAsync(long uid, long orderId, int symbol, double newPrice) {
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

    public CommandResult cancelOrder(long uid, long orderId, int symbol) {
        return send(buildCancelOrderCommand(uid, orderId, symbol));
    }

    public CompletableFuture<CommandResult> cancelOrderAsync(long uid, long orderId, int symbol) {
        return sendAsync(buildCancelOrderCommand(uid, orderId, symbol));
    }

    public ApiCommand buildCancelOrderCommand(long uid, long orderId, int symbol) {
        return ApiCommand.newBuilder().setTimestamp(System.currentTimeMillis())
                .setCancelOrder(ApiCancelOrder.newBuilder().setUid(uid).setOrderId(orderId).setSymbol(symbol)).build();
    }

    public CommandResult reduceOrder(long uid, long orderId, int symbol, double size) {
        return send(buildReduceOrderCommand(uid, orderId, symbol, size));
    }

    public CompletableFuture<CommandResult> reduceOrderAsync(long uid, long orderId, int symbol, double size) {
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

    public CommandResult suspendUser(long uid) {
        return send(buildSuspendUserCommand(uid));
    }

    public CompletableFuture<CommandResult> suspendUserAsync(long uid) {
        return sendAsync(buildSuspendUserCommand(uid));
    }

    public ApiCommand buildSuspendUserCommand(long uid) {
        return ApiCommand.newBuilder().setTimestamp(System.currentTimeMillis())
                .setSuspendUser(ApiSuspendUser.newBuilder().setUid(uid)).build();
    }

    public CommandResult resumeUser(long uid) {
        return send(buildResumeUserCommand(uid));
    }

    public CompletableFuture<CommandResult> resumeUserAsync(long uid) {
        return sendAsync(buildResumeUserCommand(uid));
    }

    public ApiCommand buildResumeUserCommand(long uid) {
        return ApiCommand.newBuilder().setTimestamp(System.currentTimeMillis())
                .setResumeUser(ApiResumeUser.newBuilder().setUid(uid)).build();
    }

    public CommandResult adjustLeverage(long uid, int symbol, int leverage) {
        return send(buildAdjustLeverageCommand(uid, symbol, leverage));
    }

    public CompletableFuture<CommandResult> adjustLeverageAsync(long uid, int symbol, int leverage) {
        return sendAsync(buildAdjustLeverageCommand(uid, symbol, leverage));
    }

    public ApiCommand buildAdjustLeverageCommand(long uid, int symbol, int leverage) {
        return ApiCommand.newBuilder().setTimestamp(System.currentTimeMillis())
                .setAdjustLeverage(ApiAdjustLeverage.newBuilder().setUid(uid).setSymbol(symbol).setLeverage(leverage)).build();
    }

    public CommandResult adjustMargin(long uid, MarginMode mode, int symbolOrCurrency, double amount) {
        return send(buildAdjustMarginCommand(uid, mode, symbolOrCurrency, amount));
    }

    public CompletableFuture<CommandResult> adjustMarginAsync(long uid, MarginMode mode, int symbolOrCurrency, double amount) {
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

    public CommandResult adjustMarkPrice(int symbol, double markPrice) {
        return send(buildAdjustMarkPriceCommand(symbol, markPrice));
    }

    public CompletableFuture<CommandResult> adjustMarkPriceAsync(int symbol, double markPrice) {
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

    public CommandResult settleFundingFees(int symbol, long fundingRate, long rateScaleK) {
        return send(buildSettleFundingFeesCommand(symbol, fundingRate, rateScaleK));
    }

    public CompletableFuture<CommandResult> settleFundingFeesAsync(int symbol, long fundingRate, long rateScaleK) {
        return sendAsync(buildSettleFundingFeesCommand(symbol, fundingRate, rateScaleK));
    }

    public ApiCommand buildSettleFundingFeesCommand(int symbol, long fundingRate, long rateScaleK) {
        return ApiCommand.newBuilder().setTimestamp(System.currentTimeMillis())
                .setSettleFundingFees(ApiSettleFundingFees.newBuilder().setTransactionId(reqIdGen.getAndIncrement())
                        .setSymbol(symbol).setFundingRate(fundingRate).setRateScaleK(rateScaleK)).build();
    }

    public CommandResult settlePnl(int symbol, double price) {
        return send(buildSettlePnlCommand(symbol, price));
    }

    public CompletableFuture<CommandResult> settlePnlAsync(int symbol, double price) {
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
    public CompletableFuture<SingleUserReportResult> queryUserReport(long userId) {
        return client.singleUserReport(reqIdGen.getAndIncrement(), userId);
    }

    public CompletableFuture<CommandResult> searchOrderBook(int symbol) {
        return client.searchOrderBook(symbol, 20);
    }

    @Override
    public void close() {
        try {
            commandStream.onCompleted();
            metadataManager.shutdown();
            client.close();
        } catch (Exception ignore) {

        }
    }
}