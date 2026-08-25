package com.llmrix.model.router.core.state;

import java.time.Duration;

/**
 * A single candidate invocation lease, allowing distributed stores to identify and expire in-flight work.
 */
public interface HealthAttempt {
    void cancel();

    void success(long durationNanos);

    boolean failure(long durationNanos, int threshold, Duration cooldown);
}
