package com.llmrix.model.router.core.api;

import com.llmrix.model.router.core.candidate.Candidate;
import com.llmrix.model.router.core.exception.ModelException;
import com.llmrix.model.router.core.exception.ModelTimeoutException;
import com.llmrix.model.router.core.exception.ModelUnavailableException;
import com.llmrix.model.router.core.execution.HealthState;
import com.llmrix.model.router.core.execution.HealthAttempt;
import com.llmrix.model.router.core.execution.InMemoryRouterStateStore;
import com.llmrix.model.router.core.execution.QuotaState;
import com.llmrix.model.router.core.execution.RouterStateStore;
import com.llmrix.model.router.core.execution.RequestBudget;
import com.llmrix.model.router.core.exception.BudgetExceededException;
import com.llmrix.model.router.core.execution.ExecutionPolicy;
import com.llmrix.model.router.core.routing.CandidateSnapshot;
import com.llmrix.model.router.core.routing.NoCandidateException;
import com.llmrix.model.router.core.routing.RouteExplanation;
import com.llmrix.model.router.core.routing.RoutingHints;
import com.llmrix.model.router.core.routing.RoutingStrategy;
import com.llmrix.model.router.core.routing.Strategies;
import com.llmrix.model.router.core.spi.RouterListener;
import com.llmrix.model.router.core.spi.event.AttemptCompleted;
import com.llmrix.model.router.core.spi.event.FallbackStarted;
import com.llmrix.model.router.core.spi.event.RequestCompleted;
import com.llmrix.model.router.core.spi.event.RequestStarted;
import com.llmrix.model.router.core.spi.event.RouteSelected;
import com.llmrix.model.router.core.spi.event.UsageRecorded;
import com.llmrix.model.router.core.spi.event.CandidateCooldown;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Flow;
import java.util.concurrent.SubmissionPublisher;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Predicate;

public final class RoutedChatModel implements ChatModel, AutoCloseable {
    private final Map<String, Candidate> candidates;
    private final Map<String, HealthState> health;
    private final Map<String, QuotaState> quotas;
    private final RoutingStrategy strategy;
    private final String strategyName;
    private final List<String> fallbackIds;
    private final ExecutionPolicy executionPolicy;
    private final RouterListener listener;
    private final ExecutorService executor;
    private final boolean ownsExecutor;
    private final ScheduledExecutorService scheduler;
    private final RouterStateStore stateStore;
    private final String stateNamespace;
    private final Predicate<RuntimeException> retryPredicate;
    private final Predicate<RuntimeException> fallbackPredicate;

    private RoutedChatModel(Builder builder) {
        if (builder.candidates.isEmpty()) throw new IllegalArgumentException("at least one candidate is required");
        this.candidates = Collections.unmodifiableMap(new LinkedHashMap<>(builder.candidates));
        this.health = new LinkedHashMap<>();
        this.quotas = new LinkedHashMap<>();
        candidates.forEach((id, candidate) -> {
            health.put(id, builder.stateStore.health(builder.stateNamespace, id));
            quotas.put(id, builder.stateStore.quota(builder.stateNamespace, id, candidate.limits()));
        });
        this.strategy = builder.strategy;
        this.strategyName = builder.strategyName;
        this.fallbackIds = List.copyOf(builder.fallbackIds);
        this.executionPolicy = builder.executionPolicy();
        this.listener = builder.listener;
        this.stateStore = builder.stateStore;
        this.stateNamespace = builder.stateNamespace;
        this.retryPredicate = builder.retryPredicate;
        this.fallbackPredicate = builder.fallbackPredicate;
        this.ownsExecutor = builder.executor == null;
        this.executor = ownsExecutor ? Executors.newCachedThreadPool(new DaemonThreadFactory()) : builder.executor;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(new DaemonThreadFactory());
        for (String fallbackId : fallbackIds) {
            if (!candidates.containsKey(fallbackId)) throw new IllegalArgumentException("unknown fallback candidate: " + fallbackId);
        }
    }

    public static Builder builder() { return new Builder(); }

    public static RoutedChatModel of(ChatModel primary) {
        return builder().candidate("primary", primary).build();
    }

