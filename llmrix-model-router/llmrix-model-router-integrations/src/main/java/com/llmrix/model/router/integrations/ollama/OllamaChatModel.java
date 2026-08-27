package com.llmrix.model.router.integrations.ollama;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.llmrix.model.router.core.api.Usage;
import com.llmrix.model.router.core.api.chat.ChatChunk;
import com.llmrix.model.router.core.api.chat.ChatModel;
import com.llmrix.model.router.core.api.chat.ChatRequest;
import com.llmrix.model.router.core.api.chat.ChatResponse;
import com.llmrix.model.router.core.api.chat.Message;
import com.llmrix.model.router.core.api.chat.ToolCallPart;
import com.llmrix.model.router.core.api.chat.ToolCallDelta;
import com.llmrix.model.router.core.exception.ModelUnavailableException;
import com.llmrix.model.router.core.spi.auth.RequestAuthenticator;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Flow;
import java.util.concurrent.SubmissionPublisher;

/** Native Ollama /api/chat adapter. */
public final class OllamaChatModel implements ChatModel {
    private final String modelName;
    private final URI endpoint;
    private final RequestAuthenticator authenticator;
    private final Map<String, Object> options;
    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient client;

    @Override public boolean supportsStreaming() { return true; }
    @Override public boolean supportsTools() { return true; }
    @Override public boolean supportsStructuredOutput() { return true; }
    @Override public boolean supportsPromptCache() { return false; }

    public OllamaChatModel(String modelName, String baseUrl, RequestAuthenticator authenticator,
                           Map<String, Object> options) {
        if (modelName == null || modelName.isBlank()) throw new IllegalArgumentException("modelName must not be blank");
        String base = baseUrl == null || baseUrl.isBlank() ? "http://localhost:11434" : baseUrl;
        this.modelName = modelName;
        this.endpoint = URI.create((base.endsWith("/") ? base : base + "/") + "api/chat");
        this.authenticator = authenticator == null ? RequestAuthenticator.NONE : authenticator;
        this.options = options == null ? Map.of() : Map.copyOf(options);
        this.client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    }

    @Override public ChatResponse chat(ChatRequest request) {
        ObjectNode payload = payload(request, false);
        try {
            HttpResponse<String> response = client.send(request(payload), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300)
                throw new ModelUnavailableException("Ollama request failed with status " + response.statusCode());
            return parse(mapper.readTree(response.body()));
        } catch (IOException e) {
            throw new ModelUnavailableException("Ollama request failed", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ModelUnavailableException("Ollama request interrupted", e);
        }
    }

    @Override public Flow.Publisher<ChatChunk> stream(ChatRequest request) {
        return subscriber -> {
            SubmissionPublisher<ChatChunk> publisher = new SubmissionPublisher<>();
            publisher.subscribe(subscriber);
            HttpRequest httpRequest = request(payload(request, true));
            client.sendAsync(httpRequest, HttpResponse.BodyHandlers.ofLines()).thenAccept(response -> {
                if (response.statusCode() < 200 || response.statusCode() >= 300) {
                    publisher.closeExceptionally(new ModelUnavailableException("Ollama stream failed with status " + response.statusCode()));
                    return;
                }
                try (var lines = response.body()) {
                    lines.forEach(line -> {
                        if (line == null || line.isBlank()) return;
                        try {
                            JsonNode root = mapper.readTree(line);
                            JsonNode message = root.path("message");
                            List<ToolCallDelta> deltas = new ArrayList<>();
                            for (JsonNode call : message.path("tool_calls")) {
                                deltas.add(new ToolCallDelta(0, call.path("id").asText(null),
                                        call.path("function").path("name").asText(null),
                                        call.path("function").path("arguments").toString()));
                            }
                            boolean done = root.path("done").asBoolean(false);
                            Usage usage = done ? usage(root) : Usage.UNKNOWN;
                            publisher.submit(new ChatChunk(message.path("content").asText(""), done, usage, deltas,
                                    done ? "stop" : null));
                        } catch (IOException error) {
                            publisher.closeExceptionally(new ModelUnavailableException("invalid Ollama stream response", error));
                        }
                    });
                    publisher.close();
                }
            }).exceptionally(error -> { publisher.closeExceptionally(new ModelUnavailableException("Ollama stream failed", error)); return null; });
        };
    }

    private ObjectNode payload(ChatRequest request, boolean stream) {
        ObjectNode payload = mapper.createObjectNode().put("model", modelName).put("stream", stream);
        ArrayNode messages = payload.putArray("messages");
        for (Message message : request.messages()) {
            ObjectNode item = messages.addObject().put("role", message.role());
            item.put("content", message.content());
        }
        if (!request.tools().isEmpty()) {
            ArrayNode tools = payload.putArray("tools");
            request.tools().forEach(tool -> {
                ObjectNode function = tools.addObject().put("type", "function").putObject("function");
                function.put("name", tool.name());
                if (tool.description() != null) function.put("description", tool.description());
                function.set("parameters", mapper.valueToTree(tool.parameters()));
            });
        }
        var generation = request.generationOptions();
        ObjectNode opts = payload.putObject("options");
        if (generation.temperature() != null) opts.put("temperature", generation.temperature());
        if (generation.topP() != null) opts.put("top_p", generation.topP());
        if (generation.maxOutputTokens() != null) opts.put("num_predict", generation.maxOutputTokens());
        if (generation.seed() != null) opts.put("seed", generation.seed());
        if (!generation.stop().isEmpty()) opts.set("stop", mapper.valueToTree(generation.stop()));
        options.forEach((key, value) -> {
            if ("options".equals(key) && value instanceof Map<?, ?> map) {
                map.forEach((option, optionValue) -> opts.set(String.valueOf(option), mapper.valueToTree(optionValue)));
            } else {
                payload.set(key, mapper.valueToTree(value));
            }
        });
        return payload;
    }

    private HttpRequest request(ObjectNode payload) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(endpoint).timeout(Duration.ofMinutes(5))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(payload.toString()));
        authenticator.headers().forEach(builder::setHeader);
        return builder.build();
    }

    private ChatResponse parse(JsonNode root) {
        JsonNode message = root.path("message");
        List<ToolCallPart> calls = new ArrayList<>();
        for (JsonNode call : message.path("tool_calls")) {
            calls.add(new ToolCallPart(call.path("id").asText("ollama-call"),
                    call.path("function").path("name").asText(),
                    call.path("function").path("arguments").toString()));
        }
        return new ChatResponse(message.path("content").asText(""), root.path("model").asText(modelName),
                usage(root), Map.of("provider", "ollama"), calls,
                calls.isEmpty() ? "stop" : "tool_calls");
    }

    private Usage usage(JsonNode root) {
        return new Usage(root.path("prompt_eval_count").asLong(-1), root.path("eval_count").asLong(-1));
    }
}
