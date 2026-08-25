package com.llmrix.model.router.spring.boot.autoconfigure;

import com.llmrix.model.router.spring.boot.actuate.LlmRouterHealthIndicator;
import com.llmrix.model.router.spring.boot.observability.ContextPropagatingExecutorService;
import com.llmrix.model.router.spring.boot.observability.MicrometerFuguObservationListener;
import com.llmrix.model.router.spring.boot.observability.MicrometerRouterObservationListener;
import com.llmrix.model.router.spring.boot.observability.PromptSanitizer;
import com.llmrix.model.router.spring.boot.observability.RouterMetricsBinder;
import com.llmrix.model.router.spring.boot.properties.LlmRouterProperties;
import com.llmrix.model.router.spring.boot.provider.ModelTargetRegistry;
import com.llmrix.model.router.spring.boot.routing.RoutingStrategyRegistry;

import com.llmrix.model.router.core.engine.RoutedChatModel;
import com.llmrix.model.router.core.engine.RoutedChatModels;
import com.llmrix.model.router.core.engine.RoutedModelOperations;
import com.llmrix.model.router.core.engine.RoutedModelOperationsRegistry;
import com.llmrix.model.router.core.routing.RoutingStrategy;
import com.llmrix.model.router.core.state.InMemoryRouterStateStore;
import com.llmrix.model.router.core.state.RouterStateStore;
import com.llmrix.model.router.core.engine.ExecutionPolicy;
import com.llmrix.model.router.core.event.RouterListener;
import com.llmrix.model.router.core.runtime.LlmRouter;
import com.llmrix.model.router.core.runtime.LlmRouterBuilder;
import com.llmrix.model.router.integrations.fugu.FuguListener;
import com.llmrix.model.router.core.spi.auth.ProviderAuthenticator;
import com.llmrix.model.router.core.spi.cost.ModelPricingResolver;
import com.llmrix.model.router.core.spi.provider.ModelProvider;
import com.llmrix.model.router.integrations.redis.RedisRouterStateStore;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.beans.factory.annotation.Qualifier;

import java.util.Map;
import java.util.HashSet;
import java.util.Set;
import java.util.List;
import java.util.concurrent.ExecutorService;

