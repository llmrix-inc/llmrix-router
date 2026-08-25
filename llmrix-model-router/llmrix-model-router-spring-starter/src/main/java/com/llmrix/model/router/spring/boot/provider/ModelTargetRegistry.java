package com.llmrix.model.router.spring.boot.provider;

import com.llmrix.model.router.core.model.ModelTarget;

import java.util.Map;

/**
 * Immutable configured model targets shared by all routed operation engines.
 */
public final class ModelTargetRegistry {
    private final Map<String, ModelTarget> targets;

    public ModelTargetRegistry(Map<String, ModelTarget> targets) {
        if (targets == null || targets.isEmpty())
            throw new IllegalArgumentException("at least one model target is required");
        this.targets = Map.copyOf(targets);
    }

    public ModelTarget get(String id) {
        return targets.get(id);
    }

    public Map<String, ModelTarget> all() {
        return targets;
    }
}
