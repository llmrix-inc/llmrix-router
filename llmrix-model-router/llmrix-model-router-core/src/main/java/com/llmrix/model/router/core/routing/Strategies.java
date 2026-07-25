package com.llmrix.model.router.core.routing;

import com.llmrix.model.router.core.candidate.Candidate;

import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.Map;

public final class Strategies {
    private Strategies() {}

    public static RoutingStrategy priority() {
        return (request, candidates) -> candidates.stream()
                .min(Comparator.comparingInt(snapshot -> snapshot.candidate().priority()))
                .orElseThrow(() -> new NoCandidateException("no candidate is available"))
                .candidate();
    }

    public static RoutingStrategy roundRobin() {
        AtomicInteger cursor = new AtomicInteger();
        return (request, candidates) -> {
            if (candidates.isEmpty()) throw new NoCandidateException("no candidate is available");
            return candidates.get(Math.floorMod(cursor.getAndIncrement(), candidates.size())).candidate();
        };
    }

    public static RoutingStrategy weightedRandom() {
        return (request, candidates) -> {
            int total = candidates.stream().mapToInt(snapshot -> snapshot.candidate().weight()).sum();
            if (total <= 0) return priority().select(request, candidates);
            int value = ThreadLocalRandom.current().nextInt(total);
            for (CandidateSnapshot snapshot : candidates) {
                value -= snapshot.candidate().weight();
                if (value < 0) return snapshot.candidate();
            }
            return candidates.get(candidates.size() - 1).candidate();
        };
    }

    public static RoutingStrategy leastBusy() {
        return (request, candidates) -> candidates.stream()
                .min(Comparator.comparingInt(CandidateSnapshot::inFlight)
                        .thenComparingInt(snapshot -> snapshot.candidate().priority()))
                .orElseThrow(() -> new NoCandidateException("no candidate is available"))
                .candidate();
    }

    public static RoutingStrategy latencyAware() {
        return (request, candidates) -> candidates.stream()
                .min(Comparator.<CandidateSnapshot>comparingDouble(snapshot -> snapshot.latencyEwmaMillis() <= 0
                                ? Double.MAX_VALUE : snapshot.latencyEwmaMillis())
                        .thenComparingInt(snapshot -> snapshot.candidate().priority()))
                .orElseThrow(() -> new NoCandidateException("no candidate is available"))
                .candidate();
    }

    public static RoutingStrategy costAware() {
        return (request, candidates) -> candidates.stream()
                .min(Comparator.comparingDouble(snapshot -> estimatedInputCost(snapshot.candidate(), request.estimatedInputTokens())))
                .orElseThrow(() -> new NoCandidateException("no candidate is available"))
                .candidate();
    }

    public static RoutingStrategy balanced() {
        return (request, candidates) -> candidates.stream()
                .min(Comparator.comparingDouble(snapshot -> balancedScore(snapshot, request.estimatedInputTokens())))
                .orElseThrow(() -> new NoCandidateException("no candidate is available"))
                .candidate();
    }

    public static RoutingStrategy semantic(SemanticClassifier classifier) {
        return semantic(classifier, balanced());
    }

    public static RoutingStrategy semantic(SemanticClassifier classifier, RoutingStrategy fallback) {
        return (request, candidates) -> {
            Map<String, Double> scores = classifier.score(request, List.copyOf(candidates));
            if (scores == null) throw new IllegalArgumentException("semantic classifier returned null scores");
            CandidateSnapshot selected = candidates.stream()
                    .filter(candidate -> {
                        Double score = scores.get(candidate.id());
                        return score != null && Double.isFinite(score);
                    })
                    .max(Comparator.comparingDouble(candidate -> scores.get(candidate.id())))
                    .orElse(null);
            return selected == null ? fallback.select(request, candidates) : selected.candidate();
        };
    }

    private static double balancedScore(CandidateSnapshot snapshot, int inputTokens) {
        Candidate candidate = snapshot.candidate();
        double latency = snapshot.latencyEwmaMillis() <= 0 ? 500 : snapshot.latencyEwmaMillis();
        double cost = estimatedInputCost(candidate, inputTokens);
        if (!Double.isFinite(cost)) cost = 0.001;
        return candidate.priority() * 0.25 + snapshot.inFlight() * 10 + latency * 0.01 + cost * 1_000;
    }

    private static double estimatedInputCost(Candidate candidate, int inputTokens) {
        Double rate = candidate.pricing().inputCostPerMillion();
        return rate == null ? Double.MAX_VALUE : inputTokens * rate / 1_000_000d;
    }

    public static List<String> names() {
        return List.of("priority", "round-robin", "weighted-random", "least-busy", "latency-aware", "cost-aware", "balanced");
    }
}