    public RoutedChatModel fallbackTo(ChatModel backup) {
        Builder builder = builder().strategy(strategyName, strategy).listener(listener)
                .stateStore(stateStore).stateNamespace(stateNamespace)
                .retryOn(retryPredicate).fallbackOn(fallbackPredicate)
                .timeout(executionPolicy.timeout()).maxRetries(executionPolicy.maxRetries())
                .retryDelay(executionPolicy.retryDelay()).failureThreshold(executionPolicy.failureThreshold())
                .cooldown(executionPolicy.cooldown())
                .firstTokenTimeout(executionPolicy.firstTokenTimeout())
                .streamIdleTimeout(executionPolicy.streamIdleTimeout());
        if (!ownsExecutor) builder.executor(executor);
        candidates.values().forEach(builder::candidate);
        String id = "fallback-" + candidates.size();
        builder.fallbacks(fallbackIds.toArray(String[]::new));
        builder.candidate(id, backup).fallbacks(id);
        return builder.build();
    }

    @Override
    public ChatResponse chat(ChatRequest request) {
        Objects.requireNonNull(request, "request");
        String requestId = requestId(request);
        long started = System.nanoTime();
        notifySafely(() -> listener.onRequestStarted(new RequestStarted(
                requestId, started, request, stateNamespace)));
        List<CandidateSnapshot> eligible = eligible(request);
        if (eligible.isEmpty()) {
            notifySafely(() -> listener.onRequestCompleted(new RequestCompleted(requestId, null, System.nanoTime() - started, false, 0)));
            throw new NoCandidateException("no candidate satisfies the request constraints");
        }

        Candidate selected = strategy.select(request, List.copyOf(eligible));
        notifySafely(() -> listener.onRouteSelected(new RouteSelected(
                requestId, selected.id(), strategyName, "strategy:" + strategyName)));
        List<Candidate> order = executionOrder(selected, eligible);
        int totalAttempts = 0;
        Throwable lastFailure = null;
        String previous = null;
        boolean abortFallback = false;
        long deadline = started + executionPolicy.timeout().toNanos();
        RequestBudget budget = new RequestBudget(request.routingHints().maxCostUsd());

        for (Candidate candidate : order) {
            if (previous != null) {
                String from = previous;
                Throwable reason = lastFailure;
                notifySafely(() -> listener.onFallback(new FallbackStarted(requestId, from,
                        reason == null ? "fallback" : reason.getClass().getSimpleName())));
            }
            previous = candidate.id();
            for (int attempt = 1; attempt <= executionPolicy.maxRetries() + 1; attempt++) {
                RequestBudget.Reservation reservation = budget.tryReserve(candidate, request);
                if (reservation == null) {
                    lastFailure = new BudgetExceededException(
                            "request cost budget exhausted before candidate: " + candidate.id());
                    break;
                }
                HealthState candidateHealth = health.get(candidate.id());
                HealthAttempt healthAttempt = candidateHealth.tryBeginAttempt(candidate.limits().maxConcurrency());
                if (healthAttempt == null) {
                    budget.release(reservation);
                    lastFailure = new com.llmrix.model.router.core.exception.RateLimitException(
                            "local candidate max concurrency exceeded: " + candidate.id());
                    break;
                }
                if (!quotas.get(candidate.id()).tryAcquire(request.estimatedInputTokens())) {
                    healthAttempt.cancel();
                    budget.release(reservation);
                    lastFailure = new com.llmrix.model.router.core.exception.RateLimitException(
                            "local candidate quota exceeded: " + candidate.id());
                    break;
                }
                totalAttempts++;
                long attemptStarted = System.nanoTime();
                int attemptNumber = attempt;
                notifySafely(() -> listener.onAttemptStarted(new com.llmrix.model.router.core.spi.event.AttemptStarted(
                        requestId, candidate.id(), attemptNumber)));
                try {
                    ChatResponse response = invoke(candidate, request, deadline);
                    budget.settle(reservation, candidate, response.usage());
                    quotas.get(candidate.id()).recordOutputTokens(response.usage().outputTokens());
                    publishUsage(requestId, candidate, response.usage());
                    long duration = System.nanoTime() - attemptStarted;
                    healthAttempt.success(duration);
                    notifySafely(() -> listener.onAttemptCompleted(new AttemptCompleted(
                            requestId, candidate.id(), attemptNumber, duration, true, null)));
                    int attempts = totalAttempts;
                    notifySafely(() -> listener.onRequestCompleted(new RequestCompleted(
                            requestId, candidate.id(), System.nanoTime() - started, true, attempts)));
                    return response.routedBy(candidate.id());
                } catch (RuntimeException failure) {
                    long duration = System.nanoTime() - attemptStarted;
                    boolean enteredCooldown = healthAttempt.failure(
                            duration, executionPolicy.failureThreshold(), executionPolicy.cooldown());
                    if (enteredCooldown) publishCooldown(requestId, candidate.id());
                    lastFailure = failure;
                    notifySafely(() -> listener.onAttemptCompleted(new AttemptCompleted(
                            requestId, candidate.id(), attemptNumber, duration, false, failure.getClass().getSimpleName())));
                    if (!retryable(failure) || attempt > executionPolicy.maxRetries()) {
                        abortFallback = !fallbackable(failure);
                        break;
                    }
                    waitBeforeRetry(deadline);
                }
            }
            if (abortFallback) break;
        }

        int attempts = totalAttempts;
        String finalCandidate = previous;
        notifySafely(() -> listener.onRequestCompleted(new RequestCompleted(
                requestId, finalCandidate, System.nanoTime() - started, false, attempts)));
        if (lastFailure instanceof RuntimeException runtimeException) throw runtimeException;
        throw new ModelUnavailableException("all candidates failed", lastFailure);
    }

