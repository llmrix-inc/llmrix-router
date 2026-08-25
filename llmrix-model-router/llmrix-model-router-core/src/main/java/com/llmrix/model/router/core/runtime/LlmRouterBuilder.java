package com.llmrix.model.router.core.runtime;

import com.llmrix.model.router.core.api.ModelClient;
import com.llmrix.model.router.core.engine.ExecutionPolicy;
import com.llmrix.model.router.core.engine.RoutedChatModel;
import com.llmrix.model.router.core.engine.RoutedChatModels;
import com.llmrix.model.router.core.engine.RoutedModelOperations;
import com.llmrix.model.router.core.engine.RoutedModelOperationsRegistry;
import com.llmrix.model.router.core.event.RouterListener;
import com.llmrix.model.router.core.model.Capability;
import com.llmrix.model.router.core.model.ModelLimits;
import com.llmrix.model.router.core.model.ModelPricing;
import com.llmrix.model.router.core.model.ModelTarget;
import com.llmrix.model.router.core.routing.RoutingStrategy;
import com.llmrix.model.router.core.routing.Strategies;
import com.llmrix.model.router.core.state.InMemoryRouterStateStore;
import com.llmrix.model.router.core.state.RouterStateStore;
import com.llmrix.model.router.core.spi.auth.AuthenticationContext;
import com.llmrix.model.router.core.spi.RouterIntegrationDefaults;
import com.llmrix.model.router.core.spi.auth.ProviderAuthenticator;
import com.llmrix.model.router.core.spi.auth.RequestAuthenticator;
import com.llmrix.model.router.core.spi.cost.ModelPricingResolver;
import com.llmrix.model.router.core.spi.cost.PricingContext;
import com.llmrix.model.router.core.spi.provider.ModelProvider;
import com.llmrix.model.router.core.spi.provider.ModelProviderRequest;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.ServiceLoader;
import java.util.concurrent.ExecutorService;
import java.util.function.Consumer;

/** Builds a complete router without requiring Spring or external configuration. */
public final class LlmRouterBuilder {
    private static final System.Logger LOGGER = System.getLogger(LlmRouterBuilder.class.getName());

    private final Map<String, ModelProvider> providers = new LinkedHashMap<>();
    private final Map<String, ProviderAuthenticator> authenticators = new LinkedHashMap<>();
    private final List<ModelPricingResolver> pricingResolvers = new ArrayList<>();
    private final Map<String, RoutingStrategy> strategies = new LinkedHashMap<>();
    private final Map<String, IntegrationSpec> integrations = new LinkedHashMap<>();
    private final Map<String, ModelTarget> explicitTargets = new LinkedHashMap<>();
    private final Map<String, RouteSpec> routes = new LinkedHashMap<>();

    private String defaultRoute = "general";
    private Duration timeout = ExecutionPolicy.DEFAULT.timeout();
    private int maxRetries = ExecutionPolicy.DEFAULT.maxRetries();
    private Duration retryDelay = ExecutionPolicy.DEFAULT.retryDelay();
    private int failureThreshold = ExecutionPolicy.DEFAULT.failureThreshold();
    private Duration cooldown = ExecutionPolicy.DEFAULT.cooldown();
    private Duration firstTokenTimeout = ExecutionPolicy.DEFAULT.firstTokenTimeout();
    private Duration streamIdleTimeout = ExecutionPolicy.DEFAULT.streamIdleTimeout();
    private RouterStateStore stateStore = new InMemoryRouterStateStore();
    private RouterListener listener = RouterListener.NOOP;
    private ExecutorService executor;
    private boolean failFast = true;
    private String defaultAuthenticator;

    public LlmRouterBuilder() {
        strategy("priority", Strategies.priority());
        strategy("round-robin", Strategies.roundRobin());
        strategy("weighted-random", Strategies.weightedRandom());
        strategy("least-busy", Strategies.leastBusy());
        strategy("latency-aware", Strategies.latencyAware());
        strategy("cost-aware", Strategies.costAware());
        strategy("balanced", Strategies.balanced());
        ServiceLoader.load(RouterIntegrationDefaults.class)
                .forEach(defaults -> defaults.configure(this));
    }

    public LlmRouterBuilder provider(ModelProvider value) {
        Objects.requireNonNull(value, "provider");
        providers.put(normalize(value.id(), "provider id"), value);
        return this;
    }

    public LlmRouterBuilder authenticator(ProviderAuthenticator value) {
        Objects.requireNonNull(value, "authenticator");
        authenticators.put(normalize(value.id(), "authenticator id"), value);
        return this;
    }

