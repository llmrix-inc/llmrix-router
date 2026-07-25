package com.llmrix.model.router.spring.boot.autoconfigure;

import com.llmrix.model.router.spring.boot.actuate.LlmRouterHealthIndicator;
import com.llmrix.model.router.spring.boot.observability.ContextPropagatingExecutorService;
import com.llmrix.model.router.spring.boot.observability.MicrometerFuguObservationListener;
import com.llmrix.model.router.spring.boot.observability.MicrometerRouterObservationListener;
import com.llmrix.model.router.spring.boot.observability.PromptSanitizer;
import com.llmrix.model.router.spring.boot.observability.RouterMetricsBinder;
import com.llmrix.model.router.spring.boot.properties.LlmRouterProperties;
import com.llmrix.model.router.spring.boot.provider.CandidateFactoryRegistry;
import com.llmrix.model.router.spring.boot.routing.RoutingStrategyRegistry;

import com.llmrix.model.router.core.api.RoutedChatModel;
import com.llmrix.model.router.core.api.RoutedChatModels;
import com.llmrix.model.router.core.candidate.Candidate;
import com.llmrix.model.router.core.routing.RoutingStrategy;
import com.llmrix.model.router.core.execution.InMemoryRouterStateStore;
import com.llmrix.model.router.core.execution.RouterStateStore;
import com.llmrix.model.router.core.execution.ExecutionPolicy;
import com.llmrix.model.router.core.candidate.ModelLimits;
import com.llmrix.model.router.core.candidate.ModelPricing;
import com.llmrix.model.router.core.spi.RouterListener;
import com.llmrix.model.router.integrations.fugu.FuguListener;
import com.llmrix.model.router.integrations.redis.RedisRouterStateStore;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import org.springframework.beans.factory.BeanFactory;
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

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;

