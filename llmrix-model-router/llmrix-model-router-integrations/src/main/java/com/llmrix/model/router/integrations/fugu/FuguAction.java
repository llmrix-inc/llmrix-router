package com.llmrix.model.router.integrations.fugu;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.experimental.Accessors;

import java.util.Objects;

@Getter
@EqualsAndHashCode
@Accessors(fluent = true)
public final class FuguAction {
    private final String candidateId;
    private final FuguRole role;

    public FuguAction(String candidateId, FuguRole role) {
        if (candidateId == null || candidateId.isBlank())
            throw new IllegalArgumentException("candidateId must not be blank");
        this.candidateId = candidateId;
        this.role = Objects.requireNonNull(role, "role");
    }

}
