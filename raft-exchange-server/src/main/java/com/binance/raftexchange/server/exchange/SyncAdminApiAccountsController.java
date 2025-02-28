package com.binance.raftexchange.server.exchange;

import com.binance.raftexchange.stubs.request.AccountBalanceMap;
import com.binance.raftexchange.stubs.request.ApiAddUser;
import com.binance.raftexchange.stubs.request.ApiAdjustUserBalance;
import com.binance.raftexchange.stubs.request.ApiResumeUser;
import com.binance.raftexchange.stubs.request.ApiSuspendUser;
import com.binance.raftexchange.stubs.request.BatchAddAccountsCommand;
import org.eclipse.collections.impl.map.mutable.primitive.IntLongHashMap;
import org.eclipse.collections.impl.map.mutable.primitive.LongObjectHashMap;

import java.util.Map;

public class SyncAdminApiAccountsController extends AbstractApiController {

    /**
     * 创建用户
     */
    public static byte[] createUser(ApiAddUser grpcApiAddUser) throws Exception {
        exchange.core2.core.common.api.ApiAddUser apiAddUser =
            exchange.core2.core.common.api.ApiAddUser.builder().uid(grpcApiAddUser.getUid()).build();
        LOG.info("ApiAddUser applied, msg: {}", apiAddUser);
        return callExchange(apiAddUser);
    }

    /**
     * 调整保证金
     */
    public static byte[] adjustBalance(ApiAdjustUserBalance grpcApiAdjustUserBalance) throws Exception {
        exchange.core2.core.common.api.ApiAdjustUserBalance apiAdjustUserBalance =
            exchange.core2.core.common.api.ApiAdjustUserBalance.builder().uid(grpcApiAdjustUserBalance.getUid())
                .currency(grpcApiAdjustUserBalance.getCurrency()).amount(grpcApiAdjustUserBalance.getAmount())
                .transactionId(grpcApiAdjustUserBalance.getTransactionId()).build();
        LOG.info("ApiAdjustUserBalance applied, msg: {}", apiAdjustUserBalance);
        return callExchange(apiAdjustUserBalance);
    }


    public static byte[] suspendUser(ApiSuspendUser grpcApiSuspendUser) throws Exception {
        exchange.core2.core.common.api.ApiSuspendUser apiSuspendUser = exchange.core2.core.common.api.ApiSuspendUser.builder()
                .uid(grpcApiSuspendUser.getUid())
                .build();
        LOG.info("ApiSuspendUser applied, msg: {}", apiSuspendUser);
        return callExchange(apiSuspendUser);
    }

    public static byte[] resumeUser(ApiResumeUser grpcApiResumeUser) throws Exception {
        exchange.core2.core.common.api.ApiResumeUser apiResumeUser = exchange.core2.core.common.api.ApiResumeUser.builder()
                .uid(grpcApiResumeUser.getUid())
                .build();
        LOG.info("ApiResumeUser applied, msg: {}", apiResumeUser);
        return callExchange(apiResumeUser);
    }

    public static byte[] batchAddAccounts(BatchAddAccountsCommand grpcBatchAddAccountsCommand) throws Exception {
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
        exchange.core2.core.common.api.binary.BatchAddAccountsCommand batchAddAccountsCommand = new exchange.core2.core.common.api.binary.BatchAddAccountsCommand(users);
        LOG.info("batchAddAccountsCommand applied, msg: {}", batchAddAccountsCommand);

        return callExchange(batchAddAccountsCommand);
    }
}
