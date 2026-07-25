package com.llmrix.model.router.integrations.evaluation;

import com.llmrix.model.router.core.api.ChatModel;
import com.llmrix.model.router.core.candidate.ModelPricing;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Deterministic offline weighted assignment; each sample runs on exactly one variant. */
public final class AbEvaluationRunner {
    private final Map<String, ChatModel> variants;
    private final Map<String, Integer> weights;
    private final Map<String, ModelPricing> pricing;
    private final QualityScorer scorer;
    private final int totalWeight;

    public AbEvaluationRunner(
            Map<String, ChatModel> variants,
            Map<String, Integer> weights,
            Map<String, ModelPricing> pricing,
            QualityScorer scorer) {
        if (variants == null || variants.size() < 2) throw new IllegalArgumentException("at least two variants are required");
        this.variants = new LinkedHashMap<>();
        variants.entrySet().stream().sorted(Map.Entry.comparingByKey())
                .forEach(entry -> this.variants.put(entry.getKey(), entry.getValue()));
        this.weights = new LinkedHashMap<>();
        int total = 0;
        for (String id : this.variants.keySet()) {
            Integer weight = weights.get(id);
            if (weight == null || weight < 1) throw new IllegalArgumentException("positive weight is required for " + id);
            this.weights.put(id, weight);
            total = Math.addExact(total, weight);
        }
        if (weights.size() != variants.size()) throw new IllegalArgumentException("weights must match variants");
        this.totalWeight = total;
        this.pricing = pricing == null ? Map.of() : Map.copyOf(pricing);
        this.scorer = scorer == null ? QualityScorer.unscored() : scorer;
    }

    public EvaluationReport run(List<EvaluationSample> samples) {
        List<EvaluationResult> results = new ArrayList<>();
        for (EvaluationSample sample : List.copyOf(samples)) {
            String variant = assign(sample.id());
            EvaluationReport report = new EvaluationRunner(
                    Map.of(variant, variants.get(variant)), pricing, scorer).run(List.of(sample));
            results.add(report.results().get(0));
        }
        return new EvaluationReport(results);
    }

    public String assign(String sampleId) {
        int bucket = Math.floorMod(sampleId.hashCode(), totalWeight);
        int boundary = 0;
        for (var entry : weights.entrySet()) {
            boundary += entry.getValue();
            if (bucket < boundary) return entry.getKey();
        }
        throw new IllegalStateException("unreachable assignment bucket");
    }
}
