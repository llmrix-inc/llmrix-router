package com.llmrix.model.router.integrations.fugu;

import lombok.Value;
import lombok.experimental.Accessors;

@Value
@Accessors(fluent = true)
public class FuguCompleted {
    String requestId;
    int turns;
    long durationNanos;
    boolean success;
    String termination;
    String errorType;
}

