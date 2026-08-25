package com.llmrix.model.router.core.state;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public final class TargetHealth implements HealthState {
    private final AtomicInteger failures = new AtomicInteger();
    private final AtomicInteger inFlight = new AtomicInteger();
    private final AtomicLong cooldownUntilMillis = new AtomicLong();
    private volatile double latencyEwmaMillis;

    public boolean available(long nowMillis) {
        return nowMillis >= cooldownUntilMillis.get();
    }

    public int inFlight() {
        return inFlight.get();
    }

    public double latencyEwmaMillis() {
        return latencyEwmaMillis;
    }

    public void begin() {
        inFlight.incrementAndGet();
    }

    public void cancel() {
        inFlight.decrementAndGet();
    }

    public void success(long durationNanos) {
        inFlight.decrementAndGet();
        failures.set(0);
        double millis = durationNanos / 1_000_000d;
        latencyEwmaMillis = latencyEwmaMillis == 0 ? millis : latencyEwmaMillis * 0.8 + millis * 0.2;
    }

    public boolean failure(long durationNanos, int threshold, Duration cooldown) {
        inFlight.decrementAndGet();
        double millis = durationNanos / 1_000_000d;
        latencyEwmaMillis = latencyEwmaMillis == 0 ? millis : latencyEwmaMillis * 0.8 + millis * 0.2;
        if (failures.incrementAndGet() >= threshold) {
            cooldownUntilMillis.set(System.currentTimeMillis() + cooldown.toMillis());
            failures.set(0);
            return true;
        }
        return false;
    }

    @Override
    public HealthAttempt beginAttempt() {
        return tryBeginAttempt(null);
    }

    @Override
    public HealthAttempt tryBeginAttempt(Integer maxConcurrency) {
        while (true) {
            int current = inFlight.get();
            if (maxConcurrency != null && current >= maxConcurrency) return null;
            if (inFlight.compareAndSet(current, current + 1)) {
                return new HealthAttempt() {
                    private final java.util.concurrent.atomic.AtomicBoolean settled = new java.util.concurrent.atomic.AtomicBoolean();

                    @Override
                    public void cancel() {
                        if (settled.compareAndSet(false, true)) TargetHealth.this.cancel();
                    }

                    @Override
                    public void success(long durationNanos) {
                        if (settled.compareAndSet(false, true)) TargetHealth.this.success(durationNanos);
                    }

                    @Override
                    public boolean failure(long durationNanos, int threshold, Duration cooldown) {
                        return settled.compareAndSet(false, true)
                                && TargetHealth.this.failure(durationNanos, threshold, cooldown);
                    }
                };
            }
        }
    }
}
