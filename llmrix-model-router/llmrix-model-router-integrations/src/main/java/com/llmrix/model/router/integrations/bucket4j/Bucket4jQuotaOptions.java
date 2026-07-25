package com.llmrix.model.router.integrations.bucket4j;

import java.time.Duration;

public record Bucket4jQuotaOptions(Duration refillPeriod, double burstCapacityMultiplier, boolean greedyRefill) {
    public static final Bucket4jQuotaOptions DEFAULT = new Bucket4jQuotaOptions(
            Duration.ofMinutes(1), 1.0, true);

    public Bucket4jQuotaOptions {
        if (refillPeriod == null || refillPeriod.isZero() || refillPeriod.isNegative()) {
            throw new IllegalArgumentException("refillPeriod must be positive");
        }
        if (!Double.isFinite(burstCapacityMultiplier) || burstCapacityMultiplier < 1.0) {
            throw new IllegalArgumentException("burstCapacityMultiplier must be finite and >= 1");
        }
    }
}
