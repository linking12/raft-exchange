package com.binance.raftexchange.server.exchange;

import com.binance.raftexchange.stubs.request.ApiAddUser;
import com.binance.raftexchange.stubs.request.ApiAdjustUserBalance;
import com.binance.raftexchange.stubs.request.ApiSuspendUser;

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
}
