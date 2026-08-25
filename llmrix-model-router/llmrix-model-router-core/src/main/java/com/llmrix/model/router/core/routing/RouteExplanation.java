package com.llmrix.model.router.core.routing;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.experimental.Accessors;

import java.util.List;
import java.util.Map;

@Getter
@EqualsAndHashCode
@Accessors(fluent = true)
public final class RouteExplanation {
    private final String selectedTarget;
    private final List<String> eligibleTargets;
    private final Map<String, String> excludedTargets;

    public RouteExplanation(String selectedTarget, List<String> eligibleTargets, Map<String, String> excludedTargets) {
        this.selectedTarget = selectedTarget;
        this.eligibleTargets = List.copyOf(eligibleTargets);
        this.excludedTargets = Map.copyOf(excludedTargets);
    }

}
