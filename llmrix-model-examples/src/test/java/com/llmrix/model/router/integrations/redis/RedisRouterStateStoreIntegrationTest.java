package com.llmrix.model.router.integrations.redis;

import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import com.llmrix.model.router.core.candidate.ModelLimits;
import com.llmrix.model.router.core.execution.HealthAttempt;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.time.Duration;
import java.util.UUID;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@EnabledIfEnvironmentVariable(named = "LLMRIX_MODEL_ROUTER_REDIS_URI", matches = ".+")
class RedisRouterStateStoreIntegrationTest {
    private RedisClient client;
    private StatefulRedisConnection<String, String> firstConnection;
    private StatefulRedisConnection<String, String> secondConnection;
    private RedisRouterStateStore first;
    private RedisRouterStateStore second;
    private String prefix;

    @BeforeEach
    void setUp() {
        client = RedisClient.create(System.getenv("LLMRIX_MODEL_ROUTER_REDIS_URI"));
        firstConnection = client.connect();
        secondConnection = client.connect();
        prefix = "llmrix-model-router-test-" + UUID.randomUUID();
        first = new RedisRouterStateStore(firstConnection, prefix,
                Duration.ofMillis(150), Duration.ofMinutes(1));
        second = new RedisRouterStateStore(secondConnection, prefix,
                Duration.ofMillis(150), Duration.ofMinutes(1));
    }

    @AfterEach
    void tearDown() {
        if (firstConnection != null) {
            var keys = firstConnection.sync().keys(prefix + "*");
            if (!keys.isEmpty()) firstConnection.sync().del(keys.toArray(String[]::new));
        }
        if (firstConnection != null) firstConnection.close();
        if (secondConnection != null) secondConnection.close();
        if (client != null) client.shutdown();
    }

    @Test
    void sharesAtomicConcurrencyLeasesAcrossConnectionsAndRecoversExpiredOnes() throws Exception {
        var firstHealth = first.health("route", "model");
        var secondHealth = second.health("route", "model");
        HealthAttempt acquired = firstHealth.tryBeginAttempt(1);

        assertNotNull(acquired);
        assertNull(secondHealth.tryBeginAttempt(1));
        assertEquals(1, secondHealth.inFlight());

        Thread.sleep(300);
        HealthAttempt recovered = secondHealth.tryBeginAttempt(1);
        assertNotNull(recovered);
        recovered.cancel();
        assertEquals(0, firstHealth.inFlight());
    }

    @Test
    void sharesCooldownAndAtomicQuotasAcrossConnections() throws Exception {
        var firstHealth = first.health("route", "model");
        var secondHealth = second.health("route", "model");
        HealthAttempt attempt = firstHealth.tryBeginAttempt(1);
        assertNotNull(attempt);
        assertTrue(attempt.failure(Duration.ofMillis(5).toNanos(), 1, Duration.ofMillis(100)));
        assertFalse(secondHealth.available(System.currentTimeMillis()));
        Thread.sleep(200);
        assertTrue(secondHealth.available(System.currentTimeMillis()));

        ModelLimits limits = new ModelLimits(2L, 5L, null);
        var firstQuota = first.quota("route", "model", limits);
        var secondQuota = second.quota("route", "model", limits);
        assertTrue(firstQuota.tryAcquire(3));
        assertFalse(secondQuota.tryAcquire(3));
        assertEquals("tokens-per-minute", secondQuota.rejectionReason(3));
        assertTrue(secondQuota.tryAcquire(2));
        assertFalse(firstQuota.tryAcquire(0));
        assertEquals("requests-per-minute", firstQuota.rejectionReason(0));
    }

    @Test
    void sharesBanditSelectionsRewardsAndSnapshotsAcrossConnections() {
        RedisBanditStateStore firstBandit = new RedisBanditStateStore(firstConnection, prefix);
        RedisBanditStateStore secondBandit = new RedisBanditStateStore(secondConnection, prefix);

        firstBandit.recordSelection("policy", "model-a");
        secondBandit.recordReward("policy", "model-a", 0.75);

        assertEquals(1, secondBandit.totalSelections("policy"));
        assertEquals(1, firstBandit.arm("policy", "model-a").rewardObservations());
        assertEquals(0.75, secondBandit.snapshot("policy").get("model-a").averageReward(), 0.0001);
        firstBandit.restore("policy", Map.of("model-b",
                new com.llmrix.model.router.core.routing.BanditArmStats(2, 1, 0.5, 0.5)));
        assertEquals(2, secondBandit.totalSelections("policy"));
        assertTrue(secondBandit.snapshot("policy").containsKey("model-b"));
        assertFalse(secondBandit.snapshot("policy").containsKey("model-a"));
    }
}
