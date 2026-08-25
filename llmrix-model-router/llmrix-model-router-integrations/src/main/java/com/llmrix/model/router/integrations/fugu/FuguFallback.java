package com.llmrix.model.router.integrations.fugu;

import lombok.Value;
import lombok.experimental.Accessors;

@Value
@Accessors(fluent = true)
public class FuguFallback {
    String requestId;
    String fromCandidateId;
    String toCandidateId;
    String errorType;
}

