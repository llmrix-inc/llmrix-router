package com.llmrix.model.router.integrations;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.llmrix.model.router.core.model.ModelRequirement;
import com.llmrix.model.router.core.routing.RoutingHints;

import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/** Encodes routing hints for the private HTTP hop between Orion and Router. */
public final class RoutingHintsHttpCodec {
    public static final String HEADER = "X-LLMRix-Routing-Hints";
    private static final ObjectMapper JSON = new ObjectMapper();

    private RoutingHintsHttpCodec() { }

    public static String encode(RoutingHints hints) {
        if (hints == null) return null;
        Map<String, Object> value = new LinkedHashMap<>();
        if (!hints.requirements().isEmpty()) value.put("requirements", hints.requirements().stream().map(Enum::name).toList());
        if (!hints.allowedModels().isEmpty()) value.put("allowedModels", hints.allowedModels());
        if (!hints.deniedModels().isEmpty()) value.put("deniedModels", hints.deniedModels());
        if (hints.maxCostUsd() != null) value.put("maxCostUsd", hints.maxCostUsd());
        if (hints.maxLatency() != null) value.put("maxLatencyMs", hints.maxLatency().toMillis());
        if (!hints.attributes().isEmpty()) value.put("attributes", hints.attributes());
        if (value.isEmpty()) return null;
        try {
            return Base64.getUrlEncoder().withoutPadding().encodeToString(
                    JSON.writeValueAsBytes(value));
        } catch (Exception error) {
            throw new IllegalStateException("cannot encode routing hints", error);
        }
    }

    public static RoutingHints decode(String encoded) {
        if (encoded == null || encoded.isBlank()) return RoutingHints.none();
        try {
            byte[] bytes = Base64.getUrlDecoder().decode(encoded);
            Map<String, Object> value = JSON.readValue(bytes, new TypeReference<>() { });
            RoutingHints.Builder builder = RoutingHints.builder();
            Object requirements = value.get("requirements");
            if (requirements instanceof Iterable<?> items) {
                for (Object item : items) builder.require(ModelRequirement.valueOf(String.valueOf(item)));
            }
            addStrings(builder, value.get("allowedModels"), true);
            addStrings(builder, value.get("deniedModels"), false);
            Object cost = value.get("maxCostUsd");
            if (cost instanceof Number number) builder.maxCostUsd(number.doubleValue());
            Object latency = value.get("maxLatencyMs");
            if (latency instanceof Number number) builder.maxLatency(Duration.ofMillis(number.longValue()));
            Object attributes = value.get("attributes");
            if (attributes instanceof Map<?, ?> map) {
                map.forEach((key, item) -> builder.attribute(String.valueOf(key), String.valueOf(item)));
            }
            return builder.build();
        } catch (Exception error) {
            throw new IllegalArgumentException("invalid " + HEADER + " header", error);
        }
    }

    private static void addStrings(RoutingHints.Builder builder, Object value, boolean allow) {
        if (!(value instanceof Iterable<?> items)) return;
        for (Object item : items) {
            if (allow) builder.allow(String.valueOf(item)); else builder.deny(String.valueOf(item));
        }
    }
}
