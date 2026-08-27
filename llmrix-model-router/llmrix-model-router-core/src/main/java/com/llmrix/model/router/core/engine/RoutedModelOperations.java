package com.llmrix.model.router.core.engine;

import com.llmrix.model.router.core.api.audio.AudioModel;
import com.llmrix.model.router.core.api.audio.AudioResponse;
import com.llmrix.model.router.core.api.audio.AudioTextRequest;
import com.llmrix.model.router.core.api.chat.ChatModel;
import com.llmrix.model.router.core.api.chat.ChatRequest;
import com.llmrix.model.router.core.api.chat.ChatResponse;
import com.llmrix.model.router.core.api.embedding.EmbeddingModel;
import com.llmrix.model.router.core.api.embedding.EmbeddingRequest;
import com.llmrix.model.router.core.api.embedding.EmbeddingResponse;
import com.llmrix.model.router.core.api.rerank.RerankModel;
import com.llmrix.model.router.core.api.rerank.RerankRequest;
import com.llmrix.model.router.core.api.rerank.RerankResponse;
import com.llmrix.model.router.core.api.image.ImageEditRequest;
import com.llmrix.model.router.core.api.image.ImageModel;
import com.llmrix.model.router.core.api.image.ImageRequest;
import com.llmrix.model.router.core.api.image.ImageResponse;
import com.llmrix.model.router.core.api.video.VideoContent;
import com.llmrix.model.router.core.api.video.VideoLookupRequest;
import com.llmrix.model.router.core.api.video.VideoModel;
import com.llmrix.model.router.core.api.video.VideoRemixRequest;
import com.llmrix.model.router.core.api.video.VideoRequest;
import com.llmrix.model.router.core.api.video.VideoResponse;
import com.llmrix.model.router.core.api.ModelClient;
import com.llmrix.model.router.core.api.ModelRequest;
import com.llmrix.model.router.core.api.chat.FilePart;
import com.llmrix.model.router.core.api.chat.VideoPart;
import com.llmrix.model.router.core.api.chat.AudioPart;
import com.llmrix.model.router.core.api.RoutedResponse;
import com.llmrix.model.router.core.api.audio.SpeechRequest;
import com.llmrix.model.router.core.api.Usage;
import com.llmrix.model.router.core.event.AttemptCompleted;
import com.llmrix.model.router.core.event.AttemptStarted;
import com.llmrix.model.router.core.event.RequestCompleted;
import com.llmrix.model.router.core.event.RequestStarted;
import com.llmrix.model.router.core.event.RouteSelected;
import com.llmrix.model.router.core.event.RouterListener;
import com.llmrix.model.router.core.event.TargetCooldown;
import com.llmrix.model.router.core.event.UsageRecorded;
import com.llmrix.model.router.core.exception.BudgetExceededException;
import com.llmrix.model.router.core.exception.ModelException;
import com.llmrix.model.router.core.exception.ModelTimeoutException;
import com.llmrix.model.router.core.exception.ModelUnavailableException;
import com.llmrix.model.router.core.exception.RateLimitException;
import com.llmrix.model.router.core.model.ModelRequirement;
import com.llmrix.model.router.core.model.ModelFeature;
import com.llmrix.model.router.core.model.ModelLimits;
import com.llmrix.model.router.core.model.ModelTarget;
import com.llmrix.model.router.core.routing.NoCandidateException;
import com.llmrix.model.router.core.routing.RouteCandidate;
import com.llmrix.model.router.core.routing.RoutingHints;
import com.llmrix.model.router.core.routing.RoutingStrategy;
import com.llmrix.model.router.core.routing.Strategies;
import com.llmrix.model.router.core.state.HealthAttempt;
import com.llmrix.model.router.core.state.HealthState;
import com.llmrix.model.router.core.state.InMemoryRouterStateStore;
import com.llmrix.model.router.core.state.QuotaState;
import com.llmrix.model.router.core.state.RouterStateStore;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Shared routed execution for non-streaming model operations.
 */
public final class RoutedModelOperations implements ChatModel, EmbeddingModel, RerankModel, AudioModel, ImageModel, VideoModel, AutoCloseable {
    private static final System.Logger LOGGER = System.getLogger(RoutedModelOperations.class.getName());

    private final Map<String, ModelTarget> targets;
    private final Map<String, HealthState> health = new LinkedHashMap<>();
    private final Map<String, QuotaState> quotas = new LinkedHashMap<>();
    private final RoutingStrategy strategy;
    private final String strategyName;
    private final ExecutionPolicy policy;
    private final RouterListener listener;
    private final ExecutorService executor;
    private final boolean ownsExecutor;
    private final String namespace;
    private final RouterStateStore stateStore;
    private final Predicate<RuntimeException> retryPredicate;
    private final ModelLimits routeQuota;

