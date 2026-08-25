package com.llmrix.model.router.integrations.fugu;

import lombok.Value;
import lombok.experimental.Accessors;

import java.time.Duration;

@Value
@Accessors(fluent = true)
public class FuguCandidateCooldown {
    String requestId;
    String candidateId;
    Duration duration;
}

