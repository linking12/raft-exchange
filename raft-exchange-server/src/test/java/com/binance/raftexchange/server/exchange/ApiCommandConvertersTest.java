package com.binance.raftexchange.server.exchange;

import com.binance.raftexchange.stubs.request.ApiCommand;
import exchange.core2.core.common.OrderAction;
import exchange.core2.core.common.OrderType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 锁定 LiquidationEngine 触发的强平 cmd 经 raft 复制 round-trip 后字段不丢/不串。
 * <p>
 * leader 端 exchange-core ApiCommand → {@link ApiCommandConverters#liquidationCmdToRaftLog} → proto bytes → follower 端
 * ApiCommand.parseFrom → convertXxx → exchange-core ApiCommand。 任一字段编解码错位都会导致 follower 状态机偏离 leader。
 */
class ApiCommandConvertersTest {

    @Test
    void liquidationOrder_roundTripPreservesAllFields() throws Exception {
        long ts = 1_234_567_890L;
        var leaderCmd = exchange.core2.core.common.api.ApiLiquidationOrder.builder().price(50_000L).size(7L)
            .orderId(123_456_789L).action(OrderAction.ASK).orderType(OrderType.IOC).uid(9801L).symbol(100001).build();

        byte[] raftLog = ApiCommandConverters.liquidationCmdToRaftLog(leaderCmd, ts);
        ApiCommand parsed = ApiCommand.parseFrom(raftLog);

        assertEquals(ts, parsed.getTimestamp(), "timestamp 必须透传");
        assertSame(ApiCommand.CommandCase.LIQUIDATION_ORDER, parsed.getCommandCase(),
            "oneof case 必须指向 LIQUIDATION_ORDER");

        var followerCmd = ApiCommandConverters.convertLiquidationOrder(parsed);
        assertEquals(leaderCmd.price, followerCmd.price);
        assertEquals(leaderCmd.size, followerCmd.size);
        assertEquals(leaderCmd.orderId, followerCmd.orderId);
        assertSame(leaderCmd.action, followerCmd.action);
        assertSame(leaderCmd.orderType, followerCmd.orderType);
        assertEquals(leaderCmd.uid, followerCmd.uid);
        assertEquals(leaderCmd.symbol, followerCmd.symbol);
    }

    @Test
    void ifTakeover_roundTripPreservesAllFields() throws Exception {
        long ts = 1_111_222_333L;
        var leaderCmd = exchange.core2.core.common.api.ApiIFTakeOver.builder().orderId(555_666L).uid(9802L)
            .symbol(100002).action(OrderAction.BID).size(3L).price(45_000L).build();

        byte[] raftLog = ApiCommandConverters.liquidationCmdToRaftLog(leaderCmd, ts);
        ApiCommand parsed = ApiCommand.parseFrom(raftLog);

        assertEquals(ts, parsed.getTimestamp());
        assertSame(ApiCommand.CommandCase.IF_TAKEOVER, parsed.getCommandCase());

        var followerCmd = ApiCommandConverters.convertIFTakeOver(parsed);
        assertEquals(leaderCmd.orderId, followerCmd.orderId);
        assertEquals(leaderCmd.uid, followerCmd.uid);
        assertEquals(leaderCmd.symbol, followerCmd.symbol);
        assertSame(leaderCmd.action, followerCmd.action);
        assertEquals(leaderCmd.size, followerCmd.size);
        assertEquals(leaderCmd.price, followerCmd.price);
    }

    @Test
    void autoDeleveraging_roundTripPreservesAllFields() throws Exception {
        long ts = 2_222_333_444L;
        var leaderCmd = exchange.core2.core.common.api.ApiAutoDeleveraging.builder().orderId(777_888L).uid(9803L)
            .symbol(100003).action(OrderAction.ASK).size(11L).price(40_000L).build();

        byte[] raftLog = ApiCommandConverters.liquidationCmdToRaftLog(leaderCmd, ts);
        ApiCommand parsed = ApiCommand.parseFrom(raftLog);

        assertEquals(ts, parsed.getTimestamp());
        assertSame(ApiCommand.CommandCase.AUTO_DELEVERAGING, parsed.getCommandCase());

        var followerCmd = ApiCommandConverters.convertAutoDeleveraging(parsed);
        assertEquals(leaderCmd.orderId, followerCmd.orderId);
        assertEquals(leaderCmd.uid, followerCmd.uid);
        assertEquals(leaderCmd.symbol, followerCmd.symbol);
        assertSame(leaderCmd.action, followerCmd.action);
        assertEquals(leaderCmd.size, followerCmd.size);
        assertEquals(leaderCmd.price, followerCmd.price);
    }

    @Test
    void toRaftLog_unsupportedCmdType_throws() {
        // ApiSystemLiquidationNotify 不走 raft 复制（只是下游事件），尝试序列化必须 fail-fast
        var notify = exchange.core2.core.common.api.ApiSystemLiquidationNotify.builder().fundEvent(null).build();
        assertThrows(IllegalArgumentException.class, () -> ApiCommandConverters.liquidationCmdToRaftLog(notify, 0L),
            "ApiSystemLiquidationNotify 不在 raft 复制白名单上，必须抛 IllegalArgumentException");
    }
}
