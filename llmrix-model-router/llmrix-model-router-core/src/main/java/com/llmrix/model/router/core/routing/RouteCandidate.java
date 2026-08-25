package com.llmrix.model.router.core.routing;

import com.llmrix.model.router.core.model.ModelTarget;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.experimental.Accessors;

import java.util.Objects;

@Getter
@EqualsAndHashCode
@Accessors(fluent = true)
public final class RouteCandidate {
    private final ModelTarget target;
    private final boolean available;
    private final int inFlight;
    private final double latencyEwmaMillis;

    public RouteCandidate(ModelTarget target, boolean available, int inFlight,
                          double latencyEwmaMillis) {
        this.target = Objects.requireNonNull(target, "target");
        this.available = available;
        this.inFlight = inFlight;
        this.latencyEwmaMillis = latencyEwmaMillis;
    }

    public String id() {
        return target.id();
    }
}