    private static String requestId(ChatRequest request) {
        String supplied = request.routingHints().attributes().get(
                com.llmrix.model.router.core.routing.RoutingHints.REQUEST_ID);
        return supplied == null || supplied.isBlank() ? UUID.randomUUID().toString() : supplied;
    }

    @Override
    public Flow.Publisher<ChatChunk> stream(ChatRequest request) {
        Objects.requireNonNull(request, "request");
        return subscriber -> {
            List<CandidateSnapshot> eligible = eligible(request);
            if (eligible.isEmpty()) {
                subscriber.onSubscribe(new Flow.Subscription() {
                    @Override public void request(long n) { }
                    @Override public void cancel() { }
                });
                subscriber.onError(new NoCandidateException("no candidate satisfies the request constraints"));
                return;
            }
            Candidate selected = strategy.select(request, List.copyOf(eligible));
            List<Candidate> order = executionOrder(selected, eligible);
            String requestId = requestId(request);
            long started = System.nanoTime();
            notifySafely(() -> listener.onRequestStarted(new RequestStarted(
                    requestId, started, request, stateNamespace)));
            notifySafely(() -> listener.onRouteSelected(new RouteSelected(
                    requestId, selected.id(), strategyName, "strategy:" + strategyName)));
            SubmissionPublisher<ChatChunk> relay = new SubmissionPublisher<>(executor, Flow.defaultBufferSize());
            StreamControl control = new StreamControl();
            RequestBudget budget = new RequestBudget(request.routingHints().maxCostUsd());
            relay.subscribe(new Flow.Subscriber<>() {
                @Override
                public void onSubscribe(Flow.Subscription subscription) {
                    subscriber.onSubscribe(new Flow.Subscription() {
                        @Override public void request(long n) { subscription.request(n); }
                        @Override public void cancel() {
                            subscription.cancel();
                            if (control.finish()) {
                                control.cancelUpstream();
                                StreamAttempt active = control.claimAttempt();
                                if (active != null) active.healthAttempt().cancel();
                                notifySafely(() -> listener.onRequestCompleted(new RequestCompleted(
                                        requestId, control.currentCandidate(), System.nanoTime() - started,
                                        false, control.completedAttempts())));
                            }
                        }
                    });
                }
                @Override public void onNext(ChatChunk item) { subscriber.onNext(item); }
                @Override public void onError(Throwable throwable) { subscriber.onError(throwable); }
                @Override public void onComplete() { subscriber.onComplete(); }
            });
            Runnable timeoutAction = () -> {
                if (!control.finish()) return;
                control.cancelUpstream();
                StreamAttempt active = control.claimAttempt();
                if (active != null) {
                    long duration = System.nanoTime() - active.startedNanos();
                    boolean enteredCooldown = active.healthAttempt().failure(
                            duration, executionPolicy.failureThreshold(), executionPolicy.cooldown());
                    if (enteredCooldown) publishCooldown(requestId, active.candidate().id());
                    notifySafely(() -> listener.onAttemptCompleted(new AttemptCompleted(
                            requestId, active.candidate().id(), active.attempt(), duration, false,
                            ModelTimeoutException.class.getSimpleName())));
                }
                notifySafely(() -> listener.onRequestCompleted(new RequestCompleted(
                        requestId, control.currentCandidate(), System.nanoTime() - started,
                        false, control.completedAttempts() + (active == null ? 0 : 1))));
                relay.closeExceptionally(new ModelTimeoutException("streaming router timeout exceeded", null));
            };
            ScheduledFuture<?> timeoutTask = scheduler.schedule(
                    timeoutAction, executionPolicy.timeout().toNanos(), TimeUnit.NANOSECONDS);
            control.timeoutTask(timeoutTask);
            control.configureActivityTimeouts(
                    scheduler, timeoutAction, executionPolicy.firstTokenTimeout(), executionPolicy.streamIdleTimeout());
            executor.execute(() -> streamCandidate(
                    request, order, 0, 1, relay, control, budget, requestId, started, 0, null));
        };
    }

