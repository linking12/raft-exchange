package com.binance.raftexchange.server.exchange;

import com.binance.raftexchange.server.util.SerializeHelper;
import exchange.core2.core.common.cmd.CommandResultCode;

public class SyncNoOpApiController extends AbstractApiController {

    public static byte[] handleNoOp() {
        return SerializeHelper.serializeToCommandResult(CommandResultCode.SUCCESS);
    }
}
