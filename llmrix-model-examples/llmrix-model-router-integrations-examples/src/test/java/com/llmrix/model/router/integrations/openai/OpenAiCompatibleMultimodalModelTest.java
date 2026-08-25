package com.llmrix.model.router.integrations.openai;

import com.llmrix.model.router.core.api.audio.AudioInput;
import com.llmrix.model.router.core.api.audio.AudioResponse;
import com.llmrix.model.router.core.api.audio.AudioTextRequest;
import com.llmrix.model.router.core.api.embedding.EmbeddingInput;
import com.llmrix.model.router.core.api.embedding.EmbeddingRequest;
import com.llmrix.model.router.core.api.embedding.EmbeddingResponse;
import com.llmrix.model.router.core.api.image.ImageEditRequest;
import com.llmrix.model.router.core.api.image.ImageInput;
import com.llmrix.model.router.core.api.image.ImageRequest;
import com.llmrix.model.router.core.api.image.ImageResponse;
import com.llmrix.model.router.core.api.audio.SpeechRequest;
import com.llmrix.model.router.core.api.video.VideoInput;
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
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class OpenAiCompatibleMultimodalModelTest {
    private HttpServer server;

    @AfterEach void stop() {
        if (server != null) server.stop(0);
    }

    @Test
    void sendsEmbeddingRequestAndParsesFloatVectors() throws IOException {
        AtomicReference<String> requestBody = new AtomicReference<>();
        server = server("/v1/embeddings", exchange -> {
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            assertThat(exchange.getRequestHeaders().getFirst("Authorization")).isEqualTo("Bearer secret");
            respond(exchange, "application/json", """
                    {"object":"list","model":"text-embedding-3-small",
                     "data":[{"object":"embedding","index":0,"embedding":[0.1,0.2]}],
                     "usage":{"prompt_tokens":3,"total_tokens":3}}
                    """.getBytes(StandardCharsets.UTF_8));
        });
        OpenAiTransport transport = transport();
        OpenAiCompatibleEmbeddingModel model = new OpenAiCompatibleEmbeddingModel("text-embedding-3-small", transport);

        EmbeddingResponse response = model.embed(new EmbeddingRequest(
                List.of(EmbeddingInput.text("hello")), EmbeddingRequest.EncodingFormat.FLOAT,
                256, "user-1", null));

        assertThat(requestBody.get()).contains("\"input\":\"hello\"", "\"dimensions\":256", "\"user\":\"user-1\"");
        assertThat(response.data().get(0).values()).containsExactly(0.1, 0.2);
        assertThat(response.usage().totalTokens()).isEqualTo(3);
    }

    @Test
    void sendsMultipartTranscriptionAndReturnsBinarySpeech() throws IOException {
        AtomicReference<String> multipart = new AtomicReference<>();
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/v1/audio/transcriptions", exchange -> {
            multipart.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.ISO_8859_1));
            respond(exchange, "application/json", "{\"text\":\"hello\"}".getBytes(StandardCharsets.UTF_8));
        });
        server.createContext("/v1/audio/speech", exchange ->
                respond(exchange, "audio/mpeg", new byte[]{1, 2, 3, 4}));
        server.start();
        OpenAiCompatibleAudioModel model = new OpenAiCompatibleAudioModel("gpt-4o-mini-transcribe", transport());

        AudioResponse transcription = model.transcribe(new AudioTextRequest(
                new AudioInput(new byte[]{9, 8, 7}, "sample.wav", "audio/wav"), "en", "context",
                AudioTextRequest.ResponseFormat.JSON, 0.1, List.of("word"), null));
        AudioResponse speech = model.speech(new SpeechRequest("hello", "alloy", "mp3", 1.0, null, null));

        assertThat(multipart.get()).contains("name=\"file\"; filename=\"sample.wav\"", "name=\"model\"",
                "gpt-4o-mini-transcribe", "timestamp_granularities[]");
        assertThat(transcription.mediaType()).startsWith("application/json");
        assertThat(new String(transcription.data(), StandardCharsets.UTF_8)).contains("hello");
        assertThat(speech.mediaType()).startsWith("audio/mpeg");
        assertThat(speech.data()).containsExactly(1, 2, 3, 4);
    }

    @Test
    void supportsImageGenerationAndMultipartEdit() throws IOException {
        AtomicReference<String> editBody = new AtomicReference<>();
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/v1/images/generations", exchange -> respond(exchange, "application/json", """
                {"created":123,"data":[{"url":"https://example.test/image.png","revised_prompt":"refined"}]}
                """.getBytes(StandardCharsets.UTF_8)));
        server.createContext("/v1/images/edits", exchange -> {
            editBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.ISO_8859_1));
            respond(exchange, "application/json", "{\"created\":124,\"data\":[{\"b64_json\":\"aW1hZ2U=\"}]}"
                    .getBytes(StandardCharsets.UTF_8));
        });
        server.start();
        OpenAiCompatibleImageModel model = new OpenAiCompatibleImageModel("gpt-image-1", transport());

        ImageResponse generated = model.generate(new ImageRequest("draw", 1, "1024x1024", "high",
                null, "url", null, null, null, null, null));
        ImageResponse edited = model.edit(new ImageEditRequest(
                List.of(new ImageInput(new byte[]{1, 2}, "input.png", "image/png")), null,
                "edit", 1, null, null, "b64_json", null, null, null, null, null));

        assertThat(generated.data().get(0).url()).isEqualTo("https://example.test/image.png");
        assertThat(editBody.get()).contains("filename=\"input.png\"", "name=\"prompt\"", "edit");
        assertThat(edited.data().get(0).base64()).isEqualTo("aW1hZ2U=");
    }

    @Test
    void supportsVideoLifecycleAndDoesNotSendModelOnRemix() throws IOException {
        AtomicReference<String> remixBody = new AtomicReference<>();
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/v1/videos", exchange -> {
            String path = exchange.getRequestURI().getPath();
            if ("POST".equals(exchange.getRequestMethod()) && path.endsWith("/remix")) {
                remixBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.ISO_8859_1));
                respond(exchange, "application/json", "{\"id\":\"video_remix\",\"status\":\"queued\"}"
                        .getBytes(StandardCharsets.UTF_8));
            } else if ("POST".equals(exchange.getRequestMethod())) {
                exchange.getRequestBody().readAllBytes();
                respond(exchange, "application/json", "{\"id\":\"video_1\",\"status\":\"queued\"}"
                        .getBytes(StandardCharsets.UTF_8));
            } else if ("DELETE".equals(exchange.getRequestMethod())) {
                respond(exchange, "application/json", "{\"id\":\"video_1\",\"status\":\"deleted\"}"
                        .getBytes(StandardCharsets.UTF_8));
            } else {
                respond(exchange, "application/json", "{\"id\":\"video_1\",\"status\":\"completed\"}"
                        .getBytes(StandardCharsets.UTF_8));
            }
        });
        server.createContext("/v1/videos/video_1/content", exchange ->
                respond(exchange, "video/mp4", new byte[]{4, 5, 6}));
        server.start();
        OpenAiCompatibleVideoModel model = new OpenAiCompatibleVideoModel("sora", transport());

        VideoResponse created = model.create(new VideoRequest("ocean at sunset", "8", "1280x720",
                new VideoInput(new byte[]{1, 2}, "reference.mp4", "video/mp4"), null));
        VideoResponse retrieved = model.retrieve(new VideoLookupRequest("video_1", null));
        VideoResponse remixed = model.remix(new VideoRemixRequest("video_1", "add clouds", null));
        byte[] content = model.content(new VideoLookupRequest("video_1", null)).data();
        VideoResponse deleted = model.delete(new VideoLookupRequest("video_1", null));

        assertThat(created.id()).isEqualTo("video_1");
        assertThat(retrieved.status()).isEqualTo("completed");
        assertThat(remixed.id()).isEqualTo("video_remix");
        assertThat(remixBody.get()).contains("name=\"prompt\"", "add clouds").doesNotContain("name=\"model\"");
        assertThat(content).containsExactly(4, 5, 6);
        assertThat(deleted.status()).isEqualTo("deleted");
    }

    private HttpServer server(String path, com.sun.net.httpserver.HttpHandler handler) throws IOException {
        HttpServer value = HttpServer.create(new InetSocketAddress(0), 0);
        value.createContext(path, handler);
        value.start();
        return value;
    }

    private OpenAiTransport transport() {
        return new OpenAiTransport("http://localhost:" + server.getAddress().getPort() + "/v1",
                () -> Map.of("Authorization", "Bearer secret"), Map.of());
    }

    private static void respond(com.sun.net.httpserver.HttpExchange exchange, String type, byte[] body) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", type);
        exchange.sendResponseHeaders(200, body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
    }
}
