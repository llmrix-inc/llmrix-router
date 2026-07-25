package com.llmrix.model.router.core.api;

public record StreamOptions(boolean includeUsage) {
    public static final StreamOptions DEFAULT = new StreamOptions(true);
}
