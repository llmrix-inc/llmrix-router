package com.llmrix.model.router.integrations.fugu;

import java.util.Objects;

public record FuguAction(String candidateId, FuguRole role) {
    public FuguAction {
        if (candidateId == null || candidateId.isBlank()) throw new IllegalArgumentException("candidateId must not be blank");
        Objects.requireNonNull(role, "role");
    }
}
