package com.binance.raftexchange.server.util;

import java.util.function.Function;

public interface ThrowableFunction<T, R> {
    R apply(T t) throws Throwable;

    default Function<T, R> toFunction() {
        return t -> {
            try {
                return this.apply(t);
            } catch (Throwable e) {
                throw new RuntimeException(e);
            }
        };
    }

    static <T, R> Function<T, R> warp(ThrowableFunction<T, R> tf) {
        return tf.toFunction();
    }
}