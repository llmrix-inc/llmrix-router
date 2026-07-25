package com.llmrix.model.router.integrations.evaluation;

public record EvaluationResult(String sampleId, String modelId, boolean success,
                               long latencyNanos, double costUsd, double quality,
                               String errorType) { }
