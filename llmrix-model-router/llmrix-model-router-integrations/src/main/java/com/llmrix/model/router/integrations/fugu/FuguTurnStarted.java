package com.llmrix.model.router.integrations.fugu;

import lombok.Value;
import lombok.experimental.Accessors;

@Value
@Accessors(fluent = true)
public class FuguTurnStarted {
    String requestId;
    int turn;
    String candidateId;
    FuguRole role;
}

