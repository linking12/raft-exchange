package com.binance.raftexchange.server.raft;

import java.util.function.Supplier;

/**
 * 共识层 hot path 回调返回结构。两个 backend 共享。
 *
 * @param serializer 响应字节供给函数（lazy serialize）
 * @param raftLatencyNanos raft 共识耗时
 * @param exchangeLatencyNanos 撮合（exchange-core）耗时
 * @param leaderLogPosition leader 处理此请求的 log entry 位置（aeron barrier 用来等本地追上；其它场景 0）
 */
public record RaftResponse(Supplier<byte[]> serializer, long raftLatencyNanos, long exchangeLatencyNanos,
    long leaderLogPosition) {}