    private void streamCandidate(
            ChatRequest request,
            List<Candidate> order,
            int candidateIndex,
            int attempt,
            SubmissionPublisher<ChatChunk> relay,
            StreamControl control,
            RequestBudget budget,
            String requestId,
            long requestStarted,
            int completedAttempts,
            Throwable previousFailure) {
        if (control.finished()) return;
        if (candidateIndex >= order.size()) {
            if (!control.finish()) return;
            notifySafely(() -> listener.onRequestCompleted(new RequestCompleted(
                    requestId, order.isEmpty() ? null : order.get(order.size() - 1).id(),
                    System.nanoTime() - requestStarted, false, completedAttempts)));
            relay.closeExceptionally(previousFailure == null
                    ? new ModelUnavailableException("all streaming candidates failed", null) : previousFailure);
            return;
        }
        Candidate candidate = order.get(candidateIndex);
        control.current(candidate.id(), completedAttempts);
        if (candidateIndex > 0 && attempt == 1) {
            Candidate previous = order.get(candidateIndex - 1);
            notifySafely(() -> listener.onFallback(new FallbackStarted(
                    requestId, previous.id(), previousFailure == null ? "fallback" : previousFailure.getClass().getSimpleName())));
        }
        long attemptStarted = System.nanoTime();
        if (quotas.get(candidate.id()).rejectionReason(request.estimatedInputTokens()) != null) {
            streamCandidate(request, order, candidateIndex + 1, 1, relay, control, budget, requestId, requestStarted,
                    completedAttempts, new com.llmrix.model.router.core.exception.RateLimitException(
                            "local candidate quota exceeded: " + candidate.id()));
            return;
        }
        RequestBudget.Reservation reservation = budget.tryReserve(candidate, request);
        if (reservation == null) {
            streamCandidate(request, order, candidateIndex + 1, 1, relay, control, budget,
                    requestId, requestStarted, completedAttempts,
                    new BudgetExceededException("request cost budget exhausted before candidate: " + candidate.id()));
            return;
        }
        HealthState candidateHealth = health.get(candidate.id());
        HealthAttempt healthAttempt = candidateHealth.tryBeginAttempt(candidate.limits().maxConcurrency());
        if (healthAttempt == null) {
            budget.release(reservation);
            streamCandidate(request, order, candidateIndex + 1, 1, relay, control, budget,
                    requestId, requestStarted, completedAttempts,
                    new com.llmrix.model.router.core.exception.RateLimitException(
                            "local candidate max concurrency exceeded: " + candidate.id()));
            return;
        }
        if (!quotas.get(candidate.id()).tryAcquire(request.estimatedInputTokens())) {
            healthAttempt.cancel();
            budget.release(reservation);
            streamCandidate(request, order, candidateIndex + 1, 1, relay, control, budget, requestId, requestStarted,
                    completedAttempts, new com.llmrix.model.router.core.exception.RateLimitException(
                            "local candidate quota exceeded: " + candidate.id()));
            return;
        }
        notifySafely(() -> listener.onAttemptStarted(new com.llmrix.model.router.core.spi.event.AttemptStarted(
                requestId, candidate.id(), attempt)));
        control.beginAttempt(new StreamAttempt(healthAttempt, candidate, attempt, attemptStarted, reservation));
        try {
            candidate.model().stream(request).subscribe(new Flow.Subscriber<>() {
                private static final int PREFETCH = 32;
                private static final int REPLENISH = 16;
                private boolean emitted;
                private boolean outputTokensRecorded;
                private Usage latestUsage = Usage.UNKNOWN;
                private Flow.Subscription upstream;
                private int remaining;

                @Override
                public void onSubscribe(Flow.Subscription subscription) {
                    upstream = subscription;
                    control.upstream(subscription);
                    if (control.finished()) {
                        subscription.cancel();
                        return;
                    }
                    remaining = PREFETCH;
                    subscription.request(PREFETCH);
                }

                @Override
                public void onNext(ChatChunk item) {
                    if (control.finished()) return;
                    control.onChunk();
                    if (!emitted) notifySafely(() -> listener.onFirstToken(new com.llmrix.model.router.core.spi.event.FirstTokenReceived(
                            requestId, candidate.id(), System.nanoTime() - requestStarted)));
                    emitted = true;
                    if (item.finished() && !outputTokensRecorded) {
                        quotas.get(candidate.id()).recordOutputTokens(item.usage().outputTokens());
                        latestUsage = item.usage();
                        outputTokensRecorded = true;
                    }
                    relay.submit(item);
                    if (--remaining == REPLENISH && !control.finished()) {
                        remaining += REPLENISH;
                        executor.execute(() -> upstream.request(REPLENISH));
                    }
                }

                @Override
                public void onError(Throwable failure) {
                    completeFailure(failure);
                }

                @Override
                public void onComplete() {
                    if (!control.finish()) return;
                    StreamAttempt active = control.claimAttempt();
                    if (active == null) return;
                    long duration = System.nanoTime() - attemptStarted;
                    publishUsage(requestId, candidate, latestUsage);
                    budget.settle(active.reservation(), candidate, latestUsage);
                    active.healthAttempt().success(duration);
                    notifySafely(() -> listener.onAttemptCompleted(new AttemptCompleted(
                            requestId, candidate.id(), attempt, duration, true, null)));
                    notifySafely(() -> listener.onRequestCompleted(new RequestCompleted(
                            requestId, candidate.id(), System.nanoTime() - requestStarted, true, completedAttempts + 1)));
                    relay.close();
                }

                private void completeFailure(Throwable failure) {
                    if (control.finished()) return;
                    StreamAttempt active = control.claimAttempt();
                    if (active == null) return;
                    long duration = System.nanoTime() - attemptStarted;
                    boolean enteredCooldown = active.healthAttempt().failure(
                            duration, executionPolicy.failureThreshold(), executionPolicy.cooldown());
                    if (enteredCooldown) publishCooldown(requestId, candidate.id());
                    notifySafely(() -> listener.onAttemptCompleted(new AttemptCompleted(
                            requestId, candidate.id(), attempt, duration, false, failure.getClass().getSimpleName())));
                    if (emitted) {
                        if (!control.finish()) return;
                        notifySafely(() -> listener.onRequestCompleted(new RequestCompleted(
                                requestId, candidate.id(), System.nanoTime() - requestStarted, false, completedAttempts + 1)));
                        relay.closeExceptionally(failure);
                    } else if (failure instanceof RuntimeException runtime && retryable(runtime)
                            && attempt <= executionPolicy.maxRetries()) {
                        waitBeforeRetry(requestStarted + executionPolicy.timeout().toNanos());
                        streamCandidate(request, order, candidateIndex, attempt + 1, relay, control, budget,
                                requestId, requestStarted, completedAttempts + 1, failure);
                    } else if (failure instanceof RuntimeException runtime && fallbackable(runtime)) {
                        streamCandidate(request, order, candidateIndex + 1, 1, relay, control, budget,
                                requestId, requestStarted, completedAttempts + 1, failure);
                    } else {
                        if (!control.finish()) return;
                        notifySafely(() -> listener.onRequestCompleted(new RequestCompleted(
                                requestId, candidate.id(), System.nanoTime() - requestStarted, false,
                                completedAttempts + 1)));
                        relay.closeExceptionally(failure);
                    }
                }
            });
        } catch (RuntimeException failure) {
            StreamAttempt active = control.claimAttempt();
            if (active == null) return;
            long duration = System.nanoTime() - attemptStarted;
            boolean enteredCooldown = active.healthAttempt().failure(
                    duration, executionPolicy.failureThreshold(), executionPolicy.cooldown());
            if (enteredCooldown) publishCooldown(requestId, candidate.id());
            notifySafely(() -> listener.onAttemptCompleted(new AttemptCompleted(
                    requestId, candidate.id(), attempt, duration, false, failure.getClass().getSimpleName())));
            if (retryable(failure) && attempt <= executionPolicy.maxRetries()) {
                waitBeforeRetry(requestStarted + executionPolicy.timeout().toNanos());
                streamCandidate(request, order, candidateIndex, attempt + 1, relay, control, budget,
                        requestId, requestStarted, completedAttempts + 1, failure);
            } else if (fallbackable(failure)) {
                streamCandidate(request, order, candidateIndex + 1, 1, relay, control, budget,
                        requestId, requestStarted, completedAttempts + 1, failure);
            } else {
                if (!control.finish()) return;
                notifySafely(() -> listener.onRequestCompleted(new RequestCompleted(
                        requestId, candidate.id(), System.nanoTime() - requestStarted, false,
                        completedAttempts + 1)));
                relay.closeExceptionally(failure);
            }
        }
    }