    /** Sets the authenticator used by integrations that do not specify one explicitly. */
    public LlmRouterBuilder defaultAuthenticator(String value) {
        defaultAuthenticator = value == null ? null : normalize(value, "default authenticator");
        return this;
    }

    public LlmRouterBuilder pricingResolver(ModelPricingResolver value) {
        pricingResolvers.add(Objects.requireNonNull(value, "pricingResolver"));
        return this;
    }

    public LlmRouterBuilder strategy(String name, RoutingStrategy value) {
        strategies.put(normalize(name, "strategy name"), Objects.requireNonNull(value, "strategy"));
        return this;
    }

    public LlmRouterBuilder integration(String id, Consumer<IntegrationBuilder> customizer) {
        String normalized = normalize(id, "integration id");
        try {
            IntegrationBuilder builder = new IntegrationBuilder(normalized);
            Objects.requireNonNull(customizer, "customizer").accept(builder);
            if (integrations.putIfAbsent(normalized, builder.build()) != null) {
                throw new IllegalArgumentException("duplicate integration: " + normalized);
            }
        } catch (RuntimeException error) {
            handleInvalid("integration " + normalized, error);
        }
        return this;
    }

    public LlmRouterBuilder target(ModelTarget value) {
        Objects.requireNonNull(value, "target");
        if (explicitTargets.putIfAbsent(value.id(), value) != null) {
            throw new IllegalArgumentException("duplicate target: " + value.id());
        }
        return this;
    }

    public LlmRouterBuilder route(String id, Consumer<RouteBuilder> customizer) {
        String normalized = requireText(id, "route id");
        try {
            RouteBuilder builder = new RouteBuilder(normalized);
            Objects.requireNonNull(customizer, "customizer").accept(builder);
            if (routes.putIfAbsent(normalized, builder.build()) != null) {
                throw new IllegalArgumentException("duplicate route: " + normalized);
            }
        } catch (RuntimeException error) {
            handleInvalid("route " + normalized, error);
        }
        return this;
    }

    public LlmRouterBuilder defaultRoute(String value) {
        defaultRoute = requireText(value, "defaultRoute");
        return this;
    }

    public LlmRouterBuilder timeout(Duration value) {
        timeout = Objects.requireNonNull(value, "timeout");
        return this;
    }

    public LlmRouterBuilder maxRetries(int value) {
        maxRetries = value;
        return this;
    }

    public LlmRouterBuilder retryDelay(Duration value) {
        retryDelay = Objects.requireNonNull(value, "retryDelay");
        return this;
    }

    public LlmRouterBuilder failureThreshold(int value) {
        failureThreshold = value;
        return this;
    }

    public LlmRouterBuilder cooldown(Duration value) {
        cooldown = Objects.requireNonNull(value, "cooldown");
        return this;
    }

    public LlmRouterBuilder firstTokenTimeout(Duration value) {
        firstTokenTimeout = value;
        return this;
    }

    public LlmRouterBuilder streamIdleTimeout(Duration value) {
        streamIdleTimeout = value;
        return this;
    }

    public LlmRouterBuilder stateStore(RouterStateStore value) {
        stateStore = Objects.requireNonNull(value, "stateStore");
        return this;
    }

    public LlmRouterBuilder listener(RouterListener value) {
        listener = Objects.requireNonNull(value, "listener");
        return this;
    }

    public LlmRouterBuilder executor(ExecutorService value) {
        executor = Objects.requireNonNull(value, "executor");
        return this;
    }

    public LlmRouterBuilder failFast(boolean value) {
        failFast = value;
        return this;
    }

    public LlmRouter build() {
        new ExecutionPolicy(timeout, maxRetries, retryDelay, failureThreshold, cooldown,
                firstTokenTimeout, streamIdleTimeout);
        Map<String, ModelTarget> targets = buildTargets();
        if (targets.isEmpty()) throw new IllegalArgumentException("at least one model target is required");
        if (routes.isEmpty()) throw new IllegalArgumentException("at least one route is required");

        Map<String, RoutedChatModel> chatRoutes = new LinkedHashMap<>();
        Map<String, RoutedModelOperations> operationRoutes = new LinkedHashMap<>();
        routes.forEach((routeId, route) -> {
            try {
                RoutingStrategy routingStrategy = strategies.get(normalize(route.strategy, "strategy"));
                if (routingStrategy == null) {
                    throw new IllegalArgumentException("unknown routing strategy: " + route.strategy);
                }
                Collection<ModelTarget> routeTargets = resolveTargets(routeId, route, targets);
                chatRoutes.put(routeId, buildChatRoute(routeId, route, routingStrategy, routeTargets));
                operationRoutes.put(routeId, buildOperationRoute(routeId, route, routingStrategy, routeTargets));
            } catch (RuntimeException error) {
                handleInvalid("route " + routeId, error);
            }
        });
        if (chatRoutes.isEmpty()) throw new IllegalArgumentException("no valid routes are configured");
        if (!chatRoutes.containsKey(defaultRoute)) {
            throw new IllegalArgumentException("default route does not exist: " + defaultRoute);
        }
        return new LlmRouter(defaultRoute, targets,
                new RoutedChatModels(chatRoutes), new RoutedModelOperationsRegistry(operationRoutes));
    }

