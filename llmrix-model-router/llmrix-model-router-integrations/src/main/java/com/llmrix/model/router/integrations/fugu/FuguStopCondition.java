package com.llmrix.model.router.integrations.fugu;

import java.util.Optional;

@FunctionalInterface
public interface FuguStopCondition {
    /** Returns a stable termination reason when orchestration should stop. */
    Optional<String> shouldStop(FuguState state);

    static FuguStopCondition never() { return state -> Optional.empty(); }
}
