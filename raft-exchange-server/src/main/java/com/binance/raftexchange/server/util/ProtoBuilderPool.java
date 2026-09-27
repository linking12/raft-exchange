package com.binance.raftexchange.server.util;

import com.google.protobuf.Message.Builder;
import io.netty.util.concurrent.FastThreadLocal;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;

/**
 * 优化grpc，缓存以复用builder
 */
public class ProtoBuilderPool {

    private final Map<Class<?>, FastThreadLocal<? extends Builder>> pools = new HashMap<>();

    /**
     * 注册一个类型的Builder缓存池（必须提前注册）
     */
    public <T extends Builder> void register(Class<T> builderClass, Supplier<T> supplier) {
        pools.putIfAbsent(builderClass, new FastThreadLocal<>() {
            @Override
            protected T initialValue() {
                return supplier.get();
            }
        });
    }

    /**
     * 获取已注册的Builder
     */
    public <T extends Builder> T get(Class<T> builderClass) {
        FastThreadLocal<T> local = (FastThreadLocal<T>)pools.get(builderClass);
        if (local == null) {
            throw new IllegalStateException("Builder not registered for: " + builderClass.getName());
        }
        T builder = local.get();
        builder.clear(); // 清理，确保拿到的是干净的builder
        return builder;
    }
}