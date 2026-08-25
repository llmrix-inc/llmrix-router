package com.llmrix.model.router.core.engine;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.experimental.Accessors;

import java.time.Duration;

@Getter
@EqualsAndHashCode
@Accessors(fluent = true)
public final class ExecutionPolicy {
    private final Duration timeout;
    private final int maxRetries;
    private final Duration retryDelay;
    private final int failureThreshold;
    private final Duration cooldown;
    private final Duration firstTokenTimeout;
    private final Duration streamIdleTimeout;

    public static final ExecutionPolicy DEFAULT = new ExecutionPolicy(
            Duration.ofSeconds(30), 1, Duration.ofMillis(200), 3, Duration.ofSeconds(60), null, null);

    public ExecutionPolicy(
            Duration timeout, int maxRetries, Duration retryDelay, int failureThreshold, Duration cooldown) {
        this(timeout, maxRetries, retryDelay, failureThreshold, cooldown, null, null);
    }

    public ExecutionPolicy(Duration timeout, int maxRetries, Duration retryDelay, int failureThreshold,
                           Duration cooldown, Duration firstTokenTimeout, Duration streamIdleTimeout) {
        if (timeout == null || timeout.isNegative() || timeout.isZero())
            throw new IllegalArgumentException("timeout must be > 0");
        if (maxRetries < 0) throw new IllegalArgumentException("maxRetries must be >= 0");
        if (retryDelay == null || retryDelay.isNegative())
            throw new IllegalArgumentException("retryDelay must be >= 0");
        if (failureThreshold < 1) throw new IllegalArgumentException("failureThreshold must be > 0");
        if (cooldown == null || cooldown.isNegative()) throw new IllegalArgumentException("cooldown must be >= 0");
        if (firstTokenTimeout != null && (firstTokenTimeout.isNegative() || firstTokenTimeout.isZero())) {
            throw new IllegalArgumentException("firstTokenTimeout must be > 0");
        }
        if (streamIdleTimeout != null && (streamIdleTimeout.isNegative() || streamIdleTimeout.isZero())) {
            throw new IllegalArgumentException("streamIdleTimeout must be > 0");
        }
        this.timeout = timeout;
        this.maxRetries = maxRetries;
        this.retryDelay = retryDelay;
        this.failureThreshold = failureThreshold;
        this.cooldown = cooldown;
        this.firstTokenTimeout = firstTokenTimeout;
        this.streamIdleTimeout = streamIdleTimeout;
    }

}
