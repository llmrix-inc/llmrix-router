package com.llmrix.model.router.integrations.langchain4j;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponseMetadata;
import dev.langchain4j.model.output.TokenUsage;
import com.llmrix.model.router.core.api.ChatModel;
import com.llmrix.model.router.core.api.Message;
import com.llmrix.model.router.core.api.Usage;
import com.llmrix.model.router.core.api.ChatChunk;
import com.llmrix.model.router.core.api.ContentPart;
import com.llmrix.model.router.core.api.TextPart;
import com.llmrix.model.router.core.api.ToolCallPart;
import com.llmrix.model.router.core.api.ToolResultPart;
import com.llmrix.model.router.core.api.ToolDefinition;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;
import java.util.concurrent.Flow;
import java.util.concurrent.SubmissionPublisher;
import java.util.concurrent.atomic.AtomicBoolean;

public final class LangChain4jModels {
    private static final ObjectMapper JSON = new ObjectMapper();
    private LangChain4jModels() {}

    public static ChatModel adapt(dev.langchain4j.model.chat.ChatModel delegate) {
        return request -> fromLangChain4j(delegate.chat(toLangChain4j(request)));
    }

    public static ChatModel adapt(
            dev.langchain4j.model.chat.ChatModel delegate,
            dev.langchain4j.model.chat.StreamingChatModel streaming) {
        return new ChatModel() {
            @Override public com.llmrix.model.router.core.api.ChatResponse chat(
                    com.llmrix.model.router.core.api.ChatRequest request) {
                return fromLangChain4j(delegate.chat(toLangChain4j(request)));
            }

            @Override public Flow.Publisher<ChatChunk> stream(com.llmrix.model.router.core.api.ChatRequest request) {
                return downstream -> {
                    SubmissionPublisher<ChatChunk> publisher = new SubmissionPublisher<>();
                    AtomicBoolean cancelled = new AtomicBoolean();
                    AtomicBoolean sawToolCalls = new AtomicBoolean();
                    java.util.Set<Integer> emittedToolCalls = java.util.concurrent.ConcurrentHashMap.newKeySet();
                    publisher.subscribe(new Flow.Subscriber<>() {
                        @Override public void onSubscribe(Flow.Subscription subscription) {
                            downstream.onSubscribe(new Flow.Subscription() {
                                @Override public void request(long n) { subscription.request(n); }
                                @Override public void cancel() {
                                    cancelled.set(true);
                                    subscription.cancel();
                                    publisher.close();
                                }
                            });
                        }
                        @Override public void onNext(ChatChunk item) { downstream.onNext(item); }
                        @Override public void onError(Throwable throwable) { downstream.onError(throwable); }
                        @Override public void onComplete() { downstream.onComplete(); }
                    });
                    try {
                        streaming.chat(toLangChain4j(request),
                                new dev.langchain4j.model.chat.response.StreamingChatResponseHandler() {
                                    @Override public void onPartialResponse(String partialResponse) {
                                        if (!cancelled.get()) publisher.submit(
                                                new ChatChunk(partialResponse, false, Usage.UNKNOWN));
                                    }
                                    @Override public void onPartialToolCall(
                                            dev.langchain4j.model.chat.response.PartialToolCall call) {
                                        if (!cancelled.get()) {
                                            sawToolCalls.set(true);
                                            emittedToolCalls.add(call.index());
                                            publisher.submit(new ChatChunk("", false, Usage.UNKNOWN, List.of(
                                                    new com.llmrix.model.router.core.api.ToolCallDelta(
                                                            call.index(), call.id(), call.name(),
                                                            call.partialArguments()))));
                                        }
                                    }
                                    @Override public void onCompleteToolCall(
                                            dev.langchain4j.model.chat.response.CompleteToolCall call) {
                                        submitComplete(call.index(), call.toolExecutionRequest());
                                    }
                                    @Override public void onCompleteResponse(
                                            dev.langchain4j.model.chat.response.ChatResponse response) {
                                        if (!cancelled.get()) {
                                            List<dev.langchain4j.agent.tool.ToolExecutionRequest> calls =
                                                    response.aiMessage().toolExecutionRequests();
                                            for (int index = 0; index < calls.size(); index++) {
                                                submitComplete(index, calls.get(index));
                                            }
                                        }
                                        if (cancelled.compareAndSet(false, true)) {
                                            publisher.submit(new ChatChunk("", true,
                                                    fromLangChain4j(response).usage(), List.of(),
                                                    sawToolCalls.get() ? "tool_calls" : "stop"));
                                            publisher.close();
                                        }
                                    }
                                    @Override public void onError(Throwable error) {
                                        if (cancelled.compareAndSet(false, true)) {
                                            publisher.closeExceptionally(error);
                                        }
                                    }
                                    private void submitComplete(
                                            int index, dev.langchain4j.agent.tool.ToolExecutionRequest call) {
                                        if (!cancelled.get() && emittedToolCalls.add(index)) {
                                            sawToolCalls.set(true);
                                            publisher.submit(new ChatChunk("", false, Usage.UNKNOWN, List.of(
                                                    new com.llmrix.model.router.core.api.ToolCallDelta(
                                                            index, call.id(), call.name(), call.arguments()))));
                                        }
                                    }
                                });
                    } catch (RuntimeException error) {
                        if (cancelled.compareAndSet(false, true)) publisher.closeExceptionally(error);
                    }
                };
            }
        };
    }

