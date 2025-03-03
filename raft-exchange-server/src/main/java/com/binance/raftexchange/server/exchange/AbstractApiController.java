package com.binance.raftexchange.server.exchange;

import com.binance.raftexchange.server.util.SerializeHelper;
import com.binance.raftexchange.stubs.response.CommandResultCode;
import exchange.core2.core.ExchangeApi;
import exchange.core2.core.common.api.ApiCommand;
import exchange.core2.core.common.api.binary.BinaryDataCommand;
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

    public static byte[] callExchange(BinaryDataCommand binaryDataCommand) throws Exception {
        ExchangeApi api = ExchangeApiInstance.exchangeApi();
        exchange.core2.core.common.cmd.CommandResultCode resultCode =
            api.submitBinaryDataAsync(binaryDataCommand).get();
        LOG.info("{} called, result: {}", binaryDataCommand.getClass().getSimpleName(), resultCode);
        return serializeResult(resultCode);
    }

    public static byte[] serializeResult(exchange.core2.core.common.cmd.CommandResultCode resultCode) {
        CommandResultCode grpcCommandResultCode = CommandResultCode.forNumber(Math.abs(resultCode.getCode()));
        if (grpcCommandResultCode == null) {
            return null;
        }
        return SerializeHelper.enumToBytesProto(grpcCommandResultCode);
    }
}
