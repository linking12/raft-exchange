package com.binance.raftexchange.server.exchange;

import exchange.core2.core.common.cmd.CommandResultCode;

public class SyncNoOpApiController extends AbstractApiController {

    public static byte[] handleNoOp() {
        return serializeResult(CommandResultCode.SUCCESS);
    }
}
