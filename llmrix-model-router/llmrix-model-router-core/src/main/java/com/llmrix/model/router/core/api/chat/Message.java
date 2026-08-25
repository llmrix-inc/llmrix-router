package com.llmrix.model.router.core.api.chat;

import java.util.List;
import java.util.Objects;

public final class Message {
    private final String role;
    private final List<ContentPart> contents;

    public Message(String role, String content) {
        this(role, List.of(new TextPart(content)));
    }

    public Message(String role, List<? extends ContentPart> contents) {
        if (role == null || role.isBlank()) throw new IllegalArgumentException("role must not be blank");
        Objects.requireNonNull(contents, "contents");
        if (contents.isEmpty()) throw new IllegalArgumentException("contents must not be empty");
        long toolResults = contents.stream().filter(ToolResultPart.class::isInstance).count();
        long toolCalls = contents.stream().filter(ToolCallPart.class::isInstance).count();
        if (toolResults > 0 && (!"tool".equals(role) || contents.size() != 1)) {
            throw new IllegalArgumentException("tool result must be the only content of a tool message");
        }
        if ("tool".equals(role) && toolResults != 1) {
            throw new IllegalArgumentException("tool message requires exactly one tool result");
        }
        if (toolCalls > 0 && !"assistant".equals(role)) {
            throw new IllegalArgumentException("tool calls require an assistant message");
        }
        this.role = role;
        this.contents = List.copyOf(contents);
    }

    public String role() {
        return role;
    }

    public List<ContentPart> contents() {
        return contents;
    }

    public boolean textOnly() {
        return contents.stream().allMatch(TextPart.class::isInstance);
    }

    public String content() {
        return contents.stream()
                .filter(TextPart.class::isInstance)
                .map(TextPart.class::cast)
                .map(TextPart::text)
                .reduce("", String::concat);
    }

    public static Message system(String content) {
        return new Message("system", content);
    }

    public static Message user(String content) {
        return new Message("user", content);
    }

    public static Message user(ContentPart... contents) {
        return new Message("user", List.of(contents));
    }

    public static Message assistant(String content) {
        return new Message("assistant", content);
    }

    public static Message assistant(ToolCallPart... toolCalls) {
        return new Message("assistant", List.of(toolCalls));
    }

    public static Message tool(String toolCallId, String result) {
        return new Message("tool", List.of(new ToolResultPart(toolCallId, result)));
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof Message message && role.equals(message.role) && contents.equals(message.contents);
    }

    @Override
    public int hashCode() {
        return Objects.hash(role, contents);
    }

    @Override
    public String toString() {
        return "Message[role=" + role + ", contents=" + contents + "]";
    }
}
