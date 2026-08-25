package com.llmrix.model.router.core.event;

import lombok.Value;
import lombok.experimental.Accessors;

@Value
@Accessors(fluent = true)
public class FirstTokenReceived {
    String requestId;
    String targetId;
    long durationNanos;
}