    public RouteExplanation explain(ChatRequest request) {
        Map<String, String> excluded = exclusions(request);
        List<CandidateSnapshot> eligible = eligible(request);
        String selected = eligible.isEmpty() ? null : strategy.select(request, eligible).id();
        return new RouteExplanation(selected, eligible.stream().map(CandidateSnapshot::id).toList(), excluded);
    }

    public List<CandidateSnapshot> candidates() {
        return snapshots();
    }

    private ChatResponse invoke(Candidate candidate, ChatRequest request, long deadlineNanos) {
        long remaining = deadlineNanos - System.nanoTime();
        if (remaining <= 0) throw new ModelTimeoutException("router timeout exceeded", null);
        CompletableFuture<ChatResponse> future = CompletableFuture.supplyAsync(() -> candidate.model().chat(request), executor);
        try {
            return future.get(remaining, TimeUnit.NANOSECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            throw new ModelTimeoutException("candidate timed out: " + candidate.id(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ModelUnavailableException("candidate invocation interrupted", e);
        } catch (java.util.concurrent.ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException runtimeException) throw runtimeException;
            throw new ModelUnavailableException("candidate invocation failed", cause);
        }
    }

    private boolean retryable(RuntimeException failure) {
        return retryPredicate.test(failure);
    }

    private boolean fallbackable(RuntimeException failure) {
        return fallbackPredicate.test(failure);
    }

    private void publishUsage(String requestId, Candidate candidate, Usage usage) {
        double cost = usage.inputTokens() < 0 || usage.outputTokens() < 0
                ? Double.NaN
                : candidate.pricing().estimateCost(usage.inputTokens(), usage.outputTokens());
        notifySafely(() -> listener.onUsageRecorded(new UsageRecorded(requestId, candidate.id(), usage, cost)));
    }

    private void publishCooldown(String requestId, String candidateId) {
        notifySafely(() -> listener.onCandidateCooldown(
                new CandidateCooldown(requestId, candidateId, executionPolicy.cooldown())));
    }

    private void waitBeforeRetry(long deadline) {
        long millis = Math.min(executionPolicy.retryDelay().toMillis(),
                Math.max(0, TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime())));
        if (millis == 0) return;
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ModelUnavailableException("retry interrupted", e);
        }
    }

