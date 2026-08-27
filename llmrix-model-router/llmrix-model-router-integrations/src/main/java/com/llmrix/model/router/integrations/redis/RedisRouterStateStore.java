package com.llmrix.model.router.integrations.redis;

import io.lettuce.core.RedisClient;
import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import com.llmrix.model.router.core.model.ModelLimits;
import com.llmrix.model.router.core.state.HealthAttempt;
import com.llmrix.model.router.core.state.HealthState;
import com.llmrix.model.router.core.state.QuotaState;
import com.llmrix.model.router.core.state.RouterStateStore;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.Objects;
import java.util.UUID;

/**
 * Redis-backed atomic health, lease and fixed-window quota state.
 */
public final class RedisRouterStateStore implements RouterStateStore, AutoCloseable {

    private static final String HEALTH_READ = "redis.call('ZREMRANGEBYSCORE',KEYS[2],'-inf',ARGV[1]); "
            + "return {redis.call('HGET',KEYS[1],'cooldown_until') or '0',redis.call('ZCARD',KEYS[2]),"
            + "redis.call('HGET',KEYS[1],'latency_micros') or '0'}";
    private static final String HEALTH_SETTLE = "local removed=redis.call('ZREM',KEYS[2],ARGV[1]); if removed==0 then return 0 end; "
            + "local old=tonumber(redis.call('HGET',KEYS[1],'latency_micros') or '0'); "
            + "local sample=tonumber(ARGV[2]); local next=sample; if old>0 then next=math.floor(old*0.8+sample*0.2) end; "
            + "redis.call('HSET',KEYS[1],'latency_micros',next); "
            + "if ARGV[3]=='success' then redis.call('HSET',KEYS[1],'failures',0); return 0 end; "
            + "local failures=redis.call('HINCRBY',KEYS[1],'failures',1); "
            + "if failures>=tonumber(ARGV[4]) then redis.call('HSET',KEYS[1],'failures',0,'cooldown_until',ARGV[5]); return 1 end; return 0";
    private static final String HEALTH_BEGIN = "redis.call('ZREMRANGEBYSCORE',KEYS[1],'-inf',ARGV[1]); "
            + "local limit=tonumber(ARGV[4]); if limit>=0 and redis.call('ZCARD',KEYS[1])>=limit then return 0 end; "
            + "redis.call('ZADD',KEYS[1],ARGV[2],ARGV[3]); redis.call('PEXPIRE',KEYS[1],ARGV[5]); return 1";
    private static final String QUOTA_ACQUIRE = "local now=tonumber(ARGV[1]); local started=tonumber(redis.call('HGET',KEYS[1],'started') or now); "
            + "if now<started or now-started>=tonumber(ARGV[2]) then started=now; redis.call('HSET',KEYS[1],'started',now,'requests',0,'tokens',0) end; "
            + "local requests=tonumber(redis.call('HGET',KEYS[1],'requests') or '0'); local tokens=tonumber(redis.call('HGET',KEYS[1],'tokens') or '0'); "
            + "local rpm=tonumber(ARGV[3]); local tpm=tonumber(ARGV[4]); local input=tonumber(ARGV[5]); "
            + "if rpm>=0 and requests>=rpm then return 'requests-per-minute' end; if tpm>=0 and tokens+input>tpm then return 'tokens-per-minute' end; "
            + "if ARGV[6]=='1' then redis.call('HINCRBY',KEYS[1],'requests',1); redis.call('HINCRBY',KEYS[1],'tokens',input); redis.call('PEXPIRE',KEYS[1],ARGV[2]) end; return ''";
    private static final String QUOTA_OUTPUT = "local now=tonumber(ARGV[1]); local started=tonumber(redis.call('HGET',KEYS[1],'started') or now); "
            + "if now<started or now-started>=tonumber(ARGV[2]) then redis.call('HSET',KEYS[1],'started',now,'requests',0,'tokens',0) end; "
            + "redis.call('HINCRBY',KEYS[1],'tokens',ARGV[3]); redis.call('PEXPIRE',KEYS[1],ARGV[2]); return 1";

