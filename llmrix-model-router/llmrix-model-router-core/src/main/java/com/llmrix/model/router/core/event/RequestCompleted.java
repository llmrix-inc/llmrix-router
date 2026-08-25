package com.llmrix.model.router.core.event;

import lombok.Value;
import lombok.experimental.Accessors;

@Value
@Accessors(fluent = true)
public class RequestCompleted {
    String requestId;
    String targetId;
    long durationNanos;
    boolean success;
    int attempts;
}
