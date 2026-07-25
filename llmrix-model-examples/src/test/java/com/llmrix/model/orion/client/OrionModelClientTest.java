package com.llmrix.model.orion.client;

import com.llmrix.model.router.core.api.ChatModel;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

class OrionModelClientTest {
    @Test
    void cachesModelsByRouteAndApiMode() {
        OrionModelClient client = OrionModelClient.builder()
                .baseUrl("http://localhost:8080/v1")
                .defaultModel("general")
                .build();

        ChatModel first = client.chatModel("general");
        assertSame(first, client.chatModel("general"));
        assertSame(first, client.defaultChatModel());
        assertNotSame(first, client.responsesModel("general"));
    }

    @Test
    void requiresDefaultModelOnlyForDefaultOperations() {
        OrionModelClient client = OrionModelClient.builder().build();
        assertThrows(IllegalArgumentException.class, client::defaultChatModel);
        client.chatModel("explicit-route");
    }

    @Test
    void rejectsInvalidTimeouts() {
        assertThrows(IllegalArgumentException.class, () -> OrionModelClient.builder()
                .timeout(Duration.ZERO).build());
        assertThrows(IllegalArgumentException.class, () -> OrionModelClient.builder()
                .connectTimeout(Duration.ofSeconds(-1)).build());
    }

    @Test
    void createsRequestScopedModelWithoutPollutingCache() {
        OrionModelClient client = OrionModelClient.builder().defaultModel("general").build();
        ChatModel cached = client.chatModel("general");
        OrionModelRequestOptions options = OrionModelRequestOptions.builder()
                .requestId("req-123")
                .header("X-Project", "alpha")
                .build();

        assertNotSame(cached, client.chatModel("general", options));
        assertSame(cached, client.chatModel("general"));
    }

    @Test
    void rejectsUnsafeOrAuthorizationHeaders() {
        assertThrows(IllegalArgumentException.class, () -> OrionModelRequestOptions.builder()
                .header("Authorization", "Bearer other"));
        assertThrows(IllegalArgumentException.class, () -> OrionModelRequestOptions.builder()
                .header("X-Test", "bad\nvalue"));
        assertDoesNotThrow(() -> OrionModelRequestOptions.builder().requestId("req_123").build());
    }
}
