package com.llmrix.model.router.core.event;

import lombok.Value;
import lombok.experimental.Accessors;

@Value
@Accessors(fluent = true)
public class AttemptCompleted {
    String requestId;
    String targetId;
    int attempt;
    long durationNanos;
    boolean success;
    String errorType;
}
