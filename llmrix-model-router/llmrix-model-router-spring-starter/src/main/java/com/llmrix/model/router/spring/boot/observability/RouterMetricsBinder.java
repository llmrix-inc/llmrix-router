package com.llmrix.model.router.spring.boot.observability;

import com.llmrix.model.router.core.engine.RoutedChatModels;
import com.llmrix.model.router.core.routing.RouteCandidate;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;

public final class RouterMetricsBinder implements MeterBinder {
    private final RoutedChatModels models;
    private final boolean includeCandidateId;

    public RouterMetricsBinder(RoutedChatModels models, boolean includeCandidateId) {
        this.models = models;
        this.includeCandidateId = includeCandidateId;
    }

    @Override
    public void bindTo(MeterRegistry registry) {
        models.routeIds().forEach(route -> {
            if (includeCandidateId) {
                models.get(route).targets().forEach(snapshot -> bindCandidate(registry, route, snapshot.id()));
            } else {
                Gauge.builder("llm.router.in.flight", models, ignored -> models.get(route).targets().stream()
                                .mapToInt(RouteCandidate::inFlight).sum())
                        .tag("route", route).register(registry);
                Gauge.builder("llm.router.available", models, ignored -> models.get(route).targets().stream()
                                .filter(RouteCandidate::available).count())
                        .tag("route", route).register(registry);
            }
        });
    }

    private void bindCandidate(MeterRegistry registry, String route, String candidateId) {
        Gauge.builder("llm.router.in.flight", models, ignored -> snapshot(route, candidateId).inFlight())
                .tag("route", route).tag("candidate", candidateId).register(registry);
        Gauge.builder("llm.router.available", models, ignored -> snapshot(route, candidateId).available() ? 1 : 0)
                .tag("route", route).tag("candidate", candidateId).register(registry);
    }

    private RouteCandidate snapshot(String route, String candidateId) {
        return models.get(route).targets().stream().filter(value -> value.id().equals(candidateId))
                .findFirst().orElseThrow();
    }
}
