package com.binance.raftexchange.server.exchange;

import com.binance.raftexchange.stubs.request.AccountBalanceMap;
import com.binance.raftexchange.stubs.request.ApiAddUser;
import com.binance.raftexchange.stubs.request.ApiAdjustMargin;
import com.binance.raftexchange.stubs.request.ApiAdjustUserBalance;
import com.binance.raftexchange.stubs.request.ApiResumeUser;
import com.binance.raftexchange.stubs.request.ApiSuspendUser;
import com.binance.raftexchange.stubs.request.BatchAddAccountsCommand;
import org.eclipse.collections.impl.map.mutable.primitive.IntLongHashMap;
import org.eclipse.collections.impl.map.mutable.primitive.LongObjectHashMap;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

public class SyncAdminApiAccountsController extends AbstractApiController {

    /**
     * 创建用户
     */
    public static CompletableFuture<byte[]> createUser(ApiAddUser grpcApiAddUser) {
        exchange.core2.core.common.api.ApiAddUser apiAddUser = exchange.core2.core.common.api.ApiAddUser.builder().uid(grpcApiAddUser.getUid()).build();
        LOG.debug("ApiAddUser applied, msg: {}", apiAddUser);
        return callExchange(apiAddUser);
    }

    /**
     * 增加资金
     */
    public static CompletableFuture<byte[]> adjustBalance(ApiAdjustUserBalance grpcApiAdjustUserBalance) {
        exchange.core2.core.common.api.ApiAdjustUserBalance apiAdjustUserBalance = exchange.core2.core.common.api.ApiAdjustUserBalance.builder()
            .uid(grpcApiAdjustUserBalance.getUid()).currency(grpcApiAdjustUserBalance.getCurrency()).amount(grpcApiAdjustUserBalance.getAmount())
            .transactionId(grpcApiAdjustUserBalance.getTransactionId()).build();
        LOG.debug("ApiAdjustUserBalance applied, msg: {}", apiAdjustUserBalance);
        return callExchange(apiAdjustUserBalance);
    }

    /**
     * 禁用用户
     */
    public static CompletableFuture<byte[]> suspendUser(ApiSuspendUser grpcApiSuspendUser) {
        exchange.core2.core.common.api.ApiSuspendUser apiSuspendUser =
            exchange.core2.core.common.api.ApiSuspendUser.builder().uid(grpcApiSuspendUser.getUid()).build();
        LOG.debug("ApiSuspendUser applied, msg: {}", apiSuspendUser);
        return callExchange(apiSuspendUser);
    }

    /**
     * 解禁用户
     */
    public static CompletableFuture<byte[]> resumeUser(ApiResumeUser grpcApiResumeUser) {
        exchange.core2.core.common.api.ApiResumeUser apiResumeUser =
            exchange.core2.core.common.api.ApiResumeUser.builder().uid(grpcApiResumeUser.getUid()).build();
        LOG.debug("ApiResumeUser applied, msg: {}", apiResumeUser);
        return callExchange(apiResumeUser);
    }

    /**
     * 批量创建账户
     */
    public static CompletableFuture<byte[]> batchAddAccounts(BatchAddAccountsCommand grpcBatchAddAccountsCommand) {
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

    public static CompletableFuture<byte[]> adjustMargin(ApiAdjustMargin grpcApiAdjustMargin) {
        exchange.core2.core.common.api.ApiAdjustMargin apiAdjustMargin = exchange.core2.core.common.api.ApiAdjustMargin.builder()
            .transactionId(grpcApiAdjustMargin.getTransactionId()).uid(grpcApiAdjustMargin.getUid()).symbol(grpcApiAdjustMargin.getSymbol())
            .currency(grpcApiAdjustMargin.getCurrency()).amount(grpcApiAdjustMargin.getAmount()).marginMode(exchange.core2.core.common.MarginMode.values()[grpcApiAdjustMargin.getMarginMode().getNumber()]).build();
        LOG.debug("ApiAdjustMargin applied, msg: {}", apiAdjustMargin);
        return callExchange(apiAdjustMargin);
    }
}
