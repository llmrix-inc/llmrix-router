package com.llmrix.model.router.integrations.fugu;

import com.llmrix.model.router.core.api.ChatModel;
import com.llmrix.model.router.core.api.ChatRequest;
import com.llmrix.model.router.core.api.ChatResponse;
import com.llmrix.model.router.core.api.Usage;
import com.llmrix.model.router.core.api.ChatChunk;
import com.llmrix.model.router.core.exception.BudgetExceededException;
import com.llmrix.model.router.core.exception.ModelTimeoutException;
import com.llmrix.model.router.core.exception.ModelException;
import com.llmrix.model.router.core.candidate.ModelPricing;
import com.llmrix.model.router.core.execution.RouterStateStore;
import com.llmrix.model.router.core.execution.HealthState;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.Future;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CompletableFuture;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

public final class FuguOrchestrator implements ChatModel {
    private final Map<String, ChatModel> candidates;
    private final FuguRouter router;
    private final int maxTurns;
    private final String acceptToken;
    private final Duration timeout;
    private final Integer tokenBudget;
    private final FuguStopCondition stopCondition;
    private final ExecutorService executor;
    private final ExecutorService asyncExecutor;
    private final FuguPromptTemplate promptTemplate;
    private final FuguListener listener;
    private final int maxRetries;
    private final Predicate<RuntimeException> retryPredicate;
    private final Map<String, List<String>> fallbacks;
    private final Predicate<RuntimeException> fallbackPredicate;
    private final Duration cooldown;
    private final Map<String, Long> cooldownUntil = new ConcurrentHashMap<>();
    private final Map<String, ModelPricing> pricing;
    private final Double maxCostUsd;
    private final RouterStateStore stateStore;
    private final String stateNamespace;

    private FuguOrchestrator(Builder builder) {
        if (builder.candidates.isEmpty()) throw new IllegalArgumentException("at least one candidate is required");
        this.candidates = Collections.unmodifiableMap(new LinkedHashMap<>(builder.candidates));
        this.router = Objects.requireNonNull(builder.router, "router");
        this.maxTurns = builder.maxTurns;
        this.acceptToken = builder.acceptToken;
        this.timeout = builder.timeout;
        this.tokenBudget = builder.tokenBudget;
        this.stopCondition = builder.stopCondition;
        this.executor = builder.executor;
        this.asyncExecutor = builder.asyncExecutor;
        this.promptTemplate = builder.promptTemplate;
        this.listener = builder.listener;
        this.maxRetries = builder.maxRetries;
        this.retryPredicate = builder.retryPredicate;
        Map<String, List<String>> configuredFallbacks = new LinkedHashMap<>();
        builder.fallbacks.forEach((key, value) -> configuredFallbacks.put(key, List.copyOf(value)));
        this.fallbacks = Collections.unmodifiableMap(configuredFallbacks);
        this.fallbackPredicate = builder.fallbackPredicate;
        this.cooldown = builder.cooldown;
        this.pricing = Map.copyOf(builder.pricing);
        this.maxCostUsd = builder.maxCostUsd;
        this.stateStore = builder.stateStore;
        this.stateNamespace = builder.stateNamespace;
        if (maxCostUsd != null && pricing.values().stream().anyMatch(value ->
                !Double.isFinite(value.estimateCost(1, 1)))) {
            throw new IllegalArgumentException("all Fugu candidates require pricing when maxCostUsd is configured");
        }
        fallbacks.forEach((primary, backups) -> {
            if (!candidates.containsKey(primary)) throw new IllegalArgumentException("fallback primary is unknown: " + primary);
            backups.forEach(backup -> {
                if (!candidates.containsKey(backup)) throw new IllegalArgumentException("fallback candidate is unknown: " + backup);
            });
        });
    }

    public static Builder builder() { return new Builder(); }

    @Override
    public ChatResponse chat(ChatRequest request) {
        String requestId = UUID.randomUUID().toString();
        long started = System.nanoTime();
        notifySafely(() -> listener.onStarted(new FuguStarted(requestId, maxTurns)));
        List<FuguTurn> turns = new ArrayList<>();
        try {
            return execute(request, requestId, started, turns);
        } catch (RuntimeException failure) {
            notifySafely(() -> listener.onCompleted(new FuguCompleted(
                    requestId, turns.size(), System.nanoTime() - started, false, "error",
                    failure.getClass().getSimpleName())));
            throw failure;
        }
    }

