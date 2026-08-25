package com.llmrix.model.router.core.state;

import com.llmrix.model.router.core.model.ModelLimits;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;

class InMemoryRouterStateStoreTest {
    @Test
    void reusesStateWithinNamespaceAndIsolatesRoutes() {
        InMemoryRouterStateStore store = new InMemoryRouterStateStore();
        HealthState first = store.health("route-a", "candidate");

        assertSame(first, store.health("route-a", "candidate"));
        assertNotSame(first, store.health("route-b", "candidate"));
    }

    @Test
    void sharesQuotaStateForSameRouteCandidate() {
        InMemoryRouterStateStore store = new InMemoryRouterStateStore();
        ModelLimits limits = new ModelLimits(1L, null, null);
        QuotaState first = store.quota("route", "candidate", limits);

        assertTrue(first.tryAcquire(1));
        assertFalse(store.quota("route", "candidate", limits).tryAcquire(1));
        assertTrue(store.quota("other-route", "candidate", limits).tryAcquire(1));
    }
}
