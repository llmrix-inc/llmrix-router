package com.llmrix.model.router.integrations.fugu;

import lombok.Value;
import lombok.experimental.Accessors;

@Value
@Accessors(fluent = true)
public class FuguRetry {
    String requestId;
    String candidateId;
    int nextAttempt;
    String errorType;
}