    @Override
    public CompletionStage<ChatResponse> chatAsync(ChatRequest request) {
        CompletableFuture<ChatResponse> result = new CompletableFuture<>();
        Future<?> task = asyncExecutor.submit(() -> {
            try {
                result.complete(chat(request));
            } catch (Throwable error) {
                result.completeExceptionally(error);
            }
        });
        result.whenComplete((ignored, failure) -> {
            if (result.isCancelled()) task.cancel(true);
        });
        return result;
    }

    /**
     * Fugu requires complete role outputs before choosing the next action, so this publisher emits the
     * completed orchestration result rather than pretending to provide token-level turn streaming.
     */
    @Override
    public Flow.Publisher<ChatChunk> stream(ChatRequest request) {
        return subscriber -> subscriber.onSubscribe(new Flow.Subscription() {
            private final AtomicBoolean started = new AtomicBoolean();
            private final AtomicBoolean cancelled = new AtomicBoolean();
            private final AtomicReference<CompletableFuture<ChatResponse>> future = new AtomicReference<>();
            private long demand;
            private ChatResponse response;
            private Throwable failure;
            private int stage;
            private boolean completed;

            @Override public synchronized void request(long n) {
                if (cancelled.get() || completed) return;
                if (n <= 0) {
                    completed = true;
                    subscriber.onError(new IllegalArgumentException("subscription demand must be positive"));
                    return;
                }
                demand = demand > Long.MAX_VALUE - n ? Long.MAX_VALUE : demand + n;
                if (started.compareAndSet(false, true)) {
                    CompletableFuture<ChatResponse> active = chatAsync(request).toCompletableFuture();
                    future.set(active);
                    if (cancelled.get()) active.cancel(true);
                    active.whenComplete((value, error) -> {
                        synchronized (this) {
                            response = value;
                            failure = unwrap(error);
                            drain(subscriber);
                        }
                    });
                }
                drain(subscriber);
            }

            @Override public synchronized void cancel() {
                if (!cancelled.compareAndSet(false, true)) return;
                CompletableFuture<ChatResponse> active = future.getAndSet(null);
                if (active != null) active.cancel(true);
            }

            private void drain(Flow.Subscriber<? super ChatChunk> downstream) {
                if (cancelled.get() || completed || (!started.get()) || (response == null && failure == null)) return;
                if (failure != null) {
                    completed = true;
                    downstream.onError(failure);
                    return;
                }
                while (demand > 0 && !cancelled.get() && !completed) {
                    demand--;
                    if (stage++ == 0) {
                        downstream.onNext(new ChatChunk(response.text(), false, response.usage()));
                    } else {
                        downstream.onNext(new ChatChunk("", true, response.usage(), List.of(), response.finishReason()));
                        completed = true;
                        downstream.onComplete();
                    }
                }
            }
        });
    }

    private static Throwable unwrap(Throwable error) {
        if (error instanceof java.util.concurrent.CompletionException completion && completion.getCause() != null) {
            return completion.getCause();
        }
        return error;
    }

