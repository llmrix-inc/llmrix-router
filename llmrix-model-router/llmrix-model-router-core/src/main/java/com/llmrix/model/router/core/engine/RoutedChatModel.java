package com.llmrix.model.router.core.engine;

import com.llmrix.model.router.core.api.chat.ChatChunk;
import com.llmrix.model.router.core.api.chat.ChatModel;
import com.llmrix.model.router.core.api.chat.ChatRequest;
import com.llmrix.model.router.core.api.chat.ChatResponse;
import com.llmrix.model.router.core.api.Usage;
import com.llmrix.model.router.core.api.chat.VideoPart;
import com.llmrix.model.router.core.api.chat.FilePart;
import com.llmrix.model.router.core.api.chat.AudioPart;

import com.llmrix.model.router.core.model.ModelTarget;
import com.llmrix.model.router.core.model.ModelFeature;
import com.llmrix.model.router.core.model.InputModality;
import com.llmrix.model.router.core.model.ModelLimits;
import com.llmrix.model.router.core.exception.ModelException;
import com.llmrix.model.router.core.exception.ModelTimeoutException;
import com.llmrix.model.router.core.exception.ModelUnavailableException;
import com.llmrix.model.router.core.exception.RateLimitException;
import com.llmrix.model.router.core.state.HealthState;
import com.llmrix.model.router.core.state.HealthAttempt;
import com.llmrix.model.router.core.state.InMemoryRouterStateStore;
import com.llmrix.model.router.core.state.QuotaState;
import com.llmrix.model.router.core.state.RouterStateStore;
import com.llmrix.model.router.core.engine.RequestBudget;
import com.llmrix.model.router.core.exception.BudgetExceededException;
import com.llmrix.model.router.core.engine.ExecutionPolicy;
import com.llmrix.model.router.core.routing.RouteCandidate;
import com.llmrix.model.router.core.routing.NoCandidateException;
import com.llmrix.model.router.core.routing.RouteExplanation;
import com.llmrix.model.router.core.routing.RoutingHints;
import com.llmrix.model.router.core.routing.RoutingStrategy;
import com.llmrix.model.router.core.routing.Strategies;
import com.llmrix.model.router.core.event.RouterListener;
import com.llmrix.model.router.core.event.AttemptCompleted;
import com.llmrix.model.router.core.event.RequestCompleted;
import com.llmrix.model.router.core.event.RequestStarted;
import com.llmrix.model.router.core.event.RouteSelected;
import com.llmrix.model.router.core.event.UsageRecorded;
import com.llmrix.model.router.core.event.TargetCooldown;

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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Flow;
import java.util.concurrent.SubmissionPublisher;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Predicate;

public final class RoutedChatModel implements ChatModel, AutoCloseable {
    private static final System.Logger LOGGER = System.getLogger(RoutedChatModel.class.getName());

    private final Map<String, ModelTarget> targets;
    private final Map<String, HealthState> health;
    private final Map<String, QuotaState> quotas;
    private final RoutingStrategy strategy;
    private final String strategyName;
    private final ExecutionPolicy executionPolicy;
    private final RouterListener listener;
    private final ExecutorService executor;
    private final boolean ownsExecutor;
    private final ScheduledExecutorService scheduler;
    private final RouterStateStore stateStore;
    private final String stateNamespace;
    private final Predicate<RuntimeException> retryPredicate;
    private final RoutedModelOperations unary;
    private final ModelLimits routeQuota;