    private List<Candidate> executionOrder(Candidate selected, List<CandidateSnapshot> eligible) {
        LinkedHashSet<String> ids = new LinkedHashSet<>();
        ids.add(selected.id());
        fallbackIds.forEach(ids::add);
        eligible.stream().map(CandidateSnapshot::candidate)
                .sorted(java.util.Comparator.comparingInt(Candidate::priority))
                .map(Candidate::id).forEach(ids::add);
        Set<String> eligibleIds = eligible.stream().map(CandidateSnapshot::id).collect(java.util.stream.Collectors.toSet());
        return ids.stream().filter(eligibleIds::contains).map(candidates::get).toList();
    }

    private List<CandidateSnapshot> eligible(ChatRequest request) {
        Map<String, String> excluded = exclusions(request);
        return snapshots().stream().filter(snapshot -> !excluded.containsKey(snapshot.id())).toList();
    }

    private Map<String, String> exclusions(ChatRequest request) {
        RoutingHints hints = request.routingHints();
        Map<String, String> excluded = new LinkedHashMap<>();
        for (Candidate candidate : candidates.values()) {
            HealthState candidateHealth = health.get(candidate.id());
            String reason = null;
            if (!candidateHealth.available(System.currentTimeMillis())) reason = "cooldown";
            else if (!hints.allowedModels().isEmpty() && !hints.allowedModels().contains(candidate.id())) reason = "not-allowed";
            else if (hints.deniedModels().contains(candidate.id())) reason = "denied";
            else if (!candidate.capabilities().containsAll(hints.requiredCapabilities())) reason = "missing-capability";
            else if (candidate.maxInputTokens() != null && request.estimatedInputTokens() > candidate.maxInputTokens()) reason = "context-window";
            else if (candidate.limits().maxConcurrency() != null && candidateHealth.inFlight() >= candidate.limits().maxConcurrency()) reason = "max-concurrency";
            else if (quotas.get(candidate.id()).rejectionReason(request.estimatedInputTokens()) != null) {
                reason = quotas.get(candidate.id()).rejectionReason(request.estimatedInputTokens());
            }
            else if (hints.maxLatency() != null
                    && candidateHealth.latencyEwmaMillis() > hints.maxLatency().toNanos() / 1_000_000d) reason = "max-latency";
            else if (hints.maxCostUsd() != null) {
                int outputTokens = request.generationOptions().maxOutputTokens() == null
                        ? 512 : request.generationOptions().maxOutputTokens();
                double estimate = candidate.pricing().estimateCost(request.estimatedInputTokens(), outputTokens);
                if (Double.isFinite(estimate) && estimate > hints.maxCostUsd()) reason = "max-cost";
            }
            if (reason != null) excluded.put(candidate.id(), reason);
        }
        return excluded;
    }