    private RoutedModelOperations(Builder builder) {
        if (builder.targets.isEmpty()) throw new IllegalArgumentException("at least one target is required");
        targets = Collections.unmodifiableMap(new LinkedHashMap<>(builder.targets));
        strategy = builder.strategy;
        strategyName = builder.strategyName;
        policy = new ExecutionPolicy(builder.timeout, builder.maxRetries, builder.retryDelay,
                builder.failureThreshold, builder.cooldown, null, null);
        listener = builder.listener;
        namespace = builder.namespace;
        stateStore = builder.stateStore;
        routeQuota = builder.routeQuota;
        retryPredicate = builder.retryPredicate;
        ownsExecutor = builder.executor == null;
        executor = ownsExecutor ? Executors.newCachedThreadPool(new DaemonThreadFactory()) : builder.executor;
        targets.forEach((id, target) -> {
            health.put(id, builder.stateStore.health(namespace, id));
            quotas.put(id, builder.stateStore.quota(namespace, id, target.limits()));
        });
    }

    public static Builder builder() {
        return new Builder();
    }

    @Override
    public ChatResponse chat(ChatRequest request) {
        return execute(request, ModelRequirement.CHAT, client -> client.requireChat().chat(request));
    }

    @Override
    public EmbeddingResponse embed(EmbeddingRequest request) {
        return execute(request, ModelRequirement.EMBEDDINGS, client -> client.requireEmbeddings().embed(request));
    }

    @Override
    public RerankResponse rerank(RerankRequest request) {
        return execute(request, ModelRequirement.RERANK, client -> client.requireRerank().rerank(request));
    }

    @Override
    public AudioResponse transcribe(AudioTextRequest request) {
        return execute(request, ModelRequirement.AUDIO_TRANSCRIPTION, client -> client.requireAudio().transcribe(request));
    }

    @Override
    public AudioResponse translate(AudioTextRequest request) {
        return execute(request, ModelRequirement.AUDIO_TRANSLATION, client -> client.requireAudio().translate(request));
    }

    @Override
    public AudioResponse speech(SpeechRequest request) {
        return execute(request, ModelRequirement.TEXT_TO_SPEECH, client -> client.requireAudio().speech(request));
    }

    @Override
    public ImageResponse generate(ImageRequest request) {
        return execute(request, ModelRequirement.IMAGE_GENERATION, client -> client.requireImages().generate(request));
    }

    @Override
    public ImageResponse edit(ImageEditRequest request) {
        return execute(request, ModelRequirement.IMAGE_EDIT, client -> client.requireImages().edit(request));
    }

    @Override
    public VideoResponse create(VideoRequest request) {
        return execute(request, ModelRequirement.VIDEO_GENERATION, client -> client.requireVideos().create(request));
    }

    @Override
    public VideoResponse retrieve(VideoLookupRequest request) {
        return execute(request, ModelRequirement.VIDEO_GENERATION, client -> client.requireVideos().retrieve(request));
    }

    @Override
    public VideoContent content(VideoLookupRequest request) {
        return execute(request, ModelRequirement.VIDEO_GENERATION, client -> client.requireVideos().content(request));
    }

    @Override
    public VideoResponse delete(VideoLookupRequest request) {
        return execute(request, ModelRequirement.VIDEO_GENERATION, client -> client.requireVideos().delete(request));
    }

    @Override
    public VideoResponse remix(VideoRemixRequest request) {
        return execute(request, ModelRequirement.VIDEO_GENERATION, client -> client.requireVideos().remix(request));
    }

    /** Current health and load snapshots for every target in this operation route. */
    public List<RouteCandidate> targets() {
        long now = System.currentTimeMillis();
        return targets.values().stream()
                .map(target -> {
                    HealthState state = health.get(target.id());
                    return new RouteCandidate(target, state.available(now), state.inFlight(), state.latencyEwmaMillis());
                })
                .toList();
    }

