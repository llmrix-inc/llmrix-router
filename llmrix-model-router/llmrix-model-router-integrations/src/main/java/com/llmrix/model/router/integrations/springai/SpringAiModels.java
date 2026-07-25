package com.llmrix.model.router.integrations.springai;

import com.llmrix.model.router.core.api.ChatModel;
import com.llmrix.model.router.core.api.ChatRequest;
import com.llmrix.model.router.core.api.ChatResponse;
import com.llmrix.model.router.core.api.ChatChunk;
import com.llmrix.model.router.core.api.Message;
import com.llmrix.model.router.core.api.Usage;
import com.llmrix.model.router.core.api.ContentPart;
import com.llmrix.model.router.core.api.TextPart;
import com.llmrix.model.router.core.api.ToolCallPart;
import com.llmrix.model.router.core.api.ToolResultPart;
import com.llmrix.model.router.core.api.ToolDefinition;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.List;
import java.util.Map;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicBoolean;
import reactor.adapter.JdkFlowAdapter;
import reactor.core.publisher.Flux;

public final class SpringAiModels {
    private static final ObjectMapper JSON = new ObjectMapper();
    private SpringAiModels() {}

    public static ChatModel adapt(org.springframework.ai.chat.model.ChatModel delegate) {
        return request -> fromSpring(delegate.call(toSpring(request)));
    }

    public static ChatModel adapt(
            org.springframework.ai.chat.model.ChatModel delegate,
            org.springframework.ai.chat.model.StreamingChatModel streaming) {
        return new ChatModel() {
            @Override public ChatResponse chat(ChatRequest request) {
                return fromSpring(delegate.call(toSpring(request)));
            }

            @Override public Flow.Publisher<ChatChunk> stream(ChatRequest request) {
                AtomicReference<Usage> lastUsage = new AtomicReference<>(Usage.UNKNOWN);
                AtomicBoolean sawToolCalls = new AtomicBoolean();
                Flux<ChatChunk> chunks = streaming.stream(toSpring(request)).map(response -> {
                    ChatResponse converted = fromSpring(response);
                    if (converted.usage().inputTokens() >= 0 || converted.usage().outputTokens() >= 0) {
                        lastUsage.set(converted.usage());
                    }
                    List<com.llmrix.model.router.core.api.ToolCallDelta> deltas = new java.util.ArrayList<>();
                    for (int index = 0; index < converted.toolCalls().size(); index++) {
                        ToolCallPart call = converted.toolCalls().get(index);
                        deltas.add(new com.llmrix.model.router.core.api.ToolCallDelta(
                                index, call.id(), call.name(), call.arguments()));
                    }
                    if (!deltas.isEmpty()) sawToolCalls.set(true);
                    return new ChatChunk(converted.text(), false, converted.usage(), deltas);
                }).concatWith(Flux.defer(() -> Flux.just(
                        new ChatChunk("", true, lastUsage.get(), List.of(),
                                sawToolCalls.get() ? "tool_calls" : "stop"))));
                return JdkFlowAdapter.publisherToFlowPublisher(chunks);
            }
        };
    }

    public static ChatModel adaptObject(Object delegate) {
        if (!(delegate instanceof org.springframework.ai.chat.model.ChatModel chatModel)) {
            throw new IllegalArgumentException("bean does not implement Spring AI ChatModel: " + delegate.getClass().getName());
        }
        if (delegate instanceof org.springframework.ai.chat.model.StreamingChatModel streaming) {
            return adapt(chatModel, streaming);
        }
        return adapt(chatModel);
    }

    public static org.springframework.ai.chat.model.ChatModel expose(ChatModel delegate) {
        return prompt -> toSpring(delegate.chat(fromSpring(prompt)));
    }