@AutoConfiguration
@ConditionalOnClass(RoutedChatModel.class)
@ConditionalOnProperty(prefix = "llmrix.model.router", name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(LlmRouterProperties.class)
public class LlmRouterAutoConfiguration {
    private static final System.Logger LOGGER = System.getLogger(LlmRouterAutoConfiguration.class.getName());

    @Bean
    @ConditionalOnMissingBean
    CandidateFactoryRegistry candidateFactoryRegistry(BeanFactory beanFactory) {
        return new CandidateFactoryRegistry(beanFactory);
    }

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
    @ConditionalOnMissingBean
    RoutedChatModels routedChatModels(
            LlmRouterProperties properties,
            CandidateFactoryRegistry candidateFactories,
            RoutingStrategyRegistry strategies,
            RouterStateStore stateStore,
            ObjectProvider<RouterListener> listenerProvider,
            @Qualifier("llmRouterExecutor") ObjectProvider<ExecutorService> executorProvider) {
        validateRoot(properties);
        Map<String, Candidate> candidates = createCandidates(properties, candidateFactories);
        Map<String, RoutedChatModel> routes = new LinkedHashMap<>();
        RouterListener listener = listenerProvider.getIfAvailable(() -> RouterListener.NOOP);

        properties.getRoutes().forEach((routeId, route) -> {
            try {
                validateRoute(routeId, route);
                RoutedChatModel.Builder builder = RoutedChatModel.builder()
                        .strategy(route.getStrategy(), strategies.get(route.getStrategy()))
                        .fallbacks(route.getFallbacks().toArray(String[]::new))
                        .timeout(properties.getExecution().getTimeout())
                        .maxRetries(properties.getExecution().getMaxRetries())
                        .retryDelay(properties.getExecution().getRetryDelay())
                        .failureThreshold(properties.getExecution().getFailureThreshold())
                        .cooldown(properties.getExecution().getCooldown())
                        .firstTokenTimeout(properties.getExecution().getFirstTokenTimeout())
                        .streamIdleTimeout(properties.getExecution().getStreamIdleTimeout())
                        .stateStore(stateStore)
                        .stateNamespace(routeId)
                        .listener(listener);
                ExecutorService executor = executorProvider.getIfAvailable();
                if (executor != null) {
                    builder.executor(properties.getObservability().isContextPropagationEnabled()
                            ? new ContextPropagatingExecutorService(executor) : executor);
                }
                Set<String> routeCandidateIds = new LinkedHashSet<>(route.getCandidates());
                routeCandidateIds.addAll(route.getFallbacks());
                for (String candidateId : routeCandidateIds) {
                    Candidate candidate = candidates.get(candidateId);
                    if (candidate == null) throw new IllegalArgumentException("route " + routeId + " references unknown candidate " + candidateId);
                    builder.candidate(candidate);
                }
                routes.put(routeId, builder.build());
            } catch (RuntimeException error) {
                if (properties.isFailFast()) throw error;
                LOGGER.log(System.Logger.Level.WARNING,
                        "Skipping invalid llmrix.model.router route {0}: {1}", routeId, error.getMessage());
            }
        });
        if (routes.isEmpty()) throw new IllegalArgumentException("no valid llmrix.model.router routes are configured");
        if (!routes.containsKey(properties.getDefaultRoute())) {
            throw new IllegalArgumentException("default route does not exist: " + properties.getDefaultRoute());
        }
        return new RoutedChatModels(routes);
    }

    @Bean
    @Primary
    @ConditionalOnMissingBean(RoutedChatModel.class)
    RoutedChatModel routedChatModel(LlmRouterProperties properties, RoutedChatModels models) {
        return models.get(properties.getDefaultRoute());
    }

    private static Map<String, Candidate> createCandidates(
            LlmRouterProperties properties, CandidateFactoryRegistry candidateFactories) {
        Map<String, Candidate> candidates = new LinkedHashMap<>();
        properties.getCandidates().forEach((id, candidateProperties) -> {
            try {
                validateCandidate(id, candidateProperties);
                candidates.put(id, candidateFactories.create(id, candidateProperties));
            } catch (RuntimeException error) {
                if (properties.isFailFast()) throw error;
                LOGGER.log(System.Logger.Level.WARNING,
                        "Skipping invalid llmrix.model.router candidate {0}: {1}", id, error.getMessage());
            }
        });
        return candidates;
    }

    private static void validateRoot(LlmRouterProperties properties) {
        if (properties.getState() == null || properties.getState().getMode() == null) {
            throw new IllegalArgumentException("llmrix.model.router.state.mode must not be null");
        }
        if (properties.getRoutes() == null || properties.getRoutes().isEmpty()) {
            throw new IllegalArgumentException("llmrix.model.router.routes must not be empty");
        }
        if (properties.getCandidates() == null || properties.getCandidates().isEmpty()) {
            throw new IllegalArgumentException("llmrix.model.router.candidates must not be empty");
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
        if (observability == null) throw new IllegalArgumentException("llmrix.model.router.observability must not be null");
        if (observability.getPromptMaxChars() < 1) throw new IllegalArgumentException(
                "llmrix.model.router.observability.prompt-max-chars must be > 0");
        if (observability.getPromptRoutes() == null) throw new IllegalArgumentException(
                "llmrix.model.router.observability.prompt-routes must not be null");
    }

    private static void validateCandidate(String id, LlmRouterProperties.Candidate properties) {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("candidate id must not be blank");
        if (properties == null) throw new IllegalArgumentException("candidate " + id + " configuration must not be null");
        String provider = properties.getProvider();
        if (provider == null || provider.isBlank()) throw new IllegalArgumentException("candidate " + id + " provider is required");
        if (hasText(properties.getApiKey()) && hasText(properties.getBeanName())) {
            throw new IllegalArgumentException("candidate " + id + " cannot configure both api-key and bean-name");
        }
        if (properties.getWeight() < 0) throw new IllegalArgumentException("candidate " + id + " weight must be >= 0");
        if (properties.getExtensions() == null) throw new IllegalArgumentException("candidate " + id + " extensions must not be null");
        if (provider.equals("openai-compatible") && !hasText(properties.getModelName())) {
            throw new IllegalArgumentException("candidate " + id + " model-name is required for openai-compatible");
        }
        if ((provider.equals("bean") || provider.equals("spring-ai-bean") || provider.equals("langchain4j-bean"))
                && !hasText(properties.getBeanName())) {
            throw new IllegalArgumentException("candidate " + id + " bean-name is required for provider " + provider);
        }
        new ModelPricing(properties.getInputCostPerMillion(), properties.getOutputCostPerMillion());
        LlmRouterProperties.Limits limits = properties.getLimits();
        if (limits == null) throw new IllegalArgumentException("candidate " + id + " limits must not be null");
        new ModelLimits(limits.getRequestsPerMinute(), limits.getTokensPerMinute(), limits.getMaxConcurrency());
    }

    private static void validateRoute(String id, LlmRouterProperties.Route route) {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("route id must not be blank");
        if (route == null) throw new IllegalArgumentException("route " + id + " configuration must not be null");
        if (route.getCandidates() == null || route.getCandidates().isEmpty()) {
            throw new IllegalArgumentException("route " + id + " candidates must not be empty");
        }
        if (route.getFallbacks() == null) throw new IllegalArgumentException("route " + id + " fallbacks must not be null");
        if (!hasText(route.getStrategy())) throw new IllegalArgumentException("route " + id + " strategy must not be blank");
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
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
