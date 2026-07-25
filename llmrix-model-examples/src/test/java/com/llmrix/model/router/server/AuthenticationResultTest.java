package com.llmrix.model.router.server;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AuthenticationResultTest {
    @Test
    void preservesLegacyVerifierAndProvidesStableIdentity() {
        ApiKeyVerifier verifier = key -> "secret".equals(key);

        AuthenticationResult accepted = verifier.authenticate("secret");
        AuthenticationResult rejected = verifier.authenticate("bad");

        assertTrue(accepted.authenticated());
        assertEquals("api-key", accepted.principal());
        assertEquals("api-key", accepted.quotaKey());
        assertFalse(rejected.authenticated());
    }

    @Test
    void supportsCustomQuotaKeyAndAttributes() {
        AuthenticationResult result = AuthenticationResult.authenticated(
                "tenant-a", "billing-a", Map.of("route", "premium"));

        assertEquals("tenant-a", result.principal());
        assertEquals("billing-a", result.quotaKey());
        assertEquals("premium", result.attributes().get("route"));
        assertThrows(UnsupportedOperationException.class,
                () -> result.attributes().put("x", "y"));
    }
}