    public static ChatModel adaptObject(Object delegate) {
        if (!(delegate instanceof dev.langchain4j.model.chat.ChatModel chatModel)) {
            throw new IllegalArgumentException("bean does not implement LangChain4j ChatModel: " + delegate.getClass().getName());
        }
        if (delegate instanceof dev.langchain4j.model.chat.StreamingChatModel streaming) {
            return adapt(chatModel, streaming);
        }
        return adapt(chatModel);
    }

    public static dev.langchain4j.model.chat.ChatModel expose(ChatModel delegate) {
        return new dev.langchain4j.model.chat.ChatModel() {
            @Override
            public dev.langchain4j.model.chat.response.ChatResponse doChat(ChatRequest request) {
                return toLangChain4j(delegate.chat(fromLangChain4j(request)));
            }
        };
    }

    private static ChatRequest toLangChain4j(com.llmrix.model.router.core.api.ChatRequest request) {
        rejectUnsupportedGenerationOptions(request.generationOptions(), "LangChain4j");
        Map<String, String> toolNames = new java.util.HashMap<>();
        request.messages().forEach(message -> message.contents().stream()
                .filter(ToolCallPart.class::isInstance).map(ToolCallPart.class::cast)
                .forEach(call -> toolNames.put(call.id(), call.name())));
        ChatRequest.Builder builder = ChatRequest.builder().messages(request.messages().stream()
                .map(message -> toLangChain4j(message, toolNames)).toList());
        var generation = request.generationOptions();
        if (generation.temperature() != null) builder.temperature(generation.temperature());
        if (generation.topP() != null) builder.topP(generation.topP());
        if (generation.maxOutputTokens() != null) builder.maxOutputTokens(generation.maxOutputTokens());
        if (!generation.stop().isEmpty()) builder.stopSequences(generation.stop());
        if (!request.tools().isEmpty()) {
            builder.toolSpecifications(request.tools().stream().map(LangChain4jModels::toLangChain4j).toList());
        }
        if (request.toolChoice() != null) {
            builder.toolChoice(switch (request.toolChoice().mode()) {
                case AUTO -> dev.langchain4j.model.chat.request.ToolChoice.AUTO;
                case NONE -> dev.langchain4j.model.chat.request.ToolChoice.NONE;
                case REQUIRED -> dev.langchain4j.model.chat.request.ToolChoice.REQUIRED;
                case NAMED -> throw new UnsupportedOperationException(
                        "LangChain4j 1.12 does not support named tool choice");
            });
        }
        if (request.responseFormat() != null) builder.responseFormat(toLangChain4j(request.responseFormat()));
        return builder.build();
    }

