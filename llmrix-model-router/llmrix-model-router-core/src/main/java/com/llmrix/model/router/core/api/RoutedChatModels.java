package com.llmrix.model.router.core.api;

import java.util.Map;
import java.util.Objects;
import java.util.Set;

public final class RoutedChatModels implements AutoCloseable {
    private final Map<String, RoutedChatModel> routes;

    public RoutedChatModels(Map<String, RoutedChatModel> routes) {
        if (routes == null || routes.isEmpty()) throw new IllegalArgumentException("at least one route is required");
        this.routes = Map.copyOf(routes);
    }

    public RoutedChatModel get(String routeId) {
        RoutedChatModel model = routes.get(Objects.requireNonNull(routeId, "routeId"));
        if (model == null) throw new IllegalArgumentException("unknown route: " + routeId);
        return model;
    }

    public Set<String> routeIds() {
        return routes.keySet();
    }

    @Override
    public void close() {
        routes.values().forEach(RoutedChatModel::close);
    }
}
