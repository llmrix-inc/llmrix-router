package com.llmrix.model.router.core.api;

public record Usage(long inputTokens, long outputTokens) {
    public static final Usage UNKNOWN = new Usage(-1, -1);

    public long totalTokens() {
        return inputTokens < 0 || outputTokens < 0 ? -1 : inputTokens + outputTokens;
    }
}
