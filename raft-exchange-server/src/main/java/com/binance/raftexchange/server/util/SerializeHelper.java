package com.binance.raftexchange.server.util;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.util.Deque;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.binance.raftexchange.stubs.report.HashCodeEntry;
import com.binance.raftexchange.stubs.report.Order;
import com.binance.raftexchange.stubs.report.OrderList;
import com.binance.raftexchange.stubs.report.Position;
import com.binance.raftexchange.stubs.report.QueryExecutionStatus;
import com.binance.raftexchange.stubs.report.SubmoduleKey;
import com.binance.raftexchange.stubs.report.UserStatus;
import com.binance.raftexchange.stubs.request.ApiCommand;
import com.binance.raftexchange.stubs.response.CommandResult;
import com.binance.raftexchange.stubs.response.CommandResultCode;
import com.binance.raftexchange.stubs.response.L2MarketData;
import com.binance.raftexchange.stubs.response.MatcherTradeEvent;
import com.binance.raftexchange.stubs.response.OrderCommandType;
import com.google.common.collect.Maps;
import com.google.protobuf.GeneratedMessageV3;
import com.google.protobuf.Int32Value;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.ProtocolMessageEnum;

import exchange.core2.core.common.api.reports.SingleUserReportResult;
import exchange.core2.core.common.api.reports.StateHashReportResult;
import exchange.core2.core.common.api.reports.TotalCurrencyBalanceReportResult;
import exchange.core2.core.common.cmd.OrderCommand;
import io.grpc.Drainable;
import io.grpc.KnownLength;
import io.grpc.internal.MessageFramer;
import org.eclipse.collections.impl.map.mutable.primitive.IntLongHashMap;
import org.eclipse.collections.impl.map.mutable.primitive.IntObjectHashMap;

public class SerializeHelper {
    private SerializeHelper() {
    }

    private static final ProtoBuilderPool builderPool;

