package com.llmrix.model.router.spring.boot.http.config;

import com.llmrix.model.router.core.api.chat.ChatModel;
import com.llmrix.model.router.core.api.chat.ChatRequest;
import com.llmrix.model.router.core.api.chat.ChatResponse;
import com.llmrix.model.router.core.engine.RoutedChatModel;
import com.llmrix.model.router.core.engine.RoutedChatModels;
import com.llmrix.model.router.spring.boot.http.openai.OpenAiController;
import com.llmrix.model.router.spring.boot.http.security.ApiKeyVerifier;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class HttpConfigurationTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(LlmRouterHttpAutoConfiguration.class))
            .withPropertyValues("llmrix.model.router.http.enabled=true")
            .withUserConfiguration(RouterModelConfiguration.class);

    @Test
    void disabledHttpDoesNotRegisterProtocolBeans() {
        runner.withPropertyValues("llmrix.model.router.http.enabled=false").run(context -> {
            assertThat(context).doesNotHaveBean(OpenAiController.class);
            assertThat(context).doesNotHaveBean("requestIdFilter");
        });
    }

    @Test
    void authNoneDoesNotCreateApiKeyFilter() {
        runner.withPropertyValues("llmrix.model.router.http.auth.mode=none").run(context -> {
            assertThat(context).doesNotHaveBean(ApiKeyVerifier.class);
            assertThat(context).doesNotHaveBean("apiKeyFilter");
            assertThat(context).hasBean("requestIdFilter");
        });
    }

    @Test
    void customVerifierReplacesBootstrapVerifier() {
        runner.withUserConfiguration(CustomVerifierConfiguration.class)
                .withPropertyValues("llmrix.model.router.http.auth.mode=api-key")
                .run(context -> {
                    assertThat(context).hasSingleBean(ApiKeyVerifier.class);
                    assertThat(context.getBean(ApiKeyVerifier.class).verify("custom")).isTrue();
                    assertThat(context).hasBean("apiKeyFilter");
                    assertThat(context).hasBean("requestIdFilter");
                });
    }

    @Test
    void invalidAuthModeFailsClosedDuringPropertyBinding() {
        runner.withPropertyValues("llmrix.model.router.http.auth.mode=disable")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).hasRootCauseMessage(
                            "llmrix.model.router.http.auth.mode must be api-key or none");
                });
    }

    @Configuration(proxyBeanMethods = false)
    static class CustomVerifierConfiguration {
        @Bean ApiKeyVerifier customVerifier() { return key -> "custom".equals(key); }
    }

    @Configuration(proxyBeanMethods = false)
    static class RouterModelConfiguration {
        @Bean(destroyMethod = "close")
        RoutedChatModels routedChatModels() {
            ChatModel model = new ChatModel() {
                @Override public ChatResponse chat(ChatRequest request) {
                    return ChatResponse.of("test");
                }
            };
            return new RoutedChatModels(Map.of("general", RoutedChatModel.of(model)));
        }
    }
}
