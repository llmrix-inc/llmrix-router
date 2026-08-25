package com.llmrix.model.router.core.state;

import com.llmrix.model.router.core.model.ModelLimits;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public final class InMemoryRouterStateStore implements RouterStateStore {
    private final ConcurrentMap<String, HealthState> health = new ConcurrentHashMap<String, HealthState>();
    private final ConcurrentMap<String, QuotaState> quotas = new ConcurrentHashMap<String, QuotaState>();

    @Override
    public HealthState health(String namespace, String candidateId) {
        return health.computeIfAbsent(key(namespace, candidateId), ignored -> new TargetHealth());
    }

    @Override
    public QuotaState quota(String namespace, String candidateId, ModelLimits limits) {
        return quotas.computeIfAbsent(key(namespace, candidateId), ignored -> new TargetQuota(limits));
    }

    private static String key(String namespace, String candidateId) {
        return namespace + "\u0000" + candidateId;
    }
}
