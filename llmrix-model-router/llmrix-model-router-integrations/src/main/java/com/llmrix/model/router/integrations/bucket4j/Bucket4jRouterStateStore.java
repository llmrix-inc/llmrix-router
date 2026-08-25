package com.llmrix.model.router.integrations.bucket4j;

import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.Refill;
import com.llmrix.model.router.core.model.ModelLimits;
import com.llmrix.model.router.core.state.HealthState;
import com.llmrix.model.router.core.state.InMemoryRouterStateStore;
import com.llmrix.model.router.core.state.QuotaState;
import com.llmrix.model.router.core.state.RouterStateStore;

import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Uses Bucket4j for local token-bucket quotas while delegating candidate health state.
 */
public final class Bucket4jRouterStateStore implements RouterStateStore {
    private final RouterStateStore healthDelegate;
    private final Bucket4jQuotaOptions options;
    private final ConcurrentMap<Key, QuotaState> quotas = new ConcurrentHashMap<>();

    public Bucket4jRouterStateStore() {
        this(new InMemoryRouterStateStore(), Bucket4jQuotaOptions.DEFAULT);
    }

    public Bucket4jRouterStateStore(RouterStateStore healthDelegate, Bucket4jQuotaOptions options) {
        this.healthDelegate = Objects.requireNonNull(healthDelegate, "healthDelegate");
        this.options = Objects.requireNonNull(options, "options");
    }

    @Override
    public HealthState health(String namespace, String candidateId) {
        return healthDelegate.health(namespace, candidateId);
    }

    @Override
    public QuotaState quota(String namespace, String candidateId, ModelLimits limits) {
        Key key = new Key(namespace, candidateId, limits);
        return quotas.computeIfAbsent(key, ignored -> new BucketQuota(limits, options));
    }

    private static final class Key {
        private final String namespace;
        private final String candidateId;
        private final ModelLimits limits;

        private Key(String namespace, String candidateId, ModelLimits limits) {
            this.namespace = namespace;
            this.candidateId = candidateId;
            this.limits = limits;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) return true;
            if (!(other instanceof Key)) return false;
            Key key = (Key) other;
            return namespace.equals(key.namespace)
                    && candidateId.equals(key.candidateId)
                    && limits.equals(key.limits);
        }

        @Override
        public int hashCode() {
            return Objects.hash(namespace, candidateId, limits);
        }
    }

    private static final class BucketQuota implements QuotaState {
        private final Bucket requests;
        private final Bucket tokens;

        private BucketQuota(ModelLimits limits, Bucket4jQuotaOptions options) {
            requests = bucket(limits.requestsPerMinute(), options);
            tokens = bucket(limits.tokensPerMinute(), options);
        }

        @Override
        public String rejectionReason(int estimatedInputTokens) {
            if (requests != null && requests.getAvailableTokens() < 1) return "requests-per-minute";
            if (tokens != null && tokens.getAvailableTokens() < Math.max(0, estimatedInputTokens)) {
                return "tokens-per-minute";
            }
            return null;
        }

        @Override
        public boolean tryAcquire(int estimatedInputTokens) {
            long inputTokens = Math.max(0, estimatedInputTokens);
            if (requests != null && !requests.tryConsume(1)) return false;
            if (tokens != null && !tokens.tryConsume(inputTokens)) {
                if (requests != null) requests.addTokens(1);
                return false;
            }
            return true;
        }

        @Override
        public void recordOutputTokens(long outputTokens) {
            if (tokens != null && outputTokens > 0) tokens.consumeIgnoringRateLimits(outputTokens);
        }

        private static Bucket bucket(Long rate, Bucket4jQuotaOptions options) {
            if (rate == null) return null;
            long capacity = Math.max(rate, (long) Math.ceil(rate * options.burstCapacityMultiplier()));
            Refill refill = options.greedyRefill()
                    ? Refill.greedy(rate, options.refillPeriod())
                    : Refill.intervally(rate, options.refillPeriod());
            return Bucket.builder().addLimit(Bandwidth.classic(capacity, refill)).build();
        }
    }
}