    private static Prompt toSpring(ChatRequest request) {
        rejectUnsupportedGenerationOptions(request.generationOptions());
        if (request.responseFormat() != null) throw new UnsupportedOperationException(
                "Spring AI generic ChatOptions cannot represent Core response format");
        Map<String, String> toolNames = new java.util.HashMap<>();
        request.messages().forEach(message -> message.contents().stream()
                .filter(ToolCallPart.class::isInstance).map(ToolCallPart.class::cast)
                .forEach(call -> toolNames.put(call.id(), call.name())));
        List<org.springframework.ai.chat.messages.Message> messages = request.messages().stream()
                .map(message -> toSpring(message, toolNames))
                .toList();
        var options = org.springframework.ai.model.tool.ToolCallingChatOptions.builder();
        if (request.generationOptions().temperature() != null) options.temperature(request.generationOptions().temperature());
        if (request.generationOptions().topP() != null) options.topP(request.generationOptions().topP());
        if (request.generationOptions().maxOutputTokens() != null) options.maxTokens(request.generationOptions().maxOutputTokens());
        if (!request.generationOptions().stop().isEmpty()) options.stopSequences(request.generationOptions().stop());
        boolean sendTools = !request.tools().isEmpty();
        if (request.toolChoice() != null) {
            switch (request.toolChoice().mode()) {
                case AUTO -> { }
                case NONE -> sendTools = false;
                case REQUIRED, NAMED -> throw new UnsupportedOperationException(
                        "Spring AI 1.1 ToolCallingChatOptions does not support required or named tool choice");
            }
        }
        if (sendTools) {
            options.toolCallbacks(request.tools().stream().map(SpringAiModels::toolCallback).toList())
                    .internalToolExecutionEnabled(false);
        }
        return new Prompt(messages, options.build());
    }

    private static org.springframework.ai.chat.messages.Message toSpring(
            Message message, Map<String, String> toolNames) {
        if ("assistant".equals(message.role()) && message.contents().stream().anyMatch(ToolCallPart.class::isInstance)) {
            return AssistantMessage.builder().content(message.content()).toolCalls(message.contents().stream()
                    .filter(ToolCallPart.class::isInstance).map(ToolCallPart.class::cast)
                    .map(call -> new AssistantMessage.ToolCall(
                            call.id(), "function", call.name(), call.arguments())).toList()).build();
        }
        if ("tool".equals(message.role())) {
            ToolResultPart result = (ToolResultPart) message.contents().get(0);
            return org.springframework.ai.chat.messages.ToolResponseMessage.builder()
                    .responses(List.of(new org.springframework.ai.chat.messages.ToolResponseMessage.ToolResponse(
                            result.toolCallId(), toolNames.getOrDefault(result.toolCallId(), "unknown"),
                            result.result()))).build();
        }
        if (!message.textOnly()) throw new UnsupportedOperationException(
                "multimodal core messages are not supported by the Spring AI adapter yet");
        return switch (message.role()) {
            case "system" -> new SystemMessage(message.content());
            case "assistant" -> new AssistantMessage(message.content());
            default -> new UserMessage(message.content());
        };
    }

    private static ChatRequest fromSpring(Prompt prompt) {
        ChatRequest.Builder builder = ChatRequest.builder();
        for (org.springframework.ai.chat.messages.Message message : prompt.getInstructions()) {
            builder.message(fromSpring(message));
        }
        if (prompt.getOptions() instanceof org.springframework.ai.model.tool.ToolCallingChatOptions options
                && options.getToolCallbacks() != null && !options.getToolCallbacks().isEmpty()) {
            builder.tools(options.getToolCallbacks().stream().map(callback -> {
                org.springframework.ai.tool.definition.ToolDefinition definition = callback.getToolDefinition();
                return new ToolDefinition(definition.name(), definition.description(), readSchema(definition.inputSchema()), false);
            }).toList());
        }
        org.springframework.ai.chat.prompt.ChatOptions options = prompt.getOptions();
        if (options != null) {
            builder.generationOptions(com.llmrix.model.router.core.api.GenerationOptions.builder()
                    .temperature(options.getTemperature()).topP(options.getTopP())
                    .maxOutputTokens(options.getMaxTokens())
                    .stop(options.getStopSequences() == null ? new String[0]
                            : options.getStopSequences().toArray(String[]::new)).build());
        }
        return builder.build();
    }

