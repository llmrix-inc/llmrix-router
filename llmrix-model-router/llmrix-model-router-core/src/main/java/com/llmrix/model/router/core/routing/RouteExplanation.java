package com.llmrix.model.router.core.routing;

import java.util.List;
import java.util.Map;

public record RouteExplanation(
        String selectedCandidate,
        List<String> eligibleCandidates,
        Map<String, String> excludedCandidates) {
}