    private ChatResponse execute(ChatRequest request, String requestId, long started, List<FuguTurn> turns) {
        String latestAnswer = null;
        String suggestion = null;
        String lastResponse = null;
        long deadline = timeout == null ? Long.MAX_VALUE : System.nanoTime() + timeout.toNanos();
        int usedTokens = 0;
        double[] spentUsd = {0};

        for (int index = 0; index < maxTurns; index++) {
            if (System.nanoTime() >= deadline) throw new ModelTimeoutException(
                    "Fugu orchestration timed out", new TimeoutException());
            FuguState state = new FuguState(request, List.copyOf(candidates.keySet()), turns, latestAnswer, suggestion);
            FuguAction action = Objects.requireNonNull(router.route(state), "router action");
            if (!candidates.containsKey(action.candidateId())) throw new IllegalArgumentException(
                    "router selected unknown candidate: " + action.candidateId());
            FuguRole effectiveRole = action.role() == FuguRole.VERIFIER && latestAnswer == null
                    ? FuguRole.WORKER : action.role();
            long turnStarted = System.nanoTime();
            int turnIndex = index;
            notifySafely(() -> listener.onTurnStarted(new FuguTurnStarted(
                    requestId, turnIndex, action.candidateId(), effectiveRole)));
            ChatRequest candidateRequest = promptTemplate.create(request, effectiveRole, latestAnswer, suggestion);
            Invocation invocation = invokeWithFallback(
                    requestId, action.candidateId(), candidateRequest, deadline, spentUsd);
            ChatResponse response = invocation.response();
            FuguAction effectiveAction = new FuguAction(invocation.candidateId(), effectiveRole);
            lastResponse = response.text();
            usedTokens += Math.max(1, (lastResponse.length() + 3) / 4);
            if (tokenBudget != null && usedTokens > tokenBudget) {
                throw new BudgetExceededException("Fugu token budget exceeded");
            }
            turns.add(new FuguTurn(index, effectiveAction, lastResponse));
            ChatResponse completedResponse = response;
            notifySafely(() -> listener.onTurnCompleted(new FuguTurnCompleted(
                    requestId, turns.size() - 1, effectiveAction.candidateId(), effectiveRole,
                    System.nanoTime() - turnStarted, completedResponse.usage())));

            if (effectiveRole == FuguRole.WORKER) {
                latestAnswer = lastResponse;
                suggestion = null;
            } else if (effectiveRole == FuguRole.THINKER) {
                suggestion = lastResponse;
            } else if (startsWithAccept(lastResponse)) {
                return complete(requestId, started, latestAnswer, turns, "verifier-accept");
            }
            FuguState completedState = new FuguState(
                    request, List.copyOf(candidates.keySet()), turns, latestAnswer, suggestion);
            var customTermination = stopCondition.shouldStop(completedState);
            if (customTermination.isPresent()) {
                return complete(requestId, started, latestAnswer == null ? lastResponse : latestAnswer,
                        turns, customTermination.get());
            }
        }
        return complete(requestId, started, latestAnswer == null ? lastResponse : latestAnswer, turns, "max-turns");
    }

    private Invocation invokeWithFallback(
            String requestId, String primaryId, ChatRequest request, long deadline, double[] spentUsd) {
        List<String> order = new ArrayList<>();
        order.add(primaryId);
        order.addAll(fallbacks.getOrDefault(primaryId, List.of()));
        RuntimeException lastFailure = null;
        String failedCandidate = null;
        for (int index = 0; index < order.size(); index++) {
            String candidateId = order.get(index);
            if (coolingDown(candidateId)) continue;
            if (lastFailure != null) {
                RuntimeException failure = lastFailure;
                String from = failedCandidate;
                notifySafely(() -> listener.onFallback(new FuguFallback(
                        requestId, from, candidateId, failure.getClass().getSimpleName())));
            }
            try {
                ChatResponse response = invokeCandidate(
                        requestId, candidateId, candidates.get(candidateId), request, deadline, spentUsd);
                if (stateStore == null) cooldownUntil.remove(candidateId);
                return new Invocation(candidateId, response);
            } catch (RuntimeException failure) {
                lastFailure = failure;
                failedCandidate = candidateId;
                if (!fallbackPredicate.test(failure)) throw failure;
                if (cooldown != null) {
                    if (stateStore == null) cooldownUntil.put(candidateId, System.nanoTime() + cooldown.toNanos());
                    else stateStore.health(stateNamespace, candidateId).beginAttempt()
                            .failure(0, 1, cooldown);
                    notifySafely(() -> listener.onCandidateCooldown(
                            new FuguCandidateCooldown(requestId, candidateId, cooldown)));
                }
            }
        }
        if (lastFailure != null) throw lastFailure;
        throw new com.llmrix.model.router.core.exception.ModelUnavailableException("all Fugu candidates are cooling down");
    }

    private boolean coolingDown(String candidateId) {
        if (stateStore != null) return !stateStore.health(stateNamespace, candidateId)
                .available(System.currentTimeMillis());
        Long until = cooldownUntil.get(candidateId);
        if (until == null) return false;
        if (System.nanoTime() < until) return true;
        cooldownUntil.remove(candidateId, until);
        return false;
    }

