package com.llmrix.model.router.core.api;

public record ToolChoice(Mode mode, String name) {
    public enum Mode { AUTO, NONE, REQUIRED, NAMED }

    public ToolChoice {
        if (mode == null) throw new IllegalArgumentException("tool choice mode must not be null");
        if (mode == Mode.NAMED && (name == null || name.isBlank())) {
            throw new IllegalArgumentException("named tool choice requires a name");
        }
        if (mode != Mode.NAMED && name != null) {
            throw new IllegalArgumentException("only named tool choice accepts a name");
        }
    }

    public static ToolChoice auto() { return new ToolChoice(Mode.AUTO, null); }
    public static ToolChoice none() { return new ToolChoice(Mode.NONE, null); }
    public static ToolChoice required() { return new ToolChoice(Mode.REQUIRED, null); }
    public static ToolChoice named(String name) { return new ToolChoice(Mode.NAMED, name); }
}
