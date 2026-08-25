package com.llmrix.model.router.integrations.evaluation;

import com.llmrix.model.router.core.api.chat.ChatResponse;

@FunctionalInterface
public interface QualityScorer {
    double score(EvaluationSample sample, ChatResponse response);

    static QualityScorer unscored() {
        return (sample, response) -> Double.NaN;
    }
}
