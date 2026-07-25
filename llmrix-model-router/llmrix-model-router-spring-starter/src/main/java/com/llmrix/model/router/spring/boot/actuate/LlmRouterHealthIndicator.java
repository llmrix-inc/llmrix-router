package com.llmrix.model.router.spring.boot.actuate;

import com.llmrix.model.router.core.api.RoutedChatModels;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.actuate.health.Status;

import java.util.LinkedHashMap;
import java.util.Map;

public final class LlmRouterHealthIndicator implements HealthIndicator {
    private static final Status DEGRADED = new Status("DEGRADED");
    private final RoutedChatModels models;

    public LlmRouterHealthIndicator(RoutedChatModels models) {
        this.models = models;
    }

    @Override
    public Health health() {
        Map<String, Object> routes = new LinkedHashMap<>();
        int availableRoutes = 0;
        for (String routeId : models.routeIds().stream().sorted().toList()) {
            Map<String, String> candidates = new LinkedHashMap<>();
            boolean routeAvailable = false;
            for (var candidate : models.get(routeId).candidates()) {
                String state;
                if (!candidate.available()) state = "cooldown";
                else if (candidate.candidate().limits().maxConcurrency() != null
                        && candidate.inFlight() >= candidate.candidate().limits().maxConcurrency()) {
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
