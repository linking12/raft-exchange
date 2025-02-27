package com.binance.raftexchange.server.exchange;

import com.binance.raftexchange.stubs.api.ApiAddUser;
import com.binance.raftexchange.stubs.api.ApiAdjustUserBalance;
import exchange.core2.core.ExchangeApi;
import exchange.core2.core.common.cmd.CommandResultCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CompletableFuture;

public class SyncAdminApiAccountsController {

    private static final Logger LOG = LoggerFactory.getLogger(SyncAdminApiAccountsController.class);

    public static void createUser(ApiAddUser grpcApiAddUser) throws Exception {
        exchange.core2.core.common.api.ApiAddUser apiAddUser =
                exchange.core2.core.common.api.ApiAddUser.builder().uid(grpcApiAddUser.getUid()).build();
        LOG.info("ApiAddUser applied, msg: {}", apiAddUser);

        ExchangeApi api = ExchangeApiInstance.exchangeApi();
        CompletableFuture<CommandResultCode> future = api.submitCommandAsync(apiAddUser);
        CommandResultCode resultCode = future.get();
        LOG.info("ApiAddUser called, result: {}", resultCode);
    }

    public static void adjustBalance(ApiAdjustUserBalance grpcApiAdjustUserBalance) throws Exception {
        exchange.core2.core.common.api.ApiAdjustUserBalance apiAdjustUserBalance = exchange.core2.core.common.api.ApiAdjustUserBalance.builder()
                .uid(grpcApiAdjustUserBalance.getUid())
                .currency(grpcApiAdjustUserBalance.getCurrency())
                .amount(grpcApiAdjustUserBalance.getAmount())
                .transactionId(grpcApiAdjustUserBalance.getTransactionId())
                .build();
        LOG.info("ApiAdjustUserBalance applied, msg: {}", apiAdjustUserBalance);
    }

}
