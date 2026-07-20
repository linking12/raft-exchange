package com.binance.raftexchange.server.util;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.binance.raftexchange.stubs.CoreCurrencySpecification;
import com.binance.raftexchange.stubs.CoreSymbolSpecification;
import com.binance.raftexchange.stubs.report.HashCodeEntry;
import com.binance.raftexchange.stubs.report.Order;
import com.binance.raftexchange.stubs.report.OrderList;
import com.binance.raftexchange.stubs.report.CrossLoan;
import com.binance.raftexchange.stubs.report.IsolatedLoan;
import com.binance.raftexchange.stubs.report.Position;
import com.binance.raftexchange.stubs.report.PositionList;
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

import exchange.core2.core.common.api.reports.FeeReportResult;
import exchange.core2.core.common.api.reports.SingleUserReportResult;
import exchange.core2.core.common.api.reports.StateHashReportResult;
import exchange.core2.core.common.api.reports.SymbolCurrencyReportResult;
import exchange.core2.core.common.api.reports.TotalCurrencyBalanceReportResult;
import exchange.core2.core.common.cmd.OrderCommand;
import io.grpc.Drainable;
import io.grpc.KnownLength;
import io.grpc.internal.MessageFramer;
import org.eclipse.collections.impl.map.mutable.primitive.IntLongHashMap;
import org.eclipse.collections.impl.map.mutable.primitive.IntObjectHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class SerializeHelper {
    private static final Logger LOG = LoggerFactory.getLogger(SerializeHelper.class);

    private SerializeHelper() {}

    private static final ProtoBuilderPool builderPool;

    private static final EnumMap<exchange.core2.core.common.cmd.CommandResultCode, CommandResultCode> CODE_MAPPING;

    static {
        builderPool = new ProtoBuilderPool();
        builderPool.register(com.binance.raftexchange.stubs.response.OrderCommand.Builder.class,
            com.binance.raftexchange.stubs.response.OrderCommand::newBuilder);
        builderPool.register(CommandResult.Builder.class, CommandResult::newBuilder);
        CODE_MAPPING = new EnumMap<>(exchange.core2.core.common.cmd.CommandResultCode.class);
        for (exchange.core2.core.common.cmd.CommandResultCode src : exchange.core2.core.common.cmd.CommandResultCode
            .values()) {
            CommandResultCode dst = CommandResultCode.forNumber(Math.abs(src.getCode()));
            if (dst == null) {
                throw new IllegalStateException("exchange-core CommandResultCode." + src.name() + "=" + src.getCode()
                    + " 在 grpc CommandResultCode 里没有对应条目；" + "请在 raft-exchange-spi/protos/message/response.proto 加 "
                    + src.name() + " = " + Math.abs(src.getCode()));
            }
            CODE_MAPPING.put(src, dst);
        }
    }

    private static CommandResultCode toGrpcCode(exchange.core2.core.common.cmd.CommandResultCode src) {
        return CODE_MAPPING.get(src);
    }

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

    public static GeneratedMessageV3 deserializeWithType(ByteBuffer data) throws IOException {
        return ApiCommand.parseFrom(data);
    }

    public static byte[] enumToBytesProto(ProtocolMessageEnum enumValue) {
        return Int32Value.newBuilder().setValue(enumValue.getNumber()).build().toByteArray();
    }

    public static byte[] serializeToCommandResult(exchange.core2.core.common.cmd.CommandResultCode commandResultCode) {
        try {
            return CommandResult.newBuilder().setResultCode(toGrpcCode(commandResultCode)).build().toByteArray();
        } catch (Throwable t) {
            LOG.warn("serializeToCommandResult(code) failed code={}", commandResultCode, t);
            return CommandResult.newBuilder().setResultCode(CommandResultCode.NEW).build().toByteArray();
        }
    }

    public static byte[] serializeToCommandResult(OrderCommand result) {
        try {
            return orderCommandToResult(result).toByteArray();
        } catch (Throwable t) {
            LOG.warn("serializeToCommandResult failed", t);
            return CommandResult.newBuilder().setResultCode(CommandResultCode.NEW).build().toByteArray();
        }
    }

    public static CommandResult orderCommandToResult(OrderCommand result) {
        OrderCommandType protoCmd = OrderCommandType.forNumber(result.command.getCode());
        CommandResultCode resultCode = result.resultCode == null ? null : toGrpcCode(result.resultCode);
        com.binance.raftexchange.stubs.response.OrderCommand.Builder builder =
            builderPool.get(com.binance.raftexchange.stubs.response.OrderCommand.Builder.class)
                .setOrderId(result.orderId).setSymbol(result.symbol).setPrice(result.price).setSize(result.size)
                .setReserveBidPrice(result.reserveBidPrice).setUid(result.getUid()).setTimestamp(result.timestamp)
                .setUserCookie(result.userCookie).setLeverage(result.leverage)
                .setMarginModeValue(result.marginMode.ordinal()).setEventsGroup(result.eventsGroup)
                .setServiceFlags(result.serviceFlags);
        if (protoCmd != null) {
            builder.setCommand(protoCmd);
        }
        if (resultCode != null) {
            builder.setResultCode(resultCode);
        }
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

        return builderPool.get(CommandResult.Builder.class).setOrderCommand(builder.build()).build();
    }

    private static MatcherTradeEvent toPbObject(exchange.core2.core.common.MatcherTradeEvent matcherTradeEvent) {
        if (matcherTradeEvent == null) {
            return null;
        }

        MatcherTradeEvent.Builder head = MatcherTradeEvent.newBuilder();
        MatcherTradeEvent.Builder curBuilder = head;
        exchange.core2.core.common.MatcherTradeEvent curSrc = matcherTradeEvent;
        do {
            curBuilder.setEventTypeValue(curSrc.eventType.ordinal()).setSection(curSrc.section)
                .setActiveOrderCompleted(curSrc.activeOrderCompleted).setMatchedOrderId(curSrc.matchedOrderId)
                .setMatchedOrderUid(curSrc.matchedOrderUid).setMatchedOrderCompleted(curSrc.matchedOrderCompleted)
                .setPrice(curSrc.price).setSize(curSrc.size).setBidderHoldPrice(curSrc.bidderHoldPrice);
            curSrc = curSrc.nextEvent;
            if (curSrc != null) {
                curBuilder = curBuilder.getNextEventBuilder();
            }
        } while (curSrc != null);
        return head.build();
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

    private static final Function<List<SingleUserReportResult.Position>,
        PositionList> positionMapping = l -> PositionList.newBuilder()
            .addAllPositions(l.stream()
                .map(p -> Position.newBuilder().setQuoteCurrency(p.getQuoteCurrency())
                    .setDirectionValue(p.getDirection().getMultiplier() & 0xFF).setOpenVolume(p.getOpenVolume())
                    .setOpenInitMarginSum(p.getOpenInitMarginSum()).setOpenPriceSum(p.getOpenPriceSum())
                    .setProfit(p.getProfit()).setPendingSellSize(p.getPendingSellSize())
                    .setPendingBuySize(p.getPendingBuySize()).setPendingSellAvgPrice(p.getPendingSellAvgPrice())
                    .setPendingBuyAvgPrice(p.getPendingBuyAvgPrice()).setLeverage(p.getLeverage())
                    .setMarginModeValue(p.getMarginMode().ordinal()).setExtraMargin(p.getExtraMargin())
                    .setUnrealizedProfit(p.getUnrealizedProfit()).setLiquidationPrice(p.getLiquidationPrice())
                    .setMarginRatioScaleK(p.getMarginRatioScaleK()).setMarkPrice(p.getMarkPrice()).build())
                .collect(Collectors.toList()))
            .build();

    private static final Function<List<exchange.core2.core.common.Order>,
        OrderList> ordersMapping = l -> OrderList.newBuilder()
            .addAllOrders(l.stream()
                .map(o -> Order.newBuilder().setOrderId(o.getOrderId()).setPrice(o.getPrice()).setSize(o.getSize())
                    .setFilled(o.getFilled()).setReserveBidPrice(o.getReserveBidPrice())
                    .setActionValue(o.getAction().getCode()).setUid(o.getUid()).setTimestamp(o.getTimestamp()).build())
                .collect(Collectors.toList()))
            .build();

    private static final Function<SingleUserReportResult.IsolatedLoan, IsolatedLoan> isolatedLoanMapping =
        l -> IsolatedLoan.newBuilder().setLoanId(l.getLoanId()).setSymbolId(l.getSymbolId())
            .setCollateralCurrency(l.getCollateralCurrency()).setLoanCurrency(l.getLoanCurrency())
            .setRateBps(l.getRateBps()).setOpenedAtTs(l.getOpenedAtTs()).setCollateralAmount(l.getCollateralAmount())
            .setOutstandingPrincipal(l.getOutstandingPrincipal()).setAccumulatedInterest(l.getAccumulatedInterest())
            .setDisplayInterest(l.getDisplayInterest()).setLtvBps(l.getLtvBps()).setMarkPrice(l.getMarkPrice()).build();

    private static final Function<SingleUserReportResult.CrossLoan, CrossLoan> crossLoanMapping =
        l -> CrossLoan.newBuilder().setLoanId(l.getLoanId()).setSymbolId(l.getSymbolId())
            .setLoanCurrency(l.getLoanCurrency()).setRateBps(l.getRateBps()).setOpenedAtTs(l.getOpenedAtTs())
            .setOutstandingPrincipal(l.getOutstandingPrincipal()).setAccumulatedInterest(l.getAccumulatedInterest())
            .setDisplayInterest(l.getDisplayInterest()).build();

    private static final Function<exchange.core2.core.common.CoreSymbolSpecification,
        CoreSymbolSpecification> symbolSpecMapping = s -> CoreSymbolSpecification.newBuilder()
            .setSymbolId(s.getSymbolId()).setTypeValue(s.getType().ordinal()).setBaseCurrency(s.getBaseCurrency())
            .setQuoteCurrency(s.getQuoteCurrency()).setBaseScaleK(s.getBaseScaleK()).setQuoteScaleK(s.getQuoteScaleK())
            .setTakerFee(s.getTakerFee()).setMakerFee(s.getMakerFee()).setLiquidationFee(s.getLiquidationFee())
            .setFeeScaleK(s.getFeeScaleK()).setInitMargin(s.getInitMargin())
            .setInitMarginScaleK(s.getInitMarginScaleK()).putAllMaintenanceMargin(s.getMaintenanceMargin())
            .setMaintenanceMarginScaleK(s.getMaintenanceMarginScaleK()).putAllMaxLeverage(s.getMaxLeverage()).build();

    private static final Function<exchange.core2.core.common.CoreCurrencySpecification,
        CoreCurrencySpecification> currencySpecMapping = c -> CoreCurrencySpecification.newBuilder().setId(c.getId())
            .setName(c.getName()).setDigit(c.getDigit()).build();

    public static com.binance.raftexchange.stubs.report.ReportResult
        serializeToPb(SingleUserReportResult singleUserReportResult) {

        if (singleUserReportResult
            .getQueryExecutionStatus() == SingleUserReportResult.QueryExecutionStatus.USER_NOT_FOUND) {
            com.binance.raftexchange.stubs.report.SingleUserReportResult result =
                com.binance.raftexchange.stubs.report.SingleUserReportResult.newBuilder()
                    .setUserId(singleUserReportResult.getUid())
                    .setQueryExecutionStatus(
                        QueryExecutionStatus.forNumber(singleUserReportResult.getQueryExecutionStatus().getCode()))
                    .build();
            return com.binance.raftexchange.stubs.report.ReportResult.newBuilder().setSingleUserReport(result).build();
        }

        com.binance.raftexchange.stubs.report.SingleUserReportResult result =
            com.binance.raftexchange.stubs.report.SingleUserReportResult.newBuilder()
                .setUserId(singleUserReportResult.getUid())
                .setUserStatus(UserStatus.forNumber(singleUserReportResult.getUserStatus().getCode()))
                .putAllAccounts(convertToHashMap(singleUserReportResult.getAccounts()))
                .putAllExchangeLocked(convertToHashMap(singleUserReportResult.getExchangeLocked()))
                .putAllPositions(convertToHashMap(singleUserReportResult.getPositions(), positionMapping))
                .putAllOrders(convertToHashMap(singleUserReportResult.getOrders(), ordersMapping))
                .addAllIsolatedLoans(singleUserReportResult.getIsolatedLoans().stream()
                    .map(isolatedLoanMapping).collect(Collectors.toList()))
                .addAllCrossLoans(singleUserReportResult.getCrossLoans().stream()
                    .map(crossLoanMapping).collect(Collectors.toList()))
                .putAllCrossLoanCollateral(convertToHashMap(singleUserReportResult.getCrossLoanCollateral()))
                .setCrossAccountLtvBps(singleUserReportResult.getCrossAccountLtvBps())
                .setQueryExecutionStatus(
                    QueryExecutionStatus.forNumber(singleUserReportResult.getQueryExecutionStatus().getCode()))
                .build();

        return com.binance.raftexchange.stubs.report.ReportResult.newBuilder().setSingleUserReport(result).build();
    }

    public static com.binance.raftexchange.stubs.report.ReportResult
        serializeToPb(StateHashReportResult stateHashReportResult) {
        List<HashCodeEntry> hashCodeEntries = stateHashReportResult.getHashCodes().entrySet().stream()
            .map(e -> HashCodeEntry.newBuilder()
                .setKey(SubmoduleKey.newBuilder().setModuleId(e.getKey().moduleId)
                    .setSubmoduleTypeValue(e.getKey().submodule.code))
                .setValue(e.getValue()).build())
            .collect(Collectors.toList());
        com.binance.raftexchange.stubs.report.StateHashReportResult result =
            com.binance.raftexchange.stubs.report.StateHashReportResult.newBuilder().addAllHashCodes(hashCodeEntries)
                .build();

        return com.binance.raftexchange.stubs.report.ReportResult.newBuilder().setStateHash(result).build();
    }

    public static com.binance.raftexchange.stubs.report.ReportResult
        serializeToPb(TotalCurrencyBalanceReportResult totalCurrencyBalanceReportResult) {
        com.binance.raftexchange.stubs.report.TotalCurrencyBalanceReportResult result =
            com.binance.raftexchange.stubs.report.TotalCurrencyBalanceReportResult.newBuilder()
                .putAllAccountBalances(convertToHashMap(totalCurrencyBalanceReportResult.getAccountBalances()))
                .putAllFees(convertToHashMap(totalCurrencyBalanceReportResult.getFees()))
                .putAllAdjustments(convertToHashMap(totalCurrencyBalanceReportResult.getAdjustments()))
                .putAllSuspends(convertToHashMap(totalCurrencyBalanceReportResult.getSuspends()))
                .putAllOpenInterestLong(convertToHashMap(totalCurrencyBalanceReportResult.getOpenInterestLong()))
                .putAllOpenInterestShort(convertToHashMap(totalCurrencyBalanceReportResult.getOpenInterestShort()))
                .putAllIfBalances(convertToHashMap(totalCurrencyBalanceReportResult.getIfBalances()))
                .putAllIfOpenInterestLong(convertToHashMap(totalCurrencyBalanceReportResult.getIfOpenInterestLong()))
                .putAllIfOpenInterestShort(convertToHashMap(totalCurrencyBalanceReportResult.getIfOpenInterestShort()))
                .putAllExtraMargin(convertToHashMap(totalCurrencyBalanceReportResult.getExtraMargin()))
                .putAllExchangeLocked(convertToHashMap(totalCurrencyBalanceReportResult.getExchangeLocked()))
                .putAllLoanBalances(convertToHashMap(totalCurrencyBalanceReportResult.getLoanBalances()))
                .putAllCurrencySpecs(
                    totalCurrencyBalanceReportResult.getCurrencySpecs() == null ? java.util.Collections.emptyMap()
                        : convertToHashMap(totalCurrencyBalanceReportResult.getCurrencySpecs(), currencySpecMapping))
                .putAllSymbolSpecs(
                    totalCurrencyBalanceReportResult.getSymbolSpecs() == null ? java.util.Collections.emptyMap()
                        : convertToHashMap(totalCurrencyBalanceReportResult.getSymbolSpecs(), symbolSpecMapping))
                .build();
        return com.binance.raftexchange.stubs.report.ReportResult.newBuilder().setTotalCurrencyBalance(result).build();
    }

    public static com.binance.raftexchange.stubs.report.ReportResult
        serializeToPb(SymbolCurrencyReportResult symbolCurrencyReportResult) {
        com.binance.raftexchange.stubs.report.SymbolCurrencyReportResult result =
            com.binance.raftexchange.stubs.report.SymbolCurrencyReportResult.newBuilder()
                .putAllSymbolSpecs(convertToHashMap(symbolCurrencyReportResult.getSymbolSpecs(), symbolSpecMapping))
                .putAllCurrencySpecs(
                    convertToHashMap(symbolCurrencyReportResult.getCurrencySpecs(), currencySpecMapping))
                .build();
        return com.binance.raftexchange.stubs.report.ReportResult.newBuilder().setSymbolCurrencyReport(result).build();
    }

    public static com.binance.raftexchange.stubs.report.ReportResult serializeToPb(FeeReportResult feeReportResult) {
        com.binance.raftexchange.stubs.report.FeeReportResult result =
            com.binance.raftexchange.stubs.report.FeeReportResult.newBuilder()
                .putAllFees(feeReportResult.getFees() == null ? java.util.Collections.emptyMap()
                    : convertToHashMap(feeReportResult.getFees()))
                .putAllCurrencySpecs(feeReportResult.getCurrencySpecs() == null ? java.util.Collections.emptyMap()
                    : convertToHashMap(feeReportResult.getCurrencySpecs(), currencySpecMapping))
                .build();
        return com.binance.raftexchange.stubs.report.ReportResult.newBuilder().setFeeReport(result).build();
    }

    /** 保险基金：期货 IF（per-symbol 两段值）+ 借贷 LIF（per-currency 单值，可为负），逐 shard 转换。 */
    public static com.binance.raftexchange.stubs.report.ReportResult serializeToPb(
        exchange.core2.core.common.api.reports.InsuranceFundReportResult src) {
        com.binance.raftexchange.stubs.report.InsuranceFundReportResult.Builder result =
            com.binance.raftexchange.stubs.report.InsuranceFundReportResult.newBuilder();
        src.getByShard().forEachKeyValue((shardId, data) -> {
            com.binance.raftexchange.stubs.report.InsuranceFundPerShard.Builder perShard =
                com.binance.raftexchange.stubs.report.InsuranceFundPerShard.newBuilder()
                    .putAllLoanInsuranceFund(convertToHashMap(data.getLoanInsuranceFund()))
                    .putAllMarkPriceTs(convertToHashMap(data.getMarkPriceTs()));
            data.getFuturesInsuranceFund().forEachKeyValue((symbolId, entry) -> perShard.putFuturesInsuranceFund(
                symbolId, com.binance.raftexchange.stubs.report.FuturesIfEntry.newBuilder()
                    .setAvailable(entry.getAvailable()).setPositionValue(entry.getPositionValue()).build()));
            result.putByShard(shardId, perShard.build());
        });
        return com.binance.raftexchange.stubs.report.ReportResult.newBuilder().setInsuranceFund(result).build();
    }

    /** 借贷平台侧账本：四个 per-currency 桶，逐 shard 转换。 */
    public static com.binance.raftexchange.stubs.report.ReportResult serializeToPb(
        exchange.core2.core.common.api.reports.LoanPlatformReportResult src) {
        com.binance.raftexchange.stubs.report.LoanPlatformReportResult.Builder result =
            com.binance.raftexchange.stubs.report.LoanPlatformReportResult.newBuilder();
        src.getByShard().forEachKeyValue((shardId, data) -> result.putByShard(shardId,
            com.binance.raftexchange.stubs.report.LoanPlatformPerShard.newBuilder()
                .putAllInterestRevenue(convertToHashMap(data.getInterestRevenue()))
                .putAllLoanInsuranceFund(convertToHashMap(data.getLoanInsuranceFund()))
                .putAllPoolAvailable(convertToHashMap(data.getPoolAvailable()))
                .putAllPoolBorrowed(convertToHashMap(data.getPoolBorrowed()))
                .build()));
        return com.binance.raftexchange.stubs.report.ReportResult.newBuilder().setLoanPlatform(result).build();
    }

    public static Map<Integer, Long> convertToHashMap(IntLongHashMap intLongHashMap) {
        if (intLongHashMap == null) {
            return java.util.Collections.emptyMap();
        }
        Map<Integer, Long> result = Maps.newHashMapWithExpectedSize(intLongHashMap.size());
        intLongHashMap.forEachKeyValue(result::put);
        return result;
    }

    public static <V, R> Map<Integer, R> convertToHashMap(IntObjectHashMap<V> intObjectHashMap,
        Function<V, R> valueConverter) {
        if (intObjectHashMap == null) {
            return java.util.Collections.emptyMap();
        }
        Map<Integer, R> result = Maps.newHashMapWithExpectedSize(intObjectHashMap.size());
        intObjectHashMap.forEachKeyValue((key, value) -> result.put(key, valueConverter.apply(value)));
        return result;
    }

    public static InputStream wrapKnownBytes(byte[] bytes) {
        return new FastByteArrayInputStream(bytes);
    }

    /**
     * 优化grpc writeMessage性能，避免copy
     * 
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
