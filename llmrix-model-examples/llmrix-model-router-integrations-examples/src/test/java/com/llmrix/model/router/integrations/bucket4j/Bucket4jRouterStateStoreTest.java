package com.llmrix.model.router.integrations.bucket4j;

import com.llmrix.model.router.core.model.ModelLimits;
import com.llmrix.model.router.core.state.InMemoryRouterStateStore;
import com.llmrix.model.router.core.state.QuotaState;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class Bucket4jRouterStateStoreTest {
    @Test
    void acquiresRequestAndTokenBucketsAtomically() {
        Bucket4jRouterStateStore store = new Bucket4jRouterStateStore();
        QuotaState quota = store.quota("route", "model", new ModelLimits(2L, 10L, null));

        assertTrue(quota.tryAcquire(8));
        assertFalse(quota.tryAcquire(3));
        assertEquals("tokens-per-minute", quota.rejectionReason(3));
        assertTrue(quota.tryAcquire(2));
        assertFalse(quota.tryAcquire(0));
        assertEquals("requests-per-minute", quota.rejectionReason(0));
    }

    @Test
    void outputTokensCreateDebtAgainstFutureRequests() {
        QuotaState quota = new Bucket4jRouterStateStore().quota(
                "route", "model", new ModelLimits(null, 10L, null));

        assertTrue(quota.tryAcquire(4));
        quota.recordOutputTokens(8);

        assertFalse(quota.tryAcquire(1));
        assertEquals("tokens-per-minute", quota.rejectionReason(1));
    }

    @Test
    void supportsConfiguredBurstCapacityAndStateReuse() {
        Bucket4jRouterStateStore store = new Bucket4jRouterStateStore(
                new InMemoryRouterStateStore(), new Bucket4jQuotaOptions(
                        Duration.ofMinutes(1), 2.0, false));
        ModelLimits limits = new ModelLimits(2L, null, null);
        QuotaState first = store.quota("route", "model", limits);

        assertSame(first, store.quota("route", "model", limits));
        assertTrue(first.tryAcquire(0));
        assertTrue(first.tryAcquire(0));
        assertTrue(first.tryAcquire(0));
        assertTrue(first.tryAcquire(0));
        assertFalse(first.tryAcquire(0));
    }
}
