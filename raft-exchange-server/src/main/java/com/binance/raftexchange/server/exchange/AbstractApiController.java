package com.binance.raftexchange.server.exchange;

import com.binance.raftexchange.server.raft.SerializeHelper;
import com.binance.raftexchange.stubs.command.CommandResultCode;
import exchange.core2.core.ExchangeApi;
import exchange.core2.core.common.api.ApiCommand;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public abstract class AbstractApiController {

    protected static final Logger LOG = LoggerFactory.getLogger(AbstractApiController.class);

    public static byte[] callExchange(ApiCommand apiCommand) throws Exception {
        ExchangeApi api = ExchangeApiInstance.exchangeApi();
        exchange.core2.core.common.cmd.CommandResultCode resultCode = api.submitCommandAsync(apiCommand).get();
        LOG.info("{} called, result: {}", apiCommand.getClass().getSimpleName(), resultCode);
        return serializeResult(resultCode);
    }

    private static byte[] serializeResult(exchange.core2.core.common.cmd.CommandResultCode resultCode) {
        CommandResultCode grpcCommandResultCode = CommandResultCode.forNumber(Math.abs(resultCode.getCode()));
        if (grpcCommandResultCode == null) {
            return null;
        }
        return SerializeHelper.enumToBytesProto(grpcCommandResultCode);
    }
}
