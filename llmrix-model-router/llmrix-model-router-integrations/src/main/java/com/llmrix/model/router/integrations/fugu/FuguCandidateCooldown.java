package com.llmrix.model.router.integrations.fugu;

import java.time.Duration;

public record FuguCandidateCooldown(String requestId, String candidateId, Duration duration) { }
