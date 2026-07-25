package com.llmrix.model.router.core.candidate;

public record ModelPricing(Double inputCostPerMillion, Double outputCostPerMillion) {
    public static final ModelPricing UNKNOWN = new ModelPricing(null, null);

    public ModelPricing {
        validate(inputCostPerMillion, "inputCostPerMillion");
        validate(outputCostPerMillion, "outputCostPerMillion");
    }

    public double estimateCost(long inputTokens, long outputTokens) {
        if (inputCostPerMillion == null || outputCostPerMillion == null) {
            return Double.NaN;
        }
        return inputTokens * inputCostPerMillion / 1_000_000d
                + outputTokens * outputCostPerMillion / 1_000_000d;
    }

    private static void validate(Double value, String name) {
        if (value != null && (!Double.isFinite(value) || value < 0)) {
            throw new IllegalArgumentException(name + " must be a finite value >= 0");
        }
    }
}