    private final RedisClient ownedClient;
    private final boolean ownsConnection;
    private final StatefulRedisConnection<String, String> connection;
    private final RedisCommands<String, String> redis;
    private final String keyPrefix;
    private final Duration leaseTtl;
    private final Duration quotaWindow;

    public RedisRouterStateStore(String redisUri) {
        this(redisUri, "llmrix:model:router", Duration.ofMinutes(2), Duration.ofMinutes(1));
    }

    public RedisRouterStateStore(String redisUri, String keyPrefix,
                                 Duration leaseTtl, Duration quotaWindow) {
        this(RedisClient.create(requireText(redisUri, "redisUri")), keyPrefix, leaseTtl, quotaWindow);
    }

    private RedisRouterStateStore(RedisClient client, String prefix, Duration leaseTtl, Duration quotaWindow) {
        this.ownedClient = client;
        this.ownsConnection = true;
        this.connection = client.connect();
        this.redis = connection.sync();
        this.keyPrefix = requireText(prefix, "keyPrefix");
        this.leaseTtl = positive(leaseTtl, "leaseTtl");
        this.quotaWindow = positive(quotaWindow, "quotaWindow");
    }

    public RedisRouterStateStore(StatefulRedisConnection<String, String> connection,
                                 String keyPrefix, Duration leaseTtl, Duration quotaWindow) {
        this.ownedClient = null;
        this.ownsConnection = false;
        this.connection = Objects.requireNonNull(connection, "connection");
        this.redis = connection.sync();
        this.keyPrefix = requireText(keyPrefix, "keyPrefix");
        this.leaseTtl = positive(leaseTtl, "leaseTtl");
        this.quotaWindow = positive(quotaWindow, "quotaWindow");
    }

    @Override
    public HealthState health(String namespace, String candidateId) {
        return new RedisHealth(base(namespace, candidateId));
    }

    @Override
    public QuotaState quota(String namespace, String candidateId, ModelLimits limits) {
        return new RedisQuota(base(namespace, candidateId) + ":quota", limits);
    }

    @Override
    public void close() {
        if (ownsConnection) connection.close();
        if (ownedClient != null) ownedClient.shutdown();
    }

    private String base(String namespace, String candidateId) {
        return keyPrefix + ":{" + encode(namespace) + ':' + encode(candidateId) + '}';
    }

    private final class RedisHealth implements HealthState {
        private final String state;
        private final String leases;

        private RedisHealth(String base) {
            state = base + ":health";
            leases = base + ":leases";
        }

        @Override
        public boolean available(long nowMillis) {
            return read(nowMillis).cooldownUntil <= nowMillis;
        }

        @Override
        public int inFlight() {
            return Math.toIntExact(read(System.currentTimeMillis()).inFlight);
        }

        @Override
        public double latencyEwmaMillis() {
            return read(System.currentTimeMillis()).latencyMicros / 1000d;
        }

        @Override
        public void begin() {
            throw new UnsupportedOperationException("use beginAttempt for Redis health state");
        }

        @Override
        public void cancel() {
            throw new UnsupportedOperationException("use HealthAttempt.cancel");
        }

        @Override
        public void success(long durationNanos) {
            throw new UnsupportedOperationException("use HealthAttempt.success");
        }

        @Override
        public boolean failure(long durationNanos, int threshold, Duration cooldown) {
            throw new UnsupportedOperationException("use HealthAttempt.failure");
        }

        @Override
        public HealthAttempt beginAttempt() {
            HealthAttempt attempt = tryBeginAttempt(null);
            if (attempt == null) throw new IllegalStateException("unlimited Redis lease acquisition failed");
            return attempt;
        }

