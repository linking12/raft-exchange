package com.binance.raftexchange.server.exchange.events;

import com.binance.raftexchange.server.exchange.events.IEventsHandlerByKafka.TopicGroup;
import com.binance.raftexchange.server.raft.RaftNode;
import exchange.core2.core.IFundEventsHandler.FundEventReport;
import exchange.core2.core.ITradeEventsHandler.FuturesExecutionReport;
import exchange.core2.core.ITradeEventsHandler.OrderBook;
import exchange.core2.core.ITradeEventsHandler.SpotExecutionReport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyLong;

/**
 * Verifies that no events are enqueued when the node is not leader.
 */
class IEventsHandlerByKafkaLeaderGateTest {

    private KafkaEventSink mockSink;
    private IEventsHandlerByKafka handler;

    @BeforeEach
    void setUp() {
        mockSink = mock(KafkaEventSink.class);
        handler = new IEventsHandlerByKafka(mockSink); // default: FOLLOWER
    }

    @Test
    void spotExecutionReport_skippedWhenFollower() {
        handler.spotExecutionReport(mock(SpotExecutionReport.class));
        verify(mockSink, never()).enqueue(any(TopicGroup.class), anyLong(), any());
    }

    @Test
    void futuresExecutionReport_skippedWhenFollower() {
        handler.futuresExecutionReport(mock(FuturesExecutionReport.class));
        verify(mockSink, never()).enqueue(any(TopicGroup.class), anyLong(), any());
    }

    @Test
    void orderBook_skippedWhenFollower() {
        handler.orderBook(mock(OrderBook.class));
        verify(mockSink, never()).enqueue(any(TopicGroup.class), anyLong(), any());
    }

    @Test
    void fundEventReport_skippedWhenFollower() {
        handler.fundEventReport(mock(FundEventReport.class));
        verify(mockSink, never()).enqueue(any(TopicGroup.class), anyLong(), any());
    }

    @Test
    void afterRolePromotedThenDemoted_skipsAgain() {
        handler.onRoleChange(RaftNode.NodeType.LEADER);
        handler.onRoleChange(RaftNode.NodeType.FOLLOWER);

        handler.spotExecutionReport(mock(SpotExecutionReport.class));
        handler.futuresExecutionReport(mock(FuturesExecutionReport.class));
        handler.orderBook(mock(OrderBook.class));
        handler.fundEventReport(mock(FundEventReport.class));

        verify(mockSink, never()).enqueue(any(TopicGroup.class), anyLong(), any());
    }
}
