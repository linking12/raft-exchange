package com.binance.raftexchange.server.raft;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class RaftNodeTest {

    @Test
    void equals_sameFields_isEqual() {
        RaftNode a = new RaftNode("10.0.0.1", 9090, RaftNode.NodeType.LEADER);
        RaftNode b = new RaftNode("10.0.0.1", 9090, RaftNode.NodeType.LEADER);
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
    }

    @Test
    void equals_differentHost_isNotEqual() {
        RaftNode a = new RaftNode("10.0.0.1", 9090, RaftNode.NodeType.LEADER);
        RaftNode b = new RaftNode("10.0.0.2", 9090, RaftNode.NodeType.LEADER);
        assertNotEquals(a, b);
    }

    @Test
    void equals_differentPort_isNotEqual() {
        RaftNode a = new RaftNode("10.0.0.1", 9090, RaftNode.NodeType.LEADER);
        RaftNode b = new RaftNode("10.0.0.1", 9091, RaftNode.NodeType.LEADER);
        assertNotEquals(a, b);
    }

    @Test
    void equals_differentType_isNotEqual() {
        RaftNode a = new RaftNode("10.0.0.1", 9090, RaftNode.NodeType.LEADER);
        RaftNode b = new RaftNode("10.0.0.1", 9090, RaftNode.NodeType.FOLLOWER);
        assertNotEquals(a, b);
    }

    @Test
    void accessors_returnConstructorArgs() {
        RaftNode node = new RaftNode("10.0.0.1", 9090, RaftNode.NodeType.LEADER);
        assertEquals("10.0.0.1", node.host());
        assertEquals(9090, node.port());
        assertEquals(RaftNode.NodeType.LEADER, node.nodeType());
    }
}
