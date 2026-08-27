package com.llmrix.model.router.core.api;

import lombok.Value;
import lombok.experimental.Accessors;

@Value
@Accessors(fluent = true)
public class Usage {
    public static final Usage UNKNOWN = new Usage(-1, -1);

    long inputTokens;
    long outputTokens;
    long cachedInputTokens;
    long cacheWriteTokens;
    long reasoningTokens;

    public Usage(long inputTokens, long outputTokens) {
        this(inputTokens, outputTokens, 0, 0, 0);
    }

    public Usage(long inputTokens, long outputTokens, long cachedInputTokens,
                 long cacheWriteTokens, long reasoningTokens) {
        this.inputTokens = inputTokens;
        this.outputTokens = outputTokens;
        this.cachedInputTokens = cachedInputTokens;
        this.cacheWriteTokens = cacheWriteTokens;
        this.reasoningTokens = reasoningTokens;
    }

    public long totalTokens() {
        return inputTokens < 0 || outputTokens < 0 ? -1 : inputTokens + outputTokens;
    }
}
