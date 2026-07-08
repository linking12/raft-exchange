package com.binance.raftexchange.client;

import java.time.Duration;
import java.util.Objects;

public final class ExchangeApiOptions {

    public static final Duration DEFAULT_SEND_TIMEOUT = Duration.ofSeconds(2);
    public static final Duration DEFAULT_NODES_FLUSH_INTERVAL = Duration.ofSeconds(1);
    public static final int DEFAULT_RETRY_ATTEMPTS = 3;
    public static final Duration DEFAULT_RETRY_BACKOFF = Duration.ofMillis(100);

    private final Duration sendTimeout;
    private final Duration nodesFlushInterval;
    private final int retryAttempts;
    private final Duration retryBackoff;

    private ExchangeApiOptions(Builder b) {
        this.sendTimeout = b.sendTimeout;
        this.nodesFlushInterval = b.nodesFlushInterval;
        this.retryAttempts = b.retryAttempts;
        this.retryBackoff = b.retryBackoff;
    }

    public Duration sendTimeout() {
        return sendTimeout;
    }

    public Duration nodesFlushInterval() {
        return nodesFlushInterval;
    }

    public int retryAttempts() {
        return retryAttempts;
    }

    public Duration retryBackoff() {
        return retryBackoff;
    }

    public static ExchangeApiOptions defaults() {
        return builder().build();
    }

    public static Builder builder() {
        return new Builder();
    }

    public Builder toBuilder() {
        return new Builder().sendTimeout(this.sendTimeout).nodesFlushInterval(this.nodesFlushInterval)
            .retryAttempts(this.retryAttempts).retryBackoff(this.retryBackoff);
    }

    @Override
    public String toString() {
        return "ExchangeApiOptions{sendTimeout=" + sendTimeout + ", nodesFlushInterval=" + nodesFlushInterval
            + ", retryAttempts=" + retryAttempts + ", retryBackoff=" + retryBackoff + '}';
    }

    public static final class Builder {
        private Duration sendTimeout = DEFAULT_SEND_TIMEOUT;
        private Duration nodesFlushInterval = DEFAULT_NODES_FLUSH_INTERVAL;
        private int retryAttempts = DEFAULT_RETRY_ATTEMPTS;
        private Duration retryBackoff = DEFAULT_RETRY_BACKOFF;

        private Builder() {}

        public Builder sendTimeout(Duration sendTimeout) {
            this.sendTimeout = requirePositive(sendTimeout, "sendTimeout");
            return this;
        }

        public Builder nodesFlushInterval(Duration nodesFlushInterval) {
            this.nodesFlushInterval = requirePositive(nodesFlushInterval, "nodesFlushInterval");
            return this;
        }

        /** 0 表示不重发（NEED_MOVE / DROP 都直接透传给业务层）。 */
        public Builder retryAttempts(int attempts) {
            if (attempts < 0) {
                throw new IllegalArgumentException("retryAttempts must be >= 0, got " + attempts);
            }
            this.retryAttempts = attempts;
            return this;
        }

        public Builder retryBackoff(Duration backoff) {
            this.retryBackoff = requirePositive(backoff, "retryBackoff");
            return this;
        }

        public ExchangeApiOptions build() {
            return new ExchangeApiOptions(this);
        }

        private static Duration requirePositive(Duration d, String name) {
            Objects.requireNonNull(d, name);
            if (d.isNegative() || d.isZero()) {
                throw new IllegalArgumentException(name + " must be positive, got " + d);
            }
            return d;
        }
    }
}
