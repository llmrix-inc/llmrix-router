package com.llmrix.model.router.core.routing;

import com.llmrix.model.router.core.api.ModelRequest;
import com.llmrix.model.router.core.model.ModelTarget;

import java.util.List;

@FunctionalInterface
public interface RoutingStrategy {
    ModelTarget select(ModelRequest request, List<RouteCandidate> candidates);
}