    private RoutedChatModel(Builder builder) {
        if (builder.targets.isEmpty()) throw new IllegalArgumentException("at least one target is required");
        this.targets = Collections.unmodifiableMap(new LinkedHashMap<>(builder.targets));
        this.health = new LinkedHashMap<>();
        this.quotas = new LinkedHashMap<>();
        targets.forEach((id, target) -> {
            health.put(id, builder.stateStore.health(builder.stateNamespace, id));
            quotas.put(id, builder.stateStore.quota(builder.stateNamespace, id, target.limits()));
        });
        this.strategy = builder.strategy;
        this.strategyName = builder.strategyName;
        this.executionPolicy = builder.executionPolicy();
        this.listener = builder.listener;
        this.stateStore = builder.stateStore;
        this.stateNamespace = builder.stateNamespace;
        this.routeQuota = builder.routeQuota;
        this.retryPredicate = builder.retryPredicate;
        this.ownsExecutor = builder.executor == null;
        this.executor = ownsExecutor ? Executors.newCachedThreadPool(new DaemonThreadFactory()) : builder.executor;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(new DaemonThreadFactory());
        RoutedModelOperations.Builder unaryBuilder = RoutedModelOperations.builder()
                .strategy(strategyName, strategy)
                .timeout(executionPolicy.timeout()).maxRetries(executionPolicy.maxRetries())
                .retryDelay(executionPolicy.retryDelay()).failureThreshold(executionPolicy.failureThreshold())
                .cooldown(executionPolicy.cooldown()).listener(listener).stateStore(stateStore)
                .stateNamespace(stateNamespace).retryOn(retryPredicate)
                .quota(routeQuota)
                .executor(executor);
        targets.values().forEach(unaryBuilder::target);
        this.unary = unaryBuilder.build();
    }

    public static Builder builder() {
        return new Builder();
    }

    public static RoutedChatModel of(ChatModel primary) {
        return builder().target("primary", primary).build();
    }

