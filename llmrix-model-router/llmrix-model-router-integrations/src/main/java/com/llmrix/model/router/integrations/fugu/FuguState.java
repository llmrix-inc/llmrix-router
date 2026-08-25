package com.llmrix.model.router.integrations.fugu;

import com.llmrix.model.router.core.api.chat.ChatRequest;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.experimental.Accessors;

import java.util.List;

@Getter
@EqualsAndHashCode
@Accessors(fluent = true)
public final class FuguState {
    private final ChatRequest request;
    private final List<String> candidateIds;
    private final List<FuguTurn> turns;
    private final String latestAnswer;
    private final String latestSuggestion;

    public FuguState(ChatRequest request, List<String> candidateIds, List<FuguTurn> turns,
                     String latestAnswer, String latestSuggestion) {
        this.request = request;
        this.candidateIds = List.copyOf(candidateIds);
        this.turns = List.copyOf(turns);
        this.latestAnswer = latestAnswer;
        this.latestSuggestion = latestSuggestion;
    }

}
