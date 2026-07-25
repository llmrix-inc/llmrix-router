package com.llmrix.model.router.core.execution;

import java.time.Duration;

public record ExecutionPolicy(
        Duration timeout,
        int maxRetries,
        Duration retryDelay,
        int failureThreshold,
        Duration cooldown,
        Duration firstTokenTimeout,
        Duration streamIdleTimeout) {

    public static final ExecutionPolicy DEFAULT = new ExecutionPolicy(
            Duration.ofSeconds(30), 1, Duration.ofMillis(200), 3, Duration.ofSeconds(60), null, null);

    public ExecutionPolicy(
            Duration timeout, int maxRetries, Duration retryDelay, int failureThreshold, Duration cooldown) {
        this(timeout, maxRetries, retryDelay, failureThreshold, cooldown, null, null);
    }

    public ExecutionPolicy {
        if (timeout == null || timeout.isNegative() || timeout.isZero()) throw new IllegalArgumentException("timeout must be > 0");
        if (maxRetries < 0) throw new IllegalArgumentException("maxRetries must be >= 0");
        if (retryDelay == null || retryDelay.isNegative()) throw new IllegalArgumentException("retryDelay must be >= 0");
        if (failureThreshold < 1) throw new IllegalArgumentException("failureThreshold must be > 0");
        if (cooldown == null || cooldown.isNegative()) throw new IllegalArgumentException("cooldown must be >= 0");
        if (firstTokenTimeout != null && (firstTokenTimeout.isNegative() || firstTokenTimeout.isZero())) {
            throw new IllegalArgumentException("firstTokenTimeout must be > 0");
        }
        if (streamIdleTimeout != null && (streamIdleTimeout.isNegative() || streamIdleTimeout.isZero())) {
            throw new IllegalArgumentException("streamIdleTimeout must be > 0");
        }
    }
}
