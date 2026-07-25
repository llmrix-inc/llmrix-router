package com.llmrix.model.router.core.routing;

import com.llmrix.model.router.core.api.ChatRequest;
import com.llmrix.model.router.core.api.ChatResponse;
import com.llmrix.model.router.core.candidate.Candidate;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class BanditStateStoreTest {
    @Test
    void sharesSelectionsAndRewardsAcrossStrategies() {
        InMemoryBanditStateStore store = new InMemoryBanditStateStore();
        SemanticClassifier classifier = (request, candidates) -> Map.of("a", 0d, "b", 0d);
        ContextualBanditStrategy first = new ContextualBanditStrategy(classifier, 0, store, "shared");
        ContextualBanditStrategy second = new ContextualBanditStrategy(classifier, 0, store, "shared");
        List<CandidateSnapshot> candidates = List.of(snapshot("a"), snapshot("b"));

        String selected = first.select(ChatRequest.user("x"), candidates).id();
        second.observe(selected, 1);

        assertThat(store.totalSelections("shared")).isEqualTo(1);
        assertThat(store.snapshot("shared").get(selected).rewardObservations()).isEqualTo(1);
        assertThat(second.snapshot()).isEqualTo(first.snapshot());
    }

    private static CandidateSnapshot snapshot(String id) {
        Candidate candidate = Candidate.builder(id, request -> ChatResponse.of(id)).build();
        return new CandidateSnapshot(candidate, true, 0, 0);
    }
}
