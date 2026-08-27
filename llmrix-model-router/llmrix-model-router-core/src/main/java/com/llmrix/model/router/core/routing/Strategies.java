package com.llmrix.model.router.core.routing;

import com.llmrix.model.router.core.model.ModelTarget;
import com.llmrix.model.router.core.api.chat.ChatRequest;

import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.time.Duration;

public final class Strategies {
    private Strategies() {
    }

    public static RoutingStrategy priority() {
        return (request, candidates) -> candidates.stream()
                .min(Comparator.comparingInt(snapshot -> snapshot.target().priority()))
                .orElseThrow(() -> new NoCandidateException("no candidate is available"))
                .target();
    }

    public static RoutingStrategy roundRobin() {
        AtomicInteger cursor = new AtomicInteger();
        return (request, candidates) -> {
            if (candidates.isEmpty()) throw new NoCandidateException("no candidate is available");
            return candidates.get(Math.floorMod(cursor.getAndIncrement(), candidates.size())).target();
        };
    }

    public static RoutingStrategy weightedRandom() {
        return (request, candidates) -> {
            int total = candidates.stream().mapToInt(snapshot -> snapshot.target().weight()).sum();
            if (total <= 0) return priority().select(request, candidates);
            int value = ThreadLocalRandom.current().nextInt(total);
            for (RouteCandidate snapshot : candidates) {
                value -= snapshot.target().weight();
                if (value < 0) return snapshot.target();
            }
            return candidates.get(candidates.size() - 1).target();
        };
    }

    public static RoutingStrategy leastBusy() {
        return (request, candidates) -> candidates.stream()
                .min(Comparator.comparingInt(RouteCandidate::inFlight)
                        .thenComparingInt(snapshot -> snapshot.target().priority()))
                .orElseThrow(() -> new NoCandidateException("no candidate is available"))
                .target();
    }

    public static RoutingStrategy latencyAware() {
        return (request, candidates) -> candidates.stream()
                .min(Comparator.<RouteCandidate>comparingDouble(snapshot -> snapshot.latencyEwmaMillis() <= 0
                                ? Double.MAX_VALUE : snapshot.latencyEwmaMillis())
                        .thenComparingInt(snapshot -> snapshot.target().priority()))
                .orElseThrow(() -> new NoCandidateException("no candidate is available"))
                .target();
    }

    public static RoutingStrategy costAware() {
        return (request, candidates) -> candidates.stream()
                .min(Comparator.comparingDouble(snapshot -> estimatedCost(snapshot.target(), request)))
                .orElseThrow(() -> new NoCandidateException("no candidate is available"))
                .target();
    }

    public static RoutingStrategy balanced() {
        return (request, candidates) -> candidates.stream()
                .min(Comparator.comparingDouble(snapshot -> balancedScore(snapshot, request.estimatedInputTokens())))
                .orElseThrow(() -> new NoCandidateException("no candidate is available"))
                .target();
    }

    /** Keeps requests with the same prompt-cache key on the same eligible target. */
    public static RoutingStrategy cacheAware() {
        return cacheAware(balanced(), Duration.ofMinutes(10), 10_000);
    }

    public static RoutingStrategy cacheAware(RoutingStrategy fallback) {
        return cacheAware(fallback, Duration.ofMinutes(10), 10_000);
    }

    /** Creates cache affinity with an explicit retention period and bounded entry count. */
    public static RoutingStrategy cacheAware(RoutingStrategy fallback, Duration retention, int maxEntries) {
        if (fallback == null) throw new IllegalArgumentException("fallback strategy must not be null");
        if (retention == null || retention.isZero() || retention.isNegative()) {
            throw new IllegalArgumentException("cache affinity retention must be > 0");
        }
        if (maxEntries < 1) throw new IllegalArgumentException("cache affinity maxEntries must be > 0");
        long ttlNanos = retention.toNanos();
        Map<String, AffinityEntry> affinity = new ConcurrentHashMap<>();
        return (request, candidates) -> {
            String key = request instanceof ChatRequest chat && chat.promptCache() != null
                    ? chat.promptCache().key() : request.routingHints().attributes().get("prompt_cache_key");
            if (key == null || key.isBlank()) return fallback.select(request, candidates);
            long now = System.nanoTime();
            AffinityEntry entry = affinity.get(key);
            if (entry != null && entry.expiresAtNanos() > now) {
                String preferred = entry.targetId();
                for (RouteCandidate candidate : candidates) {
                    if (preferred.equals(candidate.id())) return candidate.target();
                }
            } else if (entry != null) {
                affinity.remove(key, entry);
            }
            ModelTarget selected = fallback.select(request, candidates);
            affinity.put(key, new AffinityEntry(selected.id(), now + ttlNanos));
            if (affinity.size() > maxEntries) {
                affinity.entrySet().removeIf(value -> value.getValue().expiresAtNanos() <= now);
                if (affinity.size() > maxEntries) {
                    affinity.keySet().stream().findAny().ifPresent(affinity::remove);
                }
            }
            return selected;
        };
    }

    private record AffinityEntry(String targetId, long expiresAtNanos) {
    }

    public static RoutingStrategy semantic(SemanticClassifier classifier) {
        return semantic(classifier, balanced());
    }

    public static RoutingStrategy semantic(SemanticClassifier classifier, RoutingStrategy fallback) {
        return (request, candidates) -> {
            if (!(request instanceof com.llmrix.model.router.core.api.chat.ChatRequest chatRequest)) {
                return fallback.select(request, candidates);
            }
            Map<String, Double> scores = classifier.score(chatRequest, List.copyOf(candidates));
            if (scores == null) throw new IllegalArgumentException("semantic classifier returned null scores");
            RouteCandidate selected = candidates.stream()
                    .filter(candidate -> {
                        Double score = scores.get(candidate.id());
                        return score != null && Double.isFinite(score);
                    })
                    .max(Comparator.comparingDouble(candidate -> scores.get(candidate.id())))
                    .orElse(null);
            return selected == null ? fallback.select(request, candidates) : selected.target();
        };
    }

    private static double balancedScore(RouteCandidate snapshot, int inputTokens) {
        ModelTarget candidate = snapshot.target();
        double latency = snapshot.latencyEwmaMillis() <= 0 ? 500 : snapshot.latencyEwmaMillis();
        double cost = estimatedInputCost(candidate, inputTokens);
        if (!Double.isFinite(cost)) cost = 0.001;
        return candidate.priority() * 0.25 + snapshot.inFlight() * 10 + latency * 0.01 + cost * 1_000;
    }

    private static double estimatedInputCost(ModelTarget candidate, int inputTokens) {
        Double rate = candidate.pricing().inputCostPerMillion();
        return rate == null ? Double.MAX_VALUE : inputTokens * rate / 1_000_000d;
    }

    private static double estimatedCost(ModelTarget candidate, com.llmrix.model.router.core.api.ModelRequest request) {
        return estimatedCost(candidate, request.estimatedInputTokens(), request.estimatedOutputTokens());
    }

    private static double estimatedCost(ModelTarget candidate, int inputTokens, int outputTokens) {
        return candidate.pricing().estimateCost(inputTokens, outputTokens);
    }

}
