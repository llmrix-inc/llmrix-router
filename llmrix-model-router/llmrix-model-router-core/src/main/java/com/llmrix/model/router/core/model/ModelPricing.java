package com.llmrix.model.router.core.model;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.experimental.Accessors;

@Getter
@EqualsAndHashCode
@Accessors(fluent = true)
public final class ModelPricing {
    public static final ModelPricing UNKNOWN = new ModelPricing(null, null);

    private final Double inputCostPerMillion;
    private final Double outputCostPerMillion;

    public ModelPricing(Double inputCostPerMillion, Double outputCostPerMillion) {
        validate(inputCostPerMillion, "inputCostPerMillion");
        validate(outputCostPerMillion, "outputCostPerMillion");
        this.inputCostPerMillion = inputCostPerMillion;
        this.outputCostPerMillion = outputCostPerMillion;
    }

    public double estimateCost(long inputTokens, long outputTokens) {
        if (inputCostPerMillion == null || outputCostPerMillion == null) return Double.NaN;
        return inputTokens * inputCostPerMillion / 1_000_000d
                + outputTokens * outputCostPerMillion / 1_000_000d;
    }

    private static void validate(Double value, String name) {
        if (value != null && (!Double.isFinite(value) || value < 0)) {
            throw new IllegalArgumentException(name + " must be a finite value >= 0");
        }
    }
}
