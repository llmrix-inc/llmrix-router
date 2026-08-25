package com.llmrix.model.router.core.engine;

import com.llmrix.model.router.core.api.embedding.EmbeddingInput;
import com.llmrix.model.router.core.api.embedding.EmbeddingRequest;
import com.llmrix.model.router.core.api.embedding.EmbeddingResponse;
import com.llmrix.model.router.core.api.embedding.EmbeddingVector;
import com.llmrix.model.router.core.api.ModelClient;
import com.llmrix.model.router.core.api.Usage;
import com.llmrix.model.router.core.exception.RateLimitException;
import com.llmrix.model.router.core.model.Capability;
import com.llmrix.model.router.core.model.ModelTarget;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RoutedModelOperationsTest {
    @Test
    void routesByOperationCapabilityAndFallsBack() {
        AtomicInteger primaryCalls = new AtomicInteger();
        ModelTarget primary = embeddingTarget("primary", request -> {
            primaryCalls.incrementAndGet();
            throw new RateLimitException("busy");
        }, 1);
        ModelTarget backup = embeddingTarget("backup", request -> new EmbeddingResponse(
                List.of(EmbeddingVector.floats(0, List.of(0.1, 0.2))), "upstream", new Usage(2, 0)), 2);

        try (RoutedModelOperations operations = RoutedModelOperations.builder()
                .target(primary).target(backup).maxRetries(0).build()) {
            EmbeddingResponse response = operations.embed(new EmbeddingRequest(
                    List.of(EmbeddingInput.text("hello")), null, null, null, null));

            assertThat(primaryCalls).hasValue(1);
            assertThat(response.modelId()).isEqualTo("backup");
            assertThat(response.data().get(0).values()).containsExactly(0.1, 0.2);
        }
    }

    @Test
    void rejectsCapabilityThatProviderClientDoesNotImplement() {
        assertThatThrownBy(() -> ModelTarget.builder("invalid",
                        ModelClient.chat(request -> com.llmrix.model.router.core.api.chat.ChatResponse.of("ok")))
                .capabilities(Capability.EMBEDDINGS).build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("EMBEDDINGS");
    }

    private static ModelTarget embeddingTarget(
            String id, com.llmrix.model.router.core.api.embedding.EmbeddingModel model, int priority) {
        return ModelTarget.builder(id, ModelClient.builder().embeddings(model).build())
                .capabilities(Capability.EMBEDDINGS).priority(priority)
                .inputCostPerMillion(1).outputCostPerMillion(1).build();
    }
}
