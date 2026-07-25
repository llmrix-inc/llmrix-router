package com.llmrix.model.router.core.spi.event;

import com.llmrix.model.router.core.api.Usage;

public record UsageRecorded(String requestId, String candidateId, Usage usage, double estimatedCostUsd) { }
