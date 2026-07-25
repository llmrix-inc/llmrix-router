package com.llmrix.model.router.core.routing;

import com.llmrix.model.router.core.api.ChatRequest;
import com.llmrix.model.router.core.candidate.Candidate;

import java.util.List;

@FunctionalInterface
public interface RoutingStrategy {
    Candidate select(ChatRequest request, List<CandidateSnapshot> candidates);
}
