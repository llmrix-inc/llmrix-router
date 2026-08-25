package com.llmrix.model.router.core.routing;

import com.llmrix.model.router.core.model.Capability;

import java.time.Duration;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public final class RoutingHints {
    public static final String REQUEST_ID = "request_id";
    public static final String AUTH_PRINCIPAL = "auth_principal";
    public static final String AUTH_QUOTA_KEY = "auth_quota_key";
    private static final RoutingHints NONE = builder().build();

    private final Set<Capability> requiredCapabilities;
    private final Set<String> allowedModels;
    private final Set<String> deniedModels;
    private final Double maxCostUsd;
    private final Duration maxLatency;
    private final Map<String, String> attributes;

    private RoutingHints(Builder builder) {
        this.requiredCapabilities = Set.copyOf(builder.requiredCapabilities);
        this.allowedModels = Set.copyOf(builder.allowedModels);
        this.deniedModels = Set.copyOf(builder.deniedModels);
        this.maxCostUsd = builder.maxCostUsd;
        this.maxLatency = builder.maxLatency;
        this.attributes = Map.copyOf(builder.attributes);
    }

    public static RoutingHints none() {
        return NONE;
    }

    public static Builder builder() {
        return new Builder();
    }

    public Set<Capability> requiredCapabilities() {
        return requiredCapabilities;
    }

    public Set<String> allowedModels() {
        return allowedModels;
    }

    public Set<String> deniedModels() {
        return deniedModels;
    }

    public Double maxCostUsd() {
        return maxCostUsd;
    }

    public Duration maxLatency() {
        return maxLatency;
    }

    public Map<String, String> attributes() {
        return attributes;
    }

    public static final class Builder {
        private final EnumSet<Capability> requiredCapabilities = EnumSet.noneOf(Capability.class);
        private final Set<String> allowedModels = new HashSet<>();
        private final Set<String> deniedModels = new HashSet<>();
        private Double maxCostUsd;
        private Duration maxLatency;
        private final Map<String, String> attributes = new HashMap<>();

        public Builder require(Capability... capabilities) {
            for (Capability c : capabilities) requiredCapabilities.add(c);
            return this;
        }

        public Builder allow(String... ids) {
            allowedModels.addAll(Set.of(ids));
            return this;
        }

        public Builder deny(String... ids) {
            deniedModels.addAll(Set.of(ids));
            return this;
        }

        public Builder maxCostUsd(double value) {
            if (value < 0) throw new IllegalArgumentException("maxCostUsd must be >= 0");
            maxCostUsd = value;
            return this;
        }

        public Builder maxLatency(Duration value) {
            maxLatency = value;
            return this;
        }

        public Builder attribute(String key, String value) {
            attributes.put(key, value);
            return this;
        }

        public RoutingHints build() {
            return new RoutingHints(this);
        }
    }
}
