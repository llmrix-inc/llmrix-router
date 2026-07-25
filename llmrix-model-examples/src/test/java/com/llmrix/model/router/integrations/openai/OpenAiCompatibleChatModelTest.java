package com.llmrix.model.router.integrations.openai;

import com.sun.net.httpserver.HttpServer;
import com.llmrix.model.router.core.api.ChatResponse;
import com.llmrix.model.router.core.api.ChatChunk;
import com.llmrix.model.router.core.api.ChatRequest;
import com.llmrix.model.router.core.api.GenerationOptions;
import com.llmrix.model.router.core.api.Message;
import com.llmrix.model.router.core.api.TextPart;
import com.llmrix.model.router.core.api.ImagePart;
import com.llmrix.model.router.core.api.AudioPart;
import com.llmrix.model.router.core.api.ToolCallPart;
import com.llmrix.model.router.core.api.ToolChoice;
import com.llmrix.model.router.core.api.ToolDefinition;
import com.llmrix.model.router.core.api.ResponseFormat;
import com.llmrix.model.router.core.api.ToolCallAccumulator;
import com.llmrix.model.router.core.exception.PermissionDeniedException;
import com.llmrix.model.router.core.exception.ContextWindowException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.net.CookieHandler;
import java.net.ProxySelector;
import java.net.Authenticator;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Optional;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

