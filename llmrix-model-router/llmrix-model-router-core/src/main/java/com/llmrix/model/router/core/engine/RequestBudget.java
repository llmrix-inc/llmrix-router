package com.llmrix.model.router.core.engine;

import com.llmrix.model.router.core.api.ModelRequest;
import com.llmrix.model.router.core.api.Usage;
import com.llmrix.model.router.core.model.ModelTarget;

public final class RequestBudget {
    private final Double limitUsd;
    private double consumedUsd;

    public RequestBudget(Double limitUsd) {
        if (limitUsd != null && (!Double.isFinite(limitUsd) || limitUsd < 0)) {
            throw new IllegalArgumentException("limitUsd must be a finite value >= 0");
        }
        this.limitUsd = limitUsd;
    }

    public synchronized Reservation tryReserve(ModelTarget candidate, ModelRequest request) {
        double estimate = candidate.pricing().estimateCost(
                request.estimatedInputTokens(), request.estimatedOutputTokens());
        if (limitUsd == null) return new Reservation(0, false);
        if (!Double.isFinite(estimate)) return null;
        if (consumedUsd + estimate > limitUsd) return null;
        consumedUsd += estimate;
        return new Reservation(estimate, true);
    }

    public synchronized void settle(Reservation reservation, ModelTarget candidate, Usage usage) {
        if (reservation == null || !reservation.enforced()
                || usage.inputTokens() < 0 || usage.outputTokens() < 0) return;
        double actual = candidate.pricing().estimateCost(usage);
        if (Double.isFinite(actual)) consumedUsd += actual - reservation.reservedUsd();
    }

    public synchronized void release(Reservation reservation) {
        if (reservation != null && reservation.enforced()) consumedUsd -= reservation.reservedUsd();
    }

    public synchronized double consumedUsd() {
        return consumedUsd;
    }

    public static final class Reservation {
        private final double reservedUsd;
        private final boolean enforced;

        public Reservation(double reservedUsd, boolean enforced) {
            this.reservedUsd = reservedUsd;
            this.enforced = enforced;
        }

        public double reservedUsd() {
            return reservedUsd;
        }

        public boolean enforced() {
            return enforced;
        }
    }
}
