package com.binance.raftexchange.server.util;

import com.google.protobuf.Message.Builder;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * 优化grpc，缓存以复用builder
 */
public class ProtoBuilderPool {

    private final Map<Class<?>, ThreadLocal<? extends Builder>> pools = new ConcurrentHashMap<>();

    /**
     * 注册一个类型的Builder缓存池（必须提前注册）
     */
    public <T extends Builder> void register(Class<T> builderClass, Supplier<T> supplier) {
        pools.putIfAbsent(builderClass, ThreadLocal.withInitial(supplier));
    }

    /**
     * 获取已注册的Builder
     */
    public <T extends Builder> T get(Class<T> builderClass) {
        ThreadLocal<T> local = (ThreadLocal<T>) pools.get(builderClass);
        if (local == null) {
            throw new IllegalStateException("Builder not registered for: " + builderClass.getName());
        }
        T builder = local.get();
        builder.clear(); // 清理，确保拿到的是干净的builder
        return builder;
    }
}