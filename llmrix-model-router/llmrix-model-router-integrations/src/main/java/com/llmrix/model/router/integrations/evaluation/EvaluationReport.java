package com.llmrix.model.router.integrations.evaluation;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public record EvaluationReport(List<EvaluationResult> results) {
    public EvaluationReport { results = List.copyOf(results); }
    public double successRate() {
        return results.isEmpty() ? Double.NaN
                : results.stream().filter(EvaluationResult::success).count() / (double) results.size();
    }

    public Map<String, ModelEvaluationSummary> byModel() {
        return results.stream().collect(Collectors.groupingBy(
                EvaluationResult::modelId, Collectors.collectingAndThen(Collectors.toList(), values -> {
                    long successes = values.stream().filter(EvaluationResult::success).count();
                    return new ModelEvaluationSummary(values.get(0).modelId(), values.size(),
                            successes / (double) values.size(),
                            values.stream().mapToLong(EvaluationResult::latencyNanos).average().orElse(Double.NaN) / 1_000_000d,
                            averageFinite(values.stream().mapToDouble(EvaluationResult::costUsd).toArray()),
                            averageFinite(values.stream().mapToDouble(EvaluationResult::quality).toArray()));
                })));
    }

    private static double averageFinite(double[] values) {
        return java.util.Arrays.stream(values).filter(Double::isFinite).average().orElse(Double.NaN);
    }
}