    private static ChatMessage toLangChain4j(Message message, Map<String, String> toolNames) {
        if ("assistant".equals(message.role()) && message.contents().stream().anyMatch(ToolCallPart.class::isInstance)) {
            List<dev.langchain4j.agent.tool.ToolExecutionRequest> calls = message.contents().stream()
                    .filter(ToolCallPart.class::isInstance).map(ToolCallPart.class::cast)
                    .map(call -> dev.langchain4j.agent.tool.ToolExecutionRequest.builder()
                            .id(call.id()).name(call.name()).arguments(call.arguments()).build()).toList();
            return AiMessage.from(message.content(), calls);
        }
        if ("tool".equals(message.role())) {
            ToolResultPart result = (ToolResultPart) message.contents().get(0);
            return dev.langchain4j.data.message.ToolExecutionResultMessage.from(
                    result.toolCallId(), toolNames.getOrDefault(result.toolCallId(), "unknown"), result.result());
        }
        if (!message.textOnly()) throw new UnsupportedOperationException(
                "multimodal core messages are not supported by the LangChain4j adapter yet");
        return switch (message.role()) {
            case "system" -> SystemMessage.from(message.content());
            case "assistant" -> AiMessage.from(message.content());
            default -> UserMessage.from(message.content());
        };
    }

    private static com.llmrix.model.router.core.api.ChatRequest fromLangChain4j(ChatRequest request) {
        com.llmrix.model.router.core.api.ChatRequest.Builder builder = com.llmrix.model.router.core.api.ChatRequest.builder();
        request.messages().forEach(message -> builder.message(fromLangChain4j(message)));
        builder.generationOptions(com.llmrix.model.router.core.api.GenerationOptions.builder()
                .temperature(request.temperature()).topP(request.topP())
                .maxOutputTokens(request.maxOutputTokens())
                .stop(request.stopSequences() == null ? new String[0]
                        : request.stopSequences().toArray(String[]::new)).build());
        if (request.toolSpecifications() != null && !request.toolSpecifications().isEmpty()) {
            builder.tools(request.toolSpecifications().stream().map(specification -> new ToolDefinition(
                    specification.name(), specification.description(), fromLangChain4j(specification.parameters()), false
            )).toList());
        }
        if (request.toolChoice() != null) {
            builder.toolChoice(switch (request.toolChoice()) {
                case AUTO -> com.llmrix.model.router.core.api.ToolChoice.auto();
                case NONE -> com.llmrix.model.router.core.api.ToolChoice.none();
                case REQUIRED -> com.llmrix.model.router.core.api.ToolChoice.required();
            });
        }
        if (request.responseFormat() != null) builder.responseFormat(fromLangChain4j(request.responseFormat()));
        return builder.build();
    }

    private static Message fromLangChain4j(ChatMessage message) {
        return switch (message.type()) {
            case SYSTEM -> Message.system(((SystemMessage) message).text());
            case USER -> {
                UserMessage user = (UserMessage) message;
                if (!user.hasSingleText()) throw new UnsupportedOperationException("multimodal LangChain4j messages are not supported yet");
                yield Message.user(user.singleText());
            }
            case AI -> {
                AiMessage ai = (AiMessage) message;
                if (!ai.hasToolExecutionRequests()) yield Message.assistant(ai.text() == null ? "" : ai.text());
                List<ContentPart> parts = new java.util.ArrayList<>();
                if (ai.text() != null && !ai.text().isEmpty()) parts.add(new TextPart(ai.text()));
                ai.toolExecutionRequests().forEach(call -> parts.add(
                        new ToolCallPart(call.id(), call.name(), call.arguments())));
                yield new Message("assistant", parts);
            }
            case TOOL_EXECUTION_RESULT -> {
                var result = (dev.langchain4j.data.message.ToolExecutionResultMessage) message;
                yield Message.tool(result.id(), result.text());
            }
            default -> throw new UnsupportedOperationException("unsupported LangChain4j message type: " + message.type());
        };
    }