    private ChatResponse invokeCandidate(
            String requestId, String candidateId, ChatModel candidate, ChatRequest request,
            long deadline, double[] spentUsd) {
        for (int attempt = 0; ; attempt++) {
            Future<ChatResponse> call = null;
            double reservedCost = estimatedCost(candidateId, request);
            if (maxCostUsd != null && spentUsd[0] + reservedCost > maxCostUsd) {
                throw new BudgetExceededException("Fugu cost budget exceeded before candidate " + candidateId);
            }
            spentUsd[0] += reservedCost;
            try {
                long remaining = deadline == Long.MAX_VALUE ? Long.MAX_VALUE : deadline - System.nanoTime();
                if (remaining <= 0) throw new TimeoutException();
                call = executor.submit(() -> candidate.chat(request));
                ChatResponse response = remaining == Long.MAX_VALUE ? call.get() : call.get(remaining, TimeUnit.NANOSECONDS);
                if (response.usage().totalTokens() >= 0) {
                    spentUsd[0] += pricing.get(candidateId).estimateCost(
                            response.usage().inputTokens(), response.usage().outputTokens()) - reservedCost;
                }
                return response;
            } catch (TimeoutException e) {
                if (call != null) call.cancel(true);
                ModelTimeoutException failure = new ModelTimeoutException("Fugu candidate timed out", e);
                if (attempt >= maxRetries || !retryPredicate.test(failure)) throw failure;
                int nextAttempt = attempt + 2;
                notifySafely(() -> listener.onRetry(new FuguRetry(
                        requestId, candidateId, nextAttempt, failure.getClass().getSimpleName())));
            } catch (InterruptedException e) {
                if (call != null) call.cancel(true);
                Thread.currentThread().interrupt();
                throw new ModelTimeoutException("Fugu candidate interrupted", e);
            } catch (Exception e) {
                RuntimeException failure = e.getCause() instanceof RuntimeException runtime
                        ? runtime : new RuntimeException("Fugu candidate failed", e);
                if (attempt >= maxRetries || !retryPredicate.test(failure)) throw failure;
                int nextAttempt = attempt + 2;
                notifySafely(() -> listener.onRetry(new FuguRetry(
                        requestId, candidateId, nextAttempt, failure.getClass().getSimpleName())));
            }
        }
    }

    private double estimatedCost(String candidateId, ChatRequest request) {
        if (maxCostUsd == null) return 0;
        int outputTokens = request.generationOptions().maxOutputTokens() == null
                ? 512 : request.generationOptions().maxOutputTokens();
        return pricing.get(candidateId).estimateCost(request.estimatedInputTokens(), outputTokens);
    }

    private ChatResponse complete(String requestId, long started, String answer,
                                  List<FuguTurn> turns, String termination) {
        notifySafely(() -> listener.onCompleted(new FuguCompleted(
                requestId, turns.size(), System.nanoTime() - started, true, termination, null)));
        return result(answer, turns, termination);
    }

    private static void notifySafely(Runnable notification) {
        try { notification.run(); } catch (RuntimeException ignored) { }
    }

    private boolean startsWithAccept(String response) {
        return response != null && response.stripLeading().regionMatches(true, 0, acceptToken, 0, acceptToken.length());
    }

    private static ChatResponse result(String answer, List<FuguTurn> turns, String termination) {
        List<Map<String, Object>> trace = turns.stream().map(turn -> Map.<String, Object>of(
                "turn", turn.index(), "candidate", turn.action().candidateId(), "role", turn.action().role().name())).toList();
        return new ChatResponse(answer == null ? "" : answer, "fugu", Usage.UNKNOWN,
                Map.of("fugu.turns", trace, "fugu.termination", termination));
    }

    private record Invocation(String candidateId, ChatResponse response) { }

