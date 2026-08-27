package com.llmrix.model.router.core.state;

import com.llmrix.model.router.core.model.ModelLimits;
import com.llmrix.model.router.core.exception.RateLimitException;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Scheduler;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public final class InMemoryRouterStateStore implements RouterStateStore {
    private final ConcurrentMap<String, HealthState> health = new ConcurrentHashMap<String, HealthState>();
    private final Cache<String, QuotaState> quotas;
    private final LocalQuotaOptions options;
    private final Object quotaCreationLock = new Object();

    public InMemoryRouterStateStore() {
        this(LocalQuotaOptions.DEFAULT);
    }

    public InMemoryRouterStateStore(LocalQuotaOptions options) {
        this.options = java.util.Objects.requireNonNull(options, "options");
        this.quotas = Caffeine.newBuilder()
                .expireAfterAccess(options.idleTimeout())
                .scheduler(Scheduler.systemScheduler())
                .build();
    }

    @Override
    public HealthState health(String namespace, String candidateId) {
        return health.computeIfAbsent(key(namespace, candidateId), ignored -> new TargetHealth());
    }

    @Override
    public QuotaState quota(String namespace, String candidateId, ModelLimits limits) {
        String key = key(namespace, candidateId);
        QuotaState existing = quotas.getIfPresent(key);
        if (existing != null) return existing;
        synchronized (quotaCreationLock) {
            existing = quotas.getIfPresent(key);
            if (existing != null) return existing;
            quotas.cleanUp();
            if (quotas.estimatedSize() >= options.maxPartitions()) {
                throw new RateLimitException("local quota partition capacity exhausted");
            }
            QuotaState created = new TargetQuota(limits);
            quotas.put(key, created);
            return created;
        }
    }

    private static String key(String namespace, String candidateId) {
        return namespace + "\u0000" + candidateId;
    }
}
