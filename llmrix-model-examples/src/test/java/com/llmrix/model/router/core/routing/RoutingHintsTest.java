package com.llmrix.model.router.core.routing;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RoutingHintsTest {
    @Test
    void exposesStandardAuthenticationContextKeys() {
        RoutingHints hints = RoutingHints.builder()
                .attribute(RoutingHints.AUTH_PRINCIPAL, "tenant-a")
                .attribute(RoutingHints.AUTH_QUOTA_KEY, "billing-a")
                .build();

        assertEquals("tenant-a", hints.attributes().get(RoutingHints.AUTH_PRINCIPAL));
        assertEquals("billing-a", hints.attributes().get(RoutingHints.AUTH_QUOTA_KEY));
    }
}