    public static final class Builder {
        private final Map<String, ChatModel> candidates = new LinkedHashMap<>();
        private FuguRouter router;
        private int maxTurns = 5;
        private String acceptToken = "ACCEPT";
        private Duration timeout;
        private Integer tokenBudget;
        private FuguStopCondition stopCondition = FuguStopCondition.never();
        private ExecutorService executor = ForkJoinPool.commonPool();
        private ExecutorService asyncExecutor = ForkJoinPool.commonPool();
        private FuguPromptTemplate promptTemplate = FuguPromptTemplates.defaultTemplate();
        private FuguListener listener = FuguListener.NOOP;
        private int maxRetries;
        private Predicate<RuntimeException> retryPredicate = failure ->
                failure instanceof ModelException modelException && modelException.retryable();
        private final Map<String, List<String>> fallbacks = new LinkedHashMap<>();
        private Predicate<RuntimeException> fallbackPredicate = failure ->
                failure instanceof ModelException modelException && modelException.retryable();
        private Duration cooldown;
        private final Map<String, ModelPricing> pricing = new LinkedHashMap<>();
        private Double maxCostUsd;
        private RouterStateStore stateStore;
        private String stateNamespace = "fugu";

        public Builder candidate(String id, ChatModel model) {
            if (id == null || id.isBlank()) throw new IllegalArgumentException("candidate id must not be blank");
            if (candidates.putIfAbsent(id, Objects.requireNonNull(model, "model")) != null) {
                throw new IllegalArgumentException("duplicate candidate: " + id);
            }
            pricing.put(id, ModelPricing.UNKNOWN);
            return this;
        }
        public Builder candidate(String id, ChatModel model, ModelPricing modelPricing) {
            candidate(id, model);
            pricing.put(id, Objects.requireNonNull(modelPricing, "modelPricing"));
            return this;
        }
        public Builder router(FuguRouter value) { router = value; return this; }
        public Builder maxTurns(int value) { if (value < 1) throw new IllegalArgumentException("maxTurns must be > 0"); maxTurns = value; return this; }
        public Builder acceptToken(String value) { if (value == null || value.isBlank()) throw new IllegalArgumentException("acceptToken must not be blank"); acceptToken = value; return this; }
        public Builder timeout(Duration value) { timeout = Objects.requireNonNull(value, "timeout"); if (value.isNegative() || value.isZero()) throw new IllegalArgumentException("timeout must be positive"); return this; }
        public Builder tokenBudget(int value) { if (value < 1) throw new IllegalArgumentException("tokenBudget must be > 0"); tokenBudget = value; return this; }
        public Builder stopCondition(FuguStopCondition value) { stopCondition = Objects.requireNonNull(value, "stopCondition"); return this; }
        public Builder executor(ExecutorService value) { executor = Objects.requireNonNull(value, "executor"); return this; }
        public Builder asyncExecutor(ExecutorService value) { asyncExecutor = Objects.requireNonNull(value, "asyncExecutor"); return this; }
        public Builder promptTemplate(FuguPromptTemplate value) { promptTemplate = Objects.requireNonNull(value, "promptTemplate"); return this; }
        public Builder listener(FuguListener value) { listener = Objects.requireNonNull(value, "listener"); return this; }
        public Builder maxRetries(int value) { if (value < 0) throw new IllegalArgumentException("maxRetries must be >= 0"); maxRetries = value; return this; }
        public Builder retryOn(Predicate<RuntimeException> value) { retryPredicate = Objects.requireNonNull(value, "retryPredicate"); return this; }
        public Builder fallbacks(String primary, String... backups) {
            if (primary == null || primary.isBlank()) throw new IllegalArgumentException("fallback primary must not be blank");
            fallbacks.put(primary, List.of(backups));
            return this;
        }
        public Builder fallbackOn(Predicate<RuntimeException> value) { fallbackPredicate = Objects.requireNonNull(value, "fallbackPredicate"); return this; }
        public Builder cooldown(Duration value) { cooldown = Objects.requireNonNull(value, "cooldown"); if (value.isNegative() || value.isZero()) throw new IllegalArgumentException("cooldown must be positive"); return this; }
        public Builder stateStore(RouterStateStore value) { stateStore = Objects.requireNonNull(value, "stateStore"); return this; }
        public Builder stateNamespace(String value) { if (value == null || value.isBlank()) throw new IllegalArgumentException("stateNamespace must not be blank"); stateNamespace = value; return this; }
        public Builder maxCostUsd(double value) { if (!Double.isFinite(value) || value < 0) throw new IllegalArgumentException("maxCostUsd must be finite and >= 0"); maxCostUsd = value; return this; }
        public FuguOrchestrator build() { return new FuguOrchestrator(this); }
    }
}
