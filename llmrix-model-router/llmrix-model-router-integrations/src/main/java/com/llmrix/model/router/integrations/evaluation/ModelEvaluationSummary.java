package com.llmrix.model.router.integrations.evaluation;

public record ModelEvaluationSummary(String modelId, int samples, double successRate,
                                     double averageLatencyMillis, double averageCostUsd,
                                     double averageQuality) { }
