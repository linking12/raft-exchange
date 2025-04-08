package com.binance.raftexchange.client.tests.util;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

@Slf4j
@RequiredArgsConstructor
public class ExecutionTime implements AutoCloseable {

    private final Consumer<String> executionTimeConsumer;
    private final long startNs = System.nanoTime();

    @Getter
    private final CompletableFuture<Long> resultNs = new CompletableFuture<>();

    public ExecutionTime() {
        this.executionTimeConsumer = s -> {
        };
    }

    @Override
    public void close() {
        executionTimeConsumer.accept(getTimeFormatted());
    }

    public String getTimeFormatted() {
        if (!resultNs.isDone()) {
            resultNs.complete(System.nanoTime() - startNs);
        }
        return formatNanos(resultNs.join());
    }

    public static String formatNanos(long ns) {
        float value = ns / 1000f;
        String timeUnit = "µs";
        if (value > 1000) {
            value /= 1000;
            timeUnit = "ms";
        }

        if (value > 1000) {
            value /= 1000;
            timeUnit = "s";
        }

        if (value < 3) {
            return Math.round(value * 100) / 100f + timeUnit;
        } else if (value < 30) {
            return Math.round(value * 10) / 10f + timeUnit;
        } else {
            return Math.round(value) + timeUnit;
        }
    }
}