@AutoConfiguration
@ConditionalOnClass(RoutedChatModel.class)
@ConditionalOnProperty(prefix = "llmrix.model.router", name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(LlmRouterProperties.class)
public class LlmRouterAutoConfiguration {
    private static final System.Logger LOGGER = System.getLogger(LlmRouterAutoConfiguration.class.getName());

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "llmrix.model.router.state", name = "mode", havingValue = "local", matchIfMissing = true)
    RouterStateStore routerStateStore() {
        LOGGER.log(System.Logger.Level.INFO,
                "LLMRix Router state mode: local (limits and health are JVM-local)");
        return new InMemoryRouterStateStore();
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(prefix = "llmrix.model.router.state", name = "mode", havingValue = "redis")
    @ConditionalOnClass(name = "io.lettuce.core.RedisClient")
    static class RedisStateConfiguration {
        @Bean(destroyMethod = "close")
        @ConditionalOnMissingBean(RouterStateStore.class)
        RouterStateStore redisRouterStateStore(LlmRouterProperties properties) {
            LlmRouterProperties.Redis redis = requireRedis(properties);
            LOGGER.log(System.Logger.Level.INFO,
                    "LLMRix Router state mode: redis, namespace: {0}", redis.getKeyPrefix());
            return new RedisRouterStateStore(redis.getUri(), redis.getKeyPrefix(),
                    redis.getLeaseTtl(), redis.getQuotaWindow());
        }
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(prefix = "llmrix.model.router.state", name = "mode", havingValue = "redis")
    @ConditionalOnMissingClass("io.lettuce.core.RedisClient")
    static class MissingRedisDriverConfiguration {
        @Bean
        @ConditionalOnMissingBean(RouterStateStore.class)
        RouterStateStore missingRedisDriver() {
            throw new IllegalStateException(
                    "llmrix.model.router.state.mode=redis requires io.lettuce:lettuce-core");
        }
    }

    private static LlmRouterProperties.Redis requireRedis(LlmRouterProperties properties) {
        LlmRouterProperties.State state = properties.getState();
        if (state == null || state.getRedis() == null) {
            throw new IllegalArgumentException("llmrix.model.router.state.redis must not be null");
        }
        LlmRouterProperties.Redis redis = state.getRedis();
        if (!hasText(redis.getUri())) {
            throw new IllegalArgumentException(
                    "llmrix.model.router.state.redis.uri is required when state.mode=redis");
        }
        if (!hasText(redis.getKeyPrefix())) {
            throw new IllegalArgumentException("llmrix.model.router.state.redis.key-prefix must not be blank");
        }
        requirePositive(redis.getLeaseTtl(), "llmrix.model.router.state.redis.lease-ttl");
        requirePositive(redis.getQuotaWindow(), "llmrix.model.router.state.redis.quota-window");
        return redis;
    }

    private static void requirePositive(java.time.Duration value, String name) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
    }

    @Bean
    @ConditionalOnMissingBean
    RoutingStrategyRegistry routingStrategyRegistry(ObjectProvider<Map<String, RoutingStrategy>> customStrategies) {
        RoutingStrategyRegistry registry = new RoutingStrategyRegistry();
        Map<String, RoutingStrategy> custom = customStrategies.getIfAvailable(Map::of);
        custom.forEach(registry::register);
        return registry;
    }

    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean({LlmRouter.class, RoutedChatModels.class})
    LlmRouter llmRouter(
            LlmRouterProperties properties,
            RoutingStrategyRegistry strategies,
            RouterStateStore stateStore,
            ObjectProvider<ModelProvider> providers,
            ObjectProvider<ProviderAuthenticator> authenticators,
            ObjectProvider<ModelPricingResolver> pricingResolvers,
            ObjectProvider<RouterListener> listenerProvider,
            @Qualifier("llmRouterExecutor") ObjectProvider<ExecutorService> executorProvider) {
        validateRoot(properties);
        LlmRouterBuilder builder = LlmRouter.builder()
                .defaultRoute(properties.getDefaultRoute())
                .failFast(properties.isFailFast())
                .timeout(properties.getExecution().getTimeout())
                .maxRetries(properties.getExecution().getMaxRetries())
                .retryDelay(properties.getExecution().getRetryDelay())
                .failureThreshold(properties.getExecution().getFailureThreshold())
                .cooldown(properties.getExecution().getCooldown())
                .firstTokenTimeout(properties.getExecution().getFirstTokenTimeout())
                .streamIdleTimeout(properties.getExecution().getStreamIdleTimeout())
                .stateStore(stateStore)
                .listener(listenerProvider.getIfAvailable(() -> RouterListener.NOOP));
        providers.orderedStream().forEach(builder::provider);
        authenticators.orderedStream().forEach(builder::authenticator);
        pricingResolvers.orderedStream().forEach(builder::pricingResolver);
        strategies.all().forEach(builder::strategy);

        ExecutorService executor = executorProvider.getIfAvailable();
        if (executor != null) {
            builder.executor(properties.getObservability().isContextPropagationEnabled()
                    ? new ContextPropagatingExecutorService(executor) : executor);
        }
        properties.getIntegrations().forEach((id, integration) -> mapIntegration(builder, id, integration));
        properties.getRoutes().forEach((id, route) -> builder.route(id, configured -> {
            if (route.getModels() == null || route.getModels().isEmpty()) {
                throw new IllegalArgumentException("route " + id + " models must not be empty");
            }
            List<String> modelIds = route.getModels().stream()
                    .map(reference -> routeModelId(id, reference))
                    .toList();
            configured.strategy(route.getStrategy()).models(modelIds.toArray(String[]::new));
        }));
        return builder.build();
    }

    @Bean
    @ConditionalOnBean(LlmRouter.class)
    @ConditionalOnMissingBean
    ModelTargetRegistry modelTargetRegistry(LlmRouter router) {
        return new ModelTargetRegistry(router.targets());
    }

    @Bean(destroyMethod = "")
    @ConditionalOnBean(LlmRouter.class)
    @ConditionalOnMissingBean
    RoutedChatModels routedChatModels(LlmRouter router) {
        return router.chatRoutes();
    }

    @Bean(destroyMethod = "")
    @ConditionalOnBean(LlmRouter.class)
    @ConditionalOnMissingBean
    RoutedModelOperationsRegistry routedModelOperationsRegistry(LlmRouter router) {
        return router.operationRoutes();
    }

    @Bean(destroyMethod = "")
    @Primary
    @ConditionalOnBean(RoutedModelOperationsRegistry.class)
    @ConditionalOnMissingBean(RoutedModelOperations.class)
    RoutedModelOperations routedModelOperations(
            LlmRouterProperties properties, RoutedModelOperationsRegistry routes) {
        return routes.get(properties.getDefaultRoute());
    }

    @Bean(destroyMethod = "")
    @Primary
    @ConditionalOnMissingBean(RoutedChatModel.class)
    RoutedChatModel routedChatModel(LlmRouterProperties properties, RoutedChatModels models) {
        return models.get(properties.getDefaultRoute());
    }

    private static void mapIntegration(
            LlmRouterBuilder router, String id, LlmRouterProperties.Integration integration) {
        router.integration(id, configured -> {
            configured.provider(integration.getProvider())
                    .apiKey(integration.getApiKey())
                    .authenticator(integration.getAuthenticator())
                    .options(integration.getOptions());
            if (hasText(integration.getBaseUrl())) configured.baseUrl(integration.getBaseUrl());
            if (integration.getApiMode() == LlmRouterProperties.OpenAiApiMode.RESPONSES) configured.responsesApi();
            if (hasText(integration.getSiteUrl())) configured.siteUrl(integration.getSiteUrl());
            if (hasText(integration.getAppName())) configured.appName(integration.getAppName());
            if (integration.getModels() == null || integration.getModels().isEmpty()) {
                throw new IllegalArgumentException("integration " + id + " models must not be empty");
            }
            Set<String> modelNames = new HashSet<>();
            integration.getModels().forEach(model -> {
                if (model == null) throw new IllegalArgumentException("integration " + id + " model must not be null");
                String modelName = requireText(model.getName(), "integration " + id + " model name");
                if (!modelNames.add(modelName)) {
                    throw new IllegalArgumentException("duplicate model in integration " + id + ": " + modelName);
                }
                configured.model(modelName, target -> target
                        .capabilities(model.getCapabilities().stream()
                                .map(LlmRouterAutoConfiguration::capability)
                                .toArray(com.llmrix.model.router.core.model.Capability[]::new))
                        .maxInputTokens(model.getMaxInputTokens())
                        .pricing(model.getInputCostPerMillion(), model.getOutputCostPerMillion())
                        .limits(model.getLimits().getRequestsPerMinute(),
                                model.getLimits().getTokensPerMinute(),
                                model.getLimits().getMaxConcurrency())
                        .priority(model.getPriority())
                        .weight(model.getWeight())
                        .metadata(model.getMetadata())
                        .extensions(model.getExtensions()));
            });
        });
    }

    private static com.llmrix.model.router.core.model.Capability capability(String value) {
        if (!hasText(value)) throw new IllegalArgumentException("model capability must not be blank");
        return com.llmrix.model.router.core.model.Capability.valueOf(
                value.replace('-', '_').toUpperCase(java.util.Locale.ROOT));
    }

    private static void validateRoot(LlmRouterProperties properties) {
        if (properties.getState() == null || properties.getState().getMode() == null) {
            throw new IllegalArgumentException("llmrix.model.router.state.mode must not be null");
        }
        if (properties.getRoutes() == null || properties.getRoutes().isEmpty()) {
            throw new IllegalArgumentException("llmrix.model.router.routes must not be empty");
        }
        if (properties.getIntegrations() == null || properties.getIntegrations().isEmpty()) {
            throw new IllegalArgumentException("llmrix.model.router.integrations must not be empty");
        }
        if (properties.getDefaultRoute() == null || properties.getDefaultRoute().isBlank()) {
            throw new IllegalArgumentException("llmrix.model.router.default-route must not be blank");
        }
        LlmRouterProperties.Execution execution = properties.getExecution();
        if (execution == null) throw new IllegalArgumentException("llmrix.model.router.execution must not be null");
        new ExecutionPolicy(execution.getTimeout(), execution.getMaxRetries(), execution.getRetryDelay(),
                execution.getFailureThreshold(), execution.getCooldown(), execution.getFirstTokenTimeout(),
                execution.getStreamIdleTimeout());
        LlmRouterProperties.Observability observability = properties.getObservability();
        if (observability == null)
            throw new IllegalArgumentException("llmrix.model.router.observability must not be null");
        if (observability.getPromptMaxChars() < 1) throw new IllegalArgumentException(
                "llmrix.model.router.observability.prompt-max-chars must be > 0");
        if (observability.getPromptRoutes() == null) throw new IllegalArgumentException(
                "llmrix.model.router.observability.prompt-routes must not be null");
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private static String requireText(String value, String name) {
        if (!hasText(value)) throw new IllegalArgumentException(name + " must not be blank");
        return value;
    }

    private static String routeModelId(String routeId, LlmRouterProperties.ModelReference reference) {
        if (reference == null) throw new IllegalArgumentException("route " + routeId + " model must not be null");
        String integration = requireText(reference.getIntegration(),
                "route " + routeId + " model integration").toLowerCase(java.util.Locale.ROOT);
        String model = requireText(reference.getModel(), "route " + routeId + " model name");
        return integration + "/" + model;
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass({ObservationRegistry.class, MeterRegistry.class})
    @ConditionalOnProperty(prefix = "llmrix.model.router.observability", name = "enabled", havingValue = "true", matchIfMissing = true)
    static class MicrometerConfiguration {
        @Bean
        @ConditionalOnBean({ObservationRegistry.class, MeterRegistry.class})
        @ConditionalOnMissingBean(RouterListener.class)
        RouterListener micrometerRouterObservationListener(
                ObservationRegistry observations,
                MeterRegistry meters,
                LlmRouterProperties properties,
                ObjectProvider<PromptSanitizer> promptSanitizer) {
            return new MicrometerRouterObservationListener(
                    observations,
                    meters,
                    properties.getObservability().isIncludeCandidateId(),
                    properties.getObservability().isMetricsEnabled(),
                    properties.getObservability().isTracingEnabled(),
                    properties.getObservability().isIncludeCost(),
                    properties.getObservability().isIncludeRoutingReason(),
                    properties.getObservability().isIncludePrompts(),
                    properties.getObservability().getPromptMaxChars(),
                    properties.getObservability().getPromptRoutes(),
                    promptSanitizer.getIfAvailable());
        }

        @Bean
        @ConditionalOnBean({ObservationRegistry.class, MeterRegistry.class})
        @ConditionalOnMissingBean(FuguListener.class)
        FuguListener micrometerFuguObservationListener(
                ObservationRegistry observations,
                MeterRegistry meters,
                LlmRouterProperties properties) {
            return new MicrometerFuguObservationListener(
                    observations,
                    meters,
                    properties.getObservability().isIncludeCandidateId(),
                    properties.getObservability().isMetricsEnabled(),
                    properties.getObservability().isTracingEnabled());
        }

        @Bean
        @ConditionalOnBean(MeterRegistry.class)
        @ConditionalOnMissingBean(name = "llmRouterMetricsBinder")
        MeterBinder llmRouterMetricsBinder(RoutedChatModels models, LlmRouterProperties properties) {
            return new RouterMetricsBinder(models, properties.getObservability().isIncludeCandidateId());
        }
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(HealthIndicator.class)
    static class ActuatorConfiguration {
        @Bean
        @ConditionalOnMissingBean(name = "llmRouterHealthIndicator")
        HealthIndicator llmRouterHealthIndicator(RoutedChatModels models) {
            return new LlmRouterHealthIndicator(models);
        }
    }
}
