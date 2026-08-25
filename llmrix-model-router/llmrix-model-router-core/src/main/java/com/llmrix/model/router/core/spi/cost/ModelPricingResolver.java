package com.llmrix.model.router.core.spi.cost;

import com.llmrix.model.router.core.model.ModelPricing;

import java.util.Optional;

/**
 * Resolves model pricing when it is not explicitly configured.
 */
@FunctionalInterface
public interface ModelPricingResolver {
    Optional<ModelPricing> resolve(PricingContext context);
}
