package com.llmrix.model.router.spring.boot.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@ConfigurationProperties("llmrix.model.router")
public class LlmRouterProperties {
    public enum OpenAiApiMode { CHAT_COMPLETIONS, RESPONSES }
    public enum StateMode { LOCAL, REDIS }
    private boolean enabled = true;
    private boolean failFast = true;
    private String defaultRoute = "general";
    private Map<String, Route> routes = new LinkedHashMap<>();
    private Map<String, Candidate> candidates = new LinkedHashMap<>();
    private Execution execution = new Execution();
    private Observability observability = new Observability();
    private State state = new State();

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public boolean isFailFast() { return failFast; }
    public void setFailFast(boolean failFast) { this.failFast = failFast; }
    public String getDefaultRoute() { return defaultRoute; }
    public void setDefaultRoute(String defaultRoute) { this.defaultRoute = defaultRoute; }
    public Map<String, Route> getRoutes() { return routes; }
    public void setRoutes(Map<String, Route> routes) { this.routes = routes; }
    public Map<String, Candidate> getCandidates() { return candidates; }
    public void setCandidates(Map<String, Candidate> candidates) { this.candidates = candidates; }
    public Execution getExecution() { return execution; }
    public void setExecution(Execution execution) { this.execution = execution; }
    public Observability getObservability() { return observability; }
    public void setObservability(Observability observability) { this.observability = observability; }
    public State getState() { return state; }
    public void setState(State state) { this.state = state; }

    public static class State {
        private StateMode mode = StateMode.LOCAL;
        private Redis redis = new Redis();
        public StateMode getMode() { return mode; }
        public void setMode(StateMode mode) { this.mode = java.util.Objects.requireNonNull(mode, "mode"); }
        public Redis getRedis() { return redis; }
        public void setRedis(Redis redis) { this.redis = redis; }
    }

    public static class Redis {
        private String uri;
        private String keyPrefix = "llmrix:model:router";
        private Duration leaseTtl = Duration.ofMinutes(2);
        private Duration quotaWindow = Duration.ofMinutes(1);
        public String getUri() { return uri; }
        public void setUri(String uri) { this.uri = uri; }
        public String getKeyPrefix() { return keyPrefix; }
        public void setKeyPrefix(String keyPrefix) { this.keyPrefix = keyPrefix; }
        public Duration getLeaseTtl() { return leaseTtl; }
        public void setLeaseTtl(Duration leaseTtl) { this.leaseTtl = leaseTtl; }
        public Duration getQuotaWindow() { return quotaWindow; }
        public void setQuotaWindow(Duration quotaWindow) { this.quotaWindow = quotaWindow; }
    }

    public static class Route {
        private String strategy = "balanced";
        private List<String> candidates = List.of();
        private List<String> fallbacks = List.of();
        public String getStrategy() { return strategy; }
        public void setStrategy(String strategy) { this.strategy = strategy; }
        public List<String> getCandidates() { return candidates; }
        public void setCandidates(List<String> candidates) { this.candidates = candidates; }
        public List<String> getFallbacks() { return fallbacks; }
        public void setFallbacks(List<String> fallbacks) { this.fallbacks = fallbacks; }
    }

    public static class Candidate {
        private String provider;
        private String modelName;
        private String baseUrl;
        private String apiKey;
        private OpenAiApiMode apiMode = OpenAiApiMode.CHAT_COMPLETIONS;
        private String beanName;
        private List<String> capabilities = List.of("chat");
        private Integer maxInputTokens;
        private int priority = 100;
        private int weight = 100;
        private Double inputCostPerMillion;
        private Double outputCostPerMillion;
        private Map<String, String> metadata = Map.of();
        private Map<String, Object> extensions = Map.of();
        private Limits limits = new Limits();
        public String getProvider() { return provider; }
        public void setProvider(String provider) { this.provider = provider; }
        public String getModelName() { return modelName; }
        public void setModelName(String modelName) { this.modelName = modelName; }
        public String getBaseUrl() { return baseUrl; }
        public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
        public String getApiKey() { return apiKey; }
        public void setApiKey(String apiKey) { this.apiKey = apiKey; }
        public OpenAiApiMode getApiMode() { return apiMode; }
        public void setApiMode(OpenAiApiMode apiMode) {
            this.apiMode = java.util.Objects.requireNonNull(apiMode, "apiMode");
        }
        public String getBeanName() { return beanName; }
        public void setBeanName(String beanName) { this.beanName = beanName; }
        public List<String> getCapabilities() { return capabilities; }
        public void setCapabilities(List<String> capabilities) { this.capabilities = capabilities; }
        public Integer getMaxInputTokens() { return maxInputTokens; }
        public void setMaxInputTokens(Integer maxInputTokens) { this.maxInputTokens = maxInputTokens; }
        public int getPriority() { return priority; }
        public void setPriority(int priority) { this.priority = priority; }
        public int getWeight() { return weight; }
        public void setWeight(int weight) { this.weight = weight; }
        public Double getInputCostPerMillion() { return inputCostPerMillion; }
        public void setInputCostPerMillion(Double value) { this.inputCostPerMillion = value; }
        public Double getOutputCostPerMillion() { return outputCostPerMillion; }
        public void setOutputCostPerMillion(Double value) { this.outputCostPerMillion = value; }
        public Map<String, String> getMetadata() { return metadata; }
        public void setMetadata(Map<String, String> metadata) { this.metadata = metadata; }
        public Map<String, Object> getExtensions() { return extensions; }
        public void setExtensions(Map<String, Object> extensions) { this.extensions = extensions; }
        public Limits getLimits() { return limits; }
        public void setLimits(Limits limits) { this.limits = limits; }
    }

