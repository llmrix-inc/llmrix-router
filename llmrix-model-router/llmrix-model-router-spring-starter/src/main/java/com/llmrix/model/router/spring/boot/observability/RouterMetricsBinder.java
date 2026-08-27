package com.llmrix.model.router.spring.boot.observability;

import com.llmrix.model.router.core.engine.RoutedModelOperationsRegistry;
import com.llmrix.model.router.core.engine.RoutedChatModels;
import com.llmrix.model.router.core.routing.RouteCandidate;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;

public final class RouterMetricsBinder implements MeterBinder {
    private final RoutedModelOperationsRegistry operationRoutes;
    private final RoutedChatModels chatRoutes;
    private final boolean includeCandidateId;

    public RouterMetricsBinder(RoutedModelOperationsRegistry routes, boolean includeCandidateId) {
        this.operationRoutes = routes;
        this.chatRoutes = null;
        this.includeCandidateId = includeCandidateId;
    }

    /** Compatibility constructor for applications that only expose chat routes. */
    public RouterMetricsBinder(RoutedChatModels models, boolean includeCandidateId) {
        this.operationRoutes = null;
        this.chatRoutes = models;
        this.includeCandidateId = includeCandidateId;
    }

    @Override
    public void bindTo(MeterRegistry registry) {
        var routeIds = operationRoutes != null ? operationRoutes.routeIds() : chatRoutes.routeIds();
        routeIds.forEach(route -> {
            var snapshots = snapshots(route);
            if (includeCandidateId) {
                snapshots.forEach(snapshot -> bindCandidate(registry, route, snapshot.id()));
            } else {
                Gauge.builder("llm.router.in.flight", this, ignored -> snapshots(route).stream()
                                .mapToInt(RouteCandidate::inFlight).sum())
                        .tag("route", route).register(registry);
                Gauge.builder("llm.router.available", this, ignored -> snapshots(route).stream()
                                .filter(RouteCandidate::available).count())
                        .tag("route", route).register(registry);
            }
        });
    }

    private void bindCandidate(MeterRegistry registry, String route, String candidateId) {
        Gauge.builder("llm.router.in.flight", this, ignored -> snapshot(route, candidateId).inFlight())
                .tag("route", route).tag("candidate", candidateId).register(registry);
        Gauge.builder("llm.router.available", this, ignored -> snapshot(route, candidateId).available() ? 1 : 0)
                .tag("route", route).tag("candidate", candidateId).register(registry);
    }

    private RouteCandidate snapshot(String route, String candidateId) {
        return snapshots(route).stream().filter(value -> value.id().equals(candidateId))
                .findFirst().orElseThrow();
    }

    private java.util.List<RouteCandidate> snapshots(String route) {
        return operationRoutes != null ? operationRoutes.get(route).targets() : chatRoutes.get(route).targets();
    }
}
