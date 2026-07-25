package com.llmrix.model.router.core.execution;

public interface QuotaState {
    String rejectionReason(int estimatedInputTokens);
    boolean tryAcquire(int estimatedInputTokens);
    void recordOutputTokens(long outputTokens);
}
