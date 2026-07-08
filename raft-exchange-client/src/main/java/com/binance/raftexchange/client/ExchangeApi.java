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
import com.binance.raftexchange.stubs.request.ApiInsuranceFundDeposit;
import com.binance.raftexchange.stubs.request.ApiInsuranceFundWithdraw;
import com.binance.raftexchange.stubs.request.ApiResetFee;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static com.binance.raftexchange.client.ExchangeApiHelper.doubleToLong;
import static com.binance.raftexchange.client.ExchangeApiHelper.getFloorValue;
import static com.binance.raftexchange.client.ExchangeApiHelper.pow10;

/**
 * 用户面 API 入口。所有业务命令（addUser/placeOrder/...）最终都汇到 {@link #sendAsync(ApiCommand)}：
 *
 * <pre>
 *   业务方法 → sendAsync(cmd) → sendAsyncWithRetry → sendOnce → ApiStream → grpc
 *                                                              ↑
 *                              server NEED_MOVE 时 ApiStream 已同步触发 ExchangeClient.switchToNewLeader
 *                              （apiStub/nodeStub 指向新 leader channel），sendAsyncWithRetry 用同一 cmd 重发
 * </pre>
 *
 * <p>
 * 幂等约定：retry 复用同一 {@code transactionId}，server 端按 ID 去重返 ALREADY_APPLIED_SAME；
 * 即便重发撞上又一次切换，{@link ExchangeApiOptions#needMoveRetryAttempts()} 限定上限避免无限循环。
 */
