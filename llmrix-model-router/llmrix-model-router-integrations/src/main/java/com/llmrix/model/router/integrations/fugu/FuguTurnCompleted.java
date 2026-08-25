package com.llmrix.model.router.integrations.fugu;

import com.llmrix.model.router.core.api.Usage;
import lombok.Value;
import lombok.experimental.Accessors;

@Value
@Accessors(fluent = true)
public class FuguTurnCompleted {
    String requestId;
    int turn;
    String candidateId;
    FuguRole role;
    long durationNanos;
    Usage usage;
}