    private static com.llmrix.model.router.core.api.ChatResponse fromLangChain4j(
            dev.langchain4j.model.chat.response.ChatResponse response) {
        TokenUsage usage = response.tokenUsage();
        Usage coreUsage = usage == null ? Usage.UNKNOWN : new Usage(
                valueOrUnknown(usage.inputTokenCount()), valueOrUnknown(usage.outputTokenCount()));
        return new com.llmrix.model.router.core.api.ChatResponse(
                response.aiMessage().text() == null ? "" : response.aiMessage().text(),
                response.modelName(), coreUsage, Map.of("framework", "langchain4j"),
                response.aiMessage().toolExecutionRequests().stream().map(call ->
                        new ToolCallPart(call.id(), call.name(), call.arguments())).toList());
    }

    private static dev.langchain4j.agent.tool.ToolSpecification toLangChain4j(ToolDefinition tool) {
        var parameters = dev.langchain4j.model.chat.request.json.JsonObjectSchema.builder();
        Object properties = tool.parameters().get("properties");
        if (properties instanceof Map<?, ?> values) values.forEach((name, schema) ->
                parameters.addProperty(String.valueOf(name),
                        dev.langchain4j.model.chat.request.json.JsonRawSchema.from(writeJson(schema))));
        Object required = tool.parameters().get("required");
        if (required instanceof List<?> values) parameters.required(values.stream().map(String::valueOf).toList());
        Object additional = tool.parameters().get("additionalProperties");
        if (additional instanceof Boolean value) parameters.additionalProperties(value);
        var builder = dev.langchain4j.agent.tool.ToolSpecification.builder()
                .name(tool.name()).parameters(parameters.build());
        if (tool.description() != null) builder.description(tool.description());
        return builder.build();
    }

    private static Map<String, Object> fromLangChain4j(
            dev.langchain4j.model.chat.request.json.JsonObjectSchema schema) {
        Map<String, Object> result = new java.util.LinkedHashMap<>();
        result.put("type", "object");
        if (schema.description() != null) result.put("description", schema.description());
        Map<String, Object> properties = new java.util.LinkedHashMap<>();
        if (schema.properties() != null) schema.properties().forEach((name, value) ->
                properties.put(name, fromLangChain4j(
                        (dev.langchain4j.model.chat.request.json.JsonSchemaElement) value)));
        if (!properties.isEmpty()) result.put("properties", properties);
        if (schema.required() != null && !schema.required().isEmpty()) result.put("required", schema.required());
        if (schema.additionalProperties() != null) result.put("additionalProperties", schema.additionalProperties());
        return result;
    }

    private static Object fromLangChain4j(
            dev.langchain4j.model.chat.request.json.JsonSchemaElement schema) {
        if (schema instanceof dev.langchain4j.model.chat.request.json.JsonRawSchema raw) {
            try { return JSON.readValue(raw.schema(), Object.class); }
            catch (JsonProcessingException error) { throw new IllegalArgumentException("invalid raw tool schema", error); }
        }
        Map<String, Object> value = new java.util.LinkedHashMap<>();
        if (schema instanceof dev.langchain4j.model.chat.request.json.JsonObjectSchema object) return fromLangChain4j(object);
        if (schema instanceof dev.langchain4j.model.chat.request.json.JsonStringSchema) value.put("type", "string");
        else if (schema instanceof dev.langchain4j.model.chat.request.json.JsonIntegerSchema) value.put("type", "integer");
        else if (schema instanceof dev.langchain4j.model.chat.request.json.JsonNumberSchema) value.put("type", "number");
        else if (schema instanceof dev.langchain4j.model.chat.request.json.JsonBooleanSchema) value.put("type", "boolean");
        else if (schema instanceof dev.langchain4j.model.chat.request.json.JsonNullSchema) value.put("type", "null");
        else if (schema instanceof dev.langchain4j.model.chat.request.json.JsonEnumSchema enumeration) {
            value.put("type", "string"); value.put("enum", enumeration.enumValues());
        } else if (schema instanceof dev.langchain4j.model.chat.request.json.JsonArraySchema array) {
            value.put("type", "array"); value.put("items", fromLangChain4j(array.items()));
        } else if (schema instanceof dev.langchain4j.model.chat.request.json.JsonAnyOfSchema anyOf) {
            value.put("anyOf", anyOf.anyOf().stream().map(LangChain4jModels::fromLangChain4j).toList());
        } else if (schema instanceof dev.langchain4j.model.chat.request.json.JsonReferenceSchema reference) {
            value.put("$ref", reference.reference());
        } else throw new UnsupportedOperationException("unsupported LangChain4j JSON schema: " + schema.getClass());
        if (schema.description() != null) value.put("description", schema.description());
        return value;
    }