    private <R extends RoutedResponse<R>> R execute(
            ModelRequest request, ModelRequirement capability, Function<ModelClient, R> invocation) {
        Objects.requireNonNull(request, "request");
        String requestId = requestId(request);
        long started = System.nanoTime();
        notifySafely(() -> listener.onRequestStarted(new RequestStarted(requestId, started, request, namespace)));
        List<RouteCandidate> eligible = eligible(request, capability);
        if (eligible.isEmpty()) {
            notifySafely(() -> listener.onRequestCompleted(
                    new RequestCompleted(requestId, null, System.nanoTime() - started, false, 0)));
            throw new NoCandidateException("no target supports " + capability.name().toLowerCase());
        }
        QuotaState requestQuota = acquireRouteQuota(request);
        ModelTarget selected = strategy.select(request, List.copyOf(eligible));
        notifySafely(() -> listener.onRouteSelected(
                new RouteSelected(requestId, selected.id(), strategyName, "strategy:" + strategyName)));
        List<ModelTarget> order = executionOrder(selected, eligible);
        RequestBudget budget = new RequestBudget(request.routingHints().maxCostUsd());
        long deadline = started + policy.timeout().toNanos();
        Throwable lastFailure = null;
        int attempts = 0;

        for (ModelTarget target : order) {
            for (int attempt = 1; attempt <= policy.maxRetries() + 1; attempt++) {
                RequestBudget.Reservation reservation = budget.tryReserve(target, request);
                if (reservation == null) {
                    lastFailure = new BudgetExceededException("request cost budget exhausted before target: " + target.id());
                    break;
                }
                HealthAttempt healthAttempt = health.get(target.id()).tryBeginAttempt(target.limits().maxConcurrency());
                if (healthAttempt == null) {
                    budget.release(reservation);
                    lastFailure = new RateLimitException("local target max concurrency exceeded: " + target.id());
                    break;
                }
                if (!quotas.get(target.id()).tryAcquire(request.estimatedInputTokens())) {
                    healthAttempt.cancel();
                    budget.release(reservation);
                    lastFailure = new RateLimitException("local target quota exceeded: " + target.id());
                    break;
                }
                attempts++;
                int attemptNumber = attempt;
                long attemptStarted = System.nanoTime();
                notifySafely(() -> listener.onAttemptStarted(new AttemptStarted(requestId, target.id(), attemptNumber)));
                try {
                    R response = invoke(target, invocation, deadline);
                    Usage usage = response.usage();
                    budget.settle(reservation, target, usage);
                    quotas.get(target.id()).recordOutputTokens(Math.max(0, usage.outputTokens()));
                    if (requestQuota != null) requestQuota.recordOutputTokens(Math.max(0, usage.outputTokens()));
                    publishUsage(requestId, target, usage);
                    long duration = System.nanoTime() - attemptStarted;
                    healthAttempt.success(duration);
                    notifySafely(() -> listener.onAttemptCompleted(new AttemptCompleted(
                            requestId, target.id(), attemptNumber, duration, true, null)));
                    int totalAttempts = attempts;
                    notifySafely(() -> listener.onRequestCompleted(new RequestCompleted(
                            requestId, target.id(), System.nanoTime() - started, true, totalAttempts)));
                    return response.routedBy(target.id());
                } catch (RuntimeException failure) {
                    long duration = System.nanoTime() - attemptStarted;
                    LOGGER.log(System.Logger.Level.DEBUG,
                            "Model target attempt failed: target={0}, attempt={1}, type={2}, message={3}",
                            target.id(), attemptNumber, failure.getClass().getSimpleName(), failure.getMessage());
                    boolean cooldown = recordHealthFailure(healthAttempt, failure, duration);
                    if (cooldown) publishCooldown(requestId, target.id());
                    lastFailure = failure;
                    notifySafely(() -> listener.onAttemptCompleted(new AttemptCompleted(
                            requestId, target.id(), attemptNumber, duration, false, failure.getClass().getSimpleName())));
                    if (!retryPredicate.test(failure) || attempt > policy.maxRetries()) {
                        break;
                    }
                    waitBeforeRetry(deadline);
                }
            }
        }
        int totalAttempts = attempts;
        notifySafely(() -> listener.onRequestCompleted(new RequestCompleted(
                requestId, null, System.nanoTime() - started, false, totalAttempts)));
        // Preserve non-retryable model failures, including local request validation
        // failures that do not carry an upstream HTTP status code. Budget exhaustion
        // keeps its established aggregate-unavailable behavior.
        if (lastFailure instanceof ModelException modelFailure
                && !modelFailure.retryable()
                && !(modelFailure instanceof BudgetExceededException)) {
            throw modelFailure;
        }
        if (lastFailure != null) {
            LOGGER.log(System.Logger.Level.WARNING,
                    "All model targets failed: attempts={0}, type={1}, message={2}",
                    totalAttempts, lastFailure.getClass().getSimpleName(), lastFailure.getMessage());
        }
        throw new ModelUnavailableException("model service is temporarily unavailable", lastFailure);
    }

