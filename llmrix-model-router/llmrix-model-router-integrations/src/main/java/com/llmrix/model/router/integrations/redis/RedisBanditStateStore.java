package com.llmrix.model.router.integrations.redis;

import com.llmrix.model.router.core.routing.BanditArmStats;
import com.llmrix.model.router.core.routing.BanditStateStore;
import io.lettuce.core.RedisClient;
import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/** Redis-backed atomic state for contextual bandit selections and rewards. */
public final class RedisBanditStateStore implements BanditStateStore, AutoCloseable {
    private static final String SELECT = "redis.call('SADD',KEYS[1],ARGV[1]); "
            + "redis.call('HINCRBY',KEYS[2],'selections',1); "
            + "return redis.call('HINCRBY',KEYS[3],'total_selections',1)";
    private static final String REWARD = "redis.call('SADD',KEYS[1],ARGV[1]); "
            + "redis.call('HINCRBY',KEYS[2],'observations',1); "
            + "return redis.call('HINCRBYFLOAT',KEYS[2],'reward',ARGV[2])";

    private final RedisClient ownedClient;
    private final StatefulRedisConnection<String, String> connection;
    private final boolean ownsConnection;
    private final RedisCommands<String, String> redis;
    private final String keyPrefix;

    public RedisBanditStateStore(String redisUri) {
        this(RedisClient.create(requireText(redisUri, "redisUri")), "llmrix:model:router");
    }

    private RedisBanditStateStore(RedisClient client, String keyPrefix) {
        this.ownedClient = client;
        this.connection = client.connect();
        this.ownsConnection = true;
        this.redis = connection.sync();
        this.keyPrefix = requireText(keyPrefix, "keyPrefix");
    }

    public RedisBanditStateStore(StatefulRedisConnection<String, String> connection, String keyPrefix) {
        this.ownedClient = null;
        this.connection = Objects.requireNonNull(connection, "connection");
        this.ownsConnection = false;
        this.redis = connection.sync();
        this.keyPrefix = requireText(keyPrefix, "keyPrefix");
    }

    @Override public long totalSelections(String namespace) {
        return parseLong(redis.hget(stateKey(namespace), "total_selections"));
    }

    @Override public BanditArmStats arm(String namespace, String candidateId) {
        Map<String, String> values = redis.hgetall(armKey(namespace, candidateId));
        long selections = parseLong(values.get("selections"));
        long observations = parseLong(values.get("observations"));
        double reward = parseDouble(values.get("reward"));
        return stats(selections, observations, reward);
    }

    @Override public void recordSelection(String namespace, String candidateId) {
        String encoded = encode(requireText(candidateId, "candidateId"));
        redis.eval(SELECT, ScriptOutputType.INTEGER,
                new String[]{candidatesKey(namespace), armKeyEncoded(namespace, encoded), stateKey(namespace)}, encoded);
    }

    @Override public void recordReward(String namespace, String candidateId, double reward) {
        if (!Double.isFinite(reward) || reward < 0 || reward > 1) {
            throw new IllegalArgumentException("reward must be between 0 and 1");
        }
        String encoded = encode(requireText(candidateId, "candidateId"));
        redis.eval(REWARD, ScriptOutputType.VALUE,
                new String[]{candidatesKey(namespace), armKeyEncoded(namespace, encoded)}, encoded, Double.toString(reward));
    }

    @Override public Map<String, BanditArmStats> snapshot(String namespace) {
        Map<String, BanditArmStats> snapshot = new TreeMap<>();
        for (String encoded : redis.smembers(candidatesKey(namespace))) {
            String id = decode(encoded);
            snapshot.put(id, arm(namespace, id));
        }
        return Map.copyOf(snapshot);
    }

    @Override public void restore(String namespace, Map<String, BanditArmStats> snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        String candidates = candidatesKey(namespace);
        for (String encoded : redis.smembers(candidates)) redis.del(armKeyEncoded(namespace, encoded));
        redis.del(candidates, stateKey(namespace));
        long total = 0;
        for (var entry : snapshot.entrySet()) {
            String encoded = encode(entry.getKey());
            BanditArmStats stats = entry.getValue();
            redis.sadd(candidates, encoded);
            redis.hset(armKeyEncoded(namespace, encoded), Map.of(
                    "selections", Long.toString(stats.selections()),
                    "observations", Long.toString(stats.rewardObservations()),
                    "reward", Double.toString(stats.totalReward())));
            total = Math.addExact(total, stats.selections());
        }
        if (total > 0) redis.hset(stateKey(namespace), "total_selections", Long.toString(total));
    }

    @Override public void close() {
        if (ownsConnection) connection.close();
        if (ownedClient != null) ownedClient.shutdown();
    }

    private String root(String namespace) { return keyPrefix + ":{" + encode(requireText(namespace, "namespace")) + "}:bandit"; }
    private String candidatesKey(String namespace) { return root(namespace) + ":candidates"; }
    private String stateKey(String namespace) { return root(namespace) + ":state"; }
    private String armKey(String namespace, String id) { return armKeyEncoded(namespace, encode(requireText(id, "candidateId"))); }
    private String armKeyEncoded(String namespace, String encoded) { return root(namespace) + ":arm:" + encoded; }
    private static BanditArmStats stats(long selections, long observations, double reward) {
        return new BanditArmStats(selections, observations, reward,
                observations == 0 ? Double.NaN : reward / observations);
    }
    private static long parseLong(String value) { return value == null ? 0 : Long.parseLong(value); }
    private static double parseDouble(String value) { return value == null ? 0 : Double.parseDouble(value); }
    private static String encode(String value) { return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8)); }
    private static String decode(String value) { return new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8); }
    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " must not be blank");
        return value;
    }
}
