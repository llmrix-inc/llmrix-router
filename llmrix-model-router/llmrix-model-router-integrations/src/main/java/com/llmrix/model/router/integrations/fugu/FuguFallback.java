package com.llmrix.model.router.integrations.fugu;

public record FuguFallback(String requestId, String fromCandidateId,
                           String toCandidateId, String errorType) { }
