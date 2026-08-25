package com.llmrix.model.router.core.runtime;

import com.llmrix.model.router.core.api.ModelClient;
import com.llmrix.model.router.core.api.Usage;
import com.llmrix.model.router.core.api.chat.ChatResponse;
import com.llmrix.model.router.core.api.embedding.EmbeddingRequest;
import com.llmrix.model.router.core.api.embedding.EmbeddingResponse;
import com.llmrix.model.router.core.api.embedding.EmbeddingVector;
import com.llmrix.model.router.core.exception.ModelUnavailableException;
import com.llmrix.model.router.core.model.Capability;
import com.llmrix.model.router.core.model.ModelTarget;
import com.llmrix.model.router.core.runtime.LlmRouter;
import com.llmrix.model.router.integrations.openai.OpenAiCompatibleChatModel;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class LlmRouterBuilderTest {

    @Test
    void configuresAllBuiltInProvidersWithoutSpring() {
        try (LlmRouter router = LlmRouter.builder()
                .integration("openai", integration -> integration
                        .apiKey("openai-key")
                        .model("gpt-4.1-mini", model -> model
                                .capabilities(Capability.CHAT, Capability.TOOLS)))
                .integration("deepseek", integration -> integration
                        .apiKey("deepseek-key")
                        .model("deepseek-chat", model -> model
                                .capabilities(Capability.CHAT, Capability.CODE)))
                .integration("openrouter", integration -> integration
                        .apiKey("openrouter-key")
                        .siteUrl("https://example.test")
                        .appName("LLMRix Test")
                        .model("openai/gpt-4.1-mini", model -> model
                                .capabilities(Capability.CHAT, Capability.TOOLS)))
                .route("general", route -> route
                        .strategy("balanced")
                        .models("openai/gpt-4.1-mini", "deepseek/deepseek-chat",
                                "openrouter/openai/gpt-4.1-mini"))
                .timeout(Duration.ofSeconds(30))
                .maxRetries(1)
                .build()) {
            assertThat(router.defaultRoute()).isEqualTo("general");
            assertThat(router.targets()).containsOnlyKeys(
                    "openai/gpt-4.1-mini",
                    "deepseek/deepseek-chat",
                    "openrouter/openai/gpt-4.1-mini");
            assertThat(router.targets().values())
                    .allSatisfy(target -> assertThat(target.model())
                            .isInstanceOf(OpenAiCompatibleChatModel.class));
        }
    }

    @Test
    void routesDirectModelTargetsWithFallback() {
        ModelTarget primary = ModelTarget.builder("primary", request -> {
            throw new ModelUnavailableException("primary unavailable");
        }).build();
        ModelTarget backup = ModelTarget.builder("backup", request -> ChatResponse.of("backup"))
                .build();

        try (LlmRouter router = LlmRouter.builder()
                .target(primary)
                .target(backup)
                .route("general", route -> route
                        .strategy("priority")
                        .models("primary", "backup"))
                .maxRetries(0)
                .build()) {
            assertThat(router.chat("hello").text()).isEqualTo("backup");
        }
    }

    @Test
    void routesNonChatOperationsFromTheSameFacade() {
        ModelClient client = ModelClient.builder()
                .embeddings(request -> new EmbeddingResponse(
                        List.of(EmbeddingVector.floats(0, List.of(0.25, 0.75))),
                        "embedding-model", new Usage(2, 0)))
                .build();
        ModelTarget target = ModelTarget.builder("embeddings", client)
                .capabilities(Capability.EMBEDDINGS)
                .build();

        try (LlmRouter router = LlmRouter.builder()
                .target(target)
                .route("general", route -> route.models("embeddings"))
                .build()) {
            EmbeddingResponse response = router.embed(EmbeddingRequest.text("hello"));
            assertThat(response.modelId()).isEqualTo("embeddings");
            assertThat(response.data().get(0).values()).containsExactly(0.25, 0.75);
        }
    }
}