public class ExchangeApi implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(ExchangeApi.class);
    private static final Timer LATENCY_TIMER = Timer.builder("client.latency").publishPercentiles(0.9, 0.99)
        .publishPercentileHistogram().register(Metrics.globalRegistry);

    private final ExchangeClient client;
    private final ExchangeMetadataManager metadataManager;
    private final ExchangeApiOptions options;
    private final AtomicInteger reqIdGen = new AtomicInteger(1);

    private ExchangeApi(String host, int port, ExchangeApiOptions options) {
        this.client = new ExchangeClient(host, port, options.nodesFlushInterval());
        this.metadataManager = new ExchangeMetadataManager(client, reqIdGen);
        this.options = options;
    }

    ExchangeApi(ExchangeClient client, ExchangeMetadataManager metadataManager) {
        this(client, metadataManager, ExchangeApiOptions.defaults());
    }

    ExchangeApi(ExchangeClient client, ExchangeMetadataManager metadataManager, ExchangeApiOptions options) {
        this.client = client;
        this.metadataManager = metadataManager;
        this.options = options;
    }

    public static ExchangeApi connect(String host, int port) {
        return connect(host, port, ExchangeApiOptions.defaults());
    }

    public static ExchangeApi connect(String host, int port, ExchangeApiOptions options) {
        return new ExchangeApi(host, port, options);
    }

    public ExchangeApiOptions options() {
        return options;
    }

    public ExchangeMetadataManager getMetadataManager() {
        return metadataManager;
    }

    public CompletableFuture<CommandResultView> sendAsync(ApiCommand cmd) {
        return sendAsyncWithRetry(cmd, options.retryAttempts());
    }

    private CompletableFuture<CommandResultView> sendAsyncWithRetry(ApiCommand cmd, int attemptsLeft) {
        return sendOnce(cmd).thenCompose(view -> {
            if (attemptsLeft <= 0) {
                return CompletableFuture.completedFuture(view);
            }
            CommandResultCode code = view.getResultCode();
            if (code == CommandResultCode.NEED_MOVE) {
                LOG.info("auto-retry after NEED_MOVE: cmdType={}, attemptsLeft={}", cmd.getCommandCase(), attemptsLeft);
                return sendAsyncWithRetry(cmd, attemptsLeft - 1);
            }
            if (code == CommandResultCode.DROP) {
                LOG.info("auto-retry after DROP: cmdType={}, attemptsLeft={}, backoffMs={}", cmd.getCommandCase(),
                    attemptsLeft, options.retryBackoff().toMillis());
                return CompletableFuture.runAsync(() -> {
                }, CompletableFuture.delayedExecutor(options.retryBackoff().toMillis(), TimeUnit.MILLISECONDS))
                    .thenCompose(v -> sendAsyncWithRetry(cmd, attemptsLeft - 1));
            }
            return CompletableFuture.completedFuture(view);
        });
    }

    private CompletableFuture<CommandResultView> sendOnce(ApiCommand cmd) {
        CompletableFuture<CommandResult> f = new CompletableFuture<>();
        ApiStream stream = client.createStream(new StreamObserver<CommandResult>() {
            private final long start = System.nanoTime();

            @Override
            public void onNext(CommandResult result) {
                LATENCY_TIMER.record(System.nanoTime() - start, TimeUnit.NANOSECONDS);
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
        stream.onNext(cmd);
        return f.whenComplete((res, err) -> stream.onCompleted())
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
        return send(cmd, options.sendTimeout());
    }

    private CompletableFuture<CommandResultView> sendAsync(Supplier<ApiCommand> builder) {
        final ApiCommand cmd;
        try {
            cmd = builder.get();
        } catch (Exception ex) {
            return CompletableFuture.failedFuture(ex);
        }
        return sendAsync(cmd);
    }

    private CommandResultView await(CompletableFuture<CommandResultView> f) {
        try {
            return f.get(options.sendTimeout().toMillis(), TimeUnit.MILLISECONDS);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException) {
                throw (RuntimeException)cause;
            }
            throw new RuntimeException("cmd exec failed", cause);
        } catch (Exception e) {
            throw new RuntimeException("cmd exec failed", e);
        }
    }

    private static ApiCommand.Builder cmdBuilder() {
        return ApiCommand.newBuilder().setTimestamp(System.currentTimeMillis());
    }

    private int nextTransferId() {
        return reqIdGen.getAndIncrement();
    }

    private CommandResultView cacheOnSuccess(CommandResultView result, Runnable update) {
        if (result.getResultCode() == CommandResultCode.SUCCESS) {
            update.run();
        }
        return result;
    }

    private static CoreCurrencySpecification currencySpec(int id, String name, int digit) {
        return CoreCurrencySpecification.newBuilder().setId(id).setName(name).setDigit(digit).build();
    }

    /**
     * 在 client 层挡住 fee 配置的几条 invariant：
     * <ul>
     * <li>所有 fee 字段非负，否则 RiskEngine fee 计算会出现负扣 / 负 NSF</li>
     * <li>{@code takerFee >= makerFee}：BID 下单按 taker 估冻结上限，maker 高于 taker 会让实际成交多扣 maker accounts</li>
     * <li>{@code takerFee >= liquidationFee}：强平 fee 不应高于普通 taker fee（业务约定）</li>
     * </ul>
     */
    private static void validateFees(long takerFee, long makerFee, long liquidationFee, long feeScaleK) {
        if (takerFee < 0 || makerFee < 0 || liquidationFee < 0 || feeScaleK < 0) {
            throw new IllegalArgumentException("fee fields must be non-negative: taker=" + takerFee + " maker="
                + makerFee + " liquidation=" + liquidationFee + " feeScaleK=" + feeScaleK);
        }
        if (takerFee < makerFee) {
            throw new IllegalArgumentException("takerFee (" + takerFee + ") must be >= makerFee (" + makerFee + ")");
        }
        if (takerFee < liquidationFee) {
            throw new IllegalArgumentException(
                "takerFee (" + takerFee + ") must be >= liquidationFee (" + liquidationFee + ")");
        }
    }

    private static CoreSymbolSpecification symbolSpec(int id, SymbolType type, int baseCurrency, int quoteCurrency,
        long baseScaleK, long quoteScaleK, long takerFee, long makerFee, long liquidationFee, long feeScaleK,
        long initMargin, long initMarginScaleK, Map<Long, Long> maintenanceMargin, long maintenanceMarginScaleK,
        Map<Long, Long> maxLeverage) {
        validateFees(takerFee, makerFee, liquidationFee, feeScaleK);
        CoreSymbolSpecification.Builder b =
            CoreSymbolSpecification.newBuilder().setSymbolId(id).setType(type).setBaseCurrency(baseCurrency)
                .setQuoteCurrency(quoteCurrency).setBaseScaleK(baseScaleK).setQuoteScaleK(quoteScaleK)
                .setTakerFee(takerFee).setMakerFee(makerFee).setLiquidationFee(liquidationFee).setFeeScaleK(feeScaleK);
        if (type != SymbolType.CURRENCY_EXCHANGE_PAIR) {
            b.setInitMargin(initMargin).setInitMarginScaleK(initMarginScaleK).putAllMaintenanceMargin(maintenanceMargin)
                .setMaintenanceMarginScaleK(maintenanceMarginScaleK).putAllMaxLeverage(maxLeverage);
        }
        return b.build();
    }

    public CommandResultView addUser(long uid) {
        return await(addUserAsync(uid));
    }

    public CompletableFuture<CommandResultView> addUserAsync(long uid) {
        return sendAsync(() -> buildAddUserCommand(uid));
    }

    public ApiCommand buildAddUserCommand(long uid) {
        return cmdBuilder().setAddUser(ApiAddUser.newBuilder().setUid(uid)).build();
    }

    public CommandResultView suspendUser(long uid) {
        return await(suspendUserAsync(uid));
    }

    public CompletableFuture<CommandResultView> suspendUserAsync(long uid) {
        return sendAsync(() -> buildSuspendUserCommand(uid));
    }

    public ApiCommand buildSuspendUserCommand(long uid) {
        return cmdBuilder().setSuspendUser(ApiSuspendUser.newBuilder().setUid(uid)).build();
    }

    public CommandResultView resumeUser(long uid) {
        return await(resumeUserAsync(uid));
    }

    public CompletableFuture<CommandResultView> resumeUserAsync(long uid) {
        return sendAsync(() -> buildResumeUserCommand(uid));
    }

    public ApiCommand buildResumeUserCommand(long uid) {
        return cmdBuilder().setResumeUser(ApiResumeUser.newBuilder().setUid(uid)).build();
    }

    public CommandResultView adjustUserBalance(long uid, long transactionId, int currency, double amount) {
        return await(adjustUserBalanceAsync(uid, transactionId, currency, amount));
    }

    public CompletableFuture<CommandResultView> adjustUserBalanceAsync(long uid, long transactionId, int currency,
        double amount) {
        return sendAsync(() -> buildAdjustUserBalanceCommand(uid, transactionId, currency, amount));
    }

    public ApiCommand buildAdjustUserBalanceCommand(long uid, long transactionId, int currency, double amount) {
        long currencyScaleK = pow10(metadataManager.getCurrencySpec(currency).getDigit());
        return cmdBuilder().setAdjustBalance(
            ApiAdjustUserBalance.newBuilder().setTransactionId(transactionId).setUid(uid).setCurrency(currency)
                .setAmount(doubleToLong(amount, currencyScaleK)).setAdjustmentType(BalanceAdjustmentType.ADJUSTMENT))
            .build();
    }

    public CommandResultView addCurrency(int id, String name, int digit) {
        return await(addCurrencyAsync(id, name, digit));
    }

    public CompletableFuture<CommandResultView> addCurrencyAsync(int id, String name, int digit) {
        return sendAsync(() -> buildAddCurrencyCommand(id, name, digit)).thenApply(
            result -> cacheOnSuccess(result, () -> metadataManager.addCurrencySpec(currencySpec(id, name, digit))));
    }

    public ApiCommand buildAddCurrencyCommand(int id, String name, int digit) {
        if (metadataManager.currencyExists(id)) {
            throw new IllegalArgumentException("Currency with id " + id + " already exists.");
        }
        return cmdBuilder()
            .setBinaryData(
                ApiBinaryDataCommand.newBuilder().setTransferId(nextTransferId())
                    .setData(BinaryDataCommand.newBuilder().setAddCurrencies(
                        BatchAddCurrenciesCommand.newBuilder().putCurrencies(id, currencySpec(id, name, digit)))))
            .build();
    }

    public CommandResultView addSymbol(int id, SymbolType type, int baseCurrency, int quoteCurrency, long baseScaleK,
        long quoteScaleK, long takerFee, long makerFee, long liquidationFee, long feeScaleK, long initMargin,
        long initMarginScaleK, Map<Long, Long> maintenanceMargin, long maintenanceMarginScaleK,
        Map<Long, Long> maxLeverage) {
        return await(addSymbolAsync(id, type, baseCurrency, quoteCurrency, baseScaleK, quoteScaleK, takerFee, makerFee,
            liquidationFee, feeScaleK, initMargin, initMarginScaleK, maintenanceMargin, maintenanceMarginScaleK,
            maxLeverage));
    }

    public CompletableFuture<CommandResultView> addSymbolAsync(int id, SymbolType type, int baseCurrency,
        int quoteCurrency, long baseScaleK, long quoteScaleK, long takerFee, long makerFee, long liquidationFee,
        long feeScaleK, long initMargin, long initMarginScaleK, Map<Long, Long> maintenanceMargin,
        long maintenanceMarginScaleK, Map<Long, Long> maxLeverage) {
        return sendAsync(() -> buildAddSymbolCommand(id, type, baseCurrency, quoteCurrency, baseScaleK, quoteScaleK,
            takerFee, makerFee, liquidationFee, feeScaleK, initMargin, initMarginScaleK, maintenanceMargin,
            maintenanceMarginScaleK, maxLeverage))
                .thenApply(result -> cacheOnSuccess(result,
                    () -> metadataManager.addSymbolSpec(symbolSpec(id, type, baseCurrency, quoteCurrency, baseScaleK,
                        quoteScaleK, takerFee, makerFee, liquidationFee, feeScaleK, initMargin, initMarginScaleK,
                        maintenanceMargin, maintenanceMarginScaleK, maxLeverage))));
    }

    public ApiCommand buildAddSymbolCommand(int id, SymbolType type, int baseCurrency, int quoteCurrency,
        long baseScaleK, long quoteScaleK, long takerFee, long makerFee, long liquidationFee, long feeScaleK,
        long initMargin, long initMarginScaleK, Map<Long, Long> maintenanceMargin, long maintenanceMarginScaleK,
        Map<Long, Long> maxLeverage) {
        if (metadataManager.symbolExists(id)) {
            throw new IllegalArgumentException("Symbol with id " + id + " already exists.");
        }
        if (!metadataManager.currencyExists(baseCurrency)) {
            throw new IllegalArgumentException("Currency with id " + baseCurrency + " does not exist.");
        }
        if (!metadataManager.currencyExists(quoteCurrency)) {
            throw new IllegalArgumentException("Currency with id " + quoteCurrency + " does not exist.");
        }
        CoreSymbolSpecification spec = symbolSpec(id, type, baseCurrency, quoteCurrency, baseScaleK, quoteScaleK,
            takerFee, makerFee, liquidationFee, feeScaleK, initMargin, initMarginScaleK, maintenanceMargin,
            maintenanceMarginScaleK, maxLeverage);
        return cmdBuilder()
            .setBinaryData(ApiBinaryDataCommand.newBuilder().setTransferId(nextTransferId()).setData(
                BinaryDataCommand.newBuilder().setAddSymbols(BatchAddSymbolsCommand.newBuilder().putSymbols(id, spec))))
            .build();
    }

    public CommandResultView placeOrder(long uid, long orderId, int symbol, OrderAction action, OrderType type,
        double price, double reversePrice, double size, MarginMode marginMode, int leverage, boolean reduceOnly) {
        return await(placeOrderAsync(uid, orderId, symbol, action, type, price, reversePrice, size, marginMode,
            leverage, reduceOnly));
    }

    public CompletableFuture<CommandResultView> placeOrderAsync(long uid, long orderId, int symbol, OrderAction action,
        OrderType type, double price, double reversePrice, double size, MarginMode marginMode, int leverage,
        boolean reduceOnly) {
        return sendAsync(() -> buildPlaceOrderCommand(uid, orderId, symbol, action, type, price, reversePrice, size,
            marginMode, leverage, reduceOnly));
    }

    public ApiCommand buildPlaceOrderCommand(long uid, long orderId, int symbol, OrderAction action, OrderType type,
        double price, double reversePrice, double size, MarginMode marginMode, int leverage, boolean reduceOnly) {
        CoreSymbolSpecification spec = metadataManager.getSymbolSpec(symbol);
        boolean exchangeSymbol = spec.getType() == SymbolType.CURRENCY_EXCHANGE_PAIR;
        if (!exchangeSymbol && marginMode == null) {
            throw new IllegalArgumentException("MarginMode must be specified for non-exchange symbols.");
        }
        // BUDGET 单的 price 是 product-scale 总预算 notional，不是每单位价格
        boolean budgetOrder = type == OrderType.FOK_BUDGET || type == OrderType.IOC_BUDGET;
        long priceScaleK = budgetOrder ? spec.getBaseScaleK() * spec.getQuoteScaleK() : spec.getQuoteScaleK();
        long scaledPrice = doubleToLong(price, priceScaleK);
        long scaledReversePrice = doubleToLong(reversePrice, priceScaleK);
        long scaledSize = doubleToLong(size, spec.getBaseScaleK());
        long quotedPrice = action == OrderAction.BID ? scaledReversePrice : scaledPrice;
        long scaledNotional = budgetOrder ? quotedPrice : quotedPrice * scaledSize;
        if (!exchangeSymbol) {
            long maxLeverage = getFloorValue(spec.getMaxLeverageMap(), scaledNotional);
            if (leverage <= 0 || leverage > maxLeverage) {
                throw new IllegalArgumentException("Leverage illegal: " + leverage);
            }
        }
        ApiPlaceOrder.Builder builder =
            ApiPlaceOrder.newBuilder().setUid(uid).setOrderId(orderId).setSymbol(symbol).setAction(action)
                .setOrderType(type).setPrice(scaledPrice).setReservePrice(scaledReversePrice).setSize(scaledSize);
        if (!exchangeSymbol) {
            builder.setMarginMode(marginMode).setLeverage(leverage).setReduceOnly(reduceOnly);
        }
        return cmdBuilder().setPlaceOrder(builder).build();
    }

    public CommandResultView cancelOrder(long uid, long orderId, int symbol) {
        return await(cancelOrderAsync(uid, orderId, symbol));
    }

    public CompletableFuture<CommandResultView> cancelOrderAsync(long uid, long orderId, int symbol) {
        return sendAsync(() -> buildCancelOrderCommand(uid, orderId, symbol));
    }

    public ApiCommand buildCancelOrderCommand(long uid, long orderId, int symbol) {
        return cmdBuilder()
            .setCancelOrder(ApiCancelOrder.newBuilder().setUid(uid).setOrderId(orderId).setSymbol(symbol)).build();
    }

    public CommandResultView moveOrder(long uid, long orderId, int symbol, double newPrice) {
        return await(moveOrderAsync(uid, orderId, symbol, newPrice));
    }

    public CompletableFuture<CommandResultView> moveOrderAsync(long uid, long orderId, int symbol, double newPrice) {
        return sendAsync(() -> buildMoveOrderCommand(uid, orderId, symbol, newPrice));
    }

    public ApiCommand buildMoveOrderCommand(long uid, long orderId, int symbol, double newPrice) {
        long scaledNewPrice = doubleToLong(newPrice, metadataManager.getSymbolSpec(symbol).getQuoteScaleK());
        return cmdBuilder()
            .setMoveOrder(
                ApiMoveOrder.newBuilder().setUid(uid).setOrderId(orderId).setSymbol(symbol).setNewPrice(scaledNewPrice))
            .build();
    }

    public CommandResultView reduceOrder(long uid, long orderId, int symbol, double size) {
        return await(reduceOrderAsync(uid, orderId, symbol, size));
    }

    public CompletableFuture<CommandResultView> reduceOrderAsync(long uid, long orderId, int symbol, double size) {
        return sendAsync(() -> buildReduceOrderCommand(uid, orderId, symbol, size));
    }

    public ApiCommand buildReduceOrderCommand(long uid, long orderId, int symbol, double size) {
        long scaledSize = doubleToLong(size, metadataManager.getSymbolSpec(symbol).getBaseScaleK());
        return cmdBuilder()
            .setReduceOrder(
                ApiReduceOrder.newBuilder().setUid(uid).setOrderId(orderId).setSymbol(symbol).setReduceSize(scaledSize))
            .build();
    }

    public CommandResultView closePosition(long uid, long orderId, int symbol, OrderAction action, double price,
        double size) {
        return await(closePositionAsync(uid, orderId, symbol, action, price, size));
    }

    public CompletableFuture<CommandResultView> closePositionAsync(long uid, long orderId, int symbol,
        OrderAction action, double price, double size) {
        return sendAsync(() -> buildClosePositionCommand(uid, orderId, symbol, action, price, size));
    }

    public ApiCommand buildClosePositionCommand(long uid, long orderId, int symbol, OrderAction action, double price,
        double size) {
        CoreSymbolSpecification spec = metadataManager.getSymbolSpec(symbol);
        return cmdBuilder().setClosePosition(
            ApiClosePosition.newBuilder().setUid(uid).setOrderId(orderId).setSymbol(symbol).setAction(action)
                .setPrice(doubleToLong(price, spec.getQuoteScaleK())).setSize(doubleToLong(size, spec.getBaseScaleK())))
            .build();
    }

    public CommandResultView adjustLeverage(long uid, int symbol, int leverage) {
        return await(adjustLeverageAsync(uid, symbol, leverage));
    }

    public CompletableFuture<CommandResultView> adjustLeverageAsync(long uid, int symbol, int leverage) {
        return sendAsync(() -> buildAdjustLeverageCommand(uid, symbol, leverage));
    }

    public ApiCommand buildAdjustLeverageCommand(long uid, int symbol, int leverage) {
        return cmdBuilder()
            .setAdjustLeverage(ApiAdjustLeverage.newBuilder().setUid(uid).setSymbol(symbol).setLeverage(leverage))
            .build();
    }

    public CommandResultView adjustPositionMode(long uid, PositionMode positionMode) {
        return await(adjustPositionModeAsync(uid, positionMode));
    }

    public CompletableFuture<CommandResultView> adjustPositionModeAsync(long uid, PositionMode positionMode) {
        return sendAsync(() -> buildAdjustPositionModeCommand(uid, positionMode));
    }

    public ApiCommand buildAdjustPositionModeCommand(long uid, PositionMode positionMode) {
        return cmdBuilder()
            .setAdjustPositionMode(ApiAdjustPositionMode.newBuilder().setUid(uid).setPositionMode(positionMode))
            .build();
    }

    public CommandResultView adjustMargin(long uid, MarginMode mode, int symbolOrCurrency, double amount) {
        return await(adjustMarginAsync(uid, mode, symbolOrCurrency, amount));
    }

    public CompletableFuture<CommandResultView> adjustMarginAsync(long uid, MarginMode mode, int symbolOrCurrency,
        double amount) {
        return sendAsync(() -> buildAdjustMarginCommand(uid, mode, symbolOrCurrency, amount));
    }

    public ApiCommand buildAdjustMarginCommand(long uid, MarginMode mode, int symbolOrCurrency, double amount) {
        // 两种模式都用 currencyScaleK (10^digit) 编码 amount，对应服务端 adjustBalance 路径。
        // 注意：ISOLATED 不要用 spec.quoteScaleK，会导致扣款少一个数量级（强平价偏高）
        final int currency;
        final ApiAdjustMargin.Builder builder =
            ApiAdjustMargin.newBuilder().setTransactionId(nextTransferId()).setUid(uid);
        if (mode == MarginMode.ISOLATED) {
            currency = metadataManager.getSymbolSpec(symbolOrCurrency).getQuoteCurrency();
            builder.setSymbol(symbolOrCurrency);
        } else if (mode == MarginMode.CROSS) {
            currency = symbolOrCurrency;
            builder.setCurrency(symbolOrCurrency);
        } else {
            throw new IllegalArgumentException("Unsupported margin mode: " + mode);
        }
        long currencyScaleK = pow10(metadataManager.getCurrencySpec(currency).getDigit());
        builder.setAmount(doubleToLong(amount, currencyScaleK));
        return cmdBuilder().setAdjustMargin(builder).build();
    }

    public CommandResultView adjustMarkPrice(int symbol, double markPrice) {
        return await(adjustMarkPriceAsync(symbol, markPrice));
    }

    public CompletableFuture<CommandResultView> adjustMarkPriceAsync(int symbol, double markPrice) {
        return sendAsync(() -> buildAdjustMarkPriceCommand(symbol, markPrice));
    }

    public ApiCommand buildAdjustMarkPriceCommand(int symbol, double markPrice) {
        long scaled = doubleToLong(markPrice, metadataManager.getSymbolSpec(symbol).getQuoteScaleK());
        return cmdBuilder().setAdjustMarkprice(
            ApiAdjustMarkPrice.newBuilder().setTransactionId(nextTransferId()).setSymbol(symbol).setMarkPrice(scaled))
            .build();
    }

    public CommandResultView settleFundingFees(int symbol, OrderAction action, long fundingRate, long rateScaleK) {
        return await(settleFundingFeesAsync(symbol, action, fundingRate, rateScaleK));
    }

    public CompletableFuture<CommandResultView> settleFundingFeesAsync(int symbol, OrderAction action, long fundingRate,
        long rateScaleK) {
        return sendAsync(() -> buildSettleFundingFeesCommand(symbol, action, fundingRate, rateScaleK));
    }

    public ApiCommand buildSettleFundingFeesCommand(int symbol, OrderAction action, long fundingRate, long rateScaleK) {
        return cmdBuilder().setSettleFundingFees(ApiSettleFundingFees.newBuilder().setTransactionId(nextTransferId())
            .setSymbol(symbol).setAction(action).setFundingRate(fundingRate).setRateScaleK(rateScaleK)).build();
    }

    public CommandResultView insuranceFundDeposit(int symbol, int shardId, double amount) {
        return await(insuranceFundDepositAsync(symbol, shardId, amount));
    }

    public CompletableFuture<CommandResultView> insuranceFundDepositAsync(int symbol, int shardId, double amount) {
        return sendAsync(() -> buildInsuranceFundDepositCommand(symbol, shardId, amount));
    }

    public ApiCommand buildInsuranceFundDepositCommand(int symbol, int shardId, double amount) {
        int quoteCurrency = metadataManager.getSymbolSpec(symbol).getQuoteCurrency();
        long currencyScaleK = pow10(metadataManager.getCurrencySpec(quoteCurrency).getDigit());
        return cmdBuilder()
            .setInsuranceFundDeposit(ApiInsuranceFundDeposit.newBuilder().setTransactionId(nextTransferId())
                .setSymbol(symbol).setShardId(shardId).setCurrencyAmount(doubleToLong(amount, currencyScaleK)))
            .build();
    }

    public CommandResultView insuranceFundWithdraw(int symbol, int shardId, double amount) {
        return await(insuranceFundWithdrawAsync(symbol, shardId, amount));
    }

    public CompletableFuture<CommandResultView> insuranceFundWithdrawAsync(int symbol, int shardId, double amount) {
        return sendAsync(() -> buildInsuranceFundWithdrawCommand(symbol, shardId, amount));
    }

    public ApiCommand buildInsuranceFundWithdrawCommand(int symbol, int shardId, double amount) {
        int quoteCurrency = metadataManager.getSymbolSpec(symbol).getQuoteCurrency();
        long currencyScaleK = pow10(metadataManager.getCurrencySpec(quoteCurrency).getDigit());
        return cmdBuilder()
            .setInsuranceFundWithdraw(ApiInsuranceFundWithdraw.newBuilder().setTransactionId(nextTransferId())
                .setSymbol(symbol).setShardId(shardId).setCurrencyAmount(doubleToLong(amount, currencyScaleK)))
            .build();
    }

    public CommandResultView settlePnl(int symbol, double price) {
        return await(settlePnlAsync(symbol, price));
    }

    public CompletableFuture<CommandResultView> settlePnlAsync(int symbol, double price) {
        return sendAsync(() -> buildSettlePnlCommand(symbol, price));
    }

    public ApiCommand buildSettlePnlCommand(int symbol, double price) {
        long scaled = doubleToLong(price, metadataManager.getSymbolSpec(symbol).getQuoteScaleK());
        return cmdBuilder()
            .setSettlePnl(
                ApiSettlePNL.newBuilder().setTransactionId(nextTransferId()).setSymbol(symbol).setSettlePrice(scaled))
            .build();
    }

    public CommandResultView resetFee() {
        return await(resetFeeAsync());
    }

    public CompletableFuture<CommandResultView> resetFeeAsync() {
        return sendAsync(this::buildFeeResetCommand);
    }

    public ApiCommand buildFeeResetCommand() {
        return cmdBuilder().setResetFee(ApiResetFee.newBuilder()).build();
    }

    public CompletableFuture<SingleUserReportResultView> queryUserReport(long userId) {
        return client.singleUserReport(nextTransferId(), userId)
            .thenApply(result -> SingleUserReportResultView.build(result, metadataManager));
    }

    public CompletableFuture<CommandResultView> searchOrderBook(int symbol) {
        return client.searchOrderBook(symbol, 20).thenApply(result -> CommandResultView.build(result, metadataManager));
    }

    @Override
    public void close() {
        try {
            metadataManager.shutdown();
            client.close();
        } catch (Exception e) {
            LOG.warn("Error while closing ExchangeApi", e);
        }
    }
}
