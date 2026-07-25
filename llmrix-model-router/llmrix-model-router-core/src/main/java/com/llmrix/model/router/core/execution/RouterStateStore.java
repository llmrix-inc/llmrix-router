package com.llmrix.model.router.core.execution;

import com.llmrix.model.router.core.candidate.ModelLimits;

public interface RouterStateStore {
    HealthState health(String namespace, String candidateId);
    QuotaState quota(String namespace, String candidateId, ModelLimits limits);
}