    private Map<String, ModelTarget> buildTargets() {
        Map<String, ModelTarget> targets = new LinkedHashMap<>(explicitTargets);
        integrations.forEach((integrationId, integration) -> {
            try {
                ModelProvider provider = providers.get(normalize(integration.provider, "provider"));
                if (provider == null) throw new IllegalArgumentException(
                        "unknown model provider: " + integration.provider);
                String authenticatorId = integration.authenticator == null
                        ? defaultAuthenticator : integration.authenticator;
                ProviderAuthenticator authenticator = authenticatorId == null
                        ? null : authenticators.get(normalize(authenticatorId, "authenticator"));
                if (authenticatorId != null && authenticator == null) throw new IllegalArgumentException(
                        "unknown provider authenticator: " + authenticatorId);
                RequestAuthenticator requestAuthenticator = authenticator == null
                        ? RequestAuthenticator.NONE
                        : authenticator.create(new AuthenticationContext(
                        integrationId, provider.id(), integration.apiKey, integration.options));
                integration.models.forEach((modelName, model) -> {
                    String targetId = integrationId + "/" + modelName;
                    ModelClient client = provider.create(new ModelProviderRequest(
                            integrationId, modelName, integration.baseUrl, requestAuthenticator,
                            integration.options, model.extensions));
                    ModelTarget target = ModelTarget.builder(targetId, client)
                            .capabilities(model.capabilities.toArray(Capability[]::new))
                            .maxInputTokens(model.maxInputTokens)
                            .pricing(resolvePricing(integrationId, provider.id(), modelName, model))
                            .limits(model.limits)
                            .priority(model.priority)
                            .weight(model.weight)
                            .metadata(model.metadata)
                            .build();
                    if (targets.putIfAbsent(targetId, target) != null) {
                        throw new IllegalArgumentException("duplicate target: " + targetId);
                    }
                });
            } catch (RuntimeException error) {
                handleInvalid("integration " + integrationId, error);
            }
        });
        return Collections.unmodifiableMap(targets);
    }

    private ModelPricing resolvePricing(String integrationId, String providerId,
                                        String modelName, ModelSpec model) {
        if (model.pricing.inputCostPerMillion() != null || model.pricing.outputCostPerMillion() != null) {
            return model.pricing;
        }
        PricingContext context = new PricingContext(integrationId, providerId, modelName, model.extensions);
        for (ModelPricingResolver resolver : pricingResolvers) {
            java.util.Optional<ModelPricing> resolved = resolver.resolve(context);
            if (resolved == null) throw new IllegalStateException("model pricing resolver returned null");
            if (resolved.isPresent()) return resolved.get();
        }
        return ModelPricing.UNKNOWN;
    }

    private Collection<ModelTarget> resolveTargets(
            String routeId, RouteSpec route, Map<String, ModelTarget> targets) {
        if (route.models.isEmpty()) throw new IllegalArgumentException("route models must not be empty");
        Set<String> ids = new LinkedHashSet<>(route.models);
        List<ModelTarget> result = new ArrayList<>();
        for (String id : ids) {
            ModelTarget target = targets.get(id);
            if (target == null) throw new IllegalArgumentException(
                    "route " + routeId + " references unknown model " + id);
            result.add(target);
        }
        return result;
    }

    private RoutedChatModel buildChatRoute(String routeId, RouteSpec route,
                                           RoutingStrategy strategy,
                                           Collection<ModelTarget> targets) {
        RoutedChatModel.Builder builder = RoutedChatModel.builder()
                .strategy(route.strategy, strategy)
                .timeout(timeout).maxRetries(maxRetries).retryDelay(retryDelay)
                .failureThreshold(failureThreshold).cooldown(cooldown)
                .firstTokenTimeout(firstTokenTimeout).streamIdleTimeout(streamIdleTimeout)
                .stateStore(stateStore).stateNamespace(routeId).listener(listener);
        if (executor != null) builder.executor(executor);
        targets.forEach(builder::target);
        return builder.build();
    }

