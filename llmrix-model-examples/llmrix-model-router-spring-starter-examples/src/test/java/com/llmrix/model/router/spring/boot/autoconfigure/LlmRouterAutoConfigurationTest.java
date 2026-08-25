package com.llmrix.model.router.spring.boot.autoconfigure;

import com.llmrix.model.router.core.engine.RoutedChatModel;
import com.llmrix.model.router.core.engine.RoutedChatModels;
import com.llmrix.model.router.core.engine.RoutedModelOperations;
import com.llmrix.model.router.core.engine.RoutedModelOperationsRegistry;
import com.llmrix.model.router.core.api.chat.ChatResponse;
import com.llmrix.model.router.core.model.ModelPricing;
import com.llmrix.model.router.core.state.InMemoryRouterStateStore;
import com.llmrix.model.router.core.state.RouterStateStore;
import com.llmrix.model.router.core.spi.auth.AuthenticationContext;
import com.llmrix.model.router.core.runtime.LlmRouter;
import com.llmrix.model.router.core.spi.auth.ProviderAuthenticator;
import com.llmrix.model.router.core.spi.cost.ModelPricingResolver;
import com.llmrix.model.router.core.spi.provider.ModelProvider;
import com.llmrix.model.router.core.spi.provider.ModelProviderRequest;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Collections;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import com.llmrix.model.router.integrations.fugu.FuguListener;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;

