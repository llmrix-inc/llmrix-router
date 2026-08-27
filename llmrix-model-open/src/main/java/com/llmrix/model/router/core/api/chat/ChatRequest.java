package com.llmrix.model.router.core.api.chat;

import com.llmrix.model.router.core.api.ModelRequest;
import com.llmrix.model.router.core.routing.RoutingHints;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public final class ChatRequest implements ModelRequest {
    private final List<Message> messages;
    private final RoutingHints routingHints;
    private final Integer estimatedInputTokens;
    private final GenerationOptions generationOptions;
    private final List<ToolDefinition> tools;
    private final ToolChoice toolChoice;
    private final ResponseFormat responseFormat;
    private final StreamOptions streamOptions;
    private final PromptCacheOptions promptCache;

    private ChatRequest(Builder builder) {
        if (builder.messages.isEmpty()) {
            throw new IllegalArgumentException("at least one message is required");
        }
        this.messages = List.copyOf(builder.messages);
        this.routingHints = builder.routingHints == null ? RoutingHints.none() : builder.routingHints;
        this.estimatedInputTokens = builder.estimatedInputTokens;
        this.generationOptions = builder.generationOptions == null ? GenerationOptions.DEFAULT : builder.generationOptions;
        this.tools = List.copyOf(builder.tools);
        this.toolChoice = builder.toolChoice;
        this.responseFormat = builder.responseFormat;
        this.streamOptions = builder.streamOptions == null ? StreamOptions.DEFAULT : builder.streamOptions;
        this.promptCache = builder.promptCache;
        if (toolChoice != null && tools.isEmpty()) {
            throw new IllegalArgumentException("toolChoice requires at least one tool");
        }
        java.util.Set<String> toolNames = new java.util.HashSet<>();
        for (ToolDefinition tool : tools) {
            if (!toolNames.add(tool.name())) throw new IllegalArgumentException("duplicate tool name: " + tool.name());
        }
        if (toolChoice != null && toolChoice.mode() == ToolChoice.Mode.NAMED
                && !toolNames.contains(toolChoice.name())) {
            throw new IllegalArgumentException("toolChoice references unknown tool: " + toolChoice.name());
        }
    }

    public static ChatRequest user(String message) {
        return builder().userMessage(message).build();
    }

    public static Builder builder() {
        return new Builder();
    }

    public List<Message> messages() {
        return messages;
    }

    public RoutingHints routingHints() {
        return routingHints;
    }

    public GenerationOptions generationOptions() {
        return generationOptions;
    }

    public List<ToolDefinition> tools() {
        return tools;
    }

    public ToolChoice toolChoice() {
        return toolChoice;
    }

    public ResponseFormat responseFormat() {
        return responseFormat;
    }

    public StreamOptions streamOptions() {
        return streamOptions;
    }

    public PromptCacheOptions promptCache() {
        return promptCache;
    }

    public int estimatedInputTokens() {
        if (estimatedInputTokens != null) {
            return estimatedInputTokens;
        }
        long characters = messages.stream()
                .flatMap(message -> message.contents().stream())
                .mapToLong(ChatRequest::estimatedCharacters)
                .sum();
        characters += tools.stream().mapToLong(tool ->
                tool.name().length()
                        + (tool.description() == null ? 0 : tool.description().length())
                        + tool.parameters().toString().length()).sum();
        if (characters > Integer.MAX_VALUE) return Integer.MAX_VALUE / 4;
        return (int) Math.max(1, (characters + 3) / 4);
    }

    @Override
    public int estimatedOutputTokens() {
        Integer configured = generationOptions.maxOutputTokens();
        return configured == null ? 512 : configured;
    }

    private static int estimatedCharacters(ContentPart part) {
        if (part instanceof TextPart text) return text.text().length();
        if (part instanceof ImagePart image) return image.url().length();
        if (part instanceof AudioPart audio) return audio.data().length();
        if (part instanceof VideoPart video) return video.url().length();
        if (part instanceof FilePart file) {
            return (file.url() == null ? file.fileId().length() : file.url().length())
                    + (file.filename() == null ? 0 : file.filename().length());
        }
        if (part instanceof ToolCallPart call) return call.name().length() + call.arguments().length();
        if (part instanceof ToolResultPart result) return result.result().length();
        return 0;
    }

    public static final class Builder {
        private final List<Message> messages = new ArrayList<>();
        private RoutingHints routingHints;
        private Integer estimatedInputTokens;
        private GenerationOptions generationOptions;
        private List<ToolDefinition> tools = List.of();
        private ToolChoice toolChoice;
        private ResponseFormat responseFormat;
        private StreamOptions streamOptions;
        private PromptCacheOptions promptCache;

        public Builder message(Message message) {
            messages.add(Objects.requireNonNull(message, "message"));
            return this;
        }

        public Builder messages(List<Message> messages) {
            this.messages.clear();
            this.messages.addAll(Objects.requireNonNull(messages, "messages"));
            return this;
        }

        public Builder userMessage(String content) {
            return message(Message.user(content));
        }

        public Builder systemMessage(String content) {
            return message(Message.system(content));
        }

        public Builder assistantMessage(String content) {
            return message(Message.assistant(content));
        }

        public Builder routingHints(RoutingHints routingHints) {
            this.routingHints = routingHints;
            return this;
        }

        public Builder estimatedInputTokens(int estimatedInputTokens) {
            if (estimatedInputTokens < 0) {
                throw new IllegalArgumentException("estimatedInputTokens must be >= 0");
            }
            this.estimatedInputTokens = estimatedInputTokens;
            return this;
        }

        public Builder generationOptions(GenerationOptions value) {
            generationOptions = Objects.requireNonNull(value, "generationOptions");
            return this;
        }

        public Builder tools(List<ToolDefinition> values) {
            tools = List.copyOf(Objects.requireNonNull(values, "tools"));
            return this;
        }

        public Builder tools(ToolDefinition... values) {
            return tools(List.of(values));
        }

        public Builder toolChoice(ToolChoice value) {
            toolChoice = Objects.requireNonNull(value, "toolChoice");
            return this;
        }

        public Builder responseFormat(ResponseFormat value) {
            responseFormat = Objects.requireNonNull(value, "responseFormat");
            return this;
        }

        public Builder streamOptions(StreamOptions value) {
            streamOptions = Objects.requireNonNull(value, "streamOptions");
            return this;
        }

        public Builder promptCache(PromptCacheOptions value) {
            promptCache = Objects.requireNonNull(value, "promptCache");
            return this;
        }

        public ChatRequest build() {
            return new ChatRequest(this);
        }
    }
}
