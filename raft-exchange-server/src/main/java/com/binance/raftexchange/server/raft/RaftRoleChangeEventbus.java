package com.binance.raftexchange.server.raft;

import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

public class RaftRoleChangeEventbus {

    public static final RaftRoleChangeEventbus INSTANCE = new RaftRoleChangeEventbus();

    private CopyOnWriteArrayList<Consumer<RaftNode.NodeType>> listeners;

    private AtomicBoolean isLeader = new AtomicBoolean(false);

    private RaftRoleChangeEventbus() {
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

    public static boolean isLeader() {
        return INSTANCE.isLeader.get();
    }

}