    private List<CandidateSnapshot> snapshots() {
        long now = System.currentTimeMillis();
        List<CandidateSnapshot> result = new ArrayList<>();
        candidates.values().forEach(candidate -> {
            HealthState state = health.get(candidate.id());
            result.add(new CandidateSnapshot(candidate, state.available(now), state.inFlight(), state.latencyEwmaMillis()));
        });
        return List.copyOf(result);
    }

    private static void notifySafely(Runnable callback) {
        try { callback.run(); } catch (RuntimeException ignored) { }
    }

    @Override
    public void close() {
        if (ownsExecutor) executor.shutdownNow();
        scheduler.shutdownNow();
    }

    public static final class Builder {
        private final Map<String, Candidate> candidates = new LinkedHashMap<>();
        private RoutingStrategy strategy = Strategies.balanced();
        private String strategyName = "balanced";
        private final List<String> fallbackIds = new ArrayList<>();
        private Duration timeout = ExecutionPolicy.DEFAULT.timeout();
        private int maxRetries = ExecutionPolicy.DEFAULT.maxRetries();
        private Duration retryDelay = ExecutionPolicy.DEFAULT.retryDelay();
        private int failureThreshold = ExecutionPolicy.DEFAULT.failureThreshold();
        private Duration cooldown = ExecutionPolicy.DEFAULT.cooldown();
        private Duration firstTokenTimeout = ExecutionPolicy.DEFAULT.firstTokenTimeout();
        private Duration streamIdleTimeout = ExecutionPolicy.DEFAULT.streamIdleTimeout();
        private RouterListener listener = RouterListener.NOOP;
        private RouterStateStore stateStore = new InMemoryRouterStateStore();
        private String stateNamespace = "default";
        private Predicate<RuntimeException> retryPredicate = Builder::recoverableModelFailure;
        private Predicate<RuntimeException> fallbackPredicate = Builder::recoverableModelFailure;
        private ExecutorService executor;

        public Builder candidate(String id, ChatModel model) { return candidate(Candidate.builder(id, model).build()); }

        public Builder candidate(String id, ChatModel model, Consumer<Candidate.Builder> customizer) {
            Candidate.Builder builder = Candidate.builder(id, model);
            customizer.accept(builder);
            return candidate(builder.build());
        }

        public Builder candidate(Candidate candidate) {
            Objects.requireNonNull(candidate, "candidate");
            if (candidates.putIfAbsent(candidate.id(), candidate) != null) throw new IllegalArgumentException("duplicate candidate: " + candidate.id());
            return this;
        }