    @Override
    public ChatResponse chat(ChatRequest request) {
        return unary.chat(request);
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
            List<RouteCandidate> eligible = eligible(request, true);
            if (eligible.isEmpty()) {
                subscriber.onSubscribe(new Flow.Subscription() {
                    @Override
                    public void request(long n) {
                    }

                    @Override
                    public void cancel() {
                    }
                });
                subscriber.onError(new NoCandidateException("no target satisfies the request constraints"));
                return;
            }
            QuotaState requestQuota;
            try {
                requestQuota = acquireRouteQuota(request);
            } catch (RateLimitException failure) {
                subscriber.onSubscribe(new Flow.Subscription() {
                    @Override public void request(long n) { }
                    @Override public void cancel() { }
                });
                subscriber.onError(failure);
                return;
            }
            ModelTarget selected = strategy.select(request, List.copyOf(eligible));
            List<ModelTarget> order = executionOrder(selected, eligible);
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
                        @Override
                        public void request(long n) {
                            subscription.request(n);
                        }

                        @Override
                        public void cancel() {
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

                @Override
                public void onNext(ChatChunk item) {
                    subscriber.onNext(item);
                }

                @Override
                public void onError(Throwable throwable) {
                    subscriber.onError(throwable);
                }

                @Override
                public void onComplete() {
                    subscriber.onComplete();
                }
            });
            Runnable timeoutAction = () -> {
                if (!control.finish()) return;
                control.cancelUpstream();
                StreamAttempt active = control.claimAttempt();
                if (active != null) {
                    long duration = System.nanoTime() - active.startedNanos();
                    boolean enteredCooldown = active.healthAttempt().failure(
                            duration, executionPolicy.failureThreshold(), executionPolicy.cooldown());
                    if (enteredCooldown) publishCooldown(requestId, active.target().id());
                    notifySafely(() -> listener.onAttemptCompleted(new AttemptCompleted(
                            requestId, active.target().id(), active.attempt(), duration, false,
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
                    request, order, 0, 1, relay, control, budget, requestId, started, 0, null, requestQuota));
        };
    }

    private void streamCandidate(
            ChatRequest request,
            List<ModelTarget> order,
            int candidateIndex,
            int attempt,
            SubmissionPublisher<ChatChunk> relay,
            StreamControl control,
            RequestBudget budget,
            String requestId,
            long requestStarted,
            int completedAttempts,
            Throwable previousFailure,
            QuotaState requestQuota) {
        if (control.finished()) return;
        if (candidateIndex >= order.size()) {
            if (!control.finish()) return;
            notifySafely(() -> listener.onRequestCompleted(new RequestCompleted(
                    requestId, order.isEmpty() ? null : order.get(order.size() - 1).id(),
                    System.nanoTime() - requestStarted, false, completedAttempts)));
            relay.closeExceptionally(terminalFailure(previousFailure));
            return;
        }
        ModelTarget target = order.get(candidateIndex);
        control.current(target.id(), completedAttempts);
        long attemptStarted = System.nanoTime();
        if (quotas.get(target.id()).rejectionReason(request.estimatedInputTokens()) != null) {
            streamCandidate(request, order, candidateIndex + 1, 1, relay, control, budget, requestId, requestStarted,
                    completedAttempts, new com.llmrix.model.router.core.exception.RateLimitException(
                            "local target quota exceeded: " + target.id()), requestQuota);
            return;
        }
        RequestBudget.Reservation reservation = budget.tryReserve(target, request);
        if (reservation == null) {
            streamCandidate(request, order, candidateIndex + 1, 1, relay, control, budget,
                    requestId, requestStarted, completedAttempts,
                    new BudgetExceededException("request cost budget exhausted before target: " + target.id()), requestQuota);
            return;
        }
        HealthState candidateHealth = health.get(target.id());
        HealthAttempt healthAttempt = candidateHealth.tryBeginAttempt(target.limits().maxConcurrency());
        if (healthAttempt == null) {
            budget.release(reservation);
            streamCandidate(request, order, candidateIndex + 1, 1, relay, control, budget,
                    requestId, requestStarted, completedAttempts,
                    new com.llmrix.model.router.core.exception.RateLimitException(
                            "local target max concurrency exceeded: " + target.id()), requestQuota);
            return;
        }
        if (!quotas.get(target.id()).tryAcquire(request.estimatedInputTokens())) {
            healthAttempt.cancel();
            budget.release(reservation);
            streamCandidate(request, order, candidateIndex + 1, 1, relay, control, budget, requestId, requestStarted,
                    completedAttempts, new com.llmrix.model.router.core.exception.RateLimitException(
                            "local target quota exceeded: " + target.id()), requestQuota);
            return;
        }
        notifySafely(() -> listener.onAttemptStarted(new com.llmrix.model.router.core.event.AttemptStarted(
                requestId, target.id(), attempt)));
        control.beginAttempt(new StreamAttempt(healthAttempt, target, attempt, attemptStarted, reservation));
        try {
            target.model().stream(request).subscribe(new Flow.Subscriber<>() {
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
                    if (!emitted)
                        notifySafely(() -> listener.onFirstToken(new com.llmrix.model.router.core.event.FirstTokenReceived(
                                requestId, target.id(), System.nanoTime() - requestStarted)));
                    emitted = true;
                    if (item.finished() && !outputTokensRecorded) {
                        quotas.get(target.id()).recordOutputTokens(item.usage().outputTokens());
                        if (requestQuota != null) requestQuota.recordOutputTokens(item.usage().outputTokens());
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
                    publishUsage(requestId, target, latestUsage);
                    budget.settle(active.reservation(), target, latestUsage);
                    active.healthAttempt().success(duration);
                    notifySafely(() -> listener.onAttemptCompleted(new AttemptCompleted(
                            requestId, target.id(), attempt, duration, true, null)));
                    notifySafely(() -> listener.onRequestCompleted(new RequestCompleted(
                            requestId, target.id(), System.nanoTime() - requestStarted, true, completedAttempts + 1)));
                    relay.close();
                }

                private void completeFailure(Throwable failure) {
                    if (control.finished()) return;
                    StreamAttempt active = control.claimAttempt();
                    if (active == null) return;
                    long duration = System.nanoTime() - attemptStarted;
                    boolean enteredCooldown = recordHealthFailure(active.healthAttempt(), failure, duration);
                    if (enteredCooldown) publishCooldown(requestId, target.id());
                    notifySafely(() -> listener.onAttemptCompleted(new AttemptCompleted(
                            requestId, target.id(), attempt, duration, false, failure.getClass().getSimpleName())));
                    if (emitted) {
                        if (!control.finish()) return;
                        notifySafely(() -> listener.onRequestCompleted(new RequestCompleted(
                                requestId, target.id(), System.nanoTime() - requestStarted, false, completedAttempts + 1)));
                        relay.closeExceptionally(failure);
                    } else if (failure instanceof RuntimeException runtime && retryable(runtime)
                            && attempt <= executionPolicy.maxRetries()) {
                        waitBeforeRetry(requestStarted + executionPolicy.timeout().toNanos());
                        streamCandidate(request, order, candidateIndex, attempt + 1, relay, control, budget,
                                requestId, requestStarted, completedAttempts + 1, failure, requestQuota);
                    } else {
                        streamCandidate(request, order, candidateIndex + 1, 1, relay, control, budget,
                                requestId, requestStarted, completedAttempts + 1, failure, requestQuota);
                    }
                }
            });
        } catch (RuntimeException failure) {
            StreamAttempt active = control.claimAttempt();
            if (active == null) return;
            long duration = System.nanoTime() - attemptStarted;
            boolean enteredCooldown = recordHealthFailure(active.healthAttempt(), failure, duration);
            if (enteredCooldown) publishCooldown(requestId, target.id());
            notifySafely(() -> listener.onAttemptCompleted(new AttemptCompleted(
                    requestId, target.id(), attempt, duration, false, failure.getClass().getSimpleName())));
            if (retryable(failure) && attempt <= executionPolicy.maxRetries()) {
                waitBeforeRetry(requestStarted + executionPolicy.timeout().toNanos());
                streamCandidate(request, order, candidateIndex, attempt + 1, relay, control, budget,
                        requestId, requestStarted, completedAttempts + 1, failure, requestQuota);
            } else {
                streamCandidate(request, order, candidateIndex + 1, 1, relay, control, budget,
                        requestId, requestStarted, completedAttempts + 1, failure, requestQuota);
            }
        }
    }

    public RouteExplanation explain(ChatRequest request) {
        Map<String, String> excluded = exclusions(request, false);
        List<RouteCandidate> eligible = eligible(request, false);
        String selected = eligible.isEmpty() ? null : strategy.select(request, eligible).id();
        return new RouteExplanation(selected, eligible.stream().map(RouteCandidate::id).toList(), excluded);
    }

    public List<RouteCandidate> targets() {
        return snapshots();
    }

    private boolean retryable(RuntimeException failure) {
        return retryPredicate.test(failure);
    }

    private boolean recordHealthFailure(HealthAttempt attempt, Throwable failure, long durationNanos) {
        if (failure instanceof ModelException modelFailure && !modelFailure.retryable()) {
            attempt.cancel();
            return false;
        }
        return attempt.failure(durationNanos, executionPolicy.failureThreshold(), executionPolicy.cooldown());
    }

    private static RuntimeException terminalFailure(Throwable failure) {
        if (failure instanceof ModelException modelFailure
                && !modelFailure.retryable()
                && !(modelFailure instanceof BudgetExceededException)) {
            return modelFailure;
        }
        if (failure != null) {
            LOGGER.log(System.Logger.Level.WARNING,
                    "All streaming model targets failed: type={0}, message={1}",
                    failure.getClass().getSimpleName(), failure.getMessage());
        }
        return new ModelUnavailableException("model service is temporarily unavailable", failure);
    }

    private QuotaState acquireRouteQuota(ChatRequest request) {
        if (routeQuota.equals(ModelLimits.UNLIMITED)) return null;
        String partition = request.routingHints().attributes().get(RoutingHints.AUTH_QUOTA_KEY);
        if (partition == null || partition.isBlank()) partition = "shared";
        String key = "__route_quota__:" + partition;
        QuotaState quota = stateStore.quota(stateNamespace, key, routeQuota);
        if (!quota.tryAcquire(request.estimatedInputTokens())) {
            throw new RateLimitException("route quota exceeded: " + stateNamespace);
        }
        return quota;
    }

    private void publishUsage(String requestId, ModelTarget target, Usage usage) {
        double cost = usage.inputTokens() < 0 || usage.outputTokens() < 0
                ? Double.NaN
                : target.pricing().estimateCost(usage);
        notifySafely(() -> listener.onUsageRecorded(new UsageRecorded(requestId, target.id(), usage, cost)));
    }

    private void publishCooldown(String requestId, String candidateId) {
        notifySafely(() -> listener.onTargetCooldown(
                new TargetCooldown(requestId, candidateId, executionPolicy.cooldown())));
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

    private List<ModelTarget> executionOrder(ModelTarget selected, List<RouteCandidate> eligible) {
        LinkedHashSet<String> ids = new LinkedHashSet<>();
        ids.add(selected.id());
        eligible.stream().map(RouteCandidate::target)
                .sorted(java.util.Comparator.comparingInt(ModelTarget::priority))
                .map(ModelTarget::id).forEach(ids::add);
        Set<String> eligibleIds = eligible.stream().map(RouteCandidate::id).collect(java.util.stream.Collectors.toSet());
        return ids.stream().filter(eligibleIds::contains).map(targets::get).toList();
    }

    private List<RouteCandidate> eligible(ChatRequest request, boolean streaming) {
        Map<String, String> excluded = exclusions(request, streaming);
        return snapshots().stream().filter(snapshot -> !excluded.containsKey(snapshot.id())).toList();
    }

    private Map<String, String> exclusions(ChatRequest request, boolean streaming) {
        RoutingHints hints = request.routingHints();
        Map<String, String> excluded = new LinkedHashMap<>();
        for (ModelTarget target : targets.values()) {
            HealthState candidateHealth = health.get(target.id());
            String reason = null;
            if (!candidateHealth.available(System.currentTimeMillis())) reason = "cooldown";
            else if (!target.satisfies(com.llmrix.model.router.core.model.ModelRequirement.CHAT))
                reason = "missing-chat-capability";
            else if (streaming && !target.supports(ModelFeature.STREAMING))
                reason = "missing-streaming-feature";
            else if (!request.tools().isEmpty() && !target.supports(ModelFeature.TOOLS))
                reason = "missing-tools-feature";
            else if (request.responseFormat() != null && !target.supports(ModelFeature.STRUCTURED_OUTPUT))
                reason = "missing-structured-output-feature";
            else if (request.promptCache() != null
                    && !target.satisfies(com.llmrix.model.router.core.model.ModelRequirement.PROMPT_CACHE))
                reason = "missing-prompt-cache-capability";
            else if (request.promptCache() != null && request.promptCache().retention() != null
                    && target.metadata().containsKey("prompt-cache-retention")
                    && !request.promptCache().retention().equals(target.metadata().get("prompt-cache-retention")))
                reason = "prompt-cache-retention-mismatch";
            else if (!hints.allowedModels().isEmpty() && !hints.allowedModels().contains(target.id()))
                reason = "not-allowed";
            else if (hints.deniedModels().contains(target.id())) reason = "denied";
            else if (containsVideo(request)
                    && !target.supports(InputModality.VIDEO))
                reason = "missing-video-input";
            else if (containsFile(request)
                    && !target.supports(InputModality.FILE))
                reason = "missing-file-input";
            else if (containsAudio(request)
                    && !target.supports(InputModality.AUDIO))
                reason = "missing-audio-input";
            else if (!hints.requirements().stream().allMatch(target::satisfies)) reason = "missing-requirement";
            else if (target.maxInputTokens() != null && request.estimatedInputTokens() > target.maxInputTokens())
                reason = "context-window";
            else if (target.limits().maxConcurrency() != null && candidateHealth.inFlight() >= target.limits().maxConcurrency())
                reason = "max-concurrency";
            else {
                String quotaRejection = quotas.get(target.id()).rejectionReason(request.estimatedInputTokens());
                if (quotaRejection != null) reason = quotaRejection;
            }
            if (reason == null && hints.maxLatency() != null
                    && candidateHealth.latencyEwmaMillis() > hints.maxLatency().toNanos() / 1_000_000d)
                reason = "max-latency";
            if (reason == null && hints.maxCostUsd() != null) {
                double estimate = target.pricing().estimateCost(
                        request.estimatedInputTokens(), request.estimatedOutputTokens());
                if (!Double.isFinite(estimate) || estimate > hints.maxCostUsd()) reason = "max-cost";
            }
            if (reason != null) excluded.put(target.id(), reason);
        }
        return excluded;
    }

    private static boolean containsVideo(ChatRequest request) {
        return request.messages().stream().flatMap(message -> message.contents().stream())
                .anyMatch(VideoPart.class::isInstance);
    }

    private static boolean containsFile(ChatRequest request) {
        return request.messages().stream().flatMap(message -> message.contents().stream())
                .anyMatch(FilePart.class::isInstance);
    }

    private static boolean containsAudio(ChatRequest request) {
        return request.messages().stream().flatMap(message -> message.contents().stream())
                .anyMatch(AudioPart.class::isInstance);
    }

    private List<RouteCandidate> snapshots() {
        long now = System.currentTimeMillis();
        List<RouteCandidate> result = new ArrayList<>();
        targets.values().forEach(target -> {
            HealthState state = health.get(target.id());
            result.add(new RouteCandidate(target, state.available(now), state.inFlight(), state.latencyEwmaMillis()));
        });
        return List.copyOf(result);
    }

    private static void notifySafely(Runnable callback) {
        try {
            callback.run();
        } catch (RuntimeException ignored) {
        }
    }

    @Override
    public void close() {
        if (ownsExecutor) executor.shutdownNow();
        scheduler.shutdownNow();
    }

    public static final class Builder {
        private final Map<String, ModelTarget> targets = new LinkedHashMap<>();
        private RoutingStrategy strategy = Strategies.balanced();
        private String strategyName = "balanced";
        private Duration timeout = ExecutionPolicy.DEFAULT.timeout();
        private int maxRetries = ExecutionPolicy.DEFAULT.maxRetries();
        private Duration retryDelay = ExecutionPolicy.DEFAULT.retryDelay();
        private int failureThreshold = ExecutionPolicy.DEFAULT.failureThreshold();
        private Duration cooldown = ExecutionPolicy.DEFAULT.cooldown();
        private Duration firstTokenTimeout = ExecutionPolicy.DEFAULT.firstTokenTimeout();
        private Duration streamIdleTimeout = ExecutionPolicy.DEFAULT.streamIdleTimeout();
        private RouterListener listener = RouterListener.NOOP;
        private RouterStateStore stateStore = new InMemoryRouterStateStore();
        private ModelLimits routeQuota = ModelLimits.UNLIMITED;
        private String stateNamespace = "default";
        private Predicate<RuntimeException> retryPredicate = Builder::recoverableModelFailure;
        private ExecutorService executor;

        public Builder target(String id, ChatModel model) {
            return target(ModelTarget.builder(id, model).build());
        }

        public Builder target(String id, ChatModel model, Consumer<ModelTarget.Builder> customizer) {
            ModelTarget.Builder builder = ModelTarget.builder(id, model);
            customizer.accept(builder);
            return target(builder.build());
        }

        public Builder target(ModelTarget target) {
            Objects.requireNonNull(target, "target");
            if (targets.putIfAbsent(target.id(), target) != null)
                throw new IllegalArgumentException("duplicate target: " + target.id());
            return this;
        }

        public Builder strategy(RoutingStrategy strategy) {
            return strategy("custom", strategy);
        }

        public Builder strategy(String name, RoutingStrategy strategy) {
            this.strategyName = Objects.requireNonNull(name);
            this.strategy = Objects.requireNonNull(strategy);
            return this;
        }

        public Builder timeout(Duration value) {
            timeout = value;
            return this;
        }

        public Builder maxRetries(int value) {
            maxRetries = value;
            return this;
        }

        public Builder retryDelay(Duration value) {
            retryDelay = value;
            return this;
        }

        public Builder failureThreshold(int value) {
            failureThreshold = value;
            return this;
        }

        public Builder cooldown(Duration value) {
            cooldown = value;
            return this;
        }

        public Builder firstTokenTimeout(Duration value) {
            firstTokenTimeout = value;
            return this;
        }

        public Builder streamIdleTimeout(Duration value) {
            streamIdleTimeout = value;
            return this;
        }

        public Builder listener(RouterListener value) {
            listener = Objects.requireNonNull(value);
            return this;
        }

        public Builder retryOn(Predicate<RuntimeException> value) {
            retryPredicate = Objects.requireNonNull(value);
            return this;
        }

        public Builder executor(ExecutorService value) {
            executor = Objects.requireNonNull(value, "executor");
            return this;
        }

        public Builder stateStore(RouterStateStore value) {
            stateStore = Objects.requireNonNull(value);
            return this;
        }

        public Builder quota(ModelLimits value) {
            Objects.requireNonNull(value, "quota");
            if (value.maxConcurrency() != null)
                throw new IllegalArgumentException("route quota does not support maxConcurrency");
            routeQuota = value;
            return this;
        }

        public Builder quota(Long requestsPerMinute, Long tokensPerMinute) {
            return quota(new ModelLimits(requestsPerMinute, tokensPerMinute, null));
        }

        public Builder stateNamespace(String value) {
            if (value == null || value.isBlank())
                throw new IllegalArgumentException("stateNamespace must not be blank");
            stateNamespace = value;
            return this;
        }

        private ExecutionPolicy executionPolicy() {
            return new ExecutionPolicy(timeout, maxRetries, retryDelay, failureThreshold, cooldown,
                    firstTokenTimeout, streamIdleTimeout);
        }

        public RoutedChatModel build() {
            return new RoutedChatModel(this);
        }

        private static boolean recoverableModelFailure(RuntimeException failure) {
            return failure instanceof ModelException modelException && modelException.retryable();
        }
    }

    private static final class DaemonThreadFactory implements ThreadFactory {
        private int sequence;

        @Override
        public synchronized Thread newThread(Runnable runnable) {
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

        boolean finished() {
            return finished.get();
        }

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

        void upstream(Flow.Subscription value) {
            upstream.set(value);
        }

        void cancelUpstream() {
            Flow.Subscription subscription = upstream.getAndSet(null);
            if (subscription != null) subscription.cancel();
        }

        void beginAttempt(StreamAttempt value) {
            attempt.set(value);
        }

        StreamAttempt claimAttempt() {
            return attempt.getAndSet(null);
        }

        void current(String candidateId, int attempts) {
            currentCandidate = candidateId;
            completedAttempts = attempts;
        }

        String currentCandidate() {
            return currentCandidate;
        }

        int completedAttempts() {
            return completedAttempts;
        }

        private static void cancelTask(ScheduledFuture<?> task) {
            if (task != null) task.cancel(false);
        }
    }

    private static final class StreamAttempt {
        private final HealthAttempt healthAttempt;
        private final ModelTarget target;
        private final int attempt;
        private final long startedNanos;
        private final RequestBudget.Reservation reservation;

        private StreamAttempt(HealthAttempt healthAttempt, ModelTarget target, int attempt,
                              long startedNanos, RequestBudget.Reservation reservation) {
            this.healthAttempt = healthAttempt;
            this.target = target;
            this.attempt = attempt;
            this.startedNanos = startedNanos;
            this.reservation = reservation;
        }

        private HealthAttempt healthAttempt() {
            return healthAttempt;
        }

        private ModelTarget target() {
            return target;
        }

        private int attempt() {
            return attempt;
        }

        private long startedNanos() {
            return startedNanos;
        }

        private RequestBudget.Reservation reservation() {
            return reservation;
        }
    }
}
