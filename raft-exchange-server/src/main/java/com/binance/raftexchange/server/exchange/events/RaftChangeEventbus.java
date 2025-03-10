package com.binance.raftexchange.server.exchange.events;

import com.binance.raftexchange.server.raft.RaftNode;

import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

public class RaftChangeEventbus {

    public static final RaftChangeEventbus INSTANCE = new RaftChangeEventbus();

    private CopyOnWriteArrayList<Consumer<RaftNode.NodeType>> listeners;

    private AtomicBoolean isLeader = new AtomicBoolean(true);

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
        // 由于启动顺序不同 所以保证在raft启动后的listener也可以获取到当前的节点信息
        listener.accept(isLeader.get() ? RaftNode.NodeType.LEADER : RaftNode.NodeType.FOLLOWER);
    }

}