    static {
        builderPool = new ProtoBuilderPool();
        builderPool.register(com.binance.raftexchange.stubs.response.OrderCommand.Builder.class, com.binance.raftexchange.stubs.response.OrderCommand::newBuilder);
        builderPool.register(CommandResult.Builder.class, CommandResult::newBuilder);
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
    public static GeneratedMessageV3 deserializeWithType(ByteBuffer data) throws IOException {
        // 这里不需要OrderCommand了直接用apiCommand就行了
        return ApiCommand.parseFrom(data);
    }

    public static byte[] enumToBytesProto(ProtocolMessageEnum enumValue) {
        return Int32Value.newBuilder().setValue(enumValue.getNumber()).build().toByteArray();
    }

    public static byte[] serializeToCommandResult(exchange.core2.core.common.cmd.CommandResultCode commandResultCode) {
        return CommandResult.newBuilder().setResultCode(CommandResultCode.forNumber(Math.abs(commandResultCode.getCode()))).build().toByteArray();
    }

    public static byte[] serializeToCommandResult(OrderCommand result) {
        return orderCommandToResult(result).toByteArray();
    }

    public static CommandResult orderCommandToResult(OrderCommand result) {
        CommandResultCode resultCode = result.resultCode == null ? null : CommandResultCode.forNumber(Math.abs(result.resultCode.getCode()));
        com.binance.raftexchange.stubs.response.OrderCommand.Builder builder = builderPool.get(com.binance.raftexchange.stubs.response.OrderCommand.Builder.class)
                .setCommand(OrderCommandType.forNumber(result.command.getCode())).setOrderId(result.orderId).setSymbol(result.symbol).setPrice(result.price)
                .setSize(result.size).setReserveBidPrice(result.reserveBidPrice).setUid(result.getUid()).setTimestamp(result.timestamp)
                .setUserCookie(result.userCookie).setLeverage(result.leverage).setMarginModeValue(result.marginMode.ordinal())
                .setEventsGroup(result.eventsGroup).setServiceFlags(result.serviceFlags).setResultCode(resultCode);
        if (result.action != null) {
            builder.setActionValue(result.action.getCode());
        }

        if (result.orderType != null) {
            builder.setOrderTypeValue(result.orderType.getCode());
        }

        if (result.matcherEvent != null) {
            builder.setMatcherEvent(toPbObject(result.matcherEvent));
        }

        if (result.marketData != null) {
            builder.setMarketData(toPbObject(result.marketData));
        }

        return builderPool.get(CommandResult.Builder.class)
                .setOrderCommand(builder.build())
                .build();
    }

    private static MatcherTradeEvent toPbObject(exchange.core2.core.common.MatcherTradeEvent matcherTradeEvent) {
        if (matcherTradeEvent == null) {
            return null;
        }

        // 使用栈来模拟递归调用
        Deque<exchange.core2.core.common.MatcherTradeEvent> stack = new LinkedList<>();
        while (matcherTradeEvent != null) {
            stack.push(matcherTradeEvent);
            matcherTradeEvent = matcherTradeEvent.nextEvent;
        }
        MatcherTradeEvent.Builder builder = null;
        while (!stack.isEmpty()) {
            exchange.core2.core.common.MatcherTradeEvent currentEvent = stack.pop();
            MatcherTradeEvent.Builder newBuilder = MatcherTradeEvent.newBuilder()
                    .setOrderId(currentEvent.matchedOrderId)
                    .setPrice(currentEvent.price)
                    .setSize(currentEvent.size)
                    .setEventTypeValue(currentEvent.eventType.ordinal())
                    .setActiveOrderCompleted(currentEvent.activeOrderCompleted)
                    .setMatchedOrderCompleted(currentEvent.matchedOrderCompleted);
            if (builder != null) {
                newBuilder.setNextEvent(builder.build()); // 先构建 nextEvent
            }
            builder = newBuilder; // 更新 builder
        }
        return builder != null ? builder.build() : null;
    }

    private static L2MarketData toPbObject(exchange.core2.core.common.L2MarketData l2MarketData) {
        if (l2MarketData == null) {
            return null;
        }

        L2MarketData.Builder builder = L2MarketData.newBuilder();
        builder.setAskSizes(l2MarketData.askSize);
        builder.setBidSizes(l2MarketData.bidSize);

        if (l2MarketData.askPrices != null) {
            for (long askPrice : l2MarketData.askPrices) {
                builder.addAskPrices(askPrice);
            }
        }
        if (l2MarketData.askVolumes != null) {
            for (long askVolume : l2MarketData.askVolumes) {
                builder.addAskVolumes(askVolume);
            }
        }
        if (l2MarketData.askOrders != null) {
            for (long askOrder : l2MarketData.askOrders) {
                builder.addAskOrders(askOrder);
            }
        }
        if (l2MarketData.bidPrices != null) {
            for (long bidPrice : l2MarketData.bidPrices) {
                builder.addBidPrices(bidPrice);
            }
        }
        if (l2MarketData.bidVolumes != null) {
            for (long bidVolume : l2MarketData.bidVolumes) {
                builder.addBidVolumes(bidVolume);
            }
        }
        if (l2MarketData.bidOrders != null) {
            for (long bidOrder : l2MarketData.bidOrders) {
                builder.addBidOrders(bidOrder);
            }
        }
        return builder.build();
    }

    public static <T extends ProtocolMessageEnum> T bytesToEnumProto(byte[] bytes, Class<T> enumClass) throws InvalidProtocolBufferException {
        int intValue = Int32Value.parseFrom(bytes).getValue();
        for (T value : enumClass.getEnumConstants()) {
            if (value.getNumber() == intValue) {
                return value;
            }
        }
        throw new IllegalArgumentException("Unknown enum value: " + intValue);
    }

    private static final Function<SingleUserReportResult.Position, Position> positionMapping = p ->
            Position.newBuilder().setQuoteCurrency(p.getQuoteCurrency())
                    .setDirectionValue(p.getDirection().getMultiplier() & 0xFF)
                    .setOpenVolume(p.getOpenVolume()).setOpenPriceSum(p.getOpenPriceSum())
                    .setProfit(p.getProfit()).setPendingSellSize(p.getPendingSellSize())
                    .setPendingBuySize(p.getPendingBuySize()).setPendingSellAvgPrice(p.getPendingSellAvgPrice())
                    .setPendingBuyAvgPrice(p.getPendingBuyAvgPrice()).setLeverage(p.getLeverage())
                    .setMarginModeValue(p.getMarginMode().ordinal()).build();

    private static final Function<List<exchange.core2.core.common.Order>, OrderList> ordersMapping = l ->
            OrderList.newBuilder().addAllOrders(l.stream().map(o -> Order.newBuilder()
                    .setOrderId(o.getOrderId()).setPrice(o.getPrice()).setSize(o.getSize()).setFilled(o.getFilled())
                    .setReserveBidPrice(o.getReserveBidPrice()).setActionValue(o.getAction().getCode())
                    .setUid(o.getUid()).setTimestamp(o.getTimestamp()).build()).collect(Collectors.toList())).build();

    public static com.binance.raftexchange.stubs.report.ReportResult serializeToPb(SingleUserReportResult singleUserReportResult) {

        if (singleUserReportResult.getQueryExecutionStatus() == SingleUserReportResult.QueryExecutionStatus.USER_NOT_FOUND) {
            com.binance.raftexchange.stubs.report.SingleUserReportResult result = com.binance.raftexchange.stubs.report.SingleUserReportResult.newBuilder()
                    .setUserId(singleUserReportResult.getUid())
                    .setQueryExecutionStatus(QueryExecutionStatus.forNumber(singleUserReportResult.getQueryExecutionStatus().getCode()))
                    .build();
            return com.binance.raftexchange.stubs.report.ReportResult.newBuilder()
                    .setSingleUserReport(result)
                    .build();
        }

        com.binance.raftexchange.stubs.report.SingleUserReportResult result = com.binance.raftexchange.stubs.report.SingleUserReportResult.newBuilder()
                .setUserId(singleUserReportResult.getUid())
                .setUserStatus(UserStatus.forNumber(singleUserReportResult.getUserStatus().getCode()))
                .putAllAccounts(convertToHashMap(singleUserReportResult.getAccounts()))
                .putAllPositions(convertToHashMap(singleUserReportResult.getPositions(), positionMapping))
                .putAllOrders(convertToHashMap(singleUserReportResult.getOrders(), ordersMapping))
                .setQueryExecutionStatus(QueryExecutionStatus.forNumber(singleUserReportResult.getQueryExecutionStatus().getCode()))
                .build();

        return com.binance.raftexchange.stubs.report.ReportResult.newBuilder()
                .setSingleUserReport(result)
                .build();
    }

    public static com.binance.raftexchange.stubs.report.ReportResult serializeToPb(StateHashReportResult stateHashReportResult) {
        List<HashCodeEntry> hashCodeEntries = stateHashReportResult.getHashCodes().entrySet().stream()
                .map(e -> HashCodeEntry.newBuilder()
                        .setKey(SubmoduleKey.newBuilder()
                                .setModuleId(e.getKey().moduleId)
                                .setSubmoduleTypeValue(e.getKey().submodule.code))
                        .setValue(e.getValue())
                        .build())
                .collect(Collectors.toList());
        com.binance.raftexchange.stubs.report.StateHashReportResult result = com.binance.raftexchange.stubs.report.StateHashReportResult.newBuilder()
                .addAllHashCodes(hashCodeEntries).build();

        return com.binance.raftexchange.stubs.report.ReportResult.newBuilder()
                .setStateHash(result)
                .build();
    }

    public static com.binance.raftexchange.stubs.report.ReportResult serializeToPb(TotalCurrencyBalanceReportResult totalCurrencyBalanceReportResult) {
        com.binance.raftexchange.stubs.report.TotalCurrencyBalanceReportResult result = com.binance.raftexchange.stubs.report.TotalCurrencyBalanceReportResult.newBuilder()
                .putAllAccountBalances(convertToHashMap(totalCurrencyBalanceReportResult.getAccountBalances()))
                .putAllFees(convertToHashMap(totalCurrencyBalanceReportResult.getFees()))
                .putAllAdjustments(convertToHashMap(totalCurrencyBalanceReportResult.getAdjustments()))
                .putAllSuspends(convertToHashMap(totalCurrencyBalanceReportResult.getSuspends()))
                .putAllOrdersBalances(convertToHashMap(totalCurrencyBalanceReportResult.getOrdersBalances()))
                .putAllOpenInterestLong(convertToHashMap(totalCurrencyBalanceReportResult.getOpenInterestLong()))
                .putAllOpenInterestShort(convertToHashMap(totalCurrencyBalanceReportResult.getOpenInterestShort()))
                .build();
        return com.binance.raftexchange.stubs.report.ReportResult.newBuilder()
                .setTotalCurrencyBalance(result)
                .build();
    }

    public static Map<Integer, Long> convertToHashMap(IntLongHashMap intLongHashMap) {
        Map<Integer, Long> result = Maps.newHashMapWithExpectedSize(intLongHashMap.size());
        intLongHashMap.forEachKeyValue(result::put);
        return result;
    }

    public static <V, R> Map<Integer, R> convertToHashMap(IntObjectHashMap<V> intObjectHashMap, Function<V, R> valueConverter) {
        Map<Integer, R> result = Maps.newHashMapWithExpectedSize(intObjectHashMap.size());
        intObjectHashMap.forEachKeyValue((key, value) -> result.put(key, valueConverter.apply(value)));
        return result;
    }

    public static InputStream wrapKnownBytes(byte[] bytes) {
        return new FastByteArrayInputStream(bytes);
    }

    /**
     * 优化grpc writeMessage性能，避免copy
     * @see MessageFramer#writeToOutputStream(InputStream, OutputStream)
     */
    public static class FastByteArrayInputStream extends ByteArrayInputStream implements Drainable, KnownLength {

        public FastByteArrayInputStream(byte[] buf) {
            super(buf);
        }

        @Override
        public int drainTo(OutputStream out) throws IOException {
            // zero-copy drain
            out.write(buf, pos, count - pos);
            int written = count - pos;
            pos = count; // move position to the end
            return written;
        }
    }

}
