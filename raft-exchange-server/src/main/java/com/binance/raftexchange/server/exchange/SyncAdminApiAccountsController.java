package com.binance.raftexchange.server.exchange;

import com.binance.raftexchange.stubs.request.AccountBalanceMap;
import com.binance.raftexchange.stubs.request.ApiAddUser;
import com.binance.raftexchange.stubs.request.ApiAdjustMargin;
import com.binance.raftexchange.stubs.request.ApiAdjustPositionMode;
import com.binance.raftexchange.stubs.request.ApiAdjustUserBalance;
import com.binance.raftexchange.stubs.request.ApiCommand;
import com.binance.raftexchange.stubs.request.ApiResumeUser;
import com.binance.raftexchange.stubs.request.ApiSuspendUser;
import com.binance.raftexchange.stubs.request.BatchAddAccountsCommand;
import org.eclipse.collections.impl.map.mutable.primitive.IntLongHashMap;
import org.eclipse.collections.impl.map.mutable.primitive.LongObjectHashMap;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

public class SyncAdminApiAccountsController extends AbstractApiController {

    /**
     * 创建用户
     */
    public static CompletableFuture<Supplier<byte[]>> createUser(ApiCommand apiCommand) {
        LOG.debug("ApiAddUser applied, msg: {}", apiCommand.getAddUser());
        return callExchange(convertAddUser(apiCommand));
    }

    public static exchange.core2.core.common.api.ApiAddUser convertAddUser(ApiCommand apiCommand) {
        ApiAddUser grpcApiAddUser = apiCommand.getAddUser();
        exchange.core2.core.common.api.ApiAddUser apiAddUser = exchange.core2.core.common.api.ApiAddUser.builder().uid(grpcApiAddUser.getUid()).build();
        apiAddUser.updateTimestamp(apiCommand.getTimestamp());
        return apiAddUser;
    }

    /**
     * 增加资金
     */
    public static CompletableFuture<Supplier<byte[]>> adjustBalance(ApiCommand apiCommand) {
        LOG.debug("ApiAdjustUserBalance applied, msg: {}", apiCommand.getAdjustBalance());
        return callExchange(convertAdjustBalance(apiCommand));
    }

    public static exchange.core2.core.common.api.ApiAdjustUserBalance convertAdjustBalance(ApiCommand apiCommand) {
        ApiAdjustUserBalance grpcApiAdjustUserBalance = apiCommand.getAdjustBalance();
        exchange.core2.core.common.api.ApiAdjustUserBalance apiAdjustUserBalance = exchange.core2.core.common.api.ApiAdjustUserBalance.builder()
            .uid(grpcApiAdjustUserBalance.getUid()).currency(grpcApiAdjustUserBalance.getCurrency()).amount(grpcApiAdjustUserBalance.getAmount())
            .transactionId(grpcApiAdjustUserBalance.getTransactionId()).build();
        apiAdjustUserBalance.updateTimestamp(apiCommand.getTimestamp());
        return apiAdjustUserBalance;
    }

    /**
     * 禁用用户
     */
    public static CompletableFuture<Supplier<byte[]>> suspendUser(ApiCommand apiCommand) {
        LOG.debug("ApiSuspendUser applied, msg: {}", apiCommand.getSuspendUser());
        return callExchange(convertSuspendUser(apiCommand));
    }

    public static exchange.core2.core.common.api.ApiSuspendUser convertSuspendUser(ApiCommand apiCommand) {
        ApiSuspendUser grpcApiSuspendUser = apiCommand.getSuspendUser();
        exchange.core2.core.common.api.ApiSuspendUser apiSuspendUser =
            exchange.core2.core.common.api.ApiSuspendUser.builder().uid(grpcApiSuspendUser.getUid()).build();
        apiSuspendUser.updateTimestamp(apiCommand.getTimestamp());
        return apiSuspendUser;
    }

    /**
     * 解禁用户
     */
    public static CompletableFuture<Supplier<byte[]>> resumeUser(ApiCommand apiCommand) {
        LOG.debug("ApiResumeUser applied, msg: {}", apiCommand.getResumeUser());
        return callExchange(convertResumeUser(apiCommand));
    }

