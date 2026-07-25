package com.llmrix.model.router.integrations.evaluation;

import com.llmrix.model.router.core.api.ChatModel;
import com.llmrix.model.router.core.api.ChatResponse;
import com.llmrix.model.router.core.candidate.ModelPricing;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class EvaluationRunner {
    private final Map<String, ChatModel> models;
    private final Map<String, ModelPricing> pricing;
    private final QualityScorer scorer;

    public EvaluationRunner(Map<String, ChatModel> models, Map<String, ModelPricing> pricing, QualityScorer scorer) {
        if (models == null || models.isEmpty()) throw new IllegalArgumentException("at least one model is required");
        this.models = new LinkedHashMap<>(models);
        this.pricing = pricing == null ? Map.of() : Map.copyOf(pricing);
        this.scorer = scorer == null ? QualityScorer.unscored() : scorer;
    }

    public EvaluationReport run(List<EvaluationSample> samples) {
        List<EvaluationResult> results = new ArrayList<>();
        for (EvaluationSample sample : List.copyOf(samples)) {
            models.forEach((modelId, model) -> results.add(evaluate(sample, modelId, model)));
        }
        return new EvaluationReport(results);
    }

    private EvaluationResult evaluate(EvaluationSample sample, String modelId, ChatModel model) {
        long started = System.nanoTime();
        try {
            ChatResponse response = model.chat(sample.request());
            double cost = response.usage().totalTokens() < 0 ? Double.NaN
                    : pricing.getOrDefault(modelId, ModelPricing.UNKNOWN)
                            .estimateCost(response.usage().inputTokens(), response.usage().outputTokens());
            double quality = scorer.score(sample, response);
            if (!Double.isNaN(quality) && (!Double.isFinite(quality) || quality < 0 || quality > 1)) {
                throw new IllegalArgumentException("quality score must be between 0 and 1 or NaN");
            }
            return new EvaluationResult(sample.id(), modelId, true,
                    System.nanoTime() - started, cost, quality, null);
        } catch (RuntimeException failure) {
            return new EvaluationResult(sample.id(), modelId, false,
                    System.nanoTime() - started, Double.NaN, Double.NaN,
                    failure.getClass().getSimpleName());
        }
    }
}
