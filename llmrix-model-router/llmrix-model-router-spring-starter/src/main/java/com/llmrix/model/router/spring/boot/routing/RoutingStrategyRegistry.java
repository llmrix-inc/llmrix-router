package com.llmrix.model.router.spring.boot.routing;

import com.llmrix.model.router.core.routing.RoutingStrategy;
import com.llmrix.model.router.core.routing.Strategies;

import java.util.LinkedHashMap;
import java.util.Map;

public final class RoutingStrategyRegistry {
    private final Map<String, RoutingStrategy> strategies = new LinkedHashMap<>();

    public RoutingStrategyRegistry() {
        register("priority", Strategies.priority());
        register("round-robin", Strategies.roundRobin());
        register("weighted-random", Strategies.weightedRandom());
        register("least-busy", Strategies.leastBusy());
        register("latency-aware", Strategies.latencyAware());
        register("cost-aware", Strategies.costAware());
        register("balanced", Strategies.balanced());
        register("cache-aware", Strategies.cacheAware());
    }

    public void register(String name, RoutingStrategy strategy) {
        strategies.put(name, strategy);
    }

    public RoutingStrategy get(String name) {
        RoutingStrategy strategy = strategies.get(name);
        if (strategy == null) throw new IllegalArgumentException("unknown routing strategy: " + name);
        return strategy;
    }

    public Map<String, RoutingStrategy> all() {
        return Map.copyOf(strategies);
    }
}
