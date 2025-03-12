package com.binance.raftexchange.server.exchange;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.binance.raftexchange.server.util.SerializeHelper;

import exchange.core2.core.ExchangeApi;
import exchange.core2.core.common.api.ApiCommand;
import exchange.core2.core.common.api.ApiPersistState;
import exchange.core2.core.common.api.binary.BinaryDataCommand;

public abstract class AbstractApiController {
    protected static final Logger LOG = LoggerFactory.getLogger(AbstractApiController.class);

    public static byte[] callExchange(ApiCommand apiCommand) throws Exception {
        if (apiCommand instanceof ApiPersistState) {
            return callExchange((ApiPersistState)apiCommand);
        }
        ExchangeApi api = ExchangeApiInstance.exchangeApi();
        // thenApply那边必须要这样写submitCommandAsync里面会回调这个future 此时future::complete会在ringbuffer的处理器中调用
        // 此时orderCommand是稳定的 相当于暂时从环上被占用 同步地进行thenApply确保序列化成byte[]后再出处理器范围 这样orderCommand就可以被安全地复用
        // 注意:不要阻塞地获取submitCommandAsyncFullResponse的结果，
        // 如果这样做会导致我们跟ringBuffer并发地持有一个“已经被归还到ring”的command引用 这样就产生了一个UB
        return api.submitCommandAsyncFullResponse(apiCommand) // submitCommandAsyncFullResponse 跟 非full版本接受的command一致
            .thenApply(SerializeHelper::serializeToCommandResult).get();
    }

    public static byte[] callExchange(ApiPersistState cmd) throws Exception {
        ExchangeApi api = ExchangeApiInstance.exchangeApi();
        return api.submitPersistCommandAsync(cmd).thenApply(SerializeHelper::serializeToCommandResult).get();
    }

    public static byte[] callExchange(BinaryDataCommand binaryDataCommand) throws Exception {
        ExchangeApi api = ExchangeApiInstance.exchangeApi();
        exchange.core2.core.common.cmd.CommandResultCode resultCode = api.submitBinaryDataAsync(binaryDataCommand).get();
        LOG.info("{} called, result: {}", binaryDataCommand.getClass().getSimpleName(), resultCode);
        return SerializeHelper.serializeToCommandResult(resultCode);
    }
}