    private static String writeJson(Object value) {
        try { return JSON.writeValueAsString(value); }
        catch (JsonProcessingException error) { throw new IllegalArgumentException("invalid tool schema", error); }
    }

    private static dev.langchain4j.model.chat.request.ResponseFormat toLangChain4j(
            com.llmrix.model.router.core.api.ResponseFormat format) {
        return switch (format.type()) {
            case TEXT -> dev.langchain4j.model.chat.request.ResponseFormat.TEXT;
            case JSON_OBJECT -> dev.langchain4j.model.chat.request.ResponseFormat.JSON;
            case JSON_SCHEMA -> throw new UnsupportedOperationException(
                    "LangChain4j response format cannot preserve Core JSON Schema strict semantics");
        };
    }

    private static com.llmrix.model.router.core.api.ResponseFormat fromLangChain4j(
            dev.langchain4j.model.chat.request.ResponseFormat format) {
        if (format.type() == dev.langchain4j.model.chat.request.ResponseFormatType.TEXT) {
            return com.llmrix.model.router.core.api.ResponseFormat.text();
        }
        if (format.jsonSchema() == null) return com.llmrix.model.router.core.api.ResponseFormat.jsonObject();
        Object schema = fromLangChain4j(format.jsonSchema().rootElement());
        if (!(schema instanceof Map<?, ?> values)) throw new UnsupportedOperationException(
                "Core response JSON Schema root must be an object");
        @SuppressWarnings("unchecked") Map<String, Object> objectSchema = (Map<String, Object>) values;
        return new com.llmrix.model.router.core.api.ResponseFormat(
                com.llmrix.model.router.core.api.ResponseFormat.Type.JSON_SCHEMA,
                format.jsonSchema().name(), null, objectSchema, false);
    }

    private static dev.langchain4j.model.chat.response.ChatResponse toLangChain4j(
            com.llmrix.model.router.core.api.ChatResponse response) {
        ChatResponseMetadata.Builder<?> metadata = ChatResponseMetadata.builder();
        if (response.modelId() != null) metadata.modelName(response.modelId());
        if (response.usage().inputTokens() >= 0 && response.usage().outputTokens() >= 0) {
            metadata.tokenUsage(new TokenUsage(
                    Math.toIntExact(response.usage().inputTokens()),
                    Math.toIntExact(response.usage().outputTokens())));
        }
        return dev.langchain4j.model.chat.response.ChatResponse.builder()
                .aiMessage(AiMessage.from(response.text()))
                .metadata(metadata.build())
                .build();
    }

    private static int valueOrUnknown(Integer value) {
        return value == null ? -1 : value;
    }

    private static void rejectUnsupportedGenerationOptions(
            com.llmrix.model.router.core.api.GenerationOptions options, String framework) {
        if (options.seed() != null || options.candidateCount() != null
                || options.logprobs() != null || options.user() != null) {
            throw new UnsupportedOperationException(framework
                    + " generic ChatModel cannot represent seed, n, logprobs or user");
        }
    }
}
