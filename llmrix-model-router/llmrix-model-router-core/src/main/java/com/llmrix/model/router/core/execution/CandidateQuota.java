package com.llmrix.model.router.core.execution;

import com.llmrix.model.router.core.candidate.ModelLimits;

import java.time.Clock;

public final class CandidateQuota implements QuotaState {
    private static final long WINDOW_MILLIS = 60_000;

    private final ModelLimits limits;
    private final Clock clock;
    private long windowStartedMillis;
    private long requests;
    private long tokens;

    public CandidateQuota(ModelLimits limits) {
        this(limits, Clock.systemUTC());
    }

    CandidateQuota(ModelLimits limits, Clock clock) {
        this.limits = limits;
        this.clock = clock;
        this.windowStartedMillis = clock.millis();
    }

    public synchronized String rejectionReason(int estimatedInputTokens) {
        resetIfNeeded();
        if (limits.requestsPerMinute() != null && requests >= limits.requestsPerMinute()) {
            return "requests-per-minute";
        }
        if (limits.tokensPerMinute() != null && tokens + estimatedInputTokens > limits.tokensPerMinute()) {
            return "tokens-per-minute";
        }
        return null;
    }

    public synchronized boolean tryAcquire(int estimatedInputTokens) {
        if (rejectionReason(estimatedInputTokens) != null) return false;
        requests++;
        tokens += estimatedInputTokens;
        return true;
    }

    public synchronized void recordOutputTokens(long outputTokens) {
        resetIfNeeded();
        if (outputTokens > 0) tokens += outputTokens;
    }

    private void resetIfNeeded() {
        long now = clock.millis();
        if (now - windowStartedMillis >= WINDOW_MILLIS || now < windowStartedMillis) {
            windowStartedMillis = now;
            requests = 0;
            tokens = 0;
        }
    }
}
