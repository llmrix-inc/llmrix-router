package com.llmrix.model.router.spring.boot.properties;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import com.llmrix.model.router.core.model.InputModality;
import com.llmrix.model.router.core.model.ModelFeature;
import com.llmrix.model.router.core.model.ModelOperation;
import com.llmrix.model.router.core.model.ModelTrait;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Spring Boot configuration for the LLMRix model router. */
@ConfigurationProperties("llmrix.model.router")
@Getter
@Setter
public class LlmRouterProperties {
    /** OpenAI-compatible endpoint dialect used by an integration. */
    public enum OpenAiApiMode {CHAT_COMPLETIONS, RESPONSES}

    /** Storage backend for health, quota, and concurrency state. */
    public enum StateMode {LOCAL, REDIS}

    /** Enables or disables router auto-configuration. */
    private boolean enabled = true;
    /** Fails application startup when the router configuration is invalid. */
    private boolean failFast = true;
    /** Route used by the primary ChatModel and operation facade. */
    private String defaultRoute = "general";
    /** Named routing pools. Each route must contain at least one model reference. */
    private Map<String, Route> routes = new LinkedHashMap<>();
    /** Provider integrations and their model declarations, keyed by integration id. */
    private Map<String, Integration> integrations = new LinkedHashMap<>();
    /** Timeout, retry, and target health policy. */
    private Execution execution = new Execution();
    /** Metrics, tracing, and prompt-observation settings. */
    private Observability observability = new Observability();
    /** Shared state backend configuration. */
    private State state = new State();

    @Getter
    @Setter
    public static class State {
        /** Use local in-memory state or Redis-backed shared state. */
        private StateMode mode = StateMode.LOCAL;
        /** JVM-local quota state settings; used when mode is LOCAL. */
        private Local local = new Local();
        /** Redis connection and namespace settings; used when mode is REDIS. */
        private Redis redis = new Redis();

        public void setMode(StateMode mode) {
            this.mode = java.util.Objects.requireNonNull(mode, "mode");
        }
    }

    @Getter
    @Setter
    public static class Local {
        /** Maximum number of per-principal quota partitions kept in memory. */
        private int maxQuotaPartitions = 10_000;
        /** Idle time after which an unused local quota partition is evicted. */
        private Duration quotaIdleTimeout = Duration.ofMinutes(2);
    }

    @Getter
    @Setter
    public static class Redis {
        /** Redis URI, for example redis://localhost:6379/0. Required in REDIS mode. */
        private String uri;
        /** Prefix applied to all router keys to isolate this deployment. */
        private String keyPrefix = "llmrix:model:router";
        /** Lease duration used for distributed health and concurrency state. */
        private Duration leaseTtl = Duration.ofMinutes(2);
        /** Fixed window used for Redis-backed RPM and TPM quotas. */
        private Duration quotaWindow = Duration.ofMinutes(1);
    }

    @Getter
    @Setter
    public static class Route {
        /** Strategy name, such as balanced, priority, latency, or cache-aware. */
        private String strategy = "balanced";
        /** Complete ordered pool of integration/model references for this route. */
        private List<ModelReference> models = List.of();
        /** Optional quota shared by all targets in this route. */
        private Quota quota = new Quota();
    }

    @Getter
    @Setter
    public static class Quota {
        /** Maximum requests accepted by this route per minute. Null means unlimited. */
        private Long requestsPerMinute;
        /** Maximum estimated input tokens accepted by this route per minute. Null means unlimited. */
        private Long tokensPerMinute;
    }

    @Getter
    @Setter
    public static class ModelReference {
        /** Integration id under llmrix.model.router.integrations. */
        private String integration;
        /** Provider model name as understood by the integration. */
        private String model;
    }

    @Getter
    @Setter
    public static class Integration {
        /** Built-in or custom ModelProvider id. */
        private String provider;
        /** Base URL shared by all models in this integration. */
        private String baseUrl;
        /** Provider credential. Prefer an environment variable or secret reference. */
        private String apiKey;
        /** Authentication scheme registered for this provider. */
        private String authenticator = "bearer";
        /** OpenAI-compatible API dialect; ignored by providers with native protocols. */
        private OpenAiApiMode apiMode = OpenAiApiMode.CHAT_COMPLETIONS;
        /** OpenRouter attribution URL sent with requests. */
        private String siteUrl;
        /** OpenRouter attribution application name sent with requests. */
        private String appName;
        /**
         * Compatibility switch for forwarding the private routing-hints header to a provider.
         * Disabled by default because routing policy is an internal Router concern.
         */
        private boolean forwardRoutingHints;
        /** Provider-level options passed to the adapter. */
        private Map<String, Object> options = Map.of();
        /** Models exposed by this integration. */
        private List<Model> models = List.of();

