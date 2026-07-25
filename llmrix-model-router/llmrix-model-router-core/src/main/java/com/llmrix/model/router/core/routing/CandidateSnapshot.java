package com.llmrix.model.router.core.routing;

import com.llmrix.model.router.core.candidate.Candidate;

public record CandidateSnapshot(
        Candidate candidate,
        boolean available,
        int inFlight,
        double latencyEwmaMillis) {

    public String id() { return candidate.id(); }
}
