package com.llmrix.model.router.core.state;

import com.llmrix.model.router.core.model.ModelLimits;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CandidateQuotaTest {
    @Test
    void resetsRpmAndTpmAtNextWindow() {
        MutableClock clock = new MutableClock(1_000);
        TargetQuota quota = new TargetQuota(new ModelLimits(1L, 5L, null), clock);

        assertTrue(quota.tryAcquire(5));
        assertFalse(quota.tryAcquire(1));
        clock.advanceMillis(60_000);
        assertTrue(quota.tryAcquire(5));
    }

    @Test
    void resetsWhenClockMovesBackwards() {
        MutableClock clock = new MutableClock(60_000);
        TargetQuota quota = new TargetQuota(new ModelLimits(1L, null, null), clock);

        assertTrue(quota.tryAcquire(1));
        clock.setMillis(1_000);
        assertTrue(quota.tryAcquire(1));
    }

    private static final class MutableClock extends Clock {
        private long millis;
        private MutableClock(long millis) { this.millis = millis; }
        void advanceMillis(long value) { millis += value; }
        void setMillis(long value) { millis = value; }
        @Override public ZoneId getZone() { return ZoneId.of("UTC"); }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return Instant.ofEpochMilli(millis); }
        @Override public long millis() { return millis; }
    }
}
