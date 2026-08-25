package com.llmrix.model.orion.client;

import com.llmrix.model.router.core.api.chat.ChatModel;
import com.llmrix.model.router.core.api.audio.AudioModel;
import com.llmrix.model.router.core.api.embedding.EmbeddingModel;
import com.llmrix.model.router.core.api.image.ImageModel;
import com.llmrix.model.router.core.api.video.VideoModel;
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
    void exposesAllTypedOperationModelsAndCachesThemByRoute() {
        OrionModelClient client = OrionModelClient.builder()
                .defaultModel("general")
                .defaultEmbeddingModel("embedding")
                .defaultAudioModel("audio")
                .defaultImageModel("image")
                .defaultVideoModel("video")
                .build();

        EmbeddingModel embedding = client.embeddingModel("embedding");
        AudioModel audio = client.audioModel("audio");
        ImageModel image = client.imageModel("image");
        VideoModel video = client.videoModel("video");

        assertSame(embedding, client.embeddingModel("embedding"));
        assertSame(audio, client.audioModel("audio"));
        assertSame(image, client.imageModel("image"));
        assertSame(video, client.videoModel("video"));
        assertSame(embedding, client.defaultEmbeddingModel());
        assertSame(audio, client.defaultAudioModel());
        assertSame(image, client.defaultImageModel());
        assertSame(video, client.defaultVideoModel());
    }

    @Test
    void specializedDefaultOperationsRequireExplicitRoutes() {
        OrionModelClient client = OrionModelClient.builder().defaultModel("general").build();

        assertThrows(IllegalArgumentException.class, client::defaultEmbeddingModel);
        assertThrows(IllegalArgumentException.class, client::defaultAudioModel);
        assertThrows(IllegalArgumentException.class, client::defaultImageModel);
        assertThrows(IllegalArgumentException.class, client::defaultVideoModel);
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
