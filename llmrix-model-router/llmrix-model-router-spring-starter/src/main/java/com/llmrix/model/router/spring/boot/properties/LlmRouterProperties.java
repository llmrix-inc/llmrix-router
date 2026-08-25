package com.llmrix.model.router.spring.boot.properties;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@ConfigurationProperties("llmrix.model.router")
@Getter
@Setter
public class LlmRouterProperties {
    public enum OpenAiApiMode {CHAT_COMPLETIONS, RESPONSES}

    public enum StateMode {LOCAL, REDIS}

    private boolean enabled = true;
    private boolean failFast = true;
    private String defaultRoute = "general";
    private Map<String, Route> routes = new LinkedHashMap<>();
    private Map<String, Integration> integrations = new LinkedHashMap<>();
    private Execution execution = new Execution();
    private Observability observability = new Observability();
    private State state = new State();

    @Getter
    @Setter
    public static class State {
        private StateMode mode = StateMode.LOCAL;
        private Redis redis = new Redis();

        public void setMode(StateMode mode) {
            this.mode = java.util.Objects.requireNonNull(mode, "mode");
        }
    }

    @Getter
    @Setter
    public static class Redis {
        private String uri;
        private String keyPrefix = "llmrix:model:router";
        private Duration leaseTtl = Duration.ofMinutes(2);
        private Duration quotaWindow = Duration.ofMinutes(1);
    }

    @Getter
    @Setter
    public static class Route {
        private String strategy = "balanced";
        private List<ModelReference> models = List.of();
    }

    @Getter
    @Setter
    public static class ModelReference {
        private String integration;
        private String model;
    }

    @Getter
    @Setter
    public static class Integration {
        private String provider;
        private String baseUrl;
        private String apiKey;
        private String authenticator = "bearer";
        private OpenAiApiMode apiMode = OpenAiApiMode.CHAT_COMPLETIONS;
        private String siteUrl;
        private String appName;
        private Map<String, Object> options = Map.of();
        private List<Model> models = List.of();

        public void setApiMode(OpenAiApiMode apiMode) {
            this.apiMode = java.util.Objects.requireNonNull(apiMode, "apiMode");
        }
    }

    @Getter
    @Setter
    public static class Model {
        private String name;
        private List<String> capabilities = List.of("chat");
        private Integer maxInputTokens;
        private int priority = 100;
        private int weight = 100;
        private Double inputCostPerMillion;
        private Double outputCostPerMillion;
        private Map<String, String> metadata = Map.of();
        private Map<String, Object> extensions = Map.of();
        private Limits limits = new Limits();
    }

    @Getter
    @Setter
    public static class Limits {
        private Long requestsPerMinute;
        private Long tokensPerMinute;
        private Integer maxConcurrency;
    }

    @Getter
    @Setter
    public static class Execution {
        private Duration timeout = Duration.ofSeconds(30);
        private int maxRetries = 1;
        private Duration retryDelay = Duration.ofMillis(200);
        private int failureThreshold = 3;
        private Duration cooldown = Duration.ofSeconds(60);
        private Duration firstTokenTimeout;
        private Duration streamIdleTimeout;
    }

    @Getter
    @Setter
    public static class Observability {
        private boolean enabled = true;
        private boolean metricsEnabled = true;
        private boolean tracingEnabled = true;
        private boolean contextPropagationEnabled = true;
        private boolean includeCandidateId = true;
        private boolean includeRoutingReason;
        private boolean includeCost = true;
        private boolean includePrompts;
        private int promptMaxChars = 1_024;
        private Set<String> promptRoutes = Set.of();
    }
}