        @Override
        public HealthAttempt tryBeginAttempt(Integer maxConcurrency) {
            String id = UUID.randomUUID().toString();
            long now = System.currentTimeMillis();
            long acquired = redis.eval(HEALTH_BEGIN, ScriptOutputType.INTEGER, new String[]{leases},
                    Long.toString(now), Long.toString(now + leaseTtl.toMillis()), id,
                    maxConcurrency == null ? "-1" : maxConcurrency.toString(),
                    Long.toString(leaseTtl.toMillis() * 2));
            if (acquired == 0) return null;
            return new HealthAttempt() {
                @Override
                public void cancel() {
                    redis.zrem(leases, id);
                }

                @Override
                public void success(long durationNanos) {
                    settle(id, durationNanos, "success", 1, 0);
                }

                @Override
                public boolean failure(long durationNanos, int threshold, Duration cooldown) {
                    return settle(id, durationNanos, "failure", threshold,
                            System.currentTimeMillis() + cooldown.toMillis()) == 1;
                }
            };
        }

        private long settle(String id, long durationNanos, String outcome, int threshold, long cooldownUntil) {
            return redis.eval(HEALTH_SETTLE, ScriptOutputType.INTEGER, new String[]{state, leases},
                    id, Long.toString(Math.max(0, durationNanos / 1_000)), outcome,
                    Integer.toString(threshold), Long.toString(cooldownUntil));
        }

        private HealthSnapshot read(long now) {
            java.util.List<?> values = redis.eval(HEALTH_READ, ScriptOutputType.MULTI,
                    new String[]{state, leases}, Long.toString(now));
            return new HealthSnapshot(number(values.get(0)), number(values.get(1)), number(values.get(2)));
        }
    }

    private final class RedisQuota implements QuotaState {
        private final String key;
        private final ModelLimits limits;

        private RedisQuota(String key, ModelLimits limits) {
            this.key = key;
            this.limits = limits;
        }

        @Override
        public String rejectionReason(int estimatedInputTokens) {
            String reason = acquire(estimatedInputTokens, false);
            return reason.isEmpty() ? null : reason;
        }

        @Override
        public boolean tryAcquire(int estimatedInputTokens) {
            return acquire(estimatedInputTokens, true).isEmpty();
        }

        @Override
        public void recordOutputTokens(long outputTokens) {
            if (outputTokens <= 0) return;
            redis.eval(QUOTA_OUTPUT, ScriptOutputType.INTEGER, new String[]{key},
                    Long.toString(System.currentTimeMillis()), Long.toString(quotaWindow.toMillis()),
                    Long.toString(outputTokens));
        }

        private String acquire(int inputTokens, boolean mutate) {
            return redis.eval(QUOTA_ACQUIRE, ScriptOutputType.VALUE, new String[]{key},
                    Long.toString(System.currentTimeMillis()), Long.toString(quotaWindow.toMillis()),
                    value(limits.requestsPerMinute()), value(limits.tokensPerMinute()),
                    Integer.toString(Math.max(0, inputTokens)), mutate ? "1" : "0");
        }
    }

    private static final class HealthSnapshot {
        private final long cooldownUntil;
        private final long inFlight;
        private final long latencyMicros;

        private HealthSnapshot(long cooldownUntil, long inFlight, long latencyMicros) {
            this.cooldownUntil = cooldownUntil;
            this.inFlight = inFlight;
            this.latencyMicros = latencyMicros;
        }

        private long cooldownUntil() {
            return cooldownUntil;
        }

        private long inFlight() {
            return inFlight;
        }

        private long latencyMicros() {
            return latencyMicros;
        }
    }

    private static long number(Object value) {
        return Long.parseLong(String.valueOf(value));
    }

    private static String value(Long limit) {
        return limit == null ? "-1" : limit.toString();
    }

    private static String encode(String value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(
                requireText(value, "state key component").getBytes(StandardCharsets.UTF_8));
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " must not be blank");
        return value;
    }

    private static Duration positive(Duration value, String name) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return value;
    }
}
