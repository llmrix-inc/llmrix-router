package com.llmrix.model.router.spring.boot.http.openai;

import com.fasterxml.jackson.databind.JsonNode;
import com.llmrix.model.router.core.api.rerank.RerankRequest;
import com.llmrix.model.router.core.api.rerank.RerankResponse;
import com.llmrix.model.router.core.api.rerank.RerankResult;
import com.llmrix.model.router.core.engine.RoutedModelOperationsRegistry;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Cohere/Jina-compatible rerank endpoint backed by the normal routed operations. */
@RestController
@RequestMapping("/v1")
@ConditionalOnProperty(prefix = "llmrix.model.router.http", name = "enabled", havingValue = "true")
public final class OpenAiRerankController {
    private final OpenAiRoutingContext routing;

    @Autowired
    public OpenAiRerankController(ObjectProvider<RoutedModelOperationsRegistry> routes) {
        this(routes.getIfAvailable());
    }

    OpenAiRerankController(RoutedModelOperationsRegistry routes) {
        this.routing = new OpenAiRoutingContext(routes);
    }

    @PostMapping("/rerank")
    public Map<String, Object> rerank(@RequestBody JsonNode body, HttpServletRequest servletRequest) {
        String model = OpenAiRequestParser.text(body, "model", true);
        String query = OpenAiRequestParser.text(body, "query", true);
        List<String> documents = documents(body.get("documents"));
        RerankRequest request = new RerankRequest(query, documents,
                OpenAiRequestParser.integer(body.get("top_n")),
                booleanValue(body.get("return_documents")), routing.hints(servletRequest));
        RerankResponse response = routing.route(model).rerank(request);
        List<Map<String, Object>> results = response.results().stream()
                .map(result -> result(result, request.returnDocuments())).toList();
        return Map.of("id", "rerank_" + UUID.randomUUID().toString().replace("-", ""),
                "results", results, "model", model,
                "usage", Map.of("input_tokens", response.usage().inputTokens(),
                        "total_tokens", response.usage().totalTokens()));
    }

    private static List<String> documents(JsonNode value) {
        if (value == null || !value.isArray() || value.isEmpty())
            throw new IllegalArgumentException("documents must be a non-empty array");
        List<String> result = new ArrayList<>();
        for (JsonNode item : value) {
            if (!item.isTextual()) throw new IllegalArgumentException("documents must contain strings");
            result.add(item.asText());
        }
        return result;
    }

    private static boolean booleanValue(JsonNode value) {
        if (value == null || value.isNull()) return false;
        if (!value.isBoolean()) throw new IllegalArgumentException("return_documents must be boolean");
        return value.booleanValue();
    }

    private static Map<String, Object> result(RerankResult result, boolean returnDocuments) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("index", result.index());
        item.put("relevance_score", result.relevanceScore());
        if (returnDocuments && result.document() != null) item.put("document", Map.of("text", result.document()));
        return item;
    }
}