        public void setApiMode(OpenAiApiMode apiMode) {
            this.apiMode = java.util.Objects.requireNonNull(apiMode, "apiMode");
        }
    }

    @Getter
    @Setter
    public static class Model {
        /** Model id used in provider requests and route references. */
        private String name;
        /** Maximum input context accepted by this target. Null leaves the limit unspecified. */
        private Integer maxInputTokens;
        /** Lower values are preferred when fallback order is constructed. */
        private int priority = 100;
        /** Relative selection weight used by weighted strategies. */
        private int weight = 100;
        /** Input price in USD per one million tokens. Null means unknown. */
        private Double inputCostPerMillion;
        /** Output price in USD per one million tokens. Null means unknown. */
        private Double outputCostPerMillion;
        /** Cached input price in USD per one million tokens. */
        private Double cachedInputCostPerMillion;
        /** Prompt-cache write price in USD per one million tokens. */
        private Double cacheWriteCostPerMillion;
        /** Reasoning-token price in USD per one million tokens. */
        private Double reasoningCostPerMillion;
        /** Free-form model metadata used by routing and observability. */
        private Map<String, String> metadata = Map.of();
        /** Provider-specific model options; kept out of the common request contract. */
        private Map<String, Object> extensions = Map.of();
        /** Protocol features: streaming, tools, structured-output, or prompt-cache. */
        private Set<ModelFeature> features = Set.of();
        /** Callable operations: chat, embeddings, rerank, audio, image, or video operations. */
        private Set<ModelOperation> operations = Set.of();
        /** Non-text chat input types: vision, audio, video, or file. */
        private Set<InputModality> inputModalities = Set.of();
        /** Model traits such as code, reasoning, or long-context. */
        private Set<ModelTrait> traits = Set.of();
        /** Optional provider-model RPM, TPM, and concurrency limits. */
        private Limits limits = new Limits();
    }

    @Getter
    @Setter
    public static class Limits {
        /** Maximum requests sent to this model per minute. */
        private Long requestsPerMinute;
        /** Maximum estimated input tokens sent to this model per minute. */
        private Long tokensPerMinute;
        /** Maximum simultaneous in-flight requests for this model. */
        private Integer maxConcurrency;
    }

    @Getter
    @Setter
    public static class Execution {
        /** Overall deadline for one target attempt and its fallback chain. */
        private Duration timeout = Duration.ofSeconds(30);
        /** Number of retries made before trying the next eligible target. */
        private int maxRetries = 1;
        /** Delay between retry attempts. */
        private Duration retryDelay = Duration.ofMillis(200);
        /** Consecutive failures required before a target enters cooldown. */
        private int failureThreshold = 3;
        /** Time a failed target remains unavailable. */
        private Duration cooldown = Duration.ofSeconds(60);
        /** Optional deadline for receiving the first streaming chunk. */
        private Duration firstTokenTimeout;
        /** Optional maximum idle interval between streaming chunks. */
        private Duration streamIdleTimeout;
    }

    @Getter
    @Setter
    public static class Observability {
        /** Enables router observation listeners. */
        private boolean enabled = true;
        /** Publishes router metrics through Micrometer when available. */
        private boolean metricsEnabled = true;
        /** Publishes tracing observations when an ObservationRegistry is available. */
        private boolean tracingEnabled = true;
        /** Propagates the request context to router executor tasks. */
        private boolean contextPropagationEnabled = true;
        /** Adds the selected candidate id to observations. */
        private boolean includeCandidateId = true;
        /** Adds the routing decision reason to observations. */
        private boolean includeRoutingReason;
        /** Adds estimated and settled cost data to observations. */
        private boolean includeCost = true;
        /** Allows sanitized prompt content to be recorded. Keep disabled for sensitive workloads. */
        private boolean includePrompts;
        /** Maximum prompt characters retained in an observation. */
        private int promptMaxChars = 1_024;
        /** Routes for which prompt capture is enabled when includePrompts is true. */
        private Set<String> promptRoutes = Set.of();
    }
}
