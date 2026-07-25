package com.llmrix.model.router.integrations.fugu;

import com.llmrix.model.router.core.api.Usage;

public record FuguTurnCompleted(String requestId, int turn, String candidateId,
                                FuguRole role, long durationNanos, Usage usage) { }
