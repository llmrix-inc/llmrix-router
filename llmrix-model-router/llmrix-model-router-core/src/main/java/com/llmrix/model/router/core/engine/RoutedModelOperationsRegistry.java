package com.llmrix.model.router.core.engine;

import java.util.Map;
import java.util.Objects;
import java.util.Set;

public final class RoutedModelOperationsRegistry implements AutoCloseable {
    private final Map<String, RoutedModelOperations> routes;

    public RoutedModelOperationsRegistry(Map<String, RoutedModelOperations> routes) {
        if (routes == null || routes.isEmpty()) throw new IllegalArgumentException("at least one route is required");
        this.routes = Map.copyOf(routes);
    }

    public RoutedModelOperations get(String routeId) {
        RoutedModelOperations route = routes.get(Objects.requireNonNull(routeId, "routeId"));
        if (route == null) throw new IllegalArgumentException("unknown route: " + routeId);
        return route;
    }

    public Set<String> routeIds() {
        return routes.keySet();
    }

    @Override
    public void close() {
        routes.values().forEach(RoutedModelOperations::close);
    }
}
