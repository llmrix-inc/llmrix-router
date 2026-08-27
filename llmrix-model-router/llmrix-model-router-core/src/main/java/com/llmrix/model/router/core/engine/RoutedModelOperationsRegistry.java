package com.llmrix.model.router.core.engine;

import com.llmrix.model.router.core.exception.UnknownRouteException;

import java.util.Map;
import java.util.Objects;
import java.util.Set;

public final class RoutedModelOperationsRegistry implements AutoCloseable {
    private final Map<String, RoutedModelOperations> routes;
    private final String defaultRoute;

    public RoutedModelOperationsRegistry(Map<String, RoutedModelOperations> routes) {
        this(routes, routes == null || routes.isEmpty() ? null : routes.keySet().iterator().next());
    }

    public RoutedModelOperationsRegistry(Map<String, RoutedModelOperations> routes, String defaultRoute) {
        if (routes == null || routes.isEmpty()) throw new IllegalArgumentException("at least one route is required");
        this.routes = Map.copyOf(routes);
        this.defaultRoute = Objects.requireNonNull(defaultRoute, "defaultRoute");
        if (!this.routes.containsKey(defaultRoute)) {
            throw new IllegalArgumentException("default route does not exist: " + defaultRoute);
        }
    }

    public RoutedModelOperations get(String routeId) {
        RoutedModelOperations route = routes.get(Objects.requireNonNull(routeId, "routeId"));
        if (route == null) throw new UnknownRouteException(routeId);
        return route;
    }

    public Set<String> routeIds() {
        return routes.keySet();
    }

    public String defaultRoute() {
        return defaultRoute;
    }

    @Override
    public void close() {
        routes.values().forEach(RoutedModelOperations::close);
    }
}
