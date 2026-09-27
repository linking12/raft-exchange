package com.binance.raftexchange.server.raft.jraft.closure;

import java.util.function.BiConsumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.alipay.sofa.jraft.Closure;
import com.binance.raftexchange.server.raft.RaftResponse;

/**
 * SOFA-JRaft {@link Closure} 扩展接口：定义所有 raft callback 共享的 helper（{@link #safeInvoke} 吞 callback 抛出的异常防扩散）。
 * 子类 {@link SingleClosure} 对应单条命令，{@link BatchClosure} 对应 envelope batch。
 */
public interface RaftClosure extends Closure {

    Logger LOGGER = LoggerFactory.getLogger(RaftClosure.class);

    static void safeInvoke(BiConsumer<RaftResponse, Throwable> callback, RaftResponse resp, Throwable err,
        String context) {
        try {
            callback.accept(resp, err);
        } catch (Throwable t) {
            LOGGER.error("{} callback threw", context, t);
        }
    }
}