    public static class Limits {
        private Long requestsPerMinute;
        private Long tokensPerMinute;
        private Integer maxConcurrency;
        public Long getRequestsPerMinute() { return requestsPerMinute; }
        public void setRequestsPerMinute(Long value) { this.requestsPerMinute = value; }
        public Long getTokensPerMinute() { return tokensPerMinute; }
        public void setTokensPerMinute(Long value) { this.tokensPerMinute = value; }
        public Integer getMaxConcurrency() { return maxConcurrency; }
        public void setMaxConcurrency(Integer value) { this.maxConcurrency = value; }
    }

    public static class Execution {
        private Duration timeout = Duration.ofSeconds(30);
        private int maxRetries = 1;
        private Duration retryDelay = Duration.ofMillis(200);
        private int failureThreshold = 3;
        private Duration cooldown = Duration.ofSeconds(60);
        private Duration firstTokenTimeout;
        private Duration streamIdleTimeout;
        public Duration getTimeout() { return timeout; }
        public void setTimeout(Duration timeout) { this.timeout = timeout; }
        public int getMaxRetries() { return maxRetries; }
        public void setMaxRetries(int maxRetries) { this.maxRetries = maxRetries; }
        public Duration getRetryDelay() { return retryDelay; }
        public void setRetryDelay(Duration retryDelay) { this.retryDelay = retryDelay; }
        public int getFailureThreshold() { return failureThreshold; }
        public void setFailureThreshold(int failureThreshold) { this.failureThreshold = failureThreshold; }
        public Duration getCooldown() { return cooldown; }
        public void setCooldown(Duration cooldown) { this.cooldown = cooldown; }
        public Duration getFirstTokenTimeout() { return firstTokenTimeout; }
        public void setFirstTokenTimeout(Duration value) { this.firstTokenTimeout = value; }
        public Duration getStreamIdleTimeout() { return streamIdleTimeout; }
        public void setStreamIdleTimeout(Duration value) { this.streamIdleTimeout = value; }
    }

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
        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public boolean isMetricsEnabled() { return metricsEnabled; }
        public void setMetricsEnabled(boolean value) { this.metricsEnabled = value; }
        public boolean isTracingEnabled() { return tracingEnabled; }
        public void setTracingEnabled(boolean value) { this.tracingEnabled = value; }
        public boolean isContextPropagationEnabled() { return contextPropagationEnabled; }
        public void setContextPropagationEnabled(boolean value) { this.contextPropagationEnabled = value; }
        public boolean isIncludeCandidateId() { return includeCandidateId; }
        public void setIncludeCandidateId(boolean value) { this.includeCandidateId = value; }
        public boolean isIncludeRoutingReason() { return includeRoutingReason; }
        public void setIncludeRoutingReason(boolean value) { this.includeRoutingReason = value; }
        public boolean isIncludeCost() { return includeCost; }
        public void setIncludeCost(boolean value) { this.includeCost = value; }
        public boolean isIncludePrompts() { return includePrompts; }
        public void setIncludePrompts(boolean value) { this.includePrompts = value; }
        public int getPromptMaxChars() { return promptMaxChars; }
        public void setPromptMaxChars(int value) { this.promptMaxChars = value; }
        public Set<String> getPromptRoutes() { return promptRoutes; }
        public void setPromptRoutes(Set<String> value) { this.promptRoutes = value; }
    }
}
