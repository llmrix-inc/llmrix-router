package com.llmrix.model.router.integrations.evaluation;

import lombok.Value;
import lombok.experimental.Accessors;

@Value
@Accessors(fluent = true)
public class ModelEvaluationSummary {
    String modelId;
    int samples;
    double successRate;
    double averageLatencyMillis;
    double averageCostUsd;
    double averageQuality;
}