    public static exchange.core2.core.common.api.ApiResumeUser convertResumeUser(ApiCommand apiCommand) {
        ApiResumeUser grpcApiResumeUser = apiCommand.getResumeUser();
        exchange.core2.core.common.api.ApiResumeUser apiResumeUser =
            exchange.core2.core.common.api.ApiResumeUser.builder().uid(grpcApiResumeUser.getUid()).build();
        apiResumeUser.updateTimestamp(apiCommand.getTimestamp());
        return apiResumeUser;
    }

    /**
     * 批量创建账户
     */
    public static CompletableFuture<Supplier<byte[]>> batchAddAccounts(BatchAddAccountsCommand grpcBatchAddAccountsCommand) {
        Map<Long, AccountBalanceMap> usersMap = grpcBatchAddAccountsCommand.getUsersMap();
        LongObjectHashMap<IntLongHashMap> users = new LongObjectHashMap<>(usersMap.size());
        for (Map.Entry<Long, AccountBalanceMap> entry : usersMap.entrySet()) {
            Long uid = entry.getKey();
            AccountBalanceMap accounts = entry.getValue();
            for (Map.Entry<Integer, Long> e : accounts.getBalancesMap().entrySet()) {
                Integer currency = e.getKey();
                Long balance = e.getValue();
                users.getIfAbsentPut(uid, new IntLongHashMap()).put(currency, balance);
            }
        }
        exchange.core2.core.common.api.binary.BatchAddAccountsCommand batchAddAccountsCommand =
            new exchange.core2.core.common.api.binary.BatchAddAccountsCommand(users);
        LOG.debug("batchAddAccountsCommand applied, msg: {}", batchAddAccountsCommand);

        return callExchange(batchAddAccountsCommand);
    }

    /**
     * 调整持仓模式
     */
    public static CompletableFuture<Supplier<byte[]>> adjustPositionMode(ApiCommand apiCommand) {
        LOG.debug("ApiAdjustPositionMode applied, msg: {}", apiCommand.getAdjustPositionMode());
        return callExchange(convertAdjustPositionMode(apiCommand));
    }

    public static exchange.core2.core.common.api.ApiAdjustPositionMode convertAdjustPositionMode(ApiCommand apiCommand) {
        ApiAdjustPositionMode grpcApiAdjustPositionMode = apiCommand.getAdjustPositionMode();
        exchange.core2.core.common.api.ApiAdjustPositionMode apiAdjustPositionMode = exchange.core2.core.common.api.ApiAdjustPositionMode.builder()
            .uid(grpcApiAdjustPositionMode.getUid()).positionMode(exchange.core2.core.common.PositionMode.values()[grpcApiAdjustPositionMode.getPositionMode().getNumber()]).build();
        apiAdjustPositionMode.updateTimestamp(apiCommand.getTimestamp());
        return apiAdjustPositionMode;
    }

    /**
     * 增加补充保证金
     */
    public static CompletableFuture<Supplier<byte[]>> adjustMargin(ApiCommand apiCommand) {
        LOG.debug("ApiAdjustMargin applied, msg: {}", apiCommand.getAdjustMargin());
        return callExchange(convertAdjustMargin(apiCommand));
    }

    public static exchange.core2.core.common.api.ApiAdjustMargin convertAdjustMargin(ApiCommand apiCommand) {
        ApiAdjustMargin grpcApiAdjustMargin = apiCommand.getAdjustMargin();
        exchange.core2.core.common.api.ApiAdjustMargin apiAdjustMargin = exchange.core2.core.common.api.ApiAdjustMargin.builder()
            .transactionId(grpcApiAdjustMargin.getTransactionId()).uid(grpcApiAdjustMargin.getUid()).symbol(grpcApiAdjustMargin.getSymbol())
            .currency(grpcApiAdjustMargin.getCurrency()).amount(grpcApiAdjustMargin.getAmount())
            .marginMode(exchange.core2.core.common.MarginMode.values()[grpcApiAdjustMargin.getMarginMode().getNumber()]).build();
        apiAdjustMargin.updateTimestamp(apiCommand.getTimestamp());
        return apiAdjustMargin;
    }
}