class LlmRouterAutoConfigurationTest {
    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(LlmRouterAutoConfiguration.class))
            .withPropertyValues(
                    "llmrix.model.router.default-route=general",
                    "llmrix.model.router.routes.general.strategy=priority",
                    "llmrix.model.router.routes.general.models[0].integration=fake",
                    "llmrix.model.router.routes.general.models[0].model=test-model",
                    "llmrix.model.router.integrations.fake.provider=openai",
                    "llmrix.model.router.integrations.fake.models[0].name=test-model",
                    "llmrix.model.router.integrations.fake.models[0].capabilities=chat",
                    "llmrix.model.router.integrations.fake.base-url=https://example.test/v1");

    @Test
    void createsDefaultRouteFromProperties() {
        contextRunner.run(context -> {
            assertThat(context).hasSingleBean(LlmRouter.class);
            assertThat(context).hasSingleBean(RoutedChatModels.class);
            assertThat(context).hasSingleBean(RoutedChatModel.class);
            assertThat(context).hasSingleBean(RoutedModelOperationsRegistry.class);
            assertThat(context).hasSingleBean(RoutedModelOperations.class);
            assertThat(context.getBean(RouterStateStore.class)).isInstanceOf(InMemoryRouterStateStore.class);
        });
    }

    @Test
    void createsMultipleModelsFromOneProviderIntegration() {
        contextRunner.withPropertyValues(
                        "llmrix.model.router.routes.general.models[1].integration=fake",
                        "llmrix.model.router.routes.general.models[1].model=second-model",
                        "llmrix.model.router.integrations.fake.models[1].name=second-model",
                        "llmrix.model.router.integrations.fake.models[1].capabilities=chat,code")
                .run(context -> assertThat(context.getBean(RoutedChatModel.class).targets())
                        .extracting(candidate -> candidate.id())
                        .containsExactly("fake/test-model", "fake/second-model"));
    }

    @Test
    void discoversProviderAuthenticationAndPricingBeans() {
        contextRunner.withUserConfiguration(CustomIntegrationConfiguration.class)
                .withPropertyValues(
                        "llmrix.model.router.integrations.fake.provider=custom",
                        "llmrix.model.router.integrations.fake.authenticator=custom-auth",
                        "llmrix.model.router.integrations.fake.api-key=secret")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    RoutedChatModel router = context.getBean(RoutedChatModel.class);
                    assertThat(router.targets()).singleElement().satisfies(target -> {
                        assertThat(target.target().model().chat(com.llmrix.model.router.core.api.chat.ChatRequest.user("test")).text())
                                .isEqualTo("secret");
                        assertThat(target.target().pricing()).isEqualTo(new ModelPricing(1.25, 2.5));
                    });
                });
    }

    @Test
    void redisModeRequiresUri() {
        contextRunner.withPropertyValues("llmrix.model.router.state.mode=redis")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).rootCause()
                            .hasMessageContaining("state.redis.uri is required");
                });
    }

    @Test
    void customStateStoreOverridesRedisAutoConfiguration() {
        contextRunner.withUserConfiguration(CustomStateConfiguration.class)
                .withPropertyValues("llmrix.model.router.state.mode=redis")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(RouterStateStore.class))
                            .isSameAs(context.getBean("customRouterStateStore"));
                });
    }

    @Test
    void redisModeFailsClearlyWithoutLettuce() {
        contextRunner.withClassLoader(new FilteredClassLoader("io.lettuce"))
                .withPropertyValues(
                        "llmrix.model.router.state.mode=redis",
                        "llmrix.model.router.state.redis.uri=redis://localhost:6379")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).rootCause()
                            .hasMessageContaining("requires io.lettuce:lettuce-core");
                });
    }

    @Test
    void disabledPropertySkipsRouter() {
        contextRunner.withPropertyValues("llmrix.model.router.enabled=false").run(context ->
                assertThat(context).doesNotHaveBean(RoutedChatModels.class));
    }

    @Test
    void invalidOpenAiApiModeFailsDuringPropertyBinding() {
        contextRunner.withPropertyValues("llmrix.model.router.integrations.fake.api-mode=response")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).rootCause()
                            .hasMessageContaining("OpenAiApiMode.response");
                });
    }

    @Test
    void failFastFalseSkipsInvalidUnusedCandidate() {
        contextRunner.withPropertyValues(
                        "llmrix.model.router.fail-fast=false",
                        "llmrix.model.router.integrations.invalid.provider=openai")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(RoutedChatModel.class);
                });
    }

    @Test
    void rejectsInvalidExecutionPolicyAtStartup() {
        contextRunner.withPropertyValues("llmrix.model.router.execution.timeout=0s")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void autoConfiguresFuguObservationListener() {
        contextRunner.withUserConfiguration(MicrometerTestConfiguration.class).run(context ->
                assertThat(context).hasSingleBean(FuguListener.class));
    }

    @Test
    void rejectsLegacyProvider() {
        contextRunner.withPropertyValues("llmrix.model.router.integrations.fake.provider=openai-compatible")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void rejectsOpenRouterMetadataForOtherProviders() {
        contextRunner.withPropertyValues("llmrix.model.router.integrations.fake.app-name=LLMRix")
                .run(context -> assertThat(context).hasFailed());
    }

    @Configuration(proxyBeanMethods = false)
    static class MicrometerTestConfiguration {
        @Bean ObservationRegistry observationRegistry() { return ObservationRegistry.create(); }
        @Bean MeterRegistry meterRegistry() { return new SimpleMeterRegistry(); }
    }

    @Configuration(proxyBeanMethods = false)
    static class CustomIntegrationConfiguration {
        @Bean
        ModelProvider customModelProvider() {
            return new ModelProvider() {
                @Override public String id() { return "custom"; }
                @Override public String defaultBaseUrl() { return "https://custom.example/v1"; }
                @Override public com.llmrix.model.router.core.api.ModelClient create(ModelProviderRequest request) {
                    String authorization = request.authenticator().headers().get("X-Custom-Auth");
                    return com.llmrix.model.router.core.api.ModelClient.chat(
                            ignored -> ChatResponse.of(authorization));
                }
            };
        }

        @Bean
        ProviderAuthenticator customProviderAuthenticator() {
            return new ProviderAuthenticator() {
                @Override public String id() { return "custom-auth"; }
                @Override public com.llmrix.model.router.core.spi.auth.RequestAuthenticator create(
                        AuthenticationContext context) {
                    return () -> Collections.singletonMap("X-Custom-Auth", context.apiKey());
                }
            };
        }

        @Bean
        ModelPricingResolver customModelPricingResolver() {
            return context -> Optional.of(new ModelPricing(1.25, 2.5));
        }
    }


    @Configuration(proxyBeanMethods = false)
    static class CustomStateConfiguration {
        @Bean
        RouterStateStore customRouterStateStore() {
            return new InMemoryRouterStateStore();
        }
    }
}
