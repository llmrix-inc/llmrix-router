package com.llmrix.model.router.spring.boot.actuate;

import com.llmrix.model.router.core.engine.RoutedModelOperationsRegistry;
import com.llmrix.model.router.core.engine.RoutedChatModels;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.actuate.health.Status;

import java.util.LinkedHashMap;
import java.util.Map;

public final class LlmRouterHealthIndicator implements HealthIndicator {
    private static final Status DEGRADED = new Status("DEGRADED");
    private final RoutedModelOperationsRegistry operationRoutes;
    private final RoutedChatModels chatRoutes;

    public LlmRouterHealthIndicator(RoutedModelOperationsRegistry routes) {
        this.operationRoutes = routes;
        this.chatRoutes = null;
    }

    /** Compatibility constructor for applications that only expose chat routes. */
    public LlmRouterHealthIndicator(RoutedChatModels models) {
        this.operationRoutes = null;
        this.chatRoutes = models;
    }

    @Override
    public Health health() {
        Map<String, Object> routes = new LinkedHashMap<>();
        int availableRoutes = 0;
        var routeIds = operationRoutes != null ? operationRoutes.routeIds() : chatRoutes.routeIds();
        for (String routeId : routeIds.stream().sorted().toList()) {
            Map<String, String> candidates = new LinkedHashMap<>();
            boolean routeAvailable = false;
            var snapshots = operationRoutes != null
                    ? operationRoutes.get(routeId).targets() : chatRoutes.get(routeId).targets();
            for (var candidate : snapshots) {
                String state;
                if (!candidate.available()) state = "cooldown";
                else if (candidate.target().limits().maxConcurrency() != null
                        && candidate.inFlight() >= candidate.target().limits().maxConcurrency()) {
                    state = "saturated";
                } else {
                    state = "available";
                    routeAvailable = true;
                }
                candidates.put(candidate.id(), state);
            }
            if (routeAvailable) availableRoutes++;
            routes.put(routeId, Map.of(
                    "status", routeAvailable ? "available" : "unavailable",
                    "candidates", candidates));
        }
        Status status = availableRoutes == routes.size()
                ? Status.UP : availableRoutes == 0 ? Status.DOWN : DEGRADED;
        return Health.status(status)
                .withDetail("availableRoutes", availableRoutes)
                .withDetail("totalRoutes", routes.size())
                .withDetail("routes", routes)
                .build();
    }
}
