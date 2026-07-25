package com.llmrix.model.router.core.execution;

import java.time.Duration;

public interface HealthState {
    boolean available(long nowMillis);
    int inFlight();
    double latencyEwmaMillis();
    void begin();
    void cancel();
    void success(long durationNanos);
    boolean failure(long durationNanos, int threshold, Duration cooldown);

    default HealthAttempt beginAttempt() {
        begin();
        return new HealthAttempt() {
            private final java.util.concurrent.atomic.AtomicBoolean settled = new java.util.concurrent.atomic.AtomicBoolean();
            @Override public void cancel() {
                if (settled.compareAndSet(false, true)) HealthState.this.cancel();
            }
            @Override public void success(long durationNanos) {
                if (settled.compareAndSet(false, true)) HealthState.this.success(durationNanos);
            }
            @Override public boolean failure(long durationNanos, int threshold, Duration cooldown) {
                return settled.compareAndSet(false, true)
                        && HealthState.this.failure(durationNanos, threshold, cooldown);
            }
        };
    }

    /** Returns null when the concurrency limit is already exhausted. */
    default HealthAttempt tryBeginAttempt(Integer maxConcurrency) {
        synchronized (this) {
            if (maxConcurrency != null && inFlight() >= maxConcurrency) return null;
            return beginAttempt();
        }
    }
}