    private RoutedModelOperations buildOperationRoute(String routeId, RouteSpec route,
                                                       RoutingStrategy strategy,
                                                       Collection<ModelTarget> targets) {
        RoutedModelOperations.Builder builder = RoutedModelOperations.builder()
                .strategy(route.strategy, strategy)
                .timeout(timeout).maxRetries(maxRetries).retryDelay(retryDelay)
                .failureThreshold(failureThreshold).cooldown(cooldown)
                .stateStore(stateStore).stateNamespace(routeId).listener(listener);
        if (executor != null) builder.executor(executor);
        targets.forEach(builder::target);
        return builder.build();
    }

    private void handleInvalid(String scope, RuntimeException error) {
        if (failFast) throw error;
        LOGGER.log(System.Logger.Level.WARNING, "Skipping invalid {0}: {1}", scope, error.getMessage());
    }

    private static String normalize(String value, String name) {
        return requireText(value, name).toLowerCase(Locale.ROOT);
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " must not be blank");
        return value;
    }

    public final class IntegrationBuilder {
        private final String id;
        private String provider;
        private String baseUrl;
        private String apiKey;
        private String authenticator;
        private final Map<String, Object> options = new LinkedHashMap<>();
        private final Map<String, ModelSpec> models = new LinkedHashMap<>();

        private IntegrationBuilder(String id) {
            this.id = id;
            this.provider = id;
        }

        public IntegrationBuilder provider(String value) {
            provider = normalize(value, "provider");
            return this;
        }

        public IntegrationBuilder provider(ModelProvider value) {
            Objects.requireNonNull(value, "provider");
            LlmRouterBuilder.this.provider(value);
            provider = normalize(value.id(), "provider id");
            return this;
        }

        public IntegrationBuilder baseUrl(String value) {
            baseUrl = requireText(value, "baseUrl");
            return this;
        }

        public IntegrationBuilder apiKey(String value) {
            apiKey = value;
            return this;
        }

        public IntegrationBuilder authenticator(String value) {
            authenticator = normalize(value, "authenticator");
            return this;
        }

        public IntegrationBuilder option(String name, Object value) {
            options.put(requireText(name, "option name"), value);
            return this;
        }

        public IntegrationBuilder options(Map<String, ?> values) {
            Objects.requireNonNull(values, "options").forEach(this::option);
            return this;
        }

        public IntegrationBuilder responsesApi() {
            return option("api-mode", "responses");
        }

        public IntegrationBuilder siteUrl(String value) {
            return option("site-url", value);
        }

        public IntegrationBuilder appName(String value) {
            return option("app-name", value);
        }

        public IntegrationBuilder model(String name, Consumer<ModelBuilder> customizer) {
            String modelName = requireText(name, "model name");
            ModelBuilder builder = new ModelBuilder();
            Objects.requireNonNull(customizer, "customizer").accept(builder);
            if (models.putIfAbsent(modelName, builder.build()) != null) {
                throw new IllegalArgumentException("duplicate model in integration " + id + ": " + modelName);
            }
            return this;
        }

        private IntegrationSpec build() {
            if (models.isEmpty()) throw new IllegalArgumentException("integration " + id + " models must not be empty");
            if (!"openrouter".equals(provider)
                    && (options.containsKey("site-url") || options.containsKey("app-name"))) {
                throw new IllegalArgumentException("integration " + id
                        + " site-url and app-name are only supported for openrouter");
            }
            return new IntegrationSpec(provider, baseUrl, apiKey, authenticator,
                    Map.copyOf(options), Map.copyOf(models));
        }
    }

    public static final class ModelBuilder {
        private final EnumSet<Capability> capabilities = EnumSet.of(Capability.CHAT);
        private Integer maxInputTokens;
        private ModelPricing pricing = ModelPricing.UNKNOWN;
        private ModelLimits limits = ModelLimits.UNLIMITED;
        private int priority = 100;
        private int weight = 100;
        private Map<String, String> metadata = Map.of();
        private final Map<String, Object> extensions = new LinkedHashMap<>();

        public ModelBuilder capabilities(Capability... values) {
            capabilities.clear();
            for (Capability value : values) capabilities.add(Objects.requireNonNull(value, "capability"));
            if (capabilities.isEmpty()) throw new IllegalArgumentException("capabilities must not be empty");
            return this;
        }

        public ModelBuilder maxInputTokens(Integer value) {
            if (value != null && value < 1) throw new IllegalArgumentException("maxInputTokens must be > 0");
            maxInputTokens = value;
            return this;
        }

        public ModelBuilder pricing(double inputCostPerMillion, double outputCostPerMillion) {
            pricing = new ModelPricing(inputCostPerMillion, outputCostPerMillion);
            return this;
        }

        public ModelBuilder pricing(Double inputCostPerMillion, Double outputCostPerMillion) {
            pricing = new ModelPricing(inputCostPerMillion, outputCostPerMillion);
            return this;
        }

        public ModelBuilder pricing(ModelPricing value) {
            pricing = Objects.requireNonNull(value, "pricing");
            return this;
        }

        public ModelBuilder limits(Long requestsPerMinute, Long tokensPerMinute, Integer maxConcurrency) {
            limits = new ModelLimits(requestsPerMinute, tokensPerMinute, maxConcurrency);
            return this;
        }

        public ModelBuilder limits(ModelLimits value) {
            limits = Objects.requireNonNull(value, "limits");
            return this;
        }

        public ModelBuilder priority(int value) {
            priority = value;
            return this;
        }

        public ModelBuilder weight(int value) {
            if (value < 0) throw new IllegalArgumentException("weight must be >= 0");
            weight = value;
            return this;
        }

        public ModelBuilder metadata(Map<String, String> value) {
            metadata = Map.copyOf(Objects.requireNonNull(value, "metadata"));
            return this;
        }

        public ModelBuilder extension(String name, Object value) {
            extensions.put(requireText(name, "extension name"), value);
            return this;
        }

        public ModelBuilder extensions(Map<String, ?> values) {
            Objects.requireNonNull(values, "extensions").forEach(this::extension);
            return this;
        }

        private ModelSpec build() {
            return new ModelSpec(Set.copyOf(capabilities), maxInputTokens, pricing, limits,
                    priority, weight, metadata, Map.copyOf(extensions));
        }
    }

    public static final class RouteBuilder {
        private final String id;
        private String strategy = "balanced";
        private final List<String> models = new ArrayList<>();

        private RouteBuilder(String id) {
            this.id = id;
        }

        public RouteBuilder strategy(String value) {
            strategy = normalize(value, "strategy");
            return this;
        }

        public RouteBuilder models(String... values) {
            addIds(models, values, "model");
            return this;
        }

        private RouteSpec build() {
            if (models.isEmpty()) throw new IllegalArgumentException("route " + id + " models must not be empty");
            return new RouteSpec(strategy, List.copyOf(models));
        }

        private static void addIds(List<String> destination, String[] values, String name) {
            Objects.requireNonNull(values, name + "s");
            for (String value : values) destination.add(requireText(value, name));
        }
    }

    private static final class IntegrationSpec {
        private final String provider;
        private final String baseUrl;
        private final String apiKey;
        private final String authenticator;
        private final Map<String, Object> options;
        private final Map<String, ModelSpec> models;

        private IntegrationSpec(String provider, String baseUrl, String apiKey, String authenticator,
                                Map<String, Object> options, Map<String, ModelSpec> models) {
            this.provider = provider;
            this.baseUrl = baseUrl;
            this.apiKey = apiKey;
            this.authenticator = authenticator;
            this.options = options;
            this.models = models;
        }
    }

    private static final class ModelSpec {
        private final Set<Capability> capabilities;
        private final Integer maxInputTokens;
        private final ModelPricing pricing;
        private final ModelLimits limits;
        private final int priority;
        private final int weight;
        private final Map<String, String> metadata;
        private final Map<String, Object> extensions;

        private ModelSpec(Set<Capability> capabilities, Integer maxInputTokens,
                          ModelPricing pricing, ModelLimits limits, int priority, int weight,
                          Map<String, String> metadata, Map<String, Object> extensions) {
            this.capabilities = capabilities;
            this.maxInputTokens = maxInputTokens;
            this.pricing = pricing;
            this.limits = limits;
            this.priority = priority;
            this.weight = weight;
            this.metadata = metadata;
            this.extensions = extensions;
        }
    }

    private static final class RouteSpec {
        private final String strategy;
        private final List<String> models;

        private RouteSpec(String strategy, List<String> models) {
            this.strategy = strategy;
            this.models = models;
        }
    }
}
