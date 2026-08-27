package com.llmrix.model.router.integrations;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/** Minimal JSON request boundary shared by provider adapters. */
public interface JsonTransport {
    ObjectMapper mapper();

    JsonNode postJson(String path, JsonNode payload);
}
