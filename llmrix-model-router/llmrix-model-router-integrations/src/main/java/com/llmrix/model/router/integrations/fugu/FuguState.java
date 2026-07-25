package com.llmrix.model.router.integrations.fugu;

import com.llmrix.model.router.core.api.ChatRequest;

import java.util.List;

public record FuguState(
        ChatRequest request,
        List<String> candidateIds,
        List<FuguTurn> turns,
        String latestAnswer,
        String latestSuggestion) {

    public FuguState {
        candidateIds = List.copyOf(candidateIds);
        turns = List.copyOf(turns);
    }
}
