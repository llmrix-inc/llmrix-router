package com.llmrix.model.examples;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfigurationMetadataTest {
    @Test
    void publishesRouterAndOrionConfigurationMetadata() throws IOException {
        var resources = Thread.currentThread().getContextClassLoader()
                .getResources("META-INF/spring-configuration-metadata.json");
        StringBuilder combined = new StringBuilder();
        for (var resource : Collections.list(resources)) {
            try (var input = resource.openStream()) {
                combined.append(new String(input.readAllBytes(), StandardCharsets.UTF_8));
            }
        }

        String metadata = combined.toString();
        assertTrue(metadata.contains("llmrix.model.router.state.mode"));
        assertTrue(metadata.contains("llmrix.model.router.state.redis.uri"));
        assertTrue(metadata.contains("llmrix.model.router.integrations.*.base-url"));
        assertTrue(metadata.contains("llmrix.model.router.integrations.*.models"));
        assertTrue(metadata.contains("llmrix.model.router.routes.*.models"));
        assertFalse(metadata.contains("llmrix.model.router.routes.*.integrations"));
        assertTrue(metadata.contains("llmrix.model.orion.default-model"));
        assertTrue(metadata.contains("llmrix.model.orion.defaults.chat"));
        assertTrue(metadata.contains("llmrix.model.orion.defaults.embedding"));
        assertTrue(metadata.contains("llmrix.model.orion.defaults.audio"));
        assertTrue(metadata.contains("llmrix.model.orion.defaults.image"));
        assertTrue(metadata.contains("llmrix.model.orion.defaults.video"));
        assertFalse(metadata.contains("\"llm-router."));
    }
}
