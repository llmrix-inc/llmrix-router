package com.llmrix.model.router.integrations.bucket4j;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.experimental.Accessors;

import java.time.Duration;

@Getter
@EqualsAndHashCode
@Accessors(fluent = true)
public final class Bucket4jQuotaOptions {
    public static final Bucket4jQuotaOptions DEFAULT = new Bucket4jQuotaOptions(
            Duration.ofMinutes(1), 1.0, true);

    private final Duration refillPeriod;
    private final double burstCapacityMultiplier;
    private final boolean greedyRefill;

    public Bucket4jQuotaOptions(Duration refillPeriod, double burstCapacityMultiplier, boolean greedyRefill) {
        if (refillPeriod == null || refillPeriod.isZero() || refillPeriod.isNegative()) {
            throw new IllegalArgumentException("refillPeriod must be positive");
        }
        if (!Double.isFinite(burstCapacityMultiplier) || burstCapacityMultiplier < 1.0) {
            throw new IllegalArgumentException("burstCapacityMultiplier must be finite and >= 1");
        }
        this.refillPeriod = refillPeriod;
        this.burstCapacityMultiplier = burstCapacityMultiplier;
        this.greedyRefill = greedyRefill;
    }

}
