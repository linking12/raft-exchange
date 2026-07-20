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
import com.binance.raftexchange.stubs.request.ApiInternalTransfer;
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
import com.binance.raftexchange.stubs.request.ApiLoanCreate;
import com.binance.raftexchange.stubs.request.ApiLoanRepay;
import com.binance.raftexchange.stubs.request.ApiLoanAddCollateral;
import com.binance.raftexchange.stubs.request.ApiLoanReleaseCollateral;
import com.binance.raftexchange.stubs.request.ApiLoanCrossAddCollateral;
import com.binance.raftexchange.stubs.request.ApiLoanCrossWithdrawCollateral;
import com.binance.raftexchange.stubs.request.ApiLoanCrossBorrow;
import com.binance.raftexchange.stubs.request.ApiLoanCrossRepay;
import com.binance.raftexchange.stubs.request.ApiPoolDeposit;
import com.binance.raftexchange.stubs.request.ApiPoolWithdraw;
import com.binance.raftexchange.stubs.request.ApiLoanIfDeposit;
import com.binance.raftexchange.stubs.request.ApiLoanIfWithdraw;
import com.binance.raftexchange.stubs.request.ApiRepriceLoanRates;
import com.binance.raftexchange.stubs.request.BatchAddLoanCommand;
import com.binance.raftexchange.stubs.request.SpotLoanGlobalConfig;
import com.binance.raftexchange.stubs.request.SpotLoanConfig;
import com.binance.raftexchange.stubs.request.SpotLoanRateCurveConfig;
import com.binance.raftexchange.stubs.report.InsuranceFundReportResult;
import com.binance.raftexchange.stubs.report.LoanPlatformReportResult;
import com.binance.raftexchange.stubs.report.TotalCurrencyBalanceReportResult;
import com.binance.raftexchange.stubs.report.FeeReportResult;
import com.binance.raftexchange.stubs.report.StateHashReportResult;
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

    /** 金额按该 currency 的 digit 换算成整数记账单位；loan 各命令共用。 */
    private long toCurrencyAmount(int currency, double amount) {
        return doubleToLong(amount, pow10(metadataManager.getCurrencySpec(currency).getDigit()));
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

    // ====================================================================================
    // 账户与元数据：建户 / 挂起恢复 / 充提 / 转账 / 币种与 symbol 配置
    // ====================================================================================

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

    public CommandResultView transfer(long fromUid, long toUid, int currency, double amount, long transactionId) {
        return await(transferAsync(fromUid, toUid, currency, amount, transactionId));
    }

    public CompletableFuture<CommandResultView> transferAsync(long fromUid, long toUid, int currency, double amount,
        long transactionId) {
        return sendAsync(() -> buildInternalTransferCommand(fromUid, toUid, currency, amount, transactionId));
    }

    public ApiCommand buildInternalTransferCommand(long fromUid, long toUid, int currency, double amount,
        long transactionId) {
        long currencyScaleK = pow10(metadataManager.getCurrencySpec(currency).getDigit());
        return cmdBuilder()
            .setInternalTransfer(ApiInternalTransfer.newBuilder().setFromUid(fromUid).setToUid(toUid)
                .setCurrency(currency).setAmount(doubleToLong(amount, currencyScaleK)).setTransactionId(transactionId))
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
        if (type == SymbolType.CURRENCY_EXCHANGE_PAIR) {
            final CoreSymbolSpecification dup = metadataManager.findSpotPair(baseCurrency, quoteCurrency);
            if (dup != null) {
                throw new IllegalArgumentException("Spot pair " + baseCurrency + "/" + quoteCurrency
                    + " already served by symbolId " + dup.getSymbolId() + "; duplicates make loan pricing ambiguous.");
            }
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

    // ====================================================================================
    // 现货与撮合：下单 / 撤单 / 改单 / 减量
    // ====================================================================================

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

    // ====================================================================================
    // 期货：平仓 / 杠杆 / 持仓模式 / 保证金 / 标记价 / 资金费 / 保险基金 / PNL 结算
    // ====================================================================================

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
        builder.setMarginMode(mode);
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

    // ====================================================================================
    // 借贷：Isolated 逐笔 / Cross 账户级 / 池子与 LIF 运营 / 利率重定价
    // ====================================================================================

    /** Isolated 开仓：抵押 collateralAmount 换借出 principal，二者分别按抵押币 / 借款币精度换算。 */
    public CommandResultView loanCreate(long uid, long loanId, int symbol, double collateralAmount, double principal) {
        return await(loanCreateAsync(uid, loanId, symbol, collateralAmount, principal));
    }

    public CompletableFuture<CommandResultView> loanCreateAsync(long uid, long loanId, int symbol,
        double collateralAmount, double principal) {
        return sendAsync(() -> buildLoanCreateCommand(uid, loanId, symbol, collateralAmount, principal));
    }

    public ApiCommand buildLoanCreateCommand(long uid, long loanId, int symbol, double collateralAmount,
        double principal) {
        CoreSymbolSpecification spec = metadataManager.getSymbolSpec(symbol);
        return cmdBuilder()
            .setLoanCreate(ApiLoanCreate.newBuilder().setTransactionId(nextTransferId()).setUid(uid).setLoanId(loanId)
                .setSymbol(symbol).setCollateralAmount(toCurrencyAmount(spec.getBaseCurrency(), collateralAmount))
                .setPrincipal(toCurrencyAmount(spec.getQuoteCurrency(), principal)))
            .build();
    }

    /**
     * Isolated 还款；repayAmount 传 0 表示 payoff 全部本息。
     * <p>
     * <b>symbol 仅用于取 quote 币精度换算金额</b>——实际记账币由服务端从 loan 记录读取，传错 symbol 不会报错， 只会把金额按错误精度缩放。
     */
    public CommandResultView loanRepay(long uid, long loanId, int symbol, double repayAmount) {
        return await(loanRepayAsync(uid, loanId, symbol, repayAmount));
    }

    public CompletableFuture<CommandResultView> loanRepayAsync(long uid, long loanId, int symbol, double repayAmount) {
        return sendAsync(() -> buildLoanRepayCommand(uid, loanId, symbol, repayAmount));
    }

    public ApiCommand buildLoanRepayCommand(long uid, long loanId, int symbol, double repayAmount) {
        int loanCurrency = metadataManager.getSymbolSpec(symbol).getQuoteCurrency();
        return cmdBuilder().setLoanRepay(ApiLoanRepay.newBuilder().setTransactionId(nextTransferId()).setUid(uid)
            .setLoanId(loanId).setRepayAmount(toCurrencyAmount(loanCurrency, repayAmount))).build();
    }

    /** Isolated 补抵押降 LTV。symbol 仅用于取 base 币精度，实际抵押币由服务端从 loan 记录读取。 */
    public CommandResultView loanAddCollateral(long uid, long loanId, int symbol, double amount) {
        return await(loanAddCollateralAsync(uid, loanId, symbol, amount));
    }

    public CompletableFuture<CommandResultView> loanAddCollateralAsync(long uid, long loanId, int symbol,
        double amount) {
        return sendAsync(() -> buildLoanAddCollateralCommand(uid, loanId, symbol, amount));
    }

    public ApiCommand buildLoanAddCollateralCommand(long uid, long loanId, int symbol, double amount) {
        int collateralCurrency = metadataManager.getSymbolSpec(symbol).getBaseCurrency();
        return cmdBuilder().setLoanAddCollateral(ApiLoanAddCollateral.newBuilder().setTransactionId(nextTransferId())
            .setUid(uid).setLoanId(loanId).setAmount(toCurrencyAmount(collateralCurrency, amount))).build();
    }

    /** Isolated 减抵押；减后 LTV 仍须低于强平线。symbol 仅用于取 base 币精度，实际抵押币由服务端从 loan 记录读取。 */
    public CommandResultView loanReleaseCollateral(long uid, long loanId, int symbol, double amount) {
        return await(loanReleaseCollateralAsync(uid, loanId, symbol, amount));
    }

    public CompletableFuture<CommandResultView> loanReleaseCollateralAsync(long uid, long loanId, int symbol,
        double amount) {
        return sendAsync(() -> buildLoanReleaseCollateralCommand(uid, loanId, symbol, amount));
    }

    public ApiCommand buildLoanReleaseCollateralCommand(long uid, long loanId, int symbol, double amount) {
        int collateralCurrency = metadataManager.getSymbolSpec(symbol).getBaseCurrency();
        return cmdBuilder()
            .setLoanReleaseCollateral(ApiLoanReleaseCollateral.newBuilder().setTransactionId(nextTransferId())
                .setUid(uid).setLoanId(loanId).setAmount(toCurrencyAmount(collateralCurrency, amount)))
            .build();
    }

    /** Cross 账户级抵押池增；抵押是账户共享的，不绑定单笔 loan。 */
    public CommandResultView loanCrossAddCollateral(long uid, int currency, double amount) {
        return await(loanCrossAddCollateralAsync(uid, currency, amount));
    }

    public CompletableFuture<CommandResultView> loanCrossAddCollateralAsync(long uid, int currency, double amount) {
        return sendAsync(() -> buildLoanCrossAddCollateralCommand(uid, currency, amount));
    }

    public ApiCommand buildLoanCrossAddCollateralCommand(long uid, int currency, double amount) {
        return cmdBuilder()
            .setLoanCrossAddCollateral(ApiLoanCrossAddCollateral.newBuilder().setTransactionId(nextTransferId())
                .setUid(uid).setCurrency(currency).setAmount(toCurrencyAmount(currency, amount)))
            .build();
    }

    /** Cross 抵押池减；撤后账户级 LTV 仍须低于强平线。 */
    public CommandResultView loanCrossWithdrawCollateral(long uid, int currency, double amount) {
        return await(loanCrossWithdrawCollateralAsync(uid, currency, amount));
    }

    public CompletableFuture<CommandResultView> loanCrossWithdrawCollateralAsync(long uid, int currency,
        double amount) {
        return sendAsync(() -> buildLoanCrossWithdrawCollateralCommand(uid, currency, amount));
    }

    public ApiCommand buildLoanCrossWithdrawCollateralCommand(long uid, int currency, double amount) {
        return cmdBuilder().setLoanCrossWithdrawCollateral(
            ApiLoanCrossWithdrawCollateral.newBuilder().setTransactionId(nextTransferId()).setUid(uid)
                .setCurrency(currency).setAmount(toCurrencyAmount(currency, amount)))
            .build();
    }

    /** Cross 借款；共享账户级抵押池，借后账户 LTV 超 initial 线则拒绝。symbolId 指现货 pair，loanCurrency=spec.quoteCurrency。 */
    public CommandResultView loanCrossBorrow(long uid, long loanId, int symbolId, double principal) {
        return await(loanCrossBorrowAsync(uid, loanId, symbolId, principal));
    }

    public CompletableFuture<CommandResultView> loanCrossBorrowAsync(long uid, long loanId, int symbolId,
        double principal) {
        return sendAsync(() -> buildLoanCrossBorrowCommand(uid, loanId, symbolId, principal));
    }

    public ApiCommand buildLoanCrossBorrowCommand(long uid, long loanId, int symbolId, double principal) {
        // principal 精度换算需 loanCurrency，从 symbolId 反推（quoteCurrency）；实际记账币由服务端同样反推，两者一致
        final int loanCurrency = metadataManager.getSymbolSpec(symbolId).getQuoteCurrency();
        return cmdBuilder()
            .setLoanCrossBorrow(ApiLoanCrossBorrow.newBuilder().setTransactionId(nextTransferId()).setUid(uid)
                .setLoanId(loanId).setSymbolId(symbolId).setPrincipal(toCurrencyAmount(loanCurrency, principal)))
            .build();
    }

    /**
     * Cross 还款；不释放抵押（抵押账户级共享）。repayAmount 传 0 表示 payoff。
     * <p>
     * <b>loanCurrency 仅用于精度换算</b>，实际记账币由服务端从 loan 记录读取，两者不一致时金额会被错误缩放。
     */
    public CommandResultView loanCrossRepay(long uid, long loanId, int loanCurrency, double repayAmount) {
        return await(loanCrossRepayAsync(uid, loanId, loanCurrency, repayAmount));
    }

    public CompletableFuture<CommandResultView> loanCrossRepayAsync(long uid, long loanId, int loanCurrency,
        double repayAmount) {
        return sendAsync(() -> buildLoanCrossRepayCommand(uid, loanId, loanCurrency, repayAmount));
    }

    public ApiCommand buildLoanCrossRepayCommand(long uid, long loanId, int loanCurrency, double repayAmount) {
        return cmdBuilder().setLoanCrossRepay(ApiLoanCrossRepay.newBuilder().setTransactionId(nextTransferId())
            .setUid(uid).setLoanId(loanId).setRepayAmount(toCurrencyAmount(loanCurrency, repayAmount))).build();
    }

    // ====================================================================================
    // 借贷运营：池子充提 / LIF 充提 / 利率重定价 / 借贷配置下发
    // ====================================================================================

    /** 借贷池注资（shardId 定向，非真实 uid）。 */
    public CommandResultView poolDeposit(int shardId, int currency, double amount) {
        return await(poolDepositAsync(shardId, currency, amount));
    }

    public CompletableFuture<CommandResultView> poolDepositAsync(int shardId, int currency, double amount) {
        return sendAsync(() -> buildPoolDepositCommand(shardId, currency, amount));
    }

    public ApiCommand buildPoolDepositCommand(int shardId, int currency, double amount) {
        return cmdBuilder().setPoolDeposit(ApiPoolDeposit.newBuilder().setShardId(shardId).setCurrency(currency)
            .setAmount(toCurrencyAmount(currency, amount))).build();
    }

    /** 借贷池提取；只能提未借出部分。 */
    public CommandResultView poolWithdraw(int shardId, int currency, double amount) {
        return await(poolWithdrawAsync(shardId, currency, amount));
    }

    public CompletableFuture<CommandResultView> poolWithdrawAsync(int shardId, int currency, double amount) {
        return sendAsync(() -> buildPoolWithdrawCommand(shardId, currency, amount));
    }

    public ApiCommand buildPoolWithdrawCommand(int shardId, int currency, double amount) {
        return cmdBuilder().setPoolWithdraw(ApiPoolWithdraw.newBuilder().setShardId(shardId).setCurrency(currency)
            .setAmount(toCurrencyAmount(currency, amount))).build();
    }

    /** LIF 注资：开张垫付启动资金，或接管把 LIF 打成负数后补仓。 */
    public CommandResultView loanIfDeposit(int shardId, int currency, double amount) {
        return await(loanIfDepositAsync(shardId, currency, amount));
    }

    public CompletableFuture<CommandResultView> loanIfDepositAsync(int shardId, int currency, double amount) {
        return sendAsync(() -> buildLoanIfDepositCommand(shardId, currency, amount));
    }

    public ApiCommand buildLoanIfDepositCommand(int shardId, int currency, double amount) {
        return cmdBuilder().setLoanIfDeposit(ApiLoanIfDeposit.newBuilder().setShardId(shardId).setCurrency(currency)
            .setAmount(toCurrencyAmount(currency, amount))).build();
    }

    /** LIF 提取：处置接管来的抵押库存，余额不足即拒（LIF 为负是接管的被动结果，不可主动透支）。 */
    public CommandResultView loanIfWithdraw(int shardId, int currency, double amount) {
        return await(loanIfWithdrawAsync(shardId, currency, amount));
    }

    public CompletableFuture<CommandResultView> loanIfWithdrawAsync(int shardId, int currency, double amount) {
        return sendAsync(() -> buildLoanIfWithdrawCommand(shardId, currency, amount));
    }

    public ApiCommand buildLoanIfWithdrawCommand(int shardId, int currency, double amount) {
        return cmdBuilder().setLoanIfWithdraw(ApiLoanIfWithdraw.newBuilder().setShardId(shardId).setCurrency(currency)
            .setAmount(toCurrencyAmount(currency, amount))).build();
    }

    /** 按池子利用率重算各币种浮动利率（定时器也会发，此处供运维手动触发）。 */
    public CommandResultView repriceLoanRates() {
        return await(repriceLoanRatesAsync());
    }

    public CompletableFuture<CommandResultView> repriceLoanRatesAsync() {
        return sendAsync(this::buildRepriceLoanRatesCommand);
    }

    public ApiCommand buildRepriceLoanRatesCommand() {
        return cmdBuilder().setRepriceLoanRates(ApiRepriceLoanRates.newBuilder()).build();
    }

    /**
     * 借贷配置下发（binary 通道）。三段可选：全局策略 / 单 symbol 市场 / 利率曲线，传 null 即不改该段。 停借用 {@code initialLtvBps = 0}，此时须显式带上原
     * liquidation/marginCall/weight，否则存量贷款会被连带强平。
     */
    public CommandResultView addLoanConfig(SpotLoanGlobalConfig global, SpotLoanConfig symbol,
        SpotLoanRateCurveConfig rate) {
        return await(addLoanConfigAsync(global, symbol, rate));
    }

    public CompletableFuture<CommandResultView> addLoanConfigAsync(SpotLoanGlobalConfig global, SpotLoanConfig symbol,
        SpotLoanRateCurveConfig rate) {
        return sendAsync(() -> buildAddLoanConfigCommand(global, symbol, rate));
    }

    public ApiCommand buildAddLoanConfigCommand(SpotLoanGlobalConfig global, SpotLoanConfig symbol,
        SpotLoanRateCurveConfig rate) {
        BatchAddLoanCommand.Builder loan = BatchAddLoanCommand.newBuilder();
        if (global != null) {
            loan.setGlobal(global);
        }
        if (symbol != null) {
            loan.setSymbol(symbol);
        }
        if (rate != null) {
            loan.setRateCurve(rate);
        }
        return cmdBuilder().setBinaryData(ApiBinaryDataCommand.newBuilder().setTransferId(nextTransferId())
            .setData(BinaryDataCommand.newBuilder().setAddLoan(loan))).build();
    }

    // ====================================================================================
    // 平台运营：手续费重置
    // ====================================================================================

    public CommandResultView resetFee() {
        return await(resetFeeAsync());
    }

    public CompletableFuture<CommandResultView> resetFeeAsync() {
        return sendAsync(this::buildFeeResetCommand);
    }

    public ApiCommand buildFeeResetCommand() {
        return cmdBuilder().setResetFee(ApiResetFee.newBuilder()).build();
    }

    // ====================================================================================
    // 查询：用户报表 / 平台报表 / 订单簿
    // ====================================================================================

    public CompletableFuture<SingleUserReportResultView> queryUserReport(long userId) {
        return client.singleUserReport(nextTransferId(), userId)
            .thenApply(result -> SingleUserReportResultView.build(result, metadataManager));
    }

    /** 两个保险基金池：期货 IF（per-symbol）+ 借贷 LIF（per-currency，可为负）。运营水位监控的数据源。 */
    public CompletableFuture<InsuranceFundReportResult> queryInsuranceFundReport() {
        return client.insuranceFundReport(nextTransferId());
    }

    /** 借贷平台侧账本：per-shard × per-currency 的 interestRevenue / LIF / poolAvailable / poolBorrowed。 */
    public CompletableFuture<LoanPlatformReportResult> queryLoanPlatformReport() {
        return client.loanPlatformReport(nextTransferId());
    }

    /** 全局资金守恒对账视图。 */
    public CompletableFuture<TotalCurrencyBalanceReportResult> queryTotalCurrencyBalanceReport() {
        return client.totalCurrencyBalanceReport(nextTransferId());
    }

    public CompletableFuture<FeeReportResult> queryFeeReport() {
        return client.feeReport(nextTransferId());
    }

    public CompletableFuture<StateHashReportResult> queryStateHashReport() {
        return client.stateHashReport(nextTransferId());
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
