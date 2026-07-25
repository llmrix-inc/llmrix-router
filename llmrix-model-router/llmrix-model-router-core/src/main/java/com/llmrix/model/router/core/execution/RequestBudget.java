package com.llmrix.model.router.core.execution;

import com.llmrix.model.router.core.api.ChatRequest;
import com.llmrix.model.router.core.api.Usage;
import com.llmrix.model.router.core.candidate.Candidate;

public final class RequestBudget {
    private static final int DEFAULT_ESTIMATED_OUTPUT_TOKENS = 512;

    private final Double limitUsd;
    private double consumedUsd;

    public RequestBudget(Double limitUsd) {
        if (limitUsd != null && (!Double.isFinite(limitUsd) || limitUsd < 0)) {
            throw new IllegalArgumentException("limitUsd must be a finite value >= 0");
        }
        this.limitUsd = limitUsd;
    }

    public synchronized Reservation tryReserve(Candidate candidate, ChatRequest request) {
        int outputTokens = request.generationOptions().maxOutputTokens() == null
                ? DEFAULT_ESTIMATED_OUTPUT_TOKENS : request.generationOptions().maxOutputTokens();
        double estimate = candidate.pricing().estimateCost(request.estimatedInputTokens(), outputTokens);
        if (limitUsd == null || !Double.isFinite(estimate)) return new Reservation(0, false);
        if (consumedUsd + estimate > limitUsd) return null;
        consumedUsd += estimate;
        return new Reservation(estimate, true);
    }

    public synchronized void settle(Reservation reservation, Candidate candidate, Usage usage) {
        if (reservation == null || !reservation.enforced()
                || usage.inputTokens() < 0 || usage.outputTokens() < 0) return;
        double actual = candidate.pricing().estimateCost(usage.inputTokens(), usage.outputTokens());
        if (Double.isFinite(actual)) consumedUsd += actual - reservation.reservedUsd();
    }

    public synchronized void release(Reservation reservation) {
        if (reservation != null && reservation.enforced()) consumedUsd -= reservation.reservedUsd();
    }

    public synchronized double consumedUsd() {
        return consumedUsd;
    }

    public record Reservation(double reservedUsd, boolean enforced) { }
}
