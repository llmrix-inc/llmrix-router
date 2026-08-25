package com.llmrix.model.router.integrations.evaluation;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Value;
import lombok.experimental.Accessors;

@Value
@Accessors(fluent = true)
public class EvaluationResult {
    @JsonProperty("sampleId")
    String sampleId;
    @JsonProperty("modelId")
    String modelId;
    @JsonProperty("success")
    boolean success;
    @JsonProperty("latencyNanos")
    long latencyNanos;
    @JsonProperty("costUsd")
    double costUsd;
    @JsonProperty("quality")
    double quality;
    @JsonProperty("errorType")
    String errorType;
}