    /**
     * Request validation, policy, authentication, and content errors do not indicate an unhealthy target.
     * Counting those failures would incorrectly cool down every target after a repeated bad request.
     */
    private boolean recordHealthFailure(HealthAttempt attempt, RuntimeException failure, long durationNanos) {
        if (failure instanceof ModelException modelFailure && !modelFailure.retryable()) {
            attempt.cancel();
            return false;
        }
        return attempt.failure(durationNanos, policy.failureThreshold(), policy.cooldown());
    }

    private QuotaState acquireRouteQuota(ModelRequest request) {
        if (routeQuota.equals(ModelLimits.UNLIMITED)) return null;
        String partition = request.routingHints().attributes().get(RoutingHints.AUTH_QUOTA_KEY);
        if (partition == null || partition.isBlank()) partition = "shared";
        String key = "__route_quota__:" + partition;
        QuotaState quota = stateStore.quota(namespace, key, routeQuota);
        if (!quota.tryAcquire(request.estimatedInputTokens())) {
            throw new RateLimitException("route quota exceeded: " + namespace);
        }
        return quota;
    }


    private <R> R invoke(ModelTarget target, Function<ModelClient, R> invocation, long deadline) {
        long remaining = deadline - System.nanoTime();
        if (remaining <= 0) throw new ModelTimeoutException("router timeout exceeded", null);
        CompletableFuture<R> future = CompletableFuture.supplyAsync(() -> invocation.apply(target.client()), executor);
        try {
            return future.get(remaining, TimeUnit.NANOSECONDS);
        } catch (TimeoutException error) {
            future.cancel(true);
            throw new ModelTimeoutException("target timed out: " + target.id(), error);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new ModelUnavailableException("target invocation interrupted", error);
        } catch (ExecutionException error) {
            Throwable cause = error.getCause();
            if (cause instanceof RuntimeException runtime) throw runtime;
            throw new ModelUnavailableException("target invocation failed", cause);
        }
    }

    private List<RouteCandidate> eligible(ModelRequest request, ModelRequirement capability) {
        RoutingHints hints = request.routingHints();
        long now = System.currentTimeMillis();
        List<RouteCandidate> result = new ArrayList<>();
        for (ModelTarget target : targets.values()) {
            HealthState state = health.get(target.id());
            if (!state.available(now) || !target.satisfies(capability)) continue;
            if (capability == ModelRequirement.CHAT && request instanceof ChatRequest chat
                    && !supportsChatInput(target, chat)) continue;
            if (capability == ModelRequirement.CHAT && request instanceof ChatRequest chat
                    && !chat.tools().isEmpty() && !target.supports(ModelFeature.TOOLS)) continue;
            if (capability == ModelRequirement.CHAT && request instanceof ChatRequest chat
                    && chat.responseFormat() != null && !target.supports(ModelFeature.STRUCTURED_OUTPUT)) continue;
            if (capability == ModelRequirement.CHAT && request instanceof ChatRequest chat
                    && chat.promptCache() != null
                    && !target.satisfies(ModelRequirement.PROMPT_CACHE)) continue;
            if (capability == ModelRequirement.CHAT && request instanceof ChatRequest chat
                    && chat.promptCache() != null && chat.promptCache().retention() != null
                    && target.metadata().containsKey("prompt-cache-retention")
                    && !chat.promptCache().retention().equals(target.metadata().get("prompt-cache-retention"))) continue;
            if (!hints.allowedModels().isEmpty() && !hints.allowedModels().contains(target.id())) continue;
            if (hints.deniedModels().contains(target.id())) continue;
            if (!hints.requirements().stream().allMatch(target::satisfies)) continue;
            if (target.maxInputTokens() != null && request.estimatedInputTokens() > target.maxInputTokens()) continue;
            if (target.limits().maxConcurrency() != null && state.inFlight() >= target.limits().maxConcurrency())
                continue;
            if (quotas.get(target.id()).rejectionReason(request.estimatedInputTokens()) != null) continue;
            if (hints.maxLatency() != null
                    && state.latencyEwmaMillis() > hints.maxLatency().toNanos() / 1_000_000d) continue;
            if (hints.maxCostUsd() != null) {
                double estimate = target.pricing().estimateCost(
                        request.estimatedInputTokens(), request.estimatedOutputTokens());
                if (!Double.isFinite(estimate) || estimate > hints.maxCostUsd()) continue;
            }
            result.add(new RouteCandidate(target, true, state.inFlight(), state.latencyEwmaMillis()));
        }
        return List.copyOf(result);
    }

