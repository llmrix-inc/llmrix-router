package com.llmrix.model.router.integrations.evaluation;

import com.llmrix.model.router.core.api.chat.ChatModel;
import com.llmrix.model.router.core.model.ModelPricing;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Offline-only replay that compares a primary model with shadow models.
 */
public final class ShadowEvaluationRunner {
    private final EvaluationRunner delegate;

    public ShadowEvaluationRunner(
            String primaryId,
            ChatModel primary,
            Map<String, ChatModel> shadows,
            Map<String, ModelPricing> pricing,
            QualityScorer scorer) {
        if (primaryId == null || primaryId.isBlank()) throw new IllegalArgumentException("primaryId must not be blank");
        Map<String, ChatModel> models = new LinkedHashMap<>();
        models.put(primaryId, primary);
        shadows.forEach((id, model) -> {
            if (models.putIfAbsent(id, model) != null) throw new IllegalArgumentException("duplicate model: " + id);
        });
        this.delegate = new EvaluationRunner(models, pricing, scorer);
    }

    public EvaluationReport run(List<EvaluationSample> samples) {
        return delegate.run(samples);
    }
}
