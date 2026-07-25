package com.llmrix.model.router.spring.boot.autoconfigure;

import com.llmrix.model.router.core.api.ChatModel;
import com.llmrix.model.router.core.api.ChatResponse;
import com.llmrix.model.router.core.api.RoutedChatModel;
import com.llmrix.model.router.core.api.RoutedChatModels;
import com.llmrix.model.router.core.execution.InMemoryRouterStateStore;
import com.llmrix.model.router.core.execution.RouterStateStore;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.Generation;

import java.util.List;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.request.ChatRequest;

import static org.assertj.core.api.Assertions.assertThat;
import com.llmrix.model.router.integrations.fugu.FuguListener;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

class LlmRouterAutoConfigurationTest {
    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(LlmRouterAutoConfiguration.class))
            .withUserConfiguration(TestConfiguration.class)
            .withPropertyValues(
                    "llmrix.model.router.default-route=general",
                    "llmrix.model.router.routes.general.strategy=priority",
                    "llmrix.model.router.routes.general.candidates[0]=fake",
                    "llmrix.model.router.candidates.fake.provider=bean",
                    "llmrix.model.router.candidates.fake.bean-name=fakeModel");

    @Test
    void createsDefaultRouteFromProperties() {
        contextRunner.run(context -> {
            assertThat(context).hasSingleBean(RoutedChatModels.class);
            assertThat(context).hasSingleBean(RoutedChatModel.class);
            assertThat(context.getBean(RouterStateStore.class)).isInstanceOf(InMemoryRouterStateStore.class);
            assertThat(context.getBean(RoutedChatModel.class).chat("hello").text()).isEqualTo("fake");
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
        contextRunner.withPropertyValues("llmrix.model.router.candidates.fake.api-mode=response")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).rootCause()
                            .hasMessageContaining("OpenAiApiMode.response");
                });
    }

    @Test
    void adaptsSpringAiBeanCandidate() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(LlmRouterAutoConfiguration.class))
                .withUserConfiguration(TestConfiguration.class)
                .withPropertyValues(
                        "llmrix.model.router.default-route=general",
                        "llmrix.model.router.routes.general.candidates[0]=spring",
                        "llmrix.model.router.candidates.spring.provider=spring-ai-bean",
                        "llmrix.model.router.candidates.spring.bean-name=springAiModel")
                .run(context -> assertThat(context.getBean(RoutedChatModel.class).chat("hello").text())
                        .isEqualTo("spring-ai"));
    }

    @Test
    void startsWithoutSpringAiOnClasspath() {
        contextRunner.withClassLoader(new FilteredClassLoader("org.springframework.ai"))
                .run(context -> {
                    assertThat(context).hasSingleBean(RoutedChatModel.class);
                    assertThat(context.getBean(RoutedChatModel.class).chat("hello").text()).isEqualTo("fake");
                });
    }

    @Test
    void adaptsLangChain4jBeanCandidate() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(LlmRouterAutoConfiguration.class))
                .withUserConfiguration(TestConfiguration.class)
                .withPropertyValues(
                        "llmrix.model.router.default-route=general",
                        "llmrix.model.router.routes.general.candidates[0]=lc4j",
                        "llmrix.model.router.candidates.lc4j.provider=langchain4j-bean",
                        "llmrix.model.router.candidates.lc4j.bean-name=langChain4jModel")
                .run(context -> assertThat(context.getBean(RoutedChatModel.class).chat("hello").text())
                        .isEqualTo("langchain4j"));
    }

    @Test
    void rejectsOpenAiCandidateWithoutModelName() {
        contextRunner.withPropertyValues(
                        "llmrix.model.router.routes.general.candidates[0]=openai",
                        "llmrix.model.router.candidates.openai.provider=openai-compatible")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void failFastFalseSkipsInvalidUnusedCandidate() {
        contextRunner.withPropertyValues(
                        "llmrix.model.router.fail-fast=false",
                        "llmrix.model.router.candidates.invalid.provider=openai-compatible")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(RoutedChatModel.class).chat("hello").text()).isEqualTo("fake");
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
    void usesNamedRouterExecutorBean() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(LlmRouterAutoConfiguration.class))
                .withUserConfiguration(ExecutorConfiguration.class)
                .withPropertyValues(
                        "llmrix.model.router.default-route=general",
                        "llmrix.model.router.routes.general.candidates[0]=executor-aware",
                        "llmrix.model.router.candidates.executor-aware.provider=bean",
                        "llmrix.model.router.candidates.executor-aware.bean-name=executorAwareModel")
                .run(context -> assertThat(context.getBean(RoutedChatModel.class).chat("hello").text())
                        .isEqualTo("llmrix-model-router-test-executor"));
    }

    @Test
    void rejectsExtensionsForProviderWithoutMapping() {
        contextRunner.withPropertyValues("llmrix.model.router.candidates.fake.extensions.reasoning-effort=high")
                .run(context -> assertThat(context).hasFailed());
    }

    @Configuration(proxyBeanMethods = false)
    static class TestConfiguration {
        @Bean
        ChatModel fakeModel() {
            return request -> ChatResponse.of("fake");
        }

        @Bean
        org.springframework.ai.chat.model.ChatModel springAiModel() {
            return prompt -> new org.springframework.ai.chat.model.ChatResponse(
                    List.of(new Generation(new AssistantMessage("spring-ai"))));
        }

        @Bean
        dev.langchain4j.model.chat.ChatModel langChain4jModel() {
            return new dev.langchain4j.model.chat.ChatModel() {
                @Override
                public dev.langchain4j.model.chat.response.ChatResponse doChat(ChatRequest request) {
                    return dev.langchain4j.model.chat.response.ChatResponse.builder()
                            .aiMessage(AiMessage.from("langchain4j"))
                            .build();
                }
            };
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class MicrometerTestConfiguration {
        @Bean ObservationRegistry observationRegistry() { return ObservationRegistry.create(); }
        @Bean MeterRegistry meterRegistry() { return new SimpleMeterRegistry(); }
    }

    @Configuration(proxyBeanMethods = false)
    static class ExecutorConfiguration {
        @Bean(destroyMethod = "shutdownNow")
        ExecutorService llmRouterExecutor() {
            return Executors.newSingleThreadExecutor(runnable -> new Thread(runnable, "llmrix-model-router-test-executor"));
        }
        @Bean ChatModel executorAwareModel() {
            return request -> ChatResponse.of(Thread.currentThread().getName());
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