    private static boolean supportsChatInput(ModelTarget target, ChatRequest request) {
        boolean video = request.messages().stream().flatMap(message -> message.contents().stream())
                .anyMatch(VideoPart.class::isInstance);
        if (video && !target.satisfies(ModelRequirement.VIDEO_INPUT)) return false;
        boolean file = request.messages().stream().flatMap(message -> message.contents().stream())
                .anyMatch(FilePart.class::isInstance);
        if (file && !target.satisfies(ModelRequirement.FILE_INPUT)) return false;
        boolean audio = request.messages().stream().flatMap(message -> message.contents().stream())
                .anyMatch(AudioPart.class::isInstance);
        return !audio || target.satisfies(ModelRequirement.AUDIO_INPUT);
    }

    private List<ModelTarget> executionOrder(ModelTarget selected, List<RouteCandidate> eligible) {
        List<ModelTarget> order = new ArrayList<>();
        order.add(selected);
        eligible.stream().map(RouteCandidate::target)
                .filter(target -> !target.id().equals(selected.id()))
                .sorted(java.util.Comparator.comparingInt(ModelTarget::priority))
                .forEach(order::add);
        return List.copyOf(order);
    }

    private void publishUsage(String requestId, ModelTarget target, Usage usage) {
        double cost = usage.inputTokens() < 0 || usage.outputTokens() < 0 ? Double.NaN
                : target.pricing().estimateCost(usage);
        notifySafely(() -> listener.onUsageRecorded(new UsageRecorded(requestId, target.id(), usage, cost)));
    }

    private void publishCooldown(String requestId, String targetId) {
        notifySafely(() -> listener.onTargetCooldown(new TargetCooldown(requestId, targetId, policy.cooldown())));
    }

    private void waitBeforeRetry(long deadline) {
        long millis = Math.min(policy.retryDelay().toMillis(),
                Math.max(0, TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime())));
        if (millis == 0) return;
        try {
            Thread.sleep(millis);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new ModelUnavailableException("retry interrupted", error);
        }
    }

    private static String requestId(ModelRequest request) {
        String supplied = request.routingHints().attributes().get(RoutingHints.REQUEST_ID);
        return supplied == null || supplied.isBlank() ? UUID.randomUUID().toString() : supplied;
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
        private RouterListener listener = RouterListener.NOOP;
        private RouterStateStore stateStore = new InMemoryRouterStateStore();
        private ModelLimits routeQuota = ModelLimits.UNLIMITED;
        private String namespace = "default";
        private Predicate<RuntimeException> retryPredicate = Builder::recoverable;
        private ExecutorService executor;

        public Builder target(ModelTarget value) {
            Objects.requireNonNull(value, "target");
            if (targets.putIfAbsent(value.id(), value) != null)
                throw new IllegalArgumentException("duplicate target: " + value.id());
            return this;
        }

        public Builder strategy(String name, RoutingStrategy value) {
            strategyName = Objects.requireNonNull(name);
            strategy = Objects.requireNonNull(value);
            return this;
        }

        public Builder timeout(Duration value) {
            timeout = Objects.requireNonNull(value);
            return this;
        }

        public Builder maxRetries(int value) {
            maxRetries = value;
            return this;
        }

        public Builder retryDelay(Duration value) {
            retryDelay = Objects.requireNonNull(value);
            return this;
        }

        public Builder failureThreshold(int value) {
            failureThreshold = value;
            return this;
        }

        public Builder cooldown(Duration value) {
            cooldown = Objects.requireNonNull(value);
            return this;
        }

        public Builder listener(RouterListener value) {
            listener = Objects.requireNonNull(value);
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
            namespace = Objects.requireNonNull(value);
            return this;
        }

        public Builder retryOn(Predicate<RuntimeException> value) {
            retryPredicate = Objects.requireNonNull(value);
            return this;
        }

        public Builder executor(ExecutorService value) {
            executor = Objects.requireNonNull(value);
            return this;
        }

        public RoutedModelOperations build() {
            return new RoutedModelOperations(this);
        }

        private static boolean recoverable(RuntimeException failure) {
            return failure instanceof ModelException modelFailure && modelFailure.retryable();
        }
    }

    private static final class DaemonThreadFactory implements ThreadFactory {
        @Override
        public Thread newThread(Runnable task) {
            Thread thread = new Thread(task, "llmrix-model-operation");
            thread.setDaemon(true);
            return thread;
        }
    }
}
