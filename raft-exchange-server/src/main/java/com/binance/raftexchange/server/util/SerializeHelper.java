package com.binance.raftexchange.server.util;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;

import com.binance.raftexchange.stubs.OrderAction;
import com.binance.raftexchange.stubs.OrderType;
import com.binance.raftexchange.stubs.request.ApiCommand;
import com.binance.raftexchange.stubs.response.CommandResult;
import com.binance.raftexchange.stubs.response.CommandResultCode;
import com.binance.raftexchange.stubs.response.L2MarketData;
import com.binance.raftexchange.stubs.response.MatcherTradeEvent;
import com.binance.raftexchange.stubs.response.OrderCommandType;
import com.google.protobuf.GeneratedMessageV3;
import com.google.protobuf.Int32Value;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.ProtocolMessageEnum;

import exchange.core2.core.common.cmd.OrderCommand;

public class SerializeHelper {
    private SerializeHelper() {
    }

    /**
     * [2-byte length] [类型字符串] [protobuf byte[]] ｜--------writeUTF--------｜｜---write---｜
     */
    public static byte[] serializeWithType(GeneratedMessageV3 message) throws IOException {
        String type = message.getClass().getSimpleName();
        byte[] byteArray = message.toByteArray();
        int capacity = 2 * Byte.BYTES + type.getBytes().length + byteArray.length;
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream(capacity);
        DataOutputStream dataOutputStream = new DataOutputStream(outputStream);
        dataOutputStream.writeUTF(type);
        dataOutputStream.write(byteArray, 0, byteArray.length);
        return outputStream.toByteArray();
    }

    public static byte[] serializeWithType(Class<? extends GeneratedMessageV3> type, byte[] pb) {
        return pb;
    }

    /**
     * [2-byte length] [类型字符串] [protobuf byte[]] ｜--------readUTF--------｜｜---readFully---｜
     */
    public static GeneratedMessageV3 deserializeWithType(byte[] data, int offset, int length) throws IOException {
        // 这里不需要OrderCommand了直接用apiCommand就行了
        return ApiCommand.parseFrom(new ByteArrayInputStream(data, offset, length));
    }

    public static byte[] enumToBytesProto(ProtocolMessageEnum enumValue) {
        return Int32Value.newBuilder().setValue(enumValue.getNumber()).build().toByteArray();
    }

    public static byte[] serializeToCommandResult(exchange.core2.core.common.cmd.CommandResultCode commandResultCode) {
        return CommandResult.newBuilder()
                .setResultCode(CommandResultCode.forNumber(Math.abs(commandResultCode.getCode())))
                .build()
                .toByteArray();
    }

    public static byte[] serializeToCommandResult(OrderCommand result) {
        //这里产生了一个临时对象 但是我估计可以通过逃逸分析被jit干掉？
        CommandResultCode resultCode = result.resultCode == null ? null : CommandResultCode.forNumber(Math.abs(result.resultCode.getCode()));
        com.binance.raftexchange.stubs.response.OrderCommand.Builder builder = com.binance.raftexchange.stubs.response.OrderCommand.newBuilder()
                .setCommand(OrderCommandType.forNumber(result.command.getCode()))
                .setOrderId(result.orderId)
                .setSymbol(result.symbol)
                .setPrice(result.price)
                .setSize(result.size)
                .setReserveBidPrice(result.reserveBidPrice)
                .setUid(result.getUid())
                .setTimestamp(result.timestamp)
                .setUserCookie(result.userCookie)
                .setEventsGroup(result.eventsGroup)
                .setServiceFlags(result.serviceFlags)
                .setResultCode(resultCode);
        if (result.action != null) {
            builder = builder.setAction(OrderAction.forNumber(result.action.getCode()));
        }

        if (result.action != null) {
            builder = builder.setOrderType(OrderType.forNumber(result.orderType.getCode()));
        }

        if (result.matcherEvent != null) {
            builder = builder.setMatcherEvent(toPbObject(result.matcherEvent));
        }

        if (result.marketData != null) {
            builder = builder.setMarketData(toPbObject(result.marketData));
        }

        return CommandResult.newBuilder()
                .setOrderCommand(builder.build())
                .build()
                .toByteArray();
    }

    private static MatcherTradeEvent toPbObject(exchange.core2.core.common.MatcherTradeEvent matcherTradeEvent) {

        if (matcherTradeEvent == null) {
            return null;
        }

        MatcherTradeEvent.Builder baseBuilder = MatcherTradeEvent.newBuilder()
                .setOrderId(matcherTradeEvent.matchedOrderId)
                .setPrice(matcherTradeEvent.price)
                .setSize(matcherTradeEvent.price);
        if (matcherTradeEvent.nextEvent == null) {
            return baseBuilder.build();
        }

        return baseBuilder
                .setNextEvent(toPbObject(matcherTradeEvent.nextEvent))
                .build();
    }

    private static L2MarketData toPbObject(exchange.core2.core.common.L2MarketData l2MarketData) {

        if (l2MarketData == null) {
            return null;
        }

        L2MarketData.Builder newedBuilder = L2MarketData.newBuilder();

        if (l2MarketData.askPrices != null) {
            //避免额外装箱操作
            for (long bidPrice : l2MarketData.bidPrices) {
                newedBuilder = newedBuilder.addBidPrices(bidPrice);
            }
        }

        newedBuilder = newedBuilder.setBidSizes(l2MarketData.bidSize);

        if (l2MarketData.askPrices != null) {
            for (long askPrice : l2MarketData.askPrices) {
                newedBuilder = newedBuilder.addAskPrices(askPrice);
            }
        }

        newedBuilder = newedBuilder.setAskSizes(l2MarketData.askSize);

        return newedBuilder.build();
    }

    public static <T extends ProtocolMessageEnum> T bytesToEnumProto(byte[] bytes, Class<T> enumClass)
            throws InvalidProtocolBufferException {
        int intValue = Int32Value.parseFrom(bytes).getValue();
        for (T value : enumClass.getEnumConstants()) {
            if (value.getNumber() == intValue) {
                return value;
            }
        }
        throw new IllegalArgumentException("Unknown enum value: " + intValue);
    }
}
