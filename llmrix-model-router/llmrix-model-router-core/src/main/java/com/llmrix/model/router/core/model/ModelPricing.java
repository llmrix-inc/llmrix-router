package com.llmrix.model.router.core.model;

import com.llmrix.model.router.core.api.Usage;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.experimental.Accessors;

@Getter
@EqualsAndHashCode
@Accessors(fluent = true)
public final class ModelPricing {
    public static final ModelPricing UNKNOWN = new ModelPricing(null, null, null, null, null);

    private final Double inputCostPerMillion;
    private final Double outputCostPerMillion;
    private final Double cachedInputCostPerMillion;
    private final Double cacheWriteCostPerMillion;
    private final Double reasoningCostPerMillion;

    public ModelPricing(Double inputCostPerMillion, Double outputCostPerMillion) {
        this(inputCostPerMillion, outputCostPerMillion, null, null, null);
    }

    public ModelPricing(Double inputCostPerMillion, Double outputCostPerMillion,
                        Double cachedInputCostPerMillion, Double cacheWriteCostPerMillion,
                        Double reasoningCostPerMillion) {
        validate(inputCostPerMillion, "inputCostPerMillion");
        validate(outputCostPerMillion, "outputCostPerMillion");
        validate(cachedInputCostPerMillion, "cachedInputCostPerMillion");
        validate(cacheWriteCostPerMillion, "cacheWriteCostPerMillion");
        validate(reasoningCostPerMillion, "reasoningCostPerMillion");
        this.inputCostPerMillion = inputCostPerMillion;
        this.outputCostPerMillion = outputCostPerMillion;
        this.cachedInputCostPerMillion = cachedInputCostPerMillion;
        this.cacheWriteCostPerMillion = cacheWriteCostPerMillion;
        this.reasoningCostPerMillion = reasoningCostPerMillion;
    }

    public double estimateCost(long inputTokens, long outputTokens) {
        if (inputCostPerMillion == null || outputCostPerMillion == null) return Double.NaN;
        return inputTokens * inputCostPerMillion / 1_000_000d
                + outputTokens * outputCostPerMillion / 1_000_000d;
    }

    public double estimateCost(Usage usage) {
        if (usage == null || usage.inputTokens() < 0 || usage.outputTokens() < 0
                || inputCostPerMillion == null || outputCostPerMillion == null) return Double.NaN;
        long cached = Math.max(0, Math.min(usage.cachedInputTokens(), usage.inputTokens()));
        long regularInput = usage.inputTokens() - cached;
        double cachedRate = cachedInputCostPerMillion == null ? inputCostPerMillion : cachedInputCostPerMillion;
        double reasoningRate = reasoningCostPerMillion == null ? outputCostPerMillion : reasoningCostPerMillion;
        double writeRate = cacheWriteCostPerMillion == null ? inputCostPerMillion : cacheWriteCostPerMillion;
        long reasoning = Math.min(Math.max(0, usage.reasoningTokens()), usage.outputTokens());
        long regularOutput = usage.outputTokens() - reasoning;
        return regularInput * inputCostPerMillion / 1_000_000d
                + cached * cachedRate / 1_000_000d
                + regularOutput * outputCostPerMillion / 1_000_000d
                + Math.max(0, usage.cacheWriteTokens()) * writeRate / 1_000_000d
                + reasoning * reasoningRate / 1_000_000d;
    }

    private static void validate(Double value, String name) {
        if (value != null && (!Double.isFinite(value) || value < 0)) {
            throw new IllegalArgumentException(name + " must be a finite value >= 0");
        }
    }
}
