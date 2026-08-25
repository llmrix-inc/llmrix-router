package com.llmrix.model.router.core.state;

import com.llmrix.model.router.core.model.ModelLimits;

public interface RouterStateStore {
    HealthState health(String namespace, String candidateId);

    QuotaState quota(String namespace, String candidateId, ModelLimits limits);
}
