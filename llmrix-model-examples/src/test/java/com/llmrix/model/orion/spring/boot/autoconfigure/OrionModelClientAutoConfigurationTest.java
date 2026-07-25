package com.llmrix.model.orion.spring.boot.autoconfigure;

import com.llmrix.model.orion.client.OrionModelClient;
import com.llmrix.model.router.core.api.ChatModel;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class OrionModelClientAutoConfigurationTest {
    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(OrionModelClientAutoConfiguration.class));

    @Test
    void createsClientWithoutDefaultModel() {
        contextRunner.withPropertyValues("llmrix.model.orion.base-url=http://localhost:9999/v1")
                .run(context -> {
                    assertThat(context).hasSingleBean(OrionModelClient.class);
                    assertThat(context).doesNotHaveBean("orionModelChatModel");
                });
    }

    @Test
    void createsCoreAndSpringAiModelsForDefaultModel() {
        contextRunner.withPropertyValues(
                        "llmrix.model.orion.base-url=http://localhost:9999/v1",
                        "llmrix.model.orion.default-model=general")
                .run(context -> {
                    assertThat(context).hasSingleBean(OrionModelClient.class);
                    assertThat(context.getBean("orionModelChatModel")).isInstanceOf(ChatModel.class);
                    assertThat(context).hasBean("orionModelSpringAiChatModel");
                });
    }

    @Test
    void disabledPropertySkipsClient() {
        contextRunner.withPropertyValues("llmrix.model.orion.enabled=false")
                .run(context -> assertThat(context).doesNotHaveBean(OrionModelClient.class));
    }
}