    private static Message fromSpring(org.springframework.ai.chat.messages.Message message) {
        if (message instanceof AssistantMessage assistant && assistant.hasToolCalls()) {
            List<ContentPart> parts = new java.util.ArrayList<>();
            if (assistant.getText() != null && !assistant.getText().isEmpty()) parts.add(new TextPart(assistant.getText()));
            assistant.getToolCalls().forEach(call -> parts.add(
                    new ToolCallPart(call.id(), call.name(), call.arguments())));
            return new Message("assistant", parts);
        }
        if (message instanceof org.springframework.ai.chat.messages.ToolResponseMessage tools) {
            if (tools.getResponses().size() != 1) throw new UnsupportedOperationException(
                    "Core tool messages support exactly one Spring AI tool response");
            var response = tools.getResponses().get(0);
            return Message.tool(response.id(), response.responseData());
        }
        return new Message(message.getMessageType().getValue(), message.getText());
    }

    private static ChatResponse fromSpring(org.springframework.ai.chat.model.ChatResponse response) {
        AssistantMessage output = response.getResult() == null ? null : response.getResult().getOutput();
        String text = output == null || output.getText() == null ? "" : output.getText();
        ChatResponseMetadata metadata = response.getMetadata();
        org.springframework.ai.chat.metadata.Usage usage = metadata == null ? null : metadata.getUsage();
        Usage coreUsage = usage == null ? Usage.UNKNOWN : new Usage(
                valueOrUnknown(usage.getPromptTokens()), valueOrUnknown(usage.getCompletionTokens()));
        String model = metadata == null ? null : metadata.getModel();
        List<ToolCallPart> calls = output == null ? List.of() : output.getToolCalls().stream()
                .map(call -> new ToolCallPart(call.id(), call.name(), call.arguments())).toList();
        return new ChatResponse(text, model, coreUsage, Map.of("framework", "spring-ai"), calls);
    }

    private static org.springframework.ai.tool.ToolCallback toolCallback(ToolDefinition definition) {
        org.springframework.ai.tool.definition.ToolDefinition springDefinition =
                org.springframework.ai.tool.definition.ToolDefinition.builder()
                        .name(definition.name())
                        .description(definition.description() == null ? "" : definition.description())
                        .inputSchema(writeSchema(definition.parameters()))
                        .build();
        return new org.springframework.ai.tool.ToolCallback() {
            @Override public org.springframework.ai.tool.definition.ToolDefinition getToolDefinition() {
                return springDefinition;
            }
            @Override public String call(String arguments) {
                throw new IllegalStateException("LLM Router tool definitions are declaration-only");
            }
        };
    }

    private static String writeSchema(Map<String, Object> schema) {
        try { return JSON.writeValueAsString(schema); }
        catch (JsonProcessingException error) { throw new IllegalArgumentException("invalid tool schema", error); }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> readSchema(String schema) {
        try { return JSON.readValue(schema, Map.class); }
        catch (JsonProcessingException error) { throw new IllegalArgumentException("invalid Spring AI tool schema", error); }
    }

    private static org.springframework.ai.chat.model.ChatResponse toSpring(ChatResponse response) {
        ChatResponseMetadata.Builder metadata = ChatResponseMetadata.builder();
        if (response.modelId() != null) metadata.model(response.modelId());
        if (response.usage().inputTokens() >= 0 && response.usage().outputTokens() >= 0) {
            metadata.usage(new SpringUsage(response.usage()));
        }
        return new org.springframework.ai.chat.model.ChatResponse(
                List.of(new Generation(new AssistantMessage(response.text()))), metadata.build());
    }

    private static int valueOrUnknown(Integer value) {
        return value == null ? -1 : value;
    }

    private static void rejectUnsupportedGenerationOptions(
            com.llmrix.model.router.core.api.GenerationOptions options) {
        if (options.seed() != null || options.candidateCount() != null
                || options.logprobs() != null || options.user() != null) {
            throw new UnsupportedOperationException(
                    "Spring AI generic ChatOptions cannot represent seed, n, logprobs or user");
        }
    }

    private record SpringUsage(Usage usage) implements org.springframework.ai.chat.metadata.Usage {
        @Override public Integer getPromptTokens() { return Math.toIntExact(usage.inputTokens()); }
        @Override public Integer getCompletionTokens() { return Math.toIntExact(usage.outputTokens()); }
        @Override public Object getNativeUsage() { return null; }
    }
}
