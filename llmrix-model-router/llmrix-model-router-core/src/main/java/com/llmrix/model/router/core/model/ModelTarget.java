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
    private final Set<ModelOperation> operations;
    private final Set<ModelFeature> features;
    private final Set<InputModality> inputModalities;
    private final Set<ModelTrait> traits;
    private final ProviderCapabilities providerCapabilities;
    private final Integer maxInputTokens;
    private final ModelPricing pricing;
    private final ModelLimits limits;
    private final int priority;
    private final int weight;
    private final Map<String, String> metadata;

    private ModelTarget(Builder builder) {
        this.id = requireText(builder.id, "id");
        this.client = Objects.requireNonNull(builder.client, "client");
        this.providerCapabilities = ProviderCapabilities.from(client);
        this.operations = Set.copyOf(builder.operations);
        if (operations.isEmpty()) throw new IllegalArgumentException("at least one model operation is required");
        this.features = builder.features.isEmpty() ? providerCapabilities.features() : Set.copyOf(builder.features);
        this.inputModalities = Set.copyOf(builder.inputModalities);
        this.traits = Set.copyOf(builder.traits);
        for (ModelOperation operation : operations)
            if (!providerCapabilities.supports(operation)) throw new IllegalArgumentException("model client does not implement operation: " + operation);
        for (ModelFeature feature : features)
            if (!providerCapabilities.supports(feature)) throw new IllegalArgumentException("model client does not implement feature: " + feature);
        this.maxInputTokens = builder.maxInputTokens;
        this.pricing = builder.pricing;
        this.limits = builder.limits;
        this.priority = builder.priority;
        this.weight = builder.weight;
        this.metadata = Map.copyOf(builder.metadata);
    }

    public static Builder builder(String id, ChatModel model) {
        return new Builder(id, ModelClient.chat(model)).operations(ModelOperation.CHAT);
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

    public Set<ModelOperation> operations() { return operations; }
    public Set<ModelFeature> features() { return features; }
    public Set<InputModality> inputModalities() { return inputModalities; }
    public Set<ModelTrait> traits() { return traits; }

    public boolean supports(ModelOperation operation) { return operations.contains(operation) && providerCapabilities.supports(operation); }
    public boolean supports(ModelFeature feature) { return features.contains(feature) && providerCapabilities.supports(feature); }
    public boolean supports(InputModality modality) { return inputModalities.contains(modality); }
    public boolean hasTrait(ModelTrait trait) { return traits.contains(trait); }
    public boolean satisfies(ModelRequirement requirement) {
        return switch (requirement) {
            case CHAT -> supports(ModelOperation.CHAT);
            case CHAT_STREAMING -> supports(ModelFeature.STREAMING);
            case TOOLS -> supports(ModelFeature.TOOLS);
            case STRUCTURED_OUTPUT -> supports(ModelFeature.STRUCTURED_OUTPUT);
            case PROMPT_CACHE -> supports(ModelFeature.PROMPT_CACHE);
            case VISION -> supports(InputModality.VISION);
            case VIDEO_INPUT -> supports(InputModality.VIDEO);
            case FILE_INPUT -> supports(InputModality.FILE);
            case AUDIO_INPUT -> supports(InputModality.AUDIO);
            case CODE -> hasTrait(ModelTrait.CODE);
            case REASONING -> hasTrait(ModelTrait.REASONING);
            case LONG_CONTEXT -> hasTrait(ModelTrait.LONG_CONTEXT);
            case EMBEDDINGS -> supports(ModelOperation.EMBEDDINGS);
            case RERANK -> supports(ModelOperation.RERANK);
            case AUDIO_TRANSCRIPTION -> supports(ModelOperation.AUDIO_TRANSCRIPTION);
            case AUDIO_TRANSLATION -> supports(ModelOperation.AUDIO_TRANSLATION);
            case TEXT_TO_SPEECH -> supports(ModelOperation.TEXT_TO_SPEECH);
            case IMAGE_GENERATION -> supports(ModelOperation.IMAGE_GENERATION);
            case IMAGE_EDIT -> supports(ModelOperation.IMAGE_EDIT);
            case VIDEO_GENERATION -> supports(ModelOperation.VIDEO_GENERATION);
        };
    }

    public ProviderCapabilities providerCapabilities() { return providerCapabilities; }

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
        private final EnumSet<ModelOperation> operations = EnumSet.noneOf(ModelOperation.class);
        private final EnumSet<ModelFeature> features = EnumSet.noneOf(ModelFeature.class);
        private final EnumSet<InputModality> inputModalities = EnumSet.noneOf(InputModality.class);
        private final EnumSet<ModelTrait> traits = EnumSet.noneOf(ModelTrait.class);
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

        public Builder operations(ModelOperation... values) {
            operations.clear(); for (ModelOperation value : values) operations.add(Objects.requireNonNull(value)); return this;
        }
        public Builder features(ModelFeature... values) {
            features.clear(); for (ModelFeature value : values) features.add(Objects.requireNonNull(value)); return this;
        }
        public Builder inputModalities(InputModality... values) {
            inputModalities.clear(); for (InputModality value : values) inputModalities.add(Objects.requireNonNull(value)); return this;
        }
        public Builder traits(ModelTrait... values) {
            traits.clear(); for (ModelTrait value : values) traits.add(Objects.requireNonNull(value)); return this;
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
