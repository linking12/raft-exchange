package com.binance.raftexchange.client;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.time.Duration;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ExchangeApiOptionsTest {

    @Test
    @DisplayName("defaults()：sendTimeout = 2s，nodesFlushInterval = 1min")
    void defaultsMatchConstant() {
        ExchangeApiOptions options = ExchangeApiOptions.defaults();
        assertEquals(Duration.ofSeconds(2), options.sendTimeout());
        assertSame(ExchangeApiOptions.DEFAULT_SEND_TIMEOUT, options.sendTimeout());
        assertEquals(Duration.ofMinutes(1), options.nodesFlushInterval());
        assertSame(ExchangeApiOptions.DEFAULT_NODES_FLUSH_INTERVAL, options.nodesFlushInterval());
    }

    @Test
    @DisplayName("builder.nodesFlushInterval(...) 覆盖默认值")
    void builderOverridesNodesFlushInterval() {
        ExchangeApiOptions options = ExchangeApiOptions.builder().nodesFlushInterval(Duration.ofSeconds(15)).build();
        assertEquals(Duration.ofSeconds(15), options.nodesFlushInterval());
        // sendTimeout 还是 default
        assertEquals(Duration.ofSeconds(2), options.sendTimeout());
    }

    @Test
    @DisplayName("toBuilder() 把 nodesFlushInterval 一起带过去")
    void toBuilderPropagatesNodesFlushInterval() {
        ExchangeApiOptions base = ExchangeApiOptions.builder().sendTimeout(Duration.ofSeconds(7))
            .nodesFlushInterval(Duration.ofSeconds(45)).build();
        ExchangeApiOptions derived = base.toBuilder().build();
        assertEquals(Duration.ofSeconds(7), derived.sendTimeout());
        assertEquals(Duration.ofSeconds(45), derived.nodesFlushInterval());
    }

    @ParameterizedTest
    @MethodSource("invalidDurations")
    @DisplayName("nodesFlushInterval 必须 > 0，否则 IllegalArgumentException")
    void rejectsZeroOrNegativeNodesFlushInterval(Duration bad) {
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
            () -> ExchangeApiOptions.builder().nodesFlushInterval(bad));
    }

    @Test
    @DisplayName("nodesFlushInterval(null) → NullPointerException 带字段名")
    void rejectsNullNodesFlushInterval() {
        NullPointerException e = org.junit.jupiter.api.Assertions.assertThrows(NullPointerException.class,
            () -> ExchangeApiOptions.builder().nodesFlushInterval(null));
        assertEquals("nodesFlushInterval", e.getMessage());
    }

    @Test
    @DisplayName("builder.sendTimeout(...) 覆盖默认值")
    void builderOverridesSendTimeout() {
        ExchangeApiOptions options = ExchangeApiOptions.builder().sendTimeout(Duration.ofSeconds(30)).build();
        assertEquals(Duration.ofSeconds(30), options.sendTimeout());
    }

    @Test
    @DisplayName("toBuilder()：基于现有 options 派生新 options，不影响原对象")
    void toBuilderPreservesAndPermitsOverride() {
        ExchangeApiOptions base = ExchangeApiOptions.builder().sendTimeout(Duration.ofSeconds(10)).build();
        ExchangeApiOptions tweaked = base.toBuilder().sendTimeout(Duration.ofSeconds(20)).build();

        assertEquals(Duration.ofSeconds(10), base.sendTimeout(), "原 options 不变");
        assertEquals(Duration.ofSeconds(20), tweaked.sendTimeout());
        assertNotSame(base, tweaked);
    }

    static Stream<Duration> invalidDurations() {
        return Stream.of(Duration.ZERO, Duration.ofMillis(-1), Duration.ofSeconds(-10));
    }

    @ParameterizedTest
    @MethodSource("invalidDurations")
    @DisplayName("sendTimeout 必须 > 0，否则 IllegalArgumentException")
    void rejectsZeroOrNegative(Duration bad) {
        assertThrows(IllegalArgumentException.class, () -> ExchangeApiOptions.builder().sendTimeout(bad));
    }

    @Test
    @DisplayName("sendTimeout(null) → NullPointerException 带字段名")
    void rejectsNull() {
        NullPointerException e =
            assertThrows(NullPointerException.class, () -> ExchangeApiOptions.builder().sendTimeout(null));
        assertEquals("sendTimeout", e.getMessage());
    }

    @Test
    @DisplayName("builder() 每次返回新实例，链式不可重入污染")
    void builderInstancesAreIndependent() {
        ExchangeApiOptions.Builder b1 = ExchangeApiOptions.builder().sendTimeout(Duration.ofSeconds(5));
        ExchangeApiOptions.Builder b2 = ExchangeApiOptions.builder().sendTimeout(Duration.ofSeconds(50));
        assertEquals(Duration.ofSeconds(5), b1.build().sendTimeout());
        assertEquals(Duration.ofSeconds(50), b2.build().sendTimeout());
    }
}
