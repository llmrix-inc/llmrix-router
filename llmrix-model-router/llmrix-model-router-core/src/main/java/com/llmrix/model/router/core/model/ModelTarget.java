package com.llmrix.model.router.core.model;

import com.llmrix.model.router.core.api.chat.ChatModel;
import com.llmrix.model.router.core.api.ModelClient;

import java.util.EnumSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public final class ModelTarget {
    private final String id;
    private final ModelClient client;
    private final Set<Capability> capabilities;
    private final Integer maxInputTokens;
    private final ModelPricing pricing;
    private final ModelLimits limits;
    private final int priority;
    private final int weight;
    private final Map<String, String> metadata;

    private ModelTarget(Builder builder) {
        this.id = requireText(builder.id, "id");
        this.client = Objects.requireNonNull(builder.client, "client");
        this.capabilities = Set.copyOf(builder.capabilities);
        for (Capability capability : capabilities) {
            if (!client.supports(capability)) {
                throw new IllegalArgumentException("model client does not implement capability: " + capability);
            }
        }
        this.maxInputTokens = builder.maxInputTokens;
        this.pricing = builder.pricing;
        this.limits = builder.limits;
        this.priority = builder.priority;
        this.weight = builder.weight;
        this.metadata = Map.copyOf(builder.metadata);
    }

    public static Builder builder(String id, ChatModel model) {
        return new Builder(id, ModelClient.chat(model));
    }

    public static Builder builder(String id, ModelClient client) {
        return new Builder(id, client);
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }

    public String id() {
        return id;
    }

    public ModelClient client() {
        return client;
    }

    public ChatModel model() {
        return client.requireChat();
    }

    public Set<Capability> capabilities() {
        return capabilities;
    }

    public Integer maxInputTokens() {
        return maxInputTokens;
    }

    public ModelPricing pricing() {
        return pricing;
    }

    public ModelLimits limits() {
        return limits;
    }

    public int priority() {
        return priority;
    }

    public int weight() {
        return weight;
    }

    public Map<String, String> metadata() {
        return metadata;
    }

    public static final class Builder {
        private final String id;
        private final ModelClient client;
        private final EnumSet<Capability> capabilities = EnumSet.of(Capability.CHAT);
        private Integer maxInputTokens;
        private ModelPricing pricing = ModelPricing.UNKNOWN;
        private ModelLimits limits = ModelLimits.UNLIMITED;
        private int priority = 100;
        private int weight = 100;
        private Map<String, String> metadata = Map.of();

        private Builder(String id, ModelClient client) {
            this.id = id;
            this.client = client;
        }

        public Builder capabilities(Capability... capabilities) {
            this.capabilities.clear();
            for (Capability capability : capabilities) {
                this.capabilities.add(Objects.requireNonNull(capability));
            }
            return this;
        }

        public Builder maxInputTokens(Integer value) {
            if (value != null && value < 1) throw new IllegalArgumentException("maxInputTokens must be > 0");
            this.maxInputTokens = value;
            return this;
        }

        public Builder pricing(ModelPricing pricing) {
            this.pricing = Objects.requireNonNull(pricing);
            return this;
        }

        public Builder inputCostPerMillion(double input) {
            this.pricing = new ModelPricing(input, pricing.outputCostPerMillion());
            return this;
        }

        public Builder outputCostPerMillion(double output) {
            this.pricing = new ModelPricing(pricing.inputCostPerMillion(), output);
            return this;
        }

        public Builder limits(ModelLimits limits) {
            this.limits = Objects.requireNonNull(limits);
            return this;
        }

        public Builder priority(int value) {
            this.priority = value;
            return this;
        }

        public Builder weight(int value) {
            if (value < 0) throw new IllegalArgumentException("weight must be >= 0");
            this.weight = value;
            return this;
        }

        public Builder metadata(Map<String, String> value) {
            this.metadata = Objects.requireNonNull(value);
            return this;
        }

        public ModelTarget build() {
            return new ModelTarget(this);
        }
    }
}
