package com.llmrix.model.router.core.api;

import lombok.Value;
import lombok.experimental.Accessors;

@Value
@Accessors(fluent = true)
public class Usage {
    public static final Usage UNKNOWN = new Usage(-1, -1);

    long inputTokens;
    long outputTokens;

    public long totalTokens() {
        return inputTokens < 0 || outputTokens < 0 ? -1 : inputTokens + outputTokens;
    }
}
