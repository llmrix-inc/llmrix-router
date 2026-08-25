package com.llmrix.model.router.server;

import com.llmrix.model.router.core.runtime.LlmRouter;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;

class LlmRouterServerApplicationTest {

    @Test
    void exposesSpringBootApplicationEntryPoint() {
        assertThat(LlmRouterServerApplication.class)
                .hasAnnotation(SpringBootApplication.class);
    }

    @Test
    void loadsModelIdsFromApplicationConfiguration() {
        try (ConfigurableApplicationContext context = new SpringApplicationBuilder(
                LlmRouterServerApplication.class)
                .web(WebApplicationType.NONE)
                .run(
                        "--llmrix.model.router.integrations.openrouter.api-key=test-openrouter",
                        "--llmrix.model.router.http.enabled=false")) {
            assertThat(context.getBean(LlmRouter.class).targets()).containsOnlyKeys(
                    "openrouter/openrouter/free",
                    "openrouter/minimax/minimax-m3:free",
                    "openrouter/google/gemma-4-31b-it:free",
                    "openrouter/google/gemma-4-26b-a4b-it:free",
                    "openrouter/z-ai/glm-5.2:free",
                    "openrouter/nvidia/nemotron-3-super-120b-a12b:free",
                    "openrouter/minimax/minimax-m2.7:free",
                    "openrouter/cohere/north-mini-code:free",
                    "openrouter/nvidia/nemotron-3-nano-omni-30b-a3b-reasoning:free",
                    "openrouter/thinkingmachines/inkling:free",
                    "openrouter/thinkingmachines/inkling-small:free");
        }
    }
}