        public Builder strategy(RoutingStrategy strategy) { return strategy("custom", strategy); }
        public Builder strategy(String name, RoutingStrategy strategy) { this.strategyName = Objects.requireNonNull(name); this.strategy = Objects.requireNonNull(strategy); return this; }
        public Builder fallbacks(String... ids) { fallbackIds.addAll(List.of(ids)); return this; }
        public Builder timeout(Duration value) { timeout = value; return this; }
        public Builder maxRetries(int value) { maxRetries = value; return this; }
        public Builder retryDelay(Duration value) { retryDelay = value; return this; }
        public Builder failureThreshold(int value) { failureThreshold = value; return this; }
        public Builder cooldown(Duration value) { cooldown = value; return this; }
        public Builder firstTokenTimeout(Duration value) { firstTokenTimeout = value; return this; }
        public Builder streamIdleTimeout(Duration value) { streamIdleTimeout = value; return this; }
        public Builder listener(RouterListener value) { listener = Objects.requireNonNull(value); return this; }
        public Builder retryOn(Predicate<RuntimeException> value) { retryPredicate = Objects.requireNonNull(value); return this; }
        public Builder fallbackOn(Predicate<RuntimeException> value) { fallbackPredicate = Objects.requireNonNull(value); return this; }
        public Builder executor(ExecutorService value) { executor = Objects.requireNonNull(value, "executor"); return this; }
        public Builder stateStore(RouterStateStore value) { stateStore = Objects.requireNonNull(value); return this; }
        public Builder stateNamespace(String value) {
            if (value == null || value.isBlank()) throw new IllegalArgumentException("stateNamespace must not be blank");
            stateNamespace = value;
            return this;
        }
        private ExecutionPolicy executionPolicy() {
            return new ExecutionPolicy(timeout, maxRetries, retryDelay, failureThreshold, cooldown,
                    firstTokenTimeout, streamIdleTimeout);
        }
        public RoutedChatModel build() { return new RoutedChatModel(this); }

        private static boolean recoverableModelFailure(RuntimeException failure) {
            return failure instanceof ModelException modelException && modelException.retryable();
        }
    }

    private static final class DaemonThreadFactory implements ThreadFactory {
        private int sequence;
        @Override public synchronized Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, "llmrix-model-router-" + sequence++);
            thread.setDaemon(true);
            return thread;
        }
    }

    private static final class StreamControl {
        private final AtomicBoolean finished = new AtomicBoolean();
        private final AtomicReference<Flow.Subscription> upstream = new AtomicReference<>();
        private final AtomicReference<StreamAttempt> attempt = new AtomicReference<>();
        private volatile ScheduledFuture<?> timeoutTask;
        private volatile ScheduledFuture<?> firstTokenTask;
        private volatile ScheduledFuture<?> idleTask;
        private volatile ScheduledExecutorService scheduler;
        private volatile Runnable timeoutAction;
        private volatile Duration streamIdleTimeout;
        private volatile String currentCandidate;
        private volatile int completedAttempts;

        boolean finished() { return finished.get(); }
        boolean finish() {
            boolean changed = finished.compareAndSet(false, true);
            if (changed) {
                cancelTask(timeoutTask);
                cancelTask(firstTokenTask);
                cancelTask(idleTask);
            }
            return changed;
        }
        void timeoutTask(ScheduledFuture<?> value) {
            timeoutTask = value;
            if (finished.get()) value.cancel(false);
        }
        void configureActivityTimeouts(
                ScheduledExecutorService scheduler,
                Runnable timeoutAction,
                Duration firstTokenTimeout,
                Duration streamIdleTimeout) {
            this.scheduler = scheduler;
            this.timeoutAction = timeoutAction;
            this.streamIdleTimeout = streamIdleTimeout;
            if (firstTokenTimeout != null) {
                firstTokenTask = scheduler.schedule(
                        timeoutAction, firstTokenTimeout.toNanos(), TimeUnit.NANOSECONDS);
            }
            if (finished.get()) cancelTask(firstTokenTask);
        }
        synchronized void onChunk() {
            cancelTask(firstTokenTask);
            firstTokenTask = null;
            cancelTask(idleTask);
            if (!finished.get() && streamIdleTimeout != null) {
                idleTask = scheduler.schedule(
                        timeoutAction, streamIdleTimeout.toNanos(), TimeUnit.NANOSECONDS);
            }
        }
        void upstream(Flow.Subscription value) { upstream.set(value); }
        void cancelUpstream() {
            Flow.Subscription subscription = upstream.getAndSet(null);
            if (subscription != null) subscription.cancel();
        }
        void beginAttempt(StreamAttempt value) { attempt.set(value); }
        StreamAttempt claimAttempt() { return attempt.getAndSet(null); }
        void current(String candidateId, int attempts) {
            currentCandidate = candidateId;
            completedAttempts = attempts;
        }
        String currentCandidate() { return currentCandidate; }
        int completedAttempts() { return completedAttempts; }
        private static void cancelTask(ScheduledFuture<?> task) {
            if (task != null) task.cancel(false);
        }
    }

    private record StreamAttempt(
            HealthAttempt healthAttempt,
            Candidate candidate,
            int attempt,
            long startedNanos,
            RequestBudget.Reservation reservation) { }
}