class OpenAiCompatibleChatModelTest {
    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) server.stop(0);
    }

    @Test
    void callsChatCompletionsAndParsesUsage() throws IOException {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            String request = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(request.contains("\"model\":\"test-model\""));
            assertTrue(request.contains("\"temperature\":0.2"));
            assertTrue(request.contains("\"top_p\":0.8"));
            assertTrue(request.contains("\"max_tokens\":32"));
            assertTrue(request.contains("\"stop\":[\"END\"]"));
            assertTrue(request.contains("\"seed\":42"));
            assertTrue(request.contains("\"n\":2"));
            assertTrue(request.contains("\"logprobs\":true"));
            assertTrue(request.contains("\"user\":\"user-123\""));
            assertTrue(request.contains("\"reasoning_effort\":\"high\""));
            assertTrue(request.contains("\"type\":\"image_url\""));
            assertTrue(request.contains("\"url\":\"https://example.test/image.png\""));
            assertTrue(request.contains("\"type\":\"input_audio\""));
            assertTrue(request.contains("\"response_format\":{\"type\":\"json_schema\""));
            assertTrue(request.contains("\"name\":\"summary\""));
            assertEquals("Bearer secret", exchange.getRequestHeaders().getFirst("Authorization"));
            byte[] body = ("{\"model\":\"resolved-model\",\"choices\":[{\"message\":{\"content\":\"hello\"},\"finish_reason\":\"length\"}],"
                    + "\"usage\":{\"prompt_tokens\":3,\"completion_tokens\":2}}").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();

        OpenAiCompatibleChatModel model = OpenAiCompatibleChatModel.builder()
                .baseUrl("http://localhost:" + server.getAddress().getPort() + "/v1")
                .apiKey("secret")
                .modelName("test-model")
                .extensions(Map.of("reasoning_effort", "high"))
                .build();

        ChatResponse response = model.chat(ChatRequest.builder()
                .message(Message.user(
                        new TextPart("hi"),
                        new ImagePart("https://example.test/image.png", "low"),
                        new AudioPart("ZmFrZQ==", "wav")))
                .generationOptions(GenerationOptions.builder()
                        .temperature(0.2).topP(0.8).maxOutputTokens(32).stop("END")
                        .seed(42L).candidateCount(2).logprobs(true).user("user-123").build())
                .responseFormat(ResponseFormat.jsonSchema("summary", Map.of(
                        "type", "object",
                        "properties", Map.of("answer", Map.of("type", "string"))), true))
                .build());

        assertEquals("hello", response.text());
        assertEquals("resolved-model", response.modelId());
        assertEquals(5, response.usage().totalTokens());
        assertEquals("length", response.finishReason());
    }

    @Test
    void mapsFunctionToolsCallsAndResults() throws IOException {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            String request = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(request.contains("\"tools\":[{\"type\":\"function\""));
            assertTrue(request.contains("\"name\":\"weather\""));
            assertTrue(request.contains("\"strict\":true"));
            assertTrue(request.contains("\"tool_choice\":{\"type\":\"function\""));
            assertTrue(request.contains("\"tool_call_id\":\"call_previous\""));
            byte[] body = ("{\"model\":\"test-model\",\"choices\":[{\"message\":{" 
                    + "\"role\":\"assistant\",\"content\":null,\"tool_calls\":[{\"id\":\"call_1\"," 
                    + "\"type\":\"function\",\"function\":{\"name\":\"weather\"," 
                    + "\"arguments\":\"{\\\"city\\\":\\\"Shanghai\\\"}\"}}]}}]}"
                    ).getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        OpenAiCompatibleChatModel model = OpenAiCompatibleChatModel.builder()
                .baseUrl("http://localhost:" + server.getAddress().getPort() + "/v1")
                .modelName("test-model")
                .build();

        ChatResponse response = model.chat(ChatRequest.builder()
                .message(Message.user("continue"))
                .message(Message.tool("call_previous", "{\"temperature\":25}"))
                .tools(new ToolDefinition("weather", "Get weather", Map.of(
                        "type", "object",
                        "properties", Map.of("city", Map.of("type", "string"))), true))
                .toolChoice(ToolChoice.named("weather"))
                .build());

        assertEquals(1, response.toolCalls().size());
        assertEquals(new ToolCallPart("call_1", "weather", "{\"city\":\"Shanghai\"}"),
                response.toolCalls().get(0));
        assertEquals(response.toolCalls(), response.assistantMessage().contents());
    }

    @Test
    void rejectsExtensionThatOverridesStandardField() {
        assertThrows(IllegalArgumentException.class, () -> OpenAiCompatibleChatModel.builder()
                .modelName("test-model")
                .extensions(Map.of("model", "other"))
                .build());
    }

    @Test
    void streamsAllChunksAfterSubscription() throws Exception {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            String request = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(request.contains("\"stream_options\":{\"include_usage\":true}"));
            byte[] body = (": heartbeat\n\n"
                    + "data: {\"choices\":[{\"delta\":\n"
                    + "data: {\"content\":\"hello\"}}]}\n\n"
                    + "data: {\"choices\":[{\"delta\":{\"content\":\" world\"}}]}\n\n"
                    + "data: {\"choices\":[],\"usage\":{\"prompt_tokens\":3,\"completion_tokens\":2}}\n\n"
                    + "data: [DONE]\n\n"
                    + "data: this-must-not-be-parsed\n\n").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        OpenAiCompatibleChatModel model = OpenAiCompatibleChatModel.builder()
                .baseUrl("http://localhost:" + server.getAddress().getPort() + "/v1")
                .modelName("test-model")
                .build();
        List<ChatChunk> chunks = new ArrayList<>();
        CountDownLatch completed = new CountDownLatch(1);

        model.stream(com.llmrix.model.router.core.api.ChatRequest.user("hi")).subscribe(new Flow.Subscriber<>() {
            @Override public void onSubscribe(Flow.Subscription subscription) { subscription.request(Long.MAX_VALUE); }
            @Override public void onNext(ChatChunk item) { chunks.add(item); }
            @Override public void onError(Throwable throwable) { completed.countDown(); }
            @Override public void onComplete() { completed.countDown(); }
        });

        assertTrue(completed.await(2, TimeUnit.SECONDS));
        assertEquals(List.of("hello", " world", ""), chunks.stream().map(ChatChunk::text).toList());
        assertTrue(chunks.get(2).finished());
        assertEquals(5, chunks.get(2).usage().totalTokens());
    }

    @Test
    void suppressesUsageWhenStreamOptionDisabled() throws Exception {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            String request = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(request.contains("\"include_usage\":false"));
            byte[] body = ("data: {\"choices\":[],\"usage\":{\"prompt_tokens\":3,\"completion_tokens\":2}}\n\n"
                    + "data: [DONE]\n\n").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        OpenAiCompatibleChatModel model = OpenAiCompatibleChatModel.builder()
                .baseUrl("http://localhost:" + server.getAddress().getPort() + "/v1")
                .modelName("test-model").build();
        CountDownLatch completed = new CountDownLatch(1);
        AtomicReference<ChatChunk> finalChunk = new AtomicReference<>();
        model.stream(ChatRequest.builder().userMessage("hi")
                .streamOptions(new com.llmrix.model.router.core.api.StreamOptions(false)).build())
                .subscribe(new Flow.Subscriber<>() {
                    @Override public void onSubscribe(Flow.Subscription subscription) { subscription.request(Long.MAX_VALUE); }
                    @Override public void onNext(ChatChunk item) { if (item.finished()) finalChunk.set(item); }
                    @Override public void onError(Throwable throwable) { completed.countDown(); }
                    @Override public void onComplete() { completed.countDown(); }
                });
        assertTrue(completed.await(2, TimeUnit.SECONDS));
        assertEquals(-1, finalChunk.get().usage().inputTokens());
    }

    @Test
    void streamsAndAccumulatesToolCallDeltas() throws Exception {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            byte[] body = ("data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"call_1\","
                    + "\"type\":\"function\",\"function\":{\"name\":\"weather\",\"arguments\":\"{\\\"city\\\":\"}}]}}]}\n\n"
                    + "data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,"
                    + "\"function\":{\"arguments\":\"\\\"Shanghai\\\"}\"}}]},\"finish_reason\":\"tool_calls\"}]}\n\n"
                    + "data: [DONE]\n\n").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        OpenAiCompatibleChatModel model = OpenAiCompatibleChatModel.builder()
                .baseUrl("http://localhost:" + server.getAddress().getPort() + "/v1")
                .modelName("test-model").build();
        ToolCallAccumulator accumulator = new ToolCallAccumulator();
        CountDownLatch completed = new CountDownLatch(1);
        AtomicReference<ChatChunk> finalChunk = new AtomicReference<>();

        model.stream(ChatRequest.user("weather")).subscribe(new Flow.Subscriber<>() {
            @Override public void onSubscribe(Flow.Subscription subscription) { subscription.request(Long.MAX_VALUE); }
            @Override public void onNext(ChatChunk item) { accumulator.add(item); if (item.finished()) finalChunk.set(item); }
            @Override public void onError(Throwable throwable) { completed.countDown(); }
            @Override public void onComplete() { completed.countDown(); }
        });

        assertTrue(completed.await(2, TimeUnit.SECONDS));
        assertEquals(List.of(new ToolCallPart("call_1", "weather", "{\"city\":\"Shanghai\"}")),
                accumulator.finish());
        assertEquals("tool_calls", finalChunk.get().finishReason());
    }

    @Test
    void mapsForbiddenResponseToPermissionDenied() throws IOException {
        OpenAiCompatibleChatModel model = errorModel(
                403, "{\"error\":{\"code\":\"insufficient_permissions\"}}");

        assertThrows(PermissionDeniedException.class, () -> model.chat("hi"));
    }

    @Test
    void mapsContextErrorCodeToContextWindowException() throws IOException {
        OpenAiCompatibleChatModel model = errorModel(
                400, "{\"error\":{\"code\":\"context_length_exceeded\"}}");

        assertThrows(ContextWindowException.class, () -> model.chat("hi"));
    }

    @Test
    void downstreamCancellationCancelsHttpFuture() throws InterruptedException {
        TrackingHttpClient httpClient = new TrackingHttpClient();
        OpenAiCompatibleChatModel model = OpenAiCompatibleChatModel.builder()
                .baseUrl("http://localhost/v1")
                .modelName("test-model")
                .httpClient(httpClient)
                .build();
        AtomicReference<Flow.Subscription> subscription = new AtomicReference<>();
        CountDownLatch subscribed = new CountDownLatch(1);

        model.stream(ChatRequest.user("hi")).subscribe(new Flow.Subscriber<>() {
            @Override public void onSubscribe(Flow.Subscription value) {
                subscription.set(value);
                subscribed.countDown();
                value.request(1);
            }
            @Override public void onNext(ChatChunk item) { }
            @Override public void onError(Throwable throwable) { }
            @Override public void onComplete() { }
        });

        assertTrue(subscribed.await(2, TimeUnit.SECONDS));
        subscription.get().cancel();
        assertTrue(httpClient.future.cancelled.get());
    }

    @Test
    void usesNativeAsyncHttpAndPropagatesCancellation() {
        TrackingHttpClient httpClient = new TrackingHttpClient();
        OpenAiCompatibleChatModel model = OpenAiCompatibleChatModel.builder()
                .baseUrl("http://localhost/v1")
                .modelName("test-model")
                .httpClient(httpClient)
                .build();

        CompletableFuture<ChatResponse> result = model.chatAsync(ChatRequest.user("hi")).toCompletableFuture();
        result.cancel(true);

        assertTrue(httpClient.future.cancelled.get());
    }

    @Test
    void callsResponsesApiAndParsesOutputText() throws IOException {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/v1/responses", exchange -> {
            String request = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(request.contains("\"type\":\"input_text\""));
            assertTrue(request.contains("\"type\":\"input_image\""));
            assertTrue(request.contains("\"max_output_tokens\":64"));
            byte[] body = ("{\"id\":\"resp_1\",\"model\":\"resolved\",\"status\":\"completed\"," 
                    + "\"output\":[{\"type\":\"message\",\"content\":[{\"type\":\"output_text\",\"text\":\"response text\"}]}],"
                    + "\"usage\":{\"input_tokens\":4,\"output_tokens\":3}}").getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        OpenAiCompatibleChatModel model = OpenAiCompatibleChatModel.builder()
                .baseUrl("http://localhost:" + server.getAddress().getPort() + "/v1")
                .modelName("test-model").responsesApi().build();

        ChatResponse response = model.chat(ChatRequest.builder().message(Message.user(
                        new TextPart("hello"), new ImagePart("https://example.test/image.png")))
                .generationOptions(GenerationOptions.builder().maxOutputTokens(64).build()).build());

        assertEquals("response text", response.text());
        assertEquals("resolved", response.modelId());
        assertEquals(7, response.usage().totalTokens());
    }

    @Test
    void mapsResponsesApiFunctionCallsAndOutputs() throws IOException {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/v1/responses", exchange -> {
            String request = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(request.contains("\"tools\":[{\"type\":\"function\",\"name\":\"weather\""));
            assertTrue(request.contains("\"parameters\":{}"));
            assertTrue(request.contains("\"tool_choice\":{\"type\":\"function\",\"name\":\"weather\"}"));
            assertTrue(request.contains("\"type\":\"function_call\",\"call_id\":\"call_1\""));
            assertTrue(request.contains("\"name\":\"weather\",\"arguments\":\"{\\\"city\\\":\\\"Beijing\\\"}\""));
            assertTrue(request.contains("\"type\":\"function_call_output\",\"call_id\":\"call_1\""));
            assertTrue(request.contains("\"output\":\"{\\\"temperature\\\":25}\""));
            byte[] body = ("{\"id\":\"resp_2\",\"model\":\"resolved\",\"status\":\"completed\"," 
                    + "\"output\":[{\"type\":\"function_call\",\"call_id\":\"call_2\"," 
                    + "\"name\":\"weather\",\"arguments\":\"{\\\"city\\\":\\\"Shanghai\\\"}\"}]}"
                    ).getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        OpenAiCompatibleChatModel model = OpenAiCompatibleChatModel.builder()
                .baseUrl("http://localhost:" + server.getAddress().getPort() + "/v1")
                .modelName("test-model").responsesApi().build();

        ChatResponse response = model.chat(ChatRequest.builder()
                .message(Message.user("continue"))
                .message(Message.assistant(new ToolCallPart(
                        "call_1", "weather", "{\"city\":\"Beijing\"}")))
                .message(Message.tool("call_1", "{\"temperature\":25}"))
                .tools(new ToolDefinition("weather", "Get weather", Map.of(), false))
                .toolChoice(ToolChoice.named("weather"))
                .build());

        assertEquals(List.of(new ToolCallPart(
                "call_2", "weather", "{\"city\":\"Shanghai\"}")), response.toolCalls());
        assertEquals("tool_calls", response.finishReason());
    }

    @Test
    void streamsResponsesApiEvents() throws Exception {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/v1/responses", exchange -> {
            byte[] body = ("event: response.created\n"
                    + "data: {\"type\":\"response.created\",\"response\":{\"id\":\"resp_1\"}}\n\n"
                    + "event: response.output_text.delta\n"
                    + "data: {\"type\":\"response.output_text.delta\",\"delta\":\"hello\"}\n\n"
                    + "event: response.completed\n"
                    + "data: {\"type\":\"response.completed\",\"response\":{\"usage\":{"
                    + "\"input_tokens\":4,\"output_tokens\":2}}}\n\n").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        OpenAiCompatibleChatModel model = OpenAiCompatibleChatModel.builder()
                .baseUrl("http://localhost:" + server.getAddress().getPort() + "/v1")
                .modelName("test-model").responsesApi().build();
        List<ChatChunk> chunks = new ArrayList<>();
        CountDownLatch completed = new CountDownLatch(1);

        model.stream(ChatRequest.user("hello")).subscribe(new Flow.Subscriber<>() {
            @Override public void onSubscribe(Flow.Subscription subscription) { subscription.request(Long.MAX_VALUE); }
            @Override public void onNext(ChatChunk item) { chunks.add(item); }
            @Override public void onError(Throwable throwable) { completed.countDown(); }
            @Override public void onComplete() { completed.countDown(); }
        });

        assertTrue(completed.await(2, TimeUnit.SECONDS));
        assertEquals(List.of("hello", ""), chunks.stream().map(ChatChunk::text).toList());
        assertTrue(chunks.get(1).finished());
        assertEquals(6, chunks.get(1).usage().totalTokens());
    }

    @Test
    void streamsResponsesApiFunctionCallDeltas() throws Exception {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/v1/responses", exchange -> {
            byte[] body = ("data: {\"type\":\"response.output_item.added\",\"output_index\":0,"
                    + "\"item\":{\"type\":\"function_call\",\"call_id\":\"call_1\","
                    + "\"name\":\"weather\",\"arguments\":\"\"}}\n\n"
                    + "data: {\"type\":\"response.function_call_arguments.delta\","
                    + "\"output_index\":0,\"delta\":\"{\\\"city\\\":\"}\n\n"
                    + "data: {\"type\":\"response.function_call_arguments.delta\","
                    + "\"output_index\":0,\"delta\":\"\\\"Shanghai\\\"}\"}\n\n"
                    + "data: {\"type\":\"response.completed\",\"response\":{\"usage\":{"
                    + "\"input_tokens\":4,\"output_tokens\":3}}}\n\n").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        OpenAiCompatibleChatModel model = OpenAiCompatibleChatModel.builder()
                .baseUrl("http://localhost:" + server.getAddress().getPort() + "/v1")
                .modelName("test-model").responsesApi().build();
        ToolCallAccumulator accumulator = new ToolCallAccumulator();
        AtomicReference<ChatChunk> finalChunk = new AtomicReference<>();
        CountDownLatch completed = new CountDownLatch(1);

        model.stream(ChatRequest.user("weather")).subscribe(new Flow.Subscriber<>() {
            @Override public void onSubscribe(Flow.Subscription subscription) { subscription.request(Long.MAX_VALUE); }
            @Override public void onNext(ChatChunk item) {
                accumulator.add(item);
                if (item.finished()) finalChunk.set(item);
            }
            @Override public void onError(Throwable throwable) { completed.countDown(); }
            @Override public void onComplete() { completed.countDown(); }
        });

        assertTrue(completed.await(2, TimeUnit.SECONDS));
        assertEquals(List.of(new ToolCallPart("call_1", "weather", "{\"city\":\"Shanghai\"}")),
                accumulator.finish());
        assertEquals("tool_calls", finalChunk.get().finishReason());
        assertEquals(7, finalChunk.get().usage().totalTokens());
    }

    private OpenAiCompatibleChatModel errorModel(int status, String response) throws IOException {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            byte[] body = response.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        return OpenAiCompatibleChatModel.builder()
                .baseUrl("http://localhost:" + server.getAddress().getPort() + "/v1")
                .modelName("test-model")
                .build();
    }

    private static final class TrackingHttpClient extends HttpClient {
        private final TrackingFuture future = new TrackingFuture();
        @Override public Optional<CookieHandler> cookieHandler() { return Optional.empty(); }
        @Override public Optional<Duration> connectTimeout() { return Optional.empty(); }
        @Override public Redirect followRedirects() { return Redirect.NEVER; }
        @Override public Optional<ProxySelector> proxy() { return Optional.empty(); }
        @Override public SSLContext sslContext() { return null; }
        @Override public SSLParameters sslParameters() { return new SSLParameters(); }
        @Override public Optional<Authenticator> authenticator() { return Optional.empty(); }
        @Override public Version version() { return Version.HTTP_1_1; }
        @Override public Optional<Executor> executor() { return Optional.empty(); }
        @Override public <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> handler) {
            throw new UnsupportedOperationException();
        }
        @Override @SuppressWarnings("unchecked")
        public <T> CompletableFuture<HttpResponse<T>> sendAsync(
                HttpRequest request, HttpResponse.BodyHandler<T> handler) {
            return (CompletableFuture<HttpResponse<T>>) (CompletableFuture<?>) future;
        }
        @Override
        public <T> CompletableFuture<HttpResponse<T>> sendAsync(
                HttpRequest request,
                HttpResponse.BodyHandler<T> handler,
                HttpResponse.PushPromiseHandler<T> pushPromiseHandler) {
            return sendAsync(request, handler);
        }
    }

    private static final class TrackingFuture extends CompletableFuture<HttpResponse<java.util.stream.Stream<String>>> {
        private final AtomicBoolean cancelled = new AtomicBoolean();
        @Override public boolean cancel(boolean mayInterruptIfRunning) {
            cancelled.set(true);
            return super.cancel(mayInterruptIfRunning);
        }
    }
}
