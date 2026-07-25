package com.llmrix.model.router.core.spi.event;

import java.time.Duration;

public record CandidateCooldown(String requestId, String candidateId, Duration cooldown) { }
