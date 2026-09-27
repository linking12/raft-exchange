package com.binance.raftexchange.server.util;

import com.binance.raftexchange.stubs.response.CommandResult;
import com.binance.raftexchange.stubs.response.OrderCommandType;
import exchange.core2.core.common.L2MarketData;
import exchange.core2.core.common.MarginMode;
import exchange.core2.core.common.MatcherEventType;
import exchange.core2.core.common.MatcherTradeEvent;
import exchange.core2.core.common.OrderAction;
import exchange.core2.core.common.OrderType;
import exchange.core2.core.common.cmd.CommandResultCode;
import exchange.core2.core.common.cmd.OrderCommand;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SerializeHelper.orderCommandToResult 把 exchange-core 的 OrderCommand 翻译成 grpc CommandResult。 涉及负数 resultCode 取绝对值、链式
 * matcherEvent / L2MarketData 嵌套、null 安全分支。
 */
class SerializeHelperOrderCommandTest {

    private static OrderCommand baseCommand() {
        return OrderCommand.testBuilder(0).command(exchange.core2.core.common.cmd.OrderCommandType.PLACE_ORDER)
            .orderId(1L).symbol(1).price(100L).size(10L).reserveBidPrice(0L).action(OrderAction.BID)
            .orderType(OrderType.GTC).uid(42L).timestamp(123L).userCookie(0).leverage(0).marginMode(MarginMode.ISOLATED)
            .resultCode(CommandResultCode.SUCCESS).build();
    }

    @Test
    void minimalOrderCommand_translatesAllScalarFields() {
        CommandResult result = SerializeHelper.orderCommandToResult(baseCommand());

        com.binance.raftexchange.stubs.response.OrderCommand pb = result.getOrderCommand();
        assertEquals(1L, pb.getOrderId());
        assertEquals(1, pb.getSymbol());
        assertEquals(100L, pb.getPrice());
        assertEquals(10L, pb.getSize());
        assertEquals(42L, pb.getUid());
        assertEquals(123L, pb.getTimestamp());
        assertEquals(OrderCommandType.PLACE_ORDER, pb.getCommand());
    }

    @Test
    void negativeResultCode_isAbsValued() {
        // 真实失败码用负数；序列化时统一翻正
        OrderCommand cmd =
            OrderCommand.testBuilder(0).command(exchange.core2.core.common.cmd.OrderCommandType.PLACE_ORDER)
                .marginMode(MarginMode.ISOLATED).resultCode(CommandResultCode.MATCHING_INVALID_ORDER_BOOK_ID).build();

        CommandResult result = SerializeHelper.orderCommandToResult(cmd);

        int gotCode = result.getOrderCommand().getResultCode().getNumber();
        int srcAbs = Math.abs(CommandResultCode.MATCHING_INVALID_ORDER_BOOK_ID.getCode());
        assertEquals(srcAbs, gotCode, "负数 resultCode 必须 abs() 后映射到 grpc enum");
    }

    @Test
    void matcherEvent_chain_isCopiedLinkedList() {
        // 链式 matcherEvent: head → next1 → next2
        MatcherTradeEvent head = new MatcherTradeEvent();
        head.eventType = MatcherEventType.TRADE;
        head.section = 1;
        head.matchedOrderId = 100;
        head.size = 5;
        head.price = 50;
        MatcherTradeEvent next1 = new MatcherTradeEvent();
        next1.eventType = MatcherEventType.TRADE;
        next1.section = 2;
        next1.matchedOrderId = 101;
        next1.size = 3;
        next1.price = 51;
        head.nextEvent = next1;
        MatcherTradeEvent next2 = new MatcherTradeEvent();
        next2.eventType = MatcherEventType.TRADE;
        next2.section = 3;
        next2.matchedOrderId = 102;
        next2.size = 2;
        next2.price = 52;
        next1.nextEvent = next2;

        OrderCommand cmd =
            OrderCommand.testBuilder(0).command(exchange.core2.core.common.cmd.OrderCommandType.PLACE_ORDER)
                .marginMode(MarginMode.ISOLATED).resultCode(CommandResultCode.SUCCESS).matcherEvent(head).build();

        CommandResult result = SerializeHelper.orderCommandToResult(cmd);

        // 验证链表 3 个节点全被翻译进 nested protobuf
        com.binance.raftexchange.stubs.response.MatcherTradeEvent pb1 = result.getOrderCommand().getMatcherEvent();
        assertEquals(1, pb1.getSection());
        assertEquals(100, pb1.getMatchedOrderId());
        com.binance.raftexchange.stubs.response.MatcherTradeEvent pb2 = pb1.getNextEvent();
        assertEquals(2, pb2.getSection());
        com.binance.raftexchange.stubs.response.MatcherTradeEvent pb3 = pb2.getNextEvent();
        assertEquals(3, pb3.getSection());
        assertEquals(102, pb3.getMatchedOrderId());
    }

    @Test
    void marketData_l2_translatesAskAndBidArrays() {
        L2MarketData md = new L2MarketData(3, 2);
        md.askSize = 3;
        md.bidSize = 2;
        md.askPrices = new long[] {100, 101, 102};
        md.askVolumes = new long[] {10, 20, 30};
        md.askOrders = new long[] {1, 2, 3};
        md.bidPrices = new long[] {99, 98};
        md.bidVolumes = new long[] {15, 25};
        md.bidOrders = new long[] {4, 5};

        OrderCommand cmd =
            OrderCommand.testBuilder(0).command(exchange.core2.core.common.cmd.OrderCommandType.ORDER_BOOK_REQUEST)
                .marginMode(MarginMode.ISOLATED).resultCode(CommandResultCode.SUCCESS).marketData(md).build();

        CommandResult result = SerializeHelper.orderCommandToResult(cmd);

        com.binance.raftexchange.stubs.response.L2MarketData mdPb = result.getOrderCommand().getMarketData();
        assertEquals(3, mdPb.getAskSizes());
        assertEquals(2, mdPb.getBidSizes());
        assertEquals(100L, mdPb.getAskPrices(0));
        assertEquals(102L, mdPb.getAskPrices(2));
        assertEquals(99L, mdPb.getBidPrices(0));
        assertEquals(25L, mdPb.getBidVolumes(1));
    }

    @Test
    void nullAction_andNullOrderType_skippedSafely() {
        // 某些 cmd（NOP / RESET_FEE 等）没有 action/orderType — 不能 NPE
        OrderCommand cmd = OrderCommand.testBuilder(0).command(exchange.core2.core.common.cmd.OrderCommandType.NOP)
            .marginMode(MarginMode.ISOLATED).resultCode(CommandResultCode.SUCCESS)
            // 不 set action / orderType → 留 null
            .build();

        CommandResult result = SerializeHelper.orderCommandToResult(cmd);

        // 不 NPE，且 action / orderType 走默认值（0）
        assertTrue(result.hasOrderCommand());
        assertEquals(0, result.getOrderCommand().getActionValue());
        assertEquals(0, result.getOrderCommand().getOrderTypeValue());
    }
}
