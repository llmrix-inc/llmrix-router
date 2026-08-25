package com.llmrix.model.router.core.routing;

import com.llmrix.model.router.core.api.chat.ChatRequest;

import java.util.List;
import java.util.Map;

@FunctionalInterface
public interface SemanticClassifier {
    /**
     * Returns candidate relevance scores; larger finite values are preferred.
     */
    Map<String, Double> score(ChatRequest request, List<RouteCandidate> candidates);
}
