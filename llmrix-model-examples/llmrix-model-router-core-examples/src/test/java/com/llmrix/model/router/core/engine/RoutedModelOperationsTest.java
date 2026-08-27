package com.llmrix.model.router.core.engine;

import com.llmrix.model.router.core.api.embedding.EmbeddingInput;
import com.llmrix.model.router.core.api.embedding.EmbeddingRequest;
import com.llmrix.model.router.core.api.embedding.EmbeddingResponse;
import com.llmrix.model.router.core.api.embedding.EmbeddingVector;
import com.llmrix.model.router.core.api.ModelClient;
import com.llmrix.model.router.core.api.Usage;
import com.llmrix.model.router.core.api.rerank.RerankRequest;
import com.llmrix.model.router.core.api.rerank.RerankResponse;
import com.llmrix.model.router.core.api.rerank.RerankResult;
import com.llmrix.model.router.core.exception.RateLimitException;
import com.llmrix.model.router.core.exception.InvalidRequestException;
import com.llmrix.model.router.core.model.ModelOperation;
import com.llmrix.model.router.core.model.ModelTarget;
import com.llmrix.model.router.core.model.ModelLimits;
import com.llmrix.model.router.core.routing.RoutingHints;
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
                .operations(ModelOperation.EMBEDDINGS).build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("EMBEDDINGS");
    }

    @Test
    void routesRerankCapability() {
        ModelTarget target = ModelTarget.builder("ranker", ModelClient.builder()
                        .rerank(request -> new RerankResponse(
                                List.of(new RerankResult(0, 0.8, "doc")), "upstream", new Usage(3, 0)))
                        .build())
                .operations(ModelOperation.RERANK).build();

        try (RoutedModelOperations operations = RoutedModelOperations.builder().target(target).build()) {
            RerankResponse response = operations.rerank(new RerankRequest("query", List.of("doc")));
            assertThat(response.modelId()).isEqualTo("ranker");
            assertThat(response.results().get(0).relevanceScore()).isEqualTo(0.8);
        }
    }

    @Test
    void preservesLocalNonRetryableModelFailuresWithoutHttpStatus() {
        ModelTarget target = embeddingTarget("invalid", request -> {
            throw new InvalidRequestException("invalid tool history");
        }, 1);

        try (RoutedModelOperations operations = RoutedModelOperations.builder()
                .target(target).maxRetries(2).build()) {
            assertThatThrownBy(() -> operations.embed(EmbeddingRequest.text("hello")))
                    .isInstanceOf(InvalidRequestException.class)
                    .hasMessage("invalid tool history");
        }
    }

    @Test
    void enforcesSharedRouteQuotaAndSeparatesAuthenticatedPartitions() {
        ModelTarget target = embeddingTarget("embed", request -> new EmbeddingResponse(
                List.of(EmbeddingVector.floats(0, List.of(0.1, 0.2))), "upstream", new Usage(1, 0)), 1);

        try (RoutedModelOperations operations = RoutedModelOperations.builder()
                .target(target).quota(new ModelLimits(1L, null, null)).build()) {
            operations.embed(EmbeddingRequest.text("first"));
            assertThatThrownBy(() -> operations.embed(EmbeddingRequest.text("second")))
                    .isInstanceOf(RateLimitException.class)
                    .hasMessageContaining("route quota exceeded");

            EmbeddingRequest otherPartition = new EmbeddingRequest(
                    List.of(EmbeddingInput.text("other")), null, null,
                    null, RoutingHints.builder().attribute(RoutingHints.AUTH_QUOTA_KEY, "tenant-b").build());
            assertThat(operations.embed(otherPartition).modelId()).isEqualTo("embed");
        }
    }

    private static ModelTarget embeddingTarget(
            String id, com.llmrix.model.router.core.api.embedding.EmbeddingModel model, int priority) {
        return ModelTarget.builder(id, ModelClient.builder().embeddings(model).build())
                .operations(ModelOperation.EMBEDDINGS).priority(priority)
                .inputCostPerMillion(1).outputCostPerMillion(1).build();
    }
}
