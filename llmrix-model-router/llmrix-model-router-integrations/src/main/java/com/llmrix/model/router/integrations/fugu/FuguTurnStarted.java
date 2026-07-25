package com.llmrix.model.router.integrations.fugu;

public record FuguTurnStarted(String requestId, int turn, String candidateId, FuguRole role) { }
