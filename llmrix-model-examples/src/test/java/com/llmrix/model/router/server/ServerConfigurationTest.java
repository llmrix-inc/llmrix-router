package com.llmrix.model.router.server;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

class ServerConfigurationTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ServerConfiguration.class))
            .withPropertyValues("llmrix.model.router.server.enabled=true");

    @Test
    void authNoneDoesNotCreateApiKeyFilter() {
        runner.withPropertyValues("llmrix.model.router.server.auth.mode=none").run(context -> {
            assertThat(context).doesNotHaveBean(ApiKeyVerifier.class);
            assertThat(context).doesNotHaveBean("apiKeyFilter");
            assertThat(context).hasBean("requestIdFilter");
        });
    }

    @Test
    void customVerifierReplacesBootstrapVerifier() {
        runner.withUserConfiguration(CustomVerifierConfiguration.class)
                .withPropertyValues("llmrix.model.router.server.auth.mode=api-key")
                .run(context -> {
                    assertThat(context).hasSingleBean(ApiKeyVerifier.class);
                    assertThat(context.getBean(ApiKeyVerifier.class).verify("custom")).isTrue();
                    assertThat(context).hasBean("apiKeyFilter");
                    assertThat(context).hasBean("requestIdFilter");
                });
    }

    @Test
    void invalidAuthModeFailsClosedDuringPropertyBinding() {
        runner.withPropertyValues("llmrix.model.router.server.auth.mode=disable")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).hasRootCauseMessage(
                            "llmrix.model.router.server.auth.mode must be api-key or none");
                });
    }

    @Configuration(proxyBeanMethods = false)
    static class CustomVerifierConfiguration {
        @Bean ApiKeyVerifier customVerifier() { return key -> "custom".equals(key); }
    }
}
