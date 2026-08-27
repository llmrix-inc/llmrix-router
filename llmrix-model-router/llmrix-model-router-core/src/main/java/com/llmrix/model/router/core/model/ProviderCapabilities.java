package com.llmrix.model.router.core.model;

import com.llmrix.model.router.core.api.ModelClient;

import java.util.Objects;
import java.util.Set;

/** Runtime capability snapshot derived from the concrete provider adapter. */
public final class ProviderCapabilities {
    private final Set<ModelOperation> operations;
    private final Set<ModelFeature> features;

    public ProviderCapabilities(Set<ModelOperation> operations, Set<ModelFeature> features) {
        this.operations = Set.copyOf(Objects.requireNonNull(operations, "operations"));
        this.features = Set.copyOf(Objects.requireNonNull(features, "features"));
    }

    public static ProviderCapabilities from(ModelClient client) {
        java.util.EnumSet<ModelOperation> operations = java.util.EnumSet.noneOf(ModelOperation.class);
        java.util.EnumSet<ModelFeature> features = java.util.EnumSet.noneOf(ModelFeature.class);
        for (ModelOperation operation : ModelOperation.values()) if (client.supports(operation)) operations.add(operation);
        for (ModelFeature feature : ModelFeature.values()) if (client.supports(feature)) features.add(feature);
        return new ProviderCapabilities(operations, features);
    }

    public Set<ModelOperation> operations() { return operations; }
    public Set<ModelFeature> features() { return features; }
    public boolean supports(ModelOperation operation) { return operations.contains(operation); }
    public boolean supports(ModelFeature feature) { return features.contains(feature); }
}
