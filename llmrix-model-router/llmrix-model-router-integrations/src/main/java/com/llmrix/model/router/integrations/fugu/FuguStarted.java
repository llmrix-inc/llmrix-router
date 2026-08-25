package com.llmrix.model.router.integrations.fugu;

import lombok.Value;
import lombok.experimental.Accessors;

@Value
@Accessors(fluent = true)
public class FuguStarted {
    String requestId;
    int maxTurns;
}

