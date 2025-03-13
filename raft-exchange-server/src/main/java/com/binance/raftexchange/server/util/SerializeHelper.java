package com.binance.raftexchange.server.util;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.binance.raftexchange.stubs.OrderAction;
import com.binance.raftexchange.stubs.OrderType;
import com.binance.raftexchange.stubs.report.Order;
import com.binance.raftexchange.stubs.report.OrderList;
import com.binance.raftexchange.stubs.report.Position;
import com.binance.raftexchange.stubs.report.PositionDirection;
import com.binance.raftexchange.stubs.report.QueryExecutionStatus;
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

import exchange.core2.core.common.api.reports.ReportResult;
import exchange.core2.core.common.api.reports.SingleUserReportResult;
import exchange.core2.core.common.api.reports.StateHashReportResult;
import exchange.core2.core.common.api.reports.TotalCurrencyBalanceReportResult;
import exchange.core2.core.common.cmd.OrderCommand;
import org.eclipse.collections.impl.map.mutable.primitive.IntLongHashMap;
import org.eclipse.collections.impl.map.mutable.primitive.IntObjectHashMap;

public class SerializeHelper {
    private SerializeHelper() {}

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
        return CommandResult.newBuilder().setResultCode(CommandResultCode.forNumber(Math.abs(commandResultCode.getCode()))).build().toByteArray();
    }

    public static byte[] serializeToCommandResult(OrderCommand result) {
        // 这里产生了一个临时对象 但是我估计可以通过逃逸分析被jit干掉？
        CommandResultCode resultCode = result.resultCode == null ? null : CommandResultCode.forNumber(Math.abs(result.resultCode.getCode()));
        com.binance.raftexchange.stubs.response.OrderCommand.Builder builder = com.binance.raftexchange.stubs.response.OrderCommand.newBuilder()
            .setCommand(OrderCommandType.forNumber(result.command.getCode())).setOrderId(result.orderId).setSymbol(result.symbol).setPrice(result.price)
            .setSize(result.size).setReserveBidPrice(result.reserveBidPrice).setUid(result.getUid()).setTimestamp(result.timestamp)
            .setUserCookie(result.userCookie).setEventsGroup(result.eventsGroup).setServiceFlags(result.serviceFlags).setResultCode(resultCode);
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

        return CommandResult.newBuilder().setOrderCommand(builder.build()).build().toByteArray();
    }

    private static MatcherTradeEvent toPbObject(exchange.core2.core.common.MatcherTradeEvent matcherTradeEvent) {

        if (matcherTradeEvent == null) {
            return null;
        }

        MatcherTradeEvent.Builder baseBuilder =
            MatcherTradeEvent.newBuilder().setOrderId(matcherTradeEvent.matchedOrderId).setPrice(matcherTradeEvent.price).setSize(matcherTradeEvent.price);
        if (matcherTradeEvent.nextEvent == null) {
            return baseBuilder.build();
        }

        return baseBuilder.setNextEvent(toPbObject(matcherTradeEvent.nextEvent)).build();
    }

    private static L2MarketData toPbObject(exchange.core2.core.common.L2MarketData l2MarketData) {

        if (l2MarketData == null) {
            return null;
        }

        L2MarketData.Builder newedBuilder = L2MarketData.newBuilder();

        if (l2MarketData.askPrices != null) {
            // 避免额外装箱操作
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
                    .setDirection(PositionDirection.forNumber(p.getDirection().getMultiplier() & 0xFF))
                    .build();

    private static final Function<List<exchange.core2.core.common.Order>, OrderList> ordersMapping = l ->
            OrderList.newBuilder().addAllOrders(l.stream().map(o -> Order.newBuilder()
                    .setOrderId(o.getOrderId()).setPrice(o.getPrice()).setSize(o.getSize()).setFilled(o.getFilled())
                    .setReserveBidPrice(o.getReserveBidPrice()).setAction(OrderAction.forNumber(o.getAction().getCode()))
                    .setUid(o.getUid()).setTimestamp(o.getTimestamp()).build()).collect(Collectors.toList())).build();

    public static <T extends ReportResult> byte[] serializeToReportResult(T reportResult) {
        if (reportResult instanceof SingleUserReportResult) {
            SingleUserReportResult singleUserReportResult = (SingleUserReportResult) reportResult;
            return com.binance.raftexchange.stubs.report.SingleUserReportResult.newBuilder()
                    .setUserId(singleUserReportResult.getUid())
                    .setUserStatus(UserStatus.forNumber(singleUserReportResult.getUserStatus().getCode()))
                    .putAllAccounts(convertToHashMap(singleUserReportResult.getAccounts()))
                    .putAllPositions(convertToHashMap(singleUserReportResult.getPositions(), positionMapping))
                    .putAllOrders(convertToHashMap(singleUserReportResult.getOrders(), ordersMapping))
                    .setQueryExecutionStatus(QueryExecutionStatus.forNumber(singleUserReportResult.getQueryExecutionStatus().getCode()))
                    .build().toByteArray();
        } else if (reportResult instanceof StateHashReportResult) {

        } else if (reportResult instanceof TotalCurrencyBalanceReportResult) {

        }
        throw new IllegalArgumentException("Unknown reportResult: " + reportResult.getClass().getSimpleName());
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
}
