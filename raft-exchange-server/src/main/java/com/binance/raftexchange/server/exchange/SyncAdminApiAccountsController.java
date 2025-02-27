package com.binance.raftexchange.server.exchange;

import com.binance.raftexchange.stubs.api.ApiAddUser;
import com.binance.raftexchange.stubs.api.ApiAdjustUserBalance;


public class SyncAdminApiAccountsController extends AbstractApiController {

    public static byte[] createUser(ApiAddUser grpcApiAddUser) throws Exception {
        exchange.core2.core.common.api.ApiAddUser apiAddUser =
                exchange.core2.core.common.api.ApiAddUser.builder().uid(grpcApiAddUser.getUid()).build();
        LOG.info("ApiAddUser applied, msg: {}", apiAddUser);

        return callExchange(apiAddUser);
    }

    public static byte[] adjustBalance(ApiAdjustUserBalance grpcApiAdjustUserBalance) throws Exception {
        exchange.core2.core.common.api.ApiAdjustUserBalance apiAdjustUserBalance = exchange.core2.core.common.api.ApiAdjustUserBalance.builder()
                .uid(grpcApiAdjustUserBalance.getUid())
                .currency(grpcApiAdjustUserBalance.getCurrency())
                .amount(grpcApiAdjustUserBalance.getAmount())
                .transactionId(grpcApiAdjustUserBalance.getTransactionId())
                .build();
        LOG.info("ApiAdjustUserBalance applied, msg: {}", apiAdjustUserBalance);

        return callExchange(apiAdjustUserBalance);
    }

}
