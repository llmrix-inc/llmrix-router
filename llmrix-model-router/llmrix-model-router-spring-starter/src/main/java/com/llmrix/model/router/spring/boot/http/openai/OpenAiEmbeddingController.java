package com.llmrix.model.router.spring.boot.http.openai;

import com.fasterxml.jackson.databind.JsonNode;
import com.llmrix.model.router.core.api.embedding.EmbeddingInput;
import com.llmrix.model.router.core.api.embedding.EmbeddingRequest;
import com.llmrix.model.router.core.api.embedding.EmbeddingResponse;
import com.llmrix.model.router.core.api.embedding.EmbeddingVector;
import com.llmrix.model.router.core.engine.RoutedModelOperationsRegistry;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/v1")
@ConditionalOnProperty(prefix = "llmrix.model.router.http", name = "enabled", havingValue = "true")
public final class OpenAiEmbeddingController {

    private final OpenAiRoutingContext routing;

    @Autowired
    public OpenAiEmbeddingController(ObjectProvider<RoutedModelOperationsRegistry> routes) {
        this(routes.getIfAvailable());
    }

    OpenAiEmbeddingController(RoutedModelOperationsRegistry routes) {
        this.routing = new OpenAiRoutingContext(routes);
    }

    @PostMapping("/embeddings")
    public Map<String, Object> embeddings(@RequestBody JsonNode body, HttpServletRequest servletRequest) {
        String model = text(body, "model", true);
        EmbeddingRequest request = new EmbeddingRequest(parseInputs(body.get("input")),
                encoding(body.path("encoding_format").asText("float")),
                integer(body.get("dimensions")), text(body, "user", false), routing.hints(servletRequest));
        EmbeddingResponse response = routing.route(model).embed(request);
        List<Map<String, Object>> data = response.data().stream().map(OpenAiEmbeddingController::vector).toList();
        return Map.of("object", "list", "data", data, "model", model,
                "usage", Map.of("prompt_tokens", response.usage().inputTokens(),
                        "total_tokens", response.usage().totalTokens()));
    }

    private static Map<String, Object> vector(EmbeddingVector vector) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("object", "embedding");
        item.put("embedding", vector.base64() == null ? vector.values() : vector.base64());
        item.put("index", vector.index());
        return item;
    }

    private static List<EmbeddingInput> parseInputs(JsonNode input) {
        if (input == null || input.isNull()) throw new IllegalArgumentException("input is required");
        if (input.isTextual()) return List.of(EmbeddingInput.text(input.asText()));
        if (!input.isArray() || input.isEmpty()) {
            throw new IllegalArgumentException("input must be a string or non-empty array");
        }
        if (input.get(0).isIntegralNumber()) return List.of(EmbeddingInput.tokens(tokens(input)));
        List<EmbeddingInput> result = new ArrayList<>();
        for (JsonNode item : input) {
            if (item.isTextual()) result.add(EmbeddingInput.text(item.asText()));
            else if (item.isArray()) result.add(EmbeddingInput.tokens(tokens(item)));
            else throw new IllegalArgumentException("input array must contain strings or token arrays");
        }
        return result;
    }

    private static List<Integer> tokens(JsonNode input) {
        List<Integer> values = new ArrayList<>();
        for (JsonNode token : input) {
            if (!token.isIntegralNumber() || !token.canConvertToInt()) {
                throw new IllegalArgumentException("token input must contain 32-bit integers");
            }
            values.add(token.intValue());
        }
        return values;
    }

    private static EmbeddingRequest.EncodingFormat encoding(String value) {
        return switch (value) {
            case "float" -> EmbeddingRequest.EncodingFormat.FLOAT;
            case "base64" -> EmbeddingRequest.EncodingFormat.BASE64;
            default -> throw new IllegalArgumentException("encoding_format must be float or base64");
        };
    }

    static String text(JsonNode body, String name, boolean required) {
        JsonNode value = body == null ? null : body.get(name);
        String result = value == null || value.isNull() ? null : value.asText();
        if (required && (result == null || result.isBlank())) throw new IllegalArgumentException(name + " is required");
        return result;
    }

    static Integer integer(JsonNode value) {
        if (value == null || value.isNull()) return null;
        if (!value.isIntegralNumber() || !value.canConvertToInt())
            throw new IllegalArgumentException("expected integer");
        return value.intValue();
    }
}
