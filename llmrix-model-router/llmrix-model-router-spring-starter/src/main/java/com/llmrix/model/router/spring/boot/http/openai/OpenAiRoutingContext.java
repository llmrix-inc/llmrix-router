package com.llmrix.model.router.spring.boot.http.openai;

import com.llmrix.model.router.core.engine.RoutedModelOperations;
import com.llmrix.model.router.core.engine.RoutedModelOperationsRegistry;
import com.llmrix.model.router.core.routing.RoutingHints;
import com.llmrix.model.router.core.routing.NoCandidateException;
import com.llmrix.model.router.spring.boot.http.security.AuthenticationResult;
import com.llmrix.model.router.spring.boot.http.web.RequestIdFilter;
import jakarta.servlet.http.HttpServletRequest;

final class OpenAiRoutingContext {
    private final RoutedModelOperationsRegistry routes;

    OpenAiRoutingContext(RoutedModelOperationsRegistry routes) {
        this.routes = routes;
    }

    RoutedModelOperations route(String model) {
        if (model == null || model.isBlank()) throw new IllegalArgumentException("model is required");
        if (routes == null) throw new NoCandidateException("multi-modal routes are not configured");
        if (!routes.routeIds().contains(model)) throw new UnknownModelException(model);
        return routes.get(model);
    }

    RoutedModelOperations routeOrDefault(String model) {
        if (model != null && !model.isBlank()) return route(model);
        if (routes == null || routes.routeIds().isEmpty()) {
            throw new NoCandidateException("multi-modal routes are not configured");
        }
        String route = routes.routeIds().contains("video") ? "video"
                : routes.routeIds().contains("general") ? "general" : routes.routeIds().iterator().next();
        return routes.get(route);
    }

    RoutingHints hints(HttpServletRequest request) {
        if (request == null) return RoutingHints.none();
        RoutingHints.Builder hints = RoutingHints.builder();
        Object requestId = request.getAttribute(RequestIdFilter.ATTRIBUTE);
        if (requestId instanceof String value && !value.isBlank()) {
            hints.attribute(RoutingHints.REQUEST_ID, value);
        }
        Object authentication = request.getAttribute(AuthenticationResult.REQUEST_ATTRIBUTE);
        if (authentication instanceof AuthenticationResult result && result.authenticated()) {
            hints.attribute(RoutingHints.AUTH_PRINCIPAL, result.principal());
            if (result.quotaKey() != null && !result.quotaKey().isBlank()) {
                hints.attribute(RoutingHints.AUTH_QUOTA_KEY, result.quotaKey());
            }
        }
        return hints.build();
    }
}
