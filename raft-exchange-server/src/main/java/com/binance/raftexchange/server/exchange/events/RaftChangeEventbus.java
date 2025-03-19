package com.binance.raftexchange.server.exchange.events;

import com.binance.raftexchange.server.raft.RaftNode;

import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

public class RaftChangeEventbus {

    public static final RaftChangeEventbus INSTANCE = new RaftChangeEventbus();

    private CopyOnWriteArrayList<Consumer<RaftNode.NodeType>> listeners;

    private AtomicBoolean isLeader = new AtomicBoolean(false);

    private RaftChangeEventbus() {
        this.listeners = new CopyOnWriteArrayList<>();
    }

    public void publish(RaftNode.NodeType currentStatus) {
        isLeader.set(currentStatus == RaftNode.NodeType.LEADER);
        for (Consumer<RaftNode.NodeType> listener : listeners) {
            listener.accept(currentStatus);
        }
    }

    public void registerListener(Consumer<RaftNode.NodeType> listener) {
        listeners.add(listener);
        listener.accept(isLeader.get() ? RaftNode.NodeType.LEADER : RaftNode.NodeType.FOLLOWER);
    }

}
