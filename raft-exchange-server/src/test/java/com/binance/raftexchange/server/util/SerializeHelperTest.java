package com.binance.raftexchange.server.util;

import com.binance.raftexchange.stubs.report.QueryExecutionStatus;
import com.binance.raftexchange.stubs.report.ReportResult;
import com.binance.raftexchange.stubs.request.ApiAddUser;
import com.binance.raftexchange.stubs.request.ApiCommand;
import com.binance.raftexchange.stubs.response.CommandResult;
import com.binance.raftexchange.stubs.response.CommandResultCode;
import com.google.protobuf.GeneratedMessageV3;
import com.google.protobuf.InvalidProtocolBufferException;
import exchange.core2.core.common.UserStatus;
import exchange.core2.core.common.api.reports.SingleUserReportResult;
import net.openhft.chronicle.bytes.Bytes;
import org.eclipse.collections.impl.map.mutable.primitive.IntLongHashMap;
import org.eclipse.collections.impl.map.mutable.primitive.IntObjectHashMap;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SerializeHelperTest {

    @Test
    void deserializeWithType_roundTripsApiCommand() throws Exception {
        ApiCommand original =
            ApiCommand.newBuilder().setTimestamp(123L).setAddUser(ApiAddUser.newBuilder().setUid(42L)).build();

        GeneratedMessageV3 parsed = SerializeHelper.deserializeWithType(ByteBuffer.wrap(original.toByteArray()));

        assertEquals(original, parsed, "round-trip 必须保持完全相等");
    }

    @Test
    void serializeToCommandResult_resultCode_producesParseableProto() throws InvalidProtocolBufferException {
        byte[] bytes =
            SerializeHelper.serializeToCommandResult(exchange.core2.core.common.cmd.CommandResultCode.SUCCESS);

        CommandResult parsed = CommandResult.parseFrom(bytes);
        assertEquals(CommandResultCode.SUCCESS, parsed.getResultCode());
    }

    @Test
    void serializeToCommandResult_negativeErrorCode_mapsToSameAbsoluteGrpcEnum() throws InvalidProtocolBufferException {
        // exchange-core 用负数表示错误码；显式映射保留 abs(code) 这个稳定语义
        exchange.core2.core.common.cmd.CommandResultCode err =
            exchange.core2.core.common.cmd.CommandResultCode.MATCHING_INVALID_ORDER_BOOK_ID;
        byte[] bytes = SerializeHelper.serializeToCommandResult(err);

        CommandResult parsed = CommandResult.parseFrom(bytes);
        assertEquals(Math.abs(err.getCode()), parsed.getResultCode().getNumber());
    }

    @Test
    void serializeToCommandResult_allExchangeCoreCodes_mapToNonNullGrpcEnum() {
        // 全集校验：exchange-core 的每个 case 都必须能映射成功（类加载期已 fail-fast 过；此处再 sanity 一遍）
        for (exchange.core2.core.common.cmd.CommandResultCode src : exchange.core2.core.common.cmd.CommandResultCode
            .values()) {
            byte[] bytes = SerializeHelper.serializeToCommandResult(src);
            try {
                CommandResult parsed = CommandResult.parseFrom(bytes);
                assertEquals(Math.abs(src.getCode()), parsed.getResultCode().getNumber(),
                    "case " + src.name() + " 应映射到 abs(" + src.getCode() + ")");
            } catch (InvalidProtocolBufferException e) {
                throw new AssertionError("case " + src.name() + " 序列化结果不可解析", e);
            }
        }
    }

    @Test
    void serializeToCommandResult_stateRecoverFailedCodes_doNotNPE() {
        // 回归 bug: STATE_RECOVER_RISK_ENGINE_FAILED=-8021 / STATE_RECOVER_MATCHING_ENGINE_FAILED=-8022
        // 在旧实现里 forNumber(abs(code)) 返 null → setResultCode(null) NPE。新映射必须能处理。
        for (exchange.core2.core.common.cmd.CommandResultCode src : new exchange.core2.core.common.cmd.CommandResultCode[] {
            exchange.core2.core.common.cmd.CommandResultCode.STATE_RECOVER_RISK_ENGINE_FAILED,
            exchange.core2.core.common.cmd.CommandResultCode.STATE_RECOVER_MATCHING_ENGINE_FAILED}) {
            byte[] bytes = SerializeHelper.serializeToCommandResult(src);
            assertEquals(src.name().length() > 0, true, "至少不能 NPE; got " + bytes.length + " bytes");
        }
    }

    @Test
    void enumProto_roundTrip() throws InvalidProtocolBufferException {
        byte[] bytes = SerializeHelper.enumToBytesProto(QueryExecutionStatus.OK);
        QueryExecutionStatus parsed = SerializeHelper.bytesToEnumProto(bytes, QueryExecutionStatus.class);
        assertEquals(QueryExecutionStatus.OK, parsed);
    }

    @Test
    void bytesToEnumProto_unknownValue_throws() {
        // 编码出来的整数不属于 QueryExecutionStatus 任何 case
        byte[] bytes = SerializeHelper.enumToBytesProto(CommandResultCode.SUCCESS);
        // CommandResultCode.SUCCESS.getNumber() = 100，QueryExecutionStatus 没有这个值
        assertThrows(IllegalArgumentException.class,
            () -> SerializeHelper.bytesToEnumProto(bytes, QueryExecutionStatus.class));
    }

    @Test
    void wrapKnownBytes_drainToWritesAllBytes() throws Exception {
        byte[] payload = {0x01, 0x02, 0x03, 0x04, 0x05};
        InputStream stream = SerializeHelper.wrapKnownBytes(payload);

        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        int written = ((SerializeHelper.FastByteArrayInputStream)stream).drainTo(sink);

        assertEquals(payload.length, written);
        assertArrayEquals(payload, sink.toByteArray());
        assertEquals(0, stream.available(), "drainTo 之后流必须读完");
    }

    @Test
    void convertToHashMap_intLongHashMap_flattensAllEntries() {
        IntLongHashMap src = new IntLongHashMap();
        src.put(1, 100L);
        src.put(2, 200L);
        src.put(3, 300L);

        Map<Integer, Long> result = SerializeHelper.convertToHashMap(src);

        assertEquals(3, result.size());
        assertEquals(100L, result.get(1));
        assertEquals(200L, result.get(2));
        assertEquals(300L, result.get(3));
    }

    @Test
    void serializeToPb_singleUserReport_userNotFound_shortCircuits() {
        SingleUserReportResult notFound = SingleUserReportResult.createFromRiskEngineNotFound(99L);

        ReportResult result = SerializeHelper.serializeToPb(notFound);

        com.binance.raftexchange.stubs.report.SingleUserReportResult pb = result.getSingleUserReport();
        assertEquals(99L, pb.getUserId());
        assertEquals(QueryExecutionStatus.USER_NOT_FOUND, pb.getQueryExecutionStatus());
        // USER_NOT_FOUND 路径不应填账户/持仓/订单 — 短路返回
        assertEquals(0, pb.getAccountsCount());
        assertEquals(0, pb.getPositionsCount());
        assertEquals(0, pb.getOrdersCount());
    }

    @Test
    void serializeToPb_singleUserReport_carriesExchangeLockedAlongsideAccounts() {
        IntLongHashMap accounts = new IntLongHashMap();
        accounts.put(2, 1000_000000L); // USDT total 1000.000000
        accounts.put(3, 500_00000000L); // BTC total 500.00000000

        IntLongHashMap locked = new IntLongHashMap();
        locked.put(2, 250_500000L); // USDT locked 250.500000（spot 挂单冻结）

        SingleUserReportResult merged = mergedReport(42L, accounts, locked);

        ReportResult result = SerializeHelper.serializeToPb(merged);
        com.binance.raftexchange.stubs.report.SingleUserReportResult pb = result.getSingleUserReport();

        assertEquals(42L, pb.getUserId());
        assertEquals(QueryExecutionStatus.OK, pb.getQueryExecutionStatus());

        // accounts 与 exchangeLocked 是独立字段，互不影响
        assertEquals(2, pb.getAccountsCount());
        assertEquals(1000_000000L, pb.getAccountsOrThrow(2));
        assertEquals(500_00000000L, pb.getAccountsOrThrow(3));

        // 只有 currency=2（USDT）有 locked，currency=3（BTC）不在 locked map
        assertEquals(1, pb.getExchangeLockedCount());
        assertEquals(250_500000L, pb.getExchangeLockedOrThrow(2));
    }

    @Test
    void serializeToPb_singleUserReport_emptyExchangeLocked_yieldsEmptyMap() {
        IntLongHashMap accounts = new IntLongHashMap();
        accounts.put(2, 100L);

        SingleUserReportResult merged = mergedReport(7L, accounts, new IntLongHashMap());

        ReportResult result = SerializeHelper.serializeToPb(merged);

        assertEquals(0, result.getSingleUserReport().getExchangeLockedCount(),
            "无 spot 冻结的用户应序列化为空 exchange_locked map");
    }

    /** 模拟生产路径：risk + matching 两段 marshal 后 merge，得到字段齐全的对象。 */
    private static SingleUserReportResult mergedReport(long uid, IntLongHashMap accounts,
        IntLongHashMap exchangeLocked) {
        SingleUserReportResult risk = SingleUserReportResult.createFromRiskEngineFound(uid, UserStatus.ACTIVE, accounts,
            exchangeLocked, new IntObjectHashMap<>(),
            java.util.Collections.emptyList(), java.util.Collections.emptyList(), new IntLongHashMap(), 0L);
        SingleUserReportResult matching =
            SingleUserReportResult.createFromMatchingEngine(uid, new IntObjectHashMap<>());

        Bytes<ByteBuffer> riskBytes = Bytes.elasticByteBuffer();
        risk.writeMarshallable(riskBytes);
        Bytes<ByteBuffer> matchingBytes = Bytes.elasticByteBuffer();
        matching.writeMarshallable(matchingBytes);

        return SingleUserReportResult.merge(Stream.of(riskBytes, matchingBytes));
    }
}
