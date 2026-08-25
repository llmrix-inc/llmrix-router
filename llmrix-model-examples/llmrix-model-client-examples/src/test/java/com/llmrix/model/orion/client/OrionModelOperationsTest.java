package com.llmrix.model.orion.client;

import com.llmrix.model.router.core.api.audio.AudioResponse;
import com.llmrix.model.router.core.api.audio.SpeechRequest;
import com.llmrix.model.router.core.api.embedding.EmbeddingRequest;
import com.llmrix.model.router.core.api.embedding.EmbeddingResponse;
import com.llmrix.model.router.core.api.image.ImageRequest;
import com.llmrix.model.router.core.api.image.ImageResponse;
import com.llmrix.model.router.core.api.video.VideoLookupRequest;
import com.llmrix.model.router.core.api.video.VideoRemixRequest;
import com.llmrix.model.router.core.api.video.VideoRequest;
import com.llmrix.model.router.core.api.video.VideoResponse;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class OrionModelOperationsTest {
    private HttpServer server;

    @AfterEach
    void stop() {
        if (server != null) server.stop(0);
    }

    @Test
    void callsEveryRemoteOperationFamilyWithConfiguredRoutes() throws IOException {
        AtomicReference<String> videoQuery = new AtomicReference<>();
        AtomicReference<String> videoCreateBody = new AtomicReference<>();
        AtomicReference<String> videoRemixBody = new AtomicReference<>();
        AtomicReference<String> authorization = new AtomicReference<>();
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/v1/embeddings", exchange -> {
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            respond(exchange, "application/json", """
                    {"model":"embedding","data":[{"index":0,"embedding":[0.1,0.2]}],
                     "usage":{"prompt_tokens":2,"total_tokens":2}}
                    """.getBytes(StandardCharsets.UTF_8));
        });
        server.createContext("/v1/audio/speech", exchange ->
                respond(exchange, "audio/mpeg", new byte[]{1, 2, 3}));
        server.createContext("/v1/images/generations", exchange ->
                respond(exchange, "application/json",
                        "{\"created\":123,\"data\":[{\"url\":\"https://example.test/image.png\"}]}"
                                .getBytes(StandardCharsets.UTF_8)));
        server.createContext("/v1/videos/video_1", exchange -> {
            videoQuery.set(exchange.getRequestURI().getRawQuery());
            if (exchange.getRequestURI().getPath().endsWith("/remix")) {
                videoRemixBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                respond(exchange, "application/json",
                        "{\"id\":\"video_2\",\"status\":\"queued\"}".getBytes(StandardCharsets.UTF_8));
            } else {
                respond(exchange, "application/json",
                        "{\"id\":\"video_1\",\"status\":\"completed\"}".getBytes(StandardCharsets.UTF_8));
            }
        });
        server.createContext("/v1/videos", exchange -> {
            videoCreateBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            respond(exchange, "application/json",
                    "{\"id\":\"video_1\",\"status\":\"queued\"}".getBytes(StandardCharsets.UTF_8));
        });
        server.start();

        OrionModelClient client = OrionModelClient.builder()
                .baseUrl("http://localhost:" + server.getAddress().getPort() + "/v1")
                .apiKey("client-key")
                .defaultEmbeddingModel("embedding")
                .defaultAudioModel("audio")
                .defaultImageModel("image")
                .defaultVideoModel("video")
                .build();

        EmbeddingResponse embedding = client.embed(EmbeddingRequest.text("hello"));
        AudioResponse speech = client.speech(new SpeechRequest("hello", "alloy", "mp3", null, null, null));
        ImageResponse image = client.generate(new ImageRequest(
                "draw", 1, null, null, null, null, null, null, null, null, null));
        VideoResponse created = client.create(new VideoRequest(
                "ocean", "8", "1280x720", "https://example.test/reference.mp4", null));
        VideoResponse video = client.retrieve(new VideoLookupRequest("video_1", null));
        VideoResponse remixed = client.remix(new VideoRemixRequest("video_1", "slower", null));

        assertThat(embedding.data().get(0).values()).containsExactly(0.1, 0.2);
        assertThat(speech.data()).containsExactly(1, 2, 3);
        assertThat(image.data().get(0).url()).isEqualTo("https://example.test/image.png");
        assertThat(created.status()).isEqualTo("queued");
        assertThat(video.status()).isEqualTo("completed");
        assertThat(remixed.id()).isEqualTo("video_2");
        assertThat(videoCreateBody.get()).contains("\"model\":\"video\"",
                "\"input_reference\":\"https://example.test/reference.mp4\"");
        assertThat(videoRemixBody.get()).contains("\"prompt\":\"slower\"");
        assertThat(videoQuery.get()).isEqualTo("model=video");
        assertThat(authorization.get()).isEqualTo("Bearer client-key");
    }

    private static void respond(com.sun.net.httpserver.HttpExchange exchange,
                                String mediaType, byte[] body) throws IOException {
        exchange.getRequestBody().readAllBytes();
        exchange.getResponseHeaders().set("Content-Type", mediaType);
        exchange.sendResponseHeaders(200, body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
    }
}
