package com.llmrix.model.router.core.execution;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.llmrix.model.router.core.candidate.ModelLimits;

public final class InMemoryRouterStateStore implements RouterStateStore {
    private final Cache<String, HealthState> health = Caffeine.newBuilder().weakValues().build();
    private final Cache<String, QuotaState> quotas = Caffeine.newBuilder().weakValues().build();

    @Override
    public HealthState health(String namespace, String candidateId) {
        return health.get(key(namespace, candidateId), ignored -> new CandidateHealth());
    }

    @Override
    public QuotaState quota(String namespace, String candidateId, ModelLimits limits) {
        return quotas.get(key(namespace, candidateId), ignored -> new CandidateQuota(limits));
    }

    private static String key(String namespace, String candidateId) {
        return namespace + "\u0000" + candidateId;
    }
}
