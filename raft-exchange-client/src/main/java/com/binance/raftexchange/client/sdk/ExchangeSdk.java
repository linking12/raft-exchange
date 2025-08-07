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
import com.binance.raftexchange.stubs.request.ApiAdjustUserBalance;
import com.binance.raftexchange.stubs.request.ApiBinaryDataCommand;
import com.binance.raftexchange.stubs.request.ApiCancelOrder;
import com.binance.raftexchange.stubs.request.ApiCommand;
import com.binance.raftexchange.stubs.request.ApiPlaceOrder;
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
        return sendAsync(buildPlaceOrderCommand(uid, orderId, symbol, action, type, price, reversePrice, size, marginMode, leverage));
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

    // todo: 继续实现其他命令
    public CommandResult cancelOrder(long uid, long orderId, int symbol) {
        ApiCancelOrder co = ApiCancelOrder.newBuilder()
                .setUid(uid)
                .setOrderId(orderId)
                .setSymbol(symbol)
                .build();
        ApiCommand cmd = ApiCommand.newBuilder().setCancelOrder(co).build();
        return send(cmd, Duration.ofSeconds(3));
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