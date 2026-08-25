package com.llmrix.model.router.spring.boot.http.openai;

import com.llmrix.model.router.spring.boot.http.security.AuthenticationResult;
import com.llmrix.model.router.spring.boot.http.web.RequestIdFilter;

import com.llmrix.model.router.core.api.chat.ChatChunk;
import com.llmrix.model.router.core.api.chat.ChatRequest;
import com.llmrix.model.router.core.api.chat.ChatResponse;
import com.llmrix.model.router.core.api.chat.Message;
import com.llmrix.model.router.core.engine.RoutedChatModel;
import com.llmrix.model.router.core.engine.RoutedChatModels;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.TextNode;
import com.llmrix.model.router.core.api.chat.ContentPart;
import com.llmrix.model.router.core.api.chat.TextPart;
import com.llmrix.model.router.core.api.chat.ImagePart;
import com.llmrix.model.router.core.api.chat.AudioPart;
import com.llmrix.model.router.core.api.chat.ToolCallPart;
import com.llmrix.model.router.core.api.chat.ToolChoice;
import com.llmrix.model.router.core.api.chat.ToolDefinition;
import com.llmrix.model.router.core.api.chat.ToolResultPart;
import com.llmrix.model.router.core.api.chat.VideoPart;
import com.llmrix.model.router.core.api.chat.FilePart;
import com.llmrix.model.router.core.api.chat.ToolCallDelta;
import com.llmrix.model.router.core.api.chat.ResponseFormat;
import com.llmrix.model.router.core.api.chat.StreamOptions;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import jakarta.servlet.http.HttpServletRequest;
import com.llmrix.model.router.core.routing.RoutingHints;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.UUID;
import java.util.concurrent.Flow;

@RestController
@RequestMapping("/v1")
@ConditionalOnProperty(prefix = "llmrix.model.router.http", name = "enabled", havingValue = "true")
public class OpenAiController {

    private static final com.fasterxml.jackson.databind.ObjectMapper JSON =
            new com.fasterxml.jackson.databind.ObjectMapper();
    private final RoutedChatModels models;

    public OpenAiController(RoutedChatModels models) {
        this.models = models;
    }

    @GetMapping("/models")
    public Map<String, Object> models() {
        List<Map<String, Object>> data = models.routeIds().stream()
                .sorted()
                .map(id -> Map.<String, Object>of("id", id, "object", "model", "owned_by", "llmrix.model.router"))
                .toList();
        return Map.of("object", "list", "data", data);
    }

    @PostMapping(value = "/chat/completions", produces = {MediaType.APPLICATION_JSON_VALUE, MediaType.TEXT_EVENT_STREAM_VALUE})
    public Object chat(@RequestBody CompletionRequest request, HttpServletRequest servletRequest) {
        if (request.model() == null || request.model().isBlank())
            throw new IllegalArgumentException("model is required");
        if (request.messages() == null || request.messages().isEmpty())
            throw new IllegalArgumentException("messages are required");
        if (!models.routeIds().contains(request.model())) throw new UnknownModelException(request.model());
        RoutedChatModel model = models.get(request.model());
        ChatRequest.Builder coreRequest = ChatRequest.builder()
                .messages(request.messages().stream().map(OpenAiController::toCoreMessage).toList())
                .generationOptions(new com.llmrix.model.router.core.api.chat.GenerationOptions(
                        request.temperature(), request.topP(),
                        request.maxCompletionTokens() != null ? request.maxCompletionTokens() : request.maxTokens(),
                        request.stop(), request.seed(), request.n(), request.logprobs(), request.user()));
        applyRequestId(coreRequest, servletRequest);
        if (request.tools() != null && !request.tools().isEmpty()) {
            coreRequest.tools(request.tools().stream().map(OpenAiController::toCoreTool).toList());
        }
        if (request.toolChoice() != null && !request.toolChoice().isNull()) {
            coreRequest.toolChoice(toCoreToolChoice(request.toolChoice()));
        }
        if (request.responseFormat() != null && !request.responseFormat().isNull()) {
            coreRequest.responseFormat(toCoreResponseFormat(request.responseFormat()));
        }
        if (request.streamOptions() != null) {
            coreRequest.streamOptions(new StreamOptions(Boolean.TRUE.equals(request.streamOptions().includeUsage())));
        }
        ChatRequest builtRequest = coreRequest.build();
        return Boolean.TRUE.equals(request.stream())
                ? stream(model, builtRequest, request.model())
                : completion(model.chat(builtRequest), request.model());
    }

    public Object chat(CompletionRequest request) {
        return chat(request, null);
    }

    @PostMapping(value = "/responses", produces = {MediaType.APPLICATION_JSON_VALUE, MediaType.TEXT_EVENT_STREAM_VALUE})
    public Object responses(@RequestBody ResponsesRequest request, HttpServletRequest servletRequest) {
        if (request.model() == null || request.model().isBlank())
            throw new IllegalArgumentException("model is required");
        rejectUnsupportedResponsesFields(request);
        if (!models.routeIds().contains(request.model())) throw new UnknownModelException(request.model());
        List<Message> messages = responsesInput(request.input());
        ChatRequest.Builder builder = ChatRequest.builder().messages(messages)
                .generationOptions(new com.llmrix.model.router.core.api.chat.GenerationOptions(
                        request.temperature(), null, request.maxOutputTokens(), List.of()));
        applyRequestId(builder, servletRequest);
        if (request.tools() != null && !request.tools().isEmpty()) {
            builder.tools(request.tools().stream().map(OpenAiController::toResponsesTool).toList());
        }
        if (request.toolChoice() != null && !request.toolChoice().isNull()) {
            builder.toolChoice(toResponsesToolChoice(request.toolChoice()));
        }
        ChatRequest coreRequest = builder.build();
        if (Boolean.TRUE.equals(request.stream())) {
            return responsesStream(models.get(request.model()), coreRequest, request.model());
        }
        ChatResponse response = models.get(request.model()).chat(coreRequest);
        String id = "resp_" + UUID.randomUUID().toString().replace("-", "");
        return Map.of(
                "id", id, "object", "response", "created_at", Instant.now().getEpochSecond(),
                "status", "completed", "model", request.model(),
                "output", responsesOutput(response),
                "usage", Map.of("input_tokens", response.usage().inputTokens(),
                        "output_tokens", response.usage().outputTokens(),
                        "total_tokens", response.usage().totalTokens()));
    }

    public Object responses(ResponsesRequest request) {
        return responses(request, null);
    }

    private static void applyRequestId(ChatRequest.Builder builder, HttpServletRequest request) {
        if (request == null) return;
        Object requestId = request.getAttribute(RequestIdFilter.ATTRIBUTE);
        RoutingHints.Builder hints = RoutingHints.builder();
        if (requestId instanceof String value && !value.isBlank())
            hints.attribute(RoutingHints.REQUEST_ID, value);
        Object authentication = request.getAttribute(AuthenticationResult.REQUEST_ATTRIBUTE);
        if (authentication instanceof AuthenticationResult result && result.authenticated()) {
            hints.attribute(RoutingHints.AUTH_PRINCIPAL, result.principal());
            if (result.quotaKey() != null && !result.quotaKey().isBlank())
                hints.attribute(RoutingHints.AUTH_QUOTA_KEY, result.quotaKey());
        }
        builder.routingHints(hints.build());
    }

    private static void rejectUnsupportedResponsesFields(ResponsesRequest request) {
        Map<String, Object> unsupported = new LinkedHashMap<>();
        unsupported.put("instructions", request.instructions());
        unsupported.put("previous_response_id", request.previousResponseId());
        unsupported.put("store", request.store());
        unsupported.put("metadata", request.metadata());
        unsupported.put("reasoning", request.reasoning());
        unsupported.put("include", request.include());
        unsupported.put("background", request.background());
        List<String> supplied = unsupported.entrySet().stream()
                .filter(entry -> entry.getValue() != null)
                .map(Map.Entry::getKey)
                .toList();
        if (!supplied.isEmpty()) {
            throw new IllegalArgumentException(
                    "unsupported Responses API fields: " + String.join(", ", supplied));
        }
    }

    private static List<Map<String, Object>> responsesOutput(ChatResponse response) {
        List<Map<String, Object>> output = new java.util.ArrayList<>();
        if (!response.text().isEmpty() || response.toolCalls().isEmpty()) {
            output.add(Map.of(
                    "id", "msg_" + UUID.randomUUID().toString().replace("-", ""),
                    "type", "message", "role", "assistant", "status", "completed",
                    "content", List.of(Map.of("type", "output_text", "text", response.text(),
                            "annotations", List.of()))));
        }
        response.toolCalls().forEach(call -> output.add(Map.of(
                "id", "fc_" + UUID.randomUUID().toString().replace("-", ""),
                "type", "function_call", "status", "completed", "call_id", call.id(),
                "name", call.name(), "arguments", call.arguments())));
        return List.copyOf(output);
    }

    private SseEmitter responsesStream(RoutedChatModel model, ChatRequest request, String modelName) {
        String id = "resp_" + UUID.randomUUID().toString().replace("-", "");
        String itemId = "msg_" + UUID.randomUUID().toString().replace("-", "");
        SseEmitter emitter = new SseEmitter(0L);
        try {
            emitter.send(SseEmitter.event().name("response.created").data(Map.of(
                    "type", "response.created", "sequence_number", 0,
                    "response", Map.of("id", id, "object", "response", "status", "in_progress", "model", modelName))));
        } catch (IOException error) {
            emitter.completeWithError(error);
            return emitter;
        }
        model.stream(request).subscribe(new Flow.Subscriber<>() {
            private Flow.Subscription subscription;
            private int sequence = 1;
            private int nextOutputIndex;
            private Integer textOutputIndex;
            private final StringBuilder outputText = new StringBuilder();
            private final Map<Integer, ResponsesStreamCall> calls = new java.util.TreeMap<>();

            @Override
            public void onSubscribe(Flow.Subscription value) {
                subscription = value;
                value.request(1);
            }

            @Override
            public void onNext(ChatChunk chunk) {
                try {
                    if (!chunk.text().isEmpty()) {
                        ensureTextStarted();
                        outputText.append(chunk.text());
                        emitter.send(SseEmitter.event().name("response.output_text.delta").data(Map.of(
                                "type", "response.output_text.delta", "sequence_number", sequence++,
                                "item_id", itemId, "output_index", textOutputIndex, "content_index", 0,
                                "delta", chunk.text())));
                    }
                    for (ToolCallDelta delta : chunk.toolCallDeltas()) {
                        ResponsesStreamCall call = calls.get(delta.index());
                        if (call == null) {
                            if (delta.id() == null || delta.name() == null) {
                                throw new IllegalArgumentException(
                                        "first tool call delta requires id and name at index " + delta.index());
                            }
                            call = new ResponsesStreamCall(delta.id(), delta.name(), nextOutputIndex++);
                            calls.put(delta.index(), call);
                            emitter.send(SseEmitter.event().name("response.output_item.added").data(Map.of(
                                    "type", "response.output_item.added", "sequence_number", sequence++,
                                    "output_index", call.outputIndex, "item", Map.of(
                                            "id", call.itemId,
                                            "type", "function_call", "status", "in_progress",
                                            "call_id", call.id, "name", call.name, "arguments", ""))));
                        } else {
                            call.requireConsistent(delta);
                        }
                        if (!delta.arguments().isEmpty()) {
                            call.arguments.append(delta.arguments());
                            emitter.send(SseEmitter.event().name("response.function_call_arguments.delta").data(Map.of(
                                    "type", "response.function_call_arguments.delta",
                                    "sequence_number", sequence++, "output_index", call.outputIndex,
                                    "item_id", call.itemId,
                                    "delta", delta.arguments())));
                        }
                    }
                    if (chunk.finished()) {
                        if (textOutputIndex == null && calls.isEmpty()) ensureTextStarted();
                        if (textOutputIndex != null) {
                            emitter.send(SseEmitter.event().name("response.output_text.done").data(Map.of(
                                    "type", "response.output_text.done", "sequence_number", sequence++,
                                    "item_id", itemId, "output_index", textOutputIndex, "content_index", 0,
                                    "text", outputText.toString())));
                            Map<String, Object> completedPart = Map.of(
                                    "type", "output_text", "text", outputText.toString(), "annotations", List.of());
                            emitter.send(SseEmitter.event().name("response.content_part.done").data(Map.of(
                                    "type", "response.content_part.done", "sequence_number", sequence++,
                                    "item_id", itemId, "output_index", textOutputIndex, "content_index", 0,
                                    "part", completedPart)));
                            emitter.send(SseEmitter.event().name("response.output_item.done").data(Map.of(
                                    "type", "response.output_item.done", "sequence_number", sequence++,
                                    "output_index", textOutputIndex, "item", Map.of(
                                            "id", itemId, "type", "message", "role", "assistant",
                                            "status", "completed", "content", List.of(completedPart)))));
                        }
                        for (var entry : calls.entrySet()) {
                            ResponsesStreamCall call = entry.getValue();
                            emitter.send(SseEmitter.event().name("response.function_call_arguments.done").data(Map.of(
                                    "type", "response.function_call_arguments.done",
                                    "sequence_number", sequence++, "output_index", call.outputIndex,
                                    "item_id", call.itemId,
                                    "arguments", call.arguments.toString())));
                            emitter.send(SseEmitter.event().name("response.output_item.done").data(Map.of(
                                    "type", "response.output_item.done", "sequence_number", sequence++,
                                    "output_index", call.outputIndex, "item", Map.of(
                                            "id", call.itemId,
                                            "type", "function_call", "status", "completed",
                                            "call_id", call.id, "name", call.name,
                                            "arguments", call.arguments.toString()))));
                        }
                        emitter.send(SseEmitter.event().name("response.completed").data(Map.of(
                                "type", "response.completed", "sequence_number", sequence,
                                "response", Map.of("id", id, "object", "response", "status", "completed",
                                        "model", modelName, "usage", Map.of(
                                                "input_tokens", chunk.usage().inputTokens(),
                                                "output_tokens", chunk.usage().outputTokens(),
                                                "total_tokens", chunk.usage().totalTokens())))));
                        emitter.complete();
                        subscription.cancel();
                    } else subscription.request(1);
                } catch (IOException error) {
                    subscription.cancel();
                    emitter.completeWithError(error);
                } catch (RuntimeException error) {
                    subscription.cancel();
                    fail(error);
                }
            }

            @Override
            public void onError(Throwable throwable) {
                fail(throwable);
            }

            @Override
            public void onComplete() {
                emitter.complete();
            }

            private void ensureTextStarted() throws IOException {
                if (textOutputIndex != null) return;
                textOutputIndex = nextOutputIndex++;
                emitter.send(SseEmitter.event().name("response.output_item.added").data(Map.of(
                        "type", "response.output_item.added", "sequence_number", sequence++,
                        "output_index", textOutputIndex, "item", Map.of(
                                "id", itemId, "type", "message", "role", "assistant",
                                "status", "in_progress", "content", List.of()))));
                emitter.send(SseEmitter.event().name("response.content_part.added").data(Map.of(
                        "type", "response.content_part.added", "sequence_number", sequence++,
                        "item_id", itemId, "output_index", textOutputIndex, "content_index", 0,
                        "part", Map.of("type", "output_text", "text", "", "annotations", List.of()))));
            }

            private void fail(Throwable failure) {
                try {
                    emitter.send(SseEmitter.event().name("response.failed").data(Map.of(
                            "type", "response.failed", "sequence_number", sequence++,
                            "response", Map.of(
                                    "id", id, "object", "response", "status", "failed", "model", modelName,
                                    "error", Map.of("code", "server_error",
                                            "message", "model execution failed")))));
                    emitter.complete();
                } catch (IOException sendFailure) {
                    emitter.completeWithError(sendFailure);
                }
            }
        });
        return emitter;
    }

    private static final class ResponsesStreamCall {
        private final String itemId = "fc_" + UUID.randomUUID().toString().replace("-", "");
        private final String id;
        private final String name;
        private final int outputIndex;
        private final StringBuilder arguments = new StringBuilder();

        private ResponsesStreamCall(String id, String name, int outputIndex) {
            this.id = id;
            this.name = name;
            this.outputIndex = outputIndex;
        }

        private void requireConsistent(ToolCallDelta delta) {
            if (delta.id() != null && !id.equals(delta.id())) {
                throw new IllegalArgumentException("conflicting tool call id at index " + delta.index());
            }
            if (delta.name() != null && !name.equals(delta.name())) {
                throw new IllegalArgumentException("conflicting tool call name at index " + delta.index());
            }
        }
    }

    private static List<Message> responsesInput(JsonNode input) {
        if (input == null || input.isNull()) throw new IllegalArgumentException("input is required");
        if (input.isTextual()) return List.of(Message.user(input.asText()));
        if (!input.isArray() || input.isEmpty())
            throw new IllegalArgumentException("input must be text or a non-empty array");
        List<Message> messages = new java.util.ArrayList<>();
        for (JsonNode item : input) {
            String type = item.path("type").asText();
            if ("function_call".equals(type)) {
                messages.add(Message.assistant(new ToolCallPart(
                        item.path("call_id").asText(item.path("id").asText()),
                        item.path("name").asText(), item.path("arguments").asText(""))));
                continue;
            }
            if ("function_call_output".equals(type)) {
                messages.add(Message.tool(item.path("call_id").asText(), item.path("output").asText("")));
                continue;
            }
            String role = item.path("role").asText();
            JsonNode content = item.path("content");
            if (role.isBlank()) throw new IllegalArgumentException("response input messages require role");
            if (content.isTextual()) {
                messages.add(new Message(role, content.asText()));
                continue;
            }
            if (!content.isArray() || content.isEmpty()) {
                throw new IllegalArgumentException("response input content must be text or a non-empty array");
            }
            List<ContentPart> parts = new java.util.ArrayList<>();
            for (JsonNode part : content) {
                switch (part.path("type").asText()) {
                    case "input_text" -> parts.add(new TextPart(part.path("text").asText()));
                    case "input_image" -> {
                        JsonNode imageUrl = part.path("image_url");
                        String url = imageUrl.isTextual() ? imageUrl.asText() : imageUrl.path("url").asText();
                        parts.add(new ImagePart(url, part.path("detail").asText(null)));
                    }
                    case "input_file" -> parts.add(toFilePart(part, true));
                    default -> throw new IllegalArgumentException(
                            "unsupported response input content type: " + part.path("type").asText());
                }
            }
            messages.add(new Message(role, parts));
        }
        return messages;
    }

    private SseEmitter stream(RoutedChatModel model, ChatRequest request, String modelName) {
        String id = "chatcmpl-" + UUID.randomUUID().toString().replace("-", "");
        long created = Instant.now().getEpochSecond();
        SseEmitter emitter = new SseEmitter(0L);
        model.stream(request).subscribe(new Flow.Subscriber<>() {
            private Flow.Subscription subscription;

            @Override
            public void onSubscribe(Flow.Subscription subscription) {
                this.subscription = subscription;
                subscription.request(1);
            }

            @Override
            public void onNext(ChatChunk chunk) {
                try {
                    emitter.send(SseEmitter.event().data(chunk(id, created, modelName, chunk)));
                    if (chunk.finished()) {
                        emitter.send(SseEmitter.event().data("[DONE]"));
                        emitter.complete();
                        subscription.cancel();
                    } else {
                        subscription.request(1);
                    }
                } catch (IOException error) {
                    subscription.cancel();
                    emitter.completeWithError(error);
                }
            }

            @Override
            public void onError(Throwable throwable) {
                emitter.completeWithError(throwable);
            }

            @Override
            public void onComplete() {
                emitter.complete();
            }
        });
        return emitter;
    }

    private static Map<String, Object> completion(ChatResponse response, String requestedModel) {
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("role", "assistant");
        message.put("content", response.toolCalls().isEmpty() || !response.text().isEmpty() ? response.text() : null);
        if (!response.toolCalls().isEmpty()) {
            message.put("tool_calls", response.toolCalls().stream().map(call -> Map.of(
                    "id", call.id(),
                    "type", "function",
                    "function", Map.of("name", call.name(), "arguments", call.arguments()))).toList());
        }
        return Map.of(
                "id", "chatcmpl-" + UUID.randomUUID().toString().replace("-", ""),
                "object", "chat.completion",
                "created", Instant.now().getEpochSecond(),
                "model", requestedModel,
                "choices", List.of(Map.of(
                        "index", 0,
                        "message", message,
                        "finish_reason", response.finishReason() == null
                                ? (response.toolCalls().isEmpty() ? "stop" : "tool_calls") : response.finishReason())),
                "usage", Map.of(
                        "prompt_tokens", response.usage().inputTokens(),
                        "completion_tokens", response.usage().outputTokens(),
                        "total_tokens", response.usage().totalTokens()));
    }

    private static Map<String, Object> chunk(String id, long created, String model, ChatChunk chunk) {
        Map<String, Object> delta = new LinkedHashMap<>();
        if (!chunk.text().isEmpty()) delta.put("content", chunk.text());
        if (!chunk.toolCallDeltas().isEmpty()) {
            delta.put("tool_calls", chunk.toolCallDeltas().stream().map(call -> {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("index", call.index());
                if (call.id() != null) item.put("id", call.id());
                item.put("type", "function");
                Map<String, Object> function = new LinkedHashMap<>();
                if (call.name() != null) function.put("name", call.name());
                if (!call.arguments().isEmpty()) function.put("arguments", call.arguments());
                item.put("function", function);
                return item;
            }).toList());
        }
        return Map.of(
                "id", id,
                "object", "chat.completion.chunk",
                "created", created,
                "model", model,
                "choices", List.of(Map.of(
                        "index", 0,
                        "delta", delta,
                        "finish_reason", chunk.finished()
                                ? (chunk.finishReason() == null ? "stop" : chunk.finishReason()) : "")));
    }

    private static Message toCoreMessage(CompletionMessage message) {
        if (message.role() == null || message.role().isBlank())
            throw new IllegalArgumentException("message role is required");
        JsonNode content = message.content();
        if ("tool".equals(message.role())) {
            if (message.toolCallId() == null || message.toolCallId().isBlank()) {
                throw new IllegalArgumentException("tool message requires tool_call_id");
            }
            if (content == null || !content.isTextual()) {
                throw new IllegalArgumentException("tool message content must be text");
            }
            return Message.tool(message.toolCallId(), content.asText());
        }
        if (message.toolCalls() != null && !message.toolCalls().isEmpty()) {
            if (!"assistant".equals(message.role())) {
                throw new IllegalArgumentException("tool_calls require an assistant message");
            }
            List<ContentPart> parts = new java.util.ArrayList<>();
            if (content != null && content.isTextual() && !content.asText().isEmpty()) {
                parts.add(new TextPart(content.asText()));
            }
            message.toolCalls().forEach(call -> {
                if (!"function".equals(call.type()) || call.function() == null) {
                    throw new IllegalArgumentException("only function tool calls are supported");
                }
                parts.add(new ToolCallPart(call.id(), call.function().name(), call.function().arguments()));
            });
            return new Message("assistant", parts);
        }
        if (content == null || content.isNull()) throw new IllegalArgumentException("message content is required");
        if (content.isTextual()) return new Message(message.role(), content.asText());
        if (!content.isArray() || content.isEmpty())
            throw new IllegalArgumentException("message content must be text or a non-empty array");
        List<ContentPart> parts = new java.util.ArrayList<>();
        for (JsonNode part : content) {
            String type = part.path("type").asText();
            switch (type) {
                case "text" -> parts.add(new TextPart(part.path("text").asText()));
                case "image_url" -> {
                    JsonNode image = part.path("image_url");
                    parts.add(new ImagePart(image.path("url").asText(), image.path("detail").asText(null)));
                }
                case "input_audio" -> {
                    JsonNode audio = part.path("input_audio");
                    parts.add(new AudioPart(audio.path("data").asText(), audio.path("format").asText()));
                }
                case "video_url" -> {
                    JsonNode video = part.path("video_url");
                    parts.add(new VideoPart(video.path("url").asText()));
                }
                case "file" -> parts.add(toFilePart(part.path("file"), false));
                default -> throw new IllegalArgumentException("unsupported message content type: " + type);
            }
        }
        return new Message(message.role(), parts);
    }

    private static FilePart toFilePart(JsonNode node, boolean responseInput) {
        JsonNode file = node.path("file");
        if (file.isMissingNode() || file.isNull()) file = node;
        String data = file.path("file_data").asText(null);
        String fileId = file.path("file_id").asText(null);
        String url = file.path("file_url").asText(null);
        if (url == null || url.isBlank()) url = file.path("url").asText(null);
        if (fileId != null && !fileId.isBlank()) {
            return FilePart.fileId(fileId, file.path("filename").asText(null));
        }
        String value = data != null && !data.isBlank() ? data : url;
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException((responseInput ? "input_file" : "file")
                    + " requires file_data, file_url or file_id");
        }
        return new FilePart(value, file.path("filename").asText(null));
    }

    private static ToolDefinition toCoreTool(CompletionTool tool) {
        if (!"function".equals(tool.type()) || tool.function() == null) {
            throw new IllegalArgumentException("only function tools are supported");
        }
        CompletionFunction function = tool.function();
        return new ToolDefinition(function.name(), function.description(), function.parameters(),
                Boolean.TRUE.equals(function.strict()));
    }

    private static ToolDefinition toResponsesTool(JsonNode tool) {
        if (!"function".equals(tool.path("type").asText("function"))) {
            throw new IllegalArgumentException("unsupported Responses tool type: " + tool.path("type").asText());
        }
        return new ToolDefinition(tool.path("name").asText(), tool.path("description").asText(null),
                tool.path("parameters").isObject()
                        ? JSON.convertValue(tool.path("parameters"),
                        new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {
                        })
                        : Map.of(),
                tool.path("strict").asBoolean(false));
    }

    private static ToolChoice toResponsesToolChoice(JsonNode choice) {
        if (choice.isTextual()) {
            return switch (choice.asText()) {
                case "auto" -> ToolChoice.auto();
                case "none" -> ToolChoice.none();
                case "required" -> ToolChoice.required();
                default -> throw new IllegalArgumentException("unsupported tool_choice: " + choice);
            };
        }
        if ("function".equals(choice.path("type").asText()) && choice.path("name").isTextual()) {
            return ToolChoice.named(choice.path("name").asText());
        }
        throw new IllegalArgumentException("tool_choice must be auto, none, required or a named function");
    }

    private static ToolChoice toCoreToolChoice(JsonNode choice) {
        if (choice.isTextual()) {
            return switch (choice.asText()) {
                case "auto" -> ToolChoice.auto();
                case "none" -> ToolChoice.none();
                case "required" -> ToolChoice.required();
                default -> throw new IllegalArgumentException("unsupported tool_choice: " + choice.asText());
            };
        }
        if (!choice.isObject() || !"function".equals(choice.path("type").asText())) {
            throw new IllegalArgumentException("tool_choice must be auto, none, required or a named function");
        }
        return ToolChoice.named(choice.path("function").path("name").asText());
    }

    private static ResponseFormat toCoreResponseFormat(JsonNode value) {
        String type = value.path("type").asText();
        return switch (type) {
            case "text" -> ResponseFormat.text();
            case "json_object" -> ResponseFormat.jsonObject();
            case "json_schema" -> {
                JsonNode schema = value.path("json_schema");
                if (!schema.isObject())
                    throw new IllegalArgumentException("json_schema response_format requires json_schema");
                Map<String, Object> definition = new com.fasterxml.jackson.databind.ObjectMapper()
                        .convertValue(schema.path("schema"), Map.class);
                yield new ResponseFormat(ResponseFormat.Type.JSON_SCHEMA,
                        schema.path("name").asText(), schema.path("description").asText(null), definition,
                        schema.has("strict") ? schema.path("strict").asBoolean() : null);
            }
            default -> throw new IllegalArgumentException("unsupported response_format: " + type);
        };
    }

    public static final class CompletionRequest {
        @JsonProperty("model")
        private String model;
        @JsonProperty("messages")
        private List<CompletionMessage> messages;
        @JsonProperty("stream")
        private Boolean stream;
        @JsonProperty("temperature")
        private Double temperature;
        @JsonProperty("top_p")
        private Double topP;
        @JsonProperty("max_tokens")
        private Integer maxTokens;
        @JsonProperty("max_completion_tokens")
        private Integer maxCompletionTokens;
        @JsonProperty("stop")
        private List<String> stop;
        @JsonProperty("seed")
        private Long seed;
        @JsonProperty("n")
        private Integer n;
        @JsonProperty("logprobs")
        private Boolean logprobs;
        @JsonProperty("user")
        private String user;
        @JsonProperty("tools")
        private List<CompletionTool> tools;
        @JsonProperty("tool_choice")
        private JsonNode toolChoice;
        @JsonProperty("response_format")
        private JsonNode responseFormat;
        @JsonProperty("stream_options")
        private CompletionStreamOptions streamOptions;

        public CompletionRequest() {
        }

        public CompletionRequest(String model, List<CompletionMessage> messages, Boolean stream,
                                 Double temperature, Double topP, Integer maxTokens,
                                 Integer maxCompletionTokens, List<String> stop, Long seed, Integer n,
                                 Boolean logprobs, String user, List<CompletionTool> tools,
                                 JsonNode toolChoice, JsonNode responseFormat,
                                 CompletionStreamOptions streamOptions) {
            this.model = model;
            this.messages = messages;
            this.stream = stream;
            this.temperature = temperature;
            this.topP = topP;
            this.maxTokens = maxTokens;
            this.maxCompletionTokens = maxCompletionTokens;
            this.stop = stop;
            this.seed = seed;
            this.n = n;
            this.logprobs = logprobs;
            this.user = user;
            this.tools = tools;
            this.toolChoice = toolChoice;
            this.responseFormat = responseFormat;
            this.streamOptions = streamOptions;
        }

        public CompletionRequest(String model, List<CompletionMessage> messages, Boolean stream) {
            this(model, messages, stream, null, null, null, null, List.of(),
                    null, null, null, null, List.of(), null, null, null);
        }

        public String model() {
            return model;
        }

        public List<CompletionMessage> messages() {
            return messages;
        }

        public Boolean stream() {
            return stream;
        }

        public Double temperature() {
            return temperature;
        }

        public Double topP() {
            return topP;
        }

        public Integer maxTokens() {
            return maxTokens;
        }

        public Integer maxCompletionTokens() {
            return maxCompletionTokens;
        }

        public List<String> stop() {
            return stop;
        }

        public Long seed() {
            return seed;
        }

        public Integer n() {
            return n;
        }

        public Boolean logprobs() {
            return logprobs;
        }

        public String user() {
            return user;
        }

        public List<CompletionTool> tools() {
            return tools;
        }

        public JsonNode toolChoice() {
            return toolChoice;
        }

        public JsonNode responseFormat() {
            return responseFormat;
        }

        public CompletionStreamOptions streamOptions() {
            return streamOptions;
        }
    }

    public static final class CompletionMessage {
        @JsonProperty("role")
        private String role;
        @JsonProperty("content")
        private JsonNode content;
        @JsonProperty("tool_calls")
        private List<CompletionToolCall> toolCalls;
        @JsonProperty("tool_call_id")
        private String toolCallId;

        public CompletionMessage() {
        }

        public CompletionMessage(String role, JsonNode content, List<CompletionToolCall> toolCalls, String toolCallId) {
            this.role = role;
            this.content = content;
            this.toolCalls = toolCalls;
            this.toolCallId = toolCallId;
        }

        public CompletionMessage(String role, String content) {
            this(role, TextNode.valueOf(content), List.of(), null);
        }

        public CompletionMessage(String role, JsonNode content) {
            this(role, content, List.of(), null);
        }

        public String role() {
            return role;
        }

        public JsonNode content() {
            return content;
        }

        public List<CompletionToolCall> toolCalls() {
            return toolCalls;
        }

        public String toolCallId() {
            return toolCallId;
        }
    }

    public static final class CompletionTool {
        @JsonProperty("type")
        private String type;
        @JsonProperty("function")
        private CompletionFunction function;

        public CompletionTool() {
        }

        public CompletionTool(String type, CompletionFunction function) {
            this.type = type;
            this.function = function;
        }

        public String type() {
            return type;
        }

        public CompletionFunction function() {
            return function;
        }
    }

    public static final class CompletionFunction {
        @JsonProperty("name")
        private String name;
        @JsonProperty("description")
        private String description;
        @JsonProperty("parameters")
        private Map<String, Object> parameters;
        @JsonProperty("strict")
        private Boolean strict;

        public CompletionFunction() {
        }

        public CompletionFunction(String name, String description, Map<String, Object> parameters, Boolean strict) {
            this.name = name;
            this.description = description;
            this.parameters = parameters;
            this.strict = strict;
        }

        public String name() {
            return name;
        }

        public String description() {
            return description;
        }

        public Map<String, Object> parameters() {
            return parameters;
        }

        public Boolean strict() {
            return strict;
        }
    }

    public static final class CompletionToolCall {
        @JsonProperty("id")
        private String id;
        @JsonProperty("type")
        private String type;
        @JsonProperty("function")
        private CompletionFunctionCall function;

        public CompletionToolCall() {
        }

        public CompletionToolCall(String id, String type, CompletionFunctionCall function) {
            this.id = id;
            this.type = type;
            this.function = function;
        }

        public String id() {
            return id;
        }

        public String type() {
            return type;
        }

        public CompletionFunctionCall function() {
            return function;
        }
    }

    public static final class CompletionFunctionCall {
        @JsonProperty("name")
        private String name;
        @JsonProperty("arguments")
        private String arguments;

        public CompletionFunctionCall() {
        }

        public CompletionFunctionCall(String name, String arguments) {
            this.name = name;
            this.arguments = arguments;
        }

        public String name() {
            return name;
        }

        public String arguments() {
            return arguments;
        }
    }

    public static final class CompletionStreamOptions {
        @JsonProperty("include_usage")
        private Boolean includeUsage;

        public CompletionStreamOptions() {
        }

        public CompletionStreamOptions(Boolean includeUsage) {
            this.includeUsage = includeUsage;
        }

        public Boolean includeUsage() {
            return includeUsage;
        }
    }

    public static final class ResponsesRequest {
        @JsonProperty("model")
        private String model;
        @JsonProperty("input")
        private JsonNode input;
        @JsonProperty("stream")
        private Boolean stream;
        @JsonProperty("temperature")
        private Double temperature;
        @JsonProperty("max_output_tokens")
        private Integer maxOutputTokens;
        @JsonProperty("tools")
        private List<JsonNode> tools;
        @JsonProperty("tool_choice")
        private JsonNode toolChoice;
        @JsonProperty("instructions")
        private JsonNode instructions;
        @JsonProperty("previous_response_id")
        private String previousResponseId;
        @JsonProperty("store")
        private Boolean store;
        @JsonProperty("metadata")
        private Map<String, String> metadata;
        @JsonProperty("reasoning")
        private JsonNode reasoning;
        @JsonProperty("include")
        private List<String> include;
        @JsonProperty("background")
        private Boolean background;

        public ResponsesRequest() {
        }

        public ResponsesRequest(String model, JsonNode input, Boolean stream, Double temperature,
                                Integer maxOutputTokens, List<JsonNode> tools, JsonNode toolChoice,
                                JsonNode instructions, String previousResponseId, Boolean store,
                                Map<String, String> metadata, JsonNode reasoning,
                                List<String> include, Boolean background) {
            this.model = model;
            this.input = input;
            this.stream = stream;
            this.temperature = temperature;
            this.maxOutputTokens = maxOutputTokens;
            this.tools = tools;
            this.toolChoice = toolChoice;
            this.instructions = instructions;
            this.previousResponseId = previousResponseId;
            this.store = store;
            this.metadata = metadata;
            this.reasoning = reasoning;
            this.include = include;
            this.background = background;
        }

        public ResponsesRequest(String model, JsonNode input, Boolean stream,
                                Double temperature, Integer maxOutputTokens) {
            this(model, input, stream, temperature, maxOutputTokens, List.of(), null,
                    null, null, null, null, null, null, null);
        }

        public ResponsesRequest(String model, JsonNode input, Boolean stream,
                                Double temperature, Integer maxOutputTokens,
                                List<JsonNode> tools, JsonNode toolChoice) {
            this(model, input, stream, temperature, maxOutputTokens, tools, toolChoice,
                    null, null, null, null, null, null, null);
        }

        public String model() {
            return model;
        }

        public JsonNode input() {
            return input;
        }

        public Boolean stream() {
            return stream;
        }

        public Double temperature() {
            return temperature;
        }

        public Integer maxOutputTokens() {
            return maxOutputTokens;
        }

        public List<JsonNode> tools() {
            return tools;
        }

        public JsonNode toolChoice() {
            return toolChoice;
        }

        public JsonNode instructions() {
            return instructions;
        }

        public String previousResponseId() {
            return previousResponseId;
        }

        public Boolean store() {
            return store;
        }

        public Map<String, String> metadata() {
            return metadata;
        }

        public JsonNode reasoning() {
            return reasoning;
        }

        public List<String> include() {
            return include;
        }

        public Boolean background() {
            return background;
        }
    }
}
