package com.llmrix.model.router.integrations.fugu;

public final class FuguRouters {
    private FuguRouters() {}

    /** A deterministic baseline useful before a learned router is available. */
    public static FuguRouter workerThenVerifier(String workerCandidate, String verifierCandidate) {
        return state -> state.latestAnswer() == null
                ? new FuguAction(workerCandidate, FuguRole.WORKER)
                : new FuguAction(verifierCandidate, FuguRole.VERIFIER);
    }
}
