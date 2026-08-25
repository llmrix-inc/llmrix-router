package com.llmrix.model.router.core.event;

import com.llmrix.model.router.core.api.Usage;
import lombok.Value;
import lombok.experimental.Accessors;

@Value
@Accessors(fluent = true)
public class UsageRecorded {
    String requestId;
    String targetId;
    Usage usage;
    double estimatedCostUsd;
}
