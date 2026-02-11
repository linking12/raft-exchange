package com.binance.raftexchange.server.exchange;

import com.binance.raftexchange.server.util.SerializeHelper;
import exchange.core2.core.common.cmd.CommandResultCode;

import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

public class SyncNoOpApiController extends AbstractApiController {

    public static CompletableFuture<Supplier<byte[]>> handleNoOp() {
        return CompletableFuture.completedFuture(
                () -> SerializeHelper.serializeToCommandResult(CommandResultCode.SUCCESS));
    }
}
