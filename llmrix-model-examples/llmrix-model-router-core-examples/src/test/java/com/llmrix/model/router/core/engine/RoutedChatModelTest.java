package com.llmrix.model.router.core.engine;

import com.llmrix.model.router.core.api.Usage;
import com.llmrix.model.router.core.api.chat.*;
import com.llmrix.model.router.core.model.ModelTarget;
import com.llmrix.model.router.core.model.ModelOperation;
import com.llmrix.model.router.core.model.ModelTrait;
import com.llmrix.model.router.core.model.InputModality;
import com.llmrix.model.router.core.model.ModelLimits;
import com.llmrix.model.router.core.model.ModelPricing;
import com.llmrix.model.router.core.stream.ToolCallAccumulator;
import com.llmrix.model.router.core.exception.BudgetExceededException;
import com.llmrix.model.router.core.exception.RateLimitException;
import com.llmrix.model.router.core.exception.ModelUnavailableException;
import com.llmrix.model.router.core.routing.NoCandidateException;
import com.llmrix.model.router.core.routing.RoutingHints;
import com.llmrix.model.router.core.routing.Strategies;
import com.llmrix.model.router.core.routing.ContextualBanditStrategy;
import com.llmrix.model.router.core.state.InMemoryRouterStateStore;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.SubmissionPublisher;
import java.util.concurrent.Executors;
import java.util.concurrent.Callable;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertFalse;

class RoutedChatModelTest {

    @Test
    void routesByRequiredCapability() {
        ChatModel general = request -> ChatResponse.of("general");
        ChatModel coding = request -> ChatResponse.of("coding");

        try (RoutedChatModel router = RoutedChatModel.builder()
                .target(ModelTarget.builder("general", general).operations(ModelOperation.CHAT).build())
                .target(ModelTarget.builder("coding", coding).operations(ModelOperation.CHAT).traits(ModelTrait.CODE).build())
                .strategy("priority", Strategies.priority())
                .build()) {
            ChatRequest request = ChatRequest.builder()
                    .userMessage("review")
                    .routingHints(RoutingHints.builder().require(ModelTrait.CODE).build())
                    .build();

            ChatResponse response = router.chat(request);

            assertEquals("coding", response.text());
            assertEquals("coding", response.modelId());
            assertEquals("coding", router.explain(request).selectedTarget());
        }
    }

    @Test
    void routesFileInputOnlyToFileCapableModel() {
        try (RoutedChatModel router = RoutedChatModel.builder()
                .target(ModelTarget.builder("chat", request -> ChatResponse.of("chat"))
                        .operations(ModelOperation.CHAT).build())
                .target(ModelTarget.builder("documents", request -> ChatResponse.of("documents"))
                        .operations(ModelOperation.CHAT).inputModalities(InputModality.FILE).build())
                .strategy("priority", Strategies.priority())
                .build()) {
            ChatRequest request = ChatRequest.builder()
                    .message(Message.user(new TextPart("summarize"),
                            new FilePart("https://example.test/report.pdf", "report.pdf")))
                    .build();

            assertEquals("documents", router.chat(request).text());
        }
    }

    @Test
    void routesAudioInputOnlyToAudioCapableModel() {
        try (RoutedChatModel router = RoutedChatModel.builder()
                .target(ModelTarget.builder("chat", request -> ChatResponse.of("chat"))
                        .operations(ModelOperation.CHAT).build())
                .target(ModelTarget.builder("audio", request -> ChatResponse.of("audio"))
                        .operations(ModelOperation.CHAT).inputModalities(InputModality.AUDIO).build())
                .strategy("priority", Strategies.priority())
                .build()) {
            ChatRequest request = ChatRequest.builder()
                    .message(Message.user(new TextPart("transcribe"), new AudioPart("ZmFrZQ==", "wav")))
                    .build();

            assertEquals("audio", router.chat(request).text());
        }
    }

    @Test
    void routesUsingSemanticClassifierScores() {
        try (RoutedChatModel router = RoutedChatModel.builder()
                .target("general", request -> ChatResponse.of("general"))
                .target("code", request -> ChatResponse.of("code"))
                .strategy("semantic", Strategies.semantic((request, candidates) ->
                        request.messages().get(0).content().contains("Java")
                                ? Map.of("code", 1.0, "general", 0.1)
                                : Map.of()))
                .build()) {
            assertEquals("code", router.chat("Review Java code").text());
            assertEquals("general", router.chat("hello").text());
        }
    }

    @Test
    void contextualBanditExploresAndLearnsFromRewards() {
        ContextualBanditStrategy strategy = new ContextualBanditStrategy((request, candidates) -> Map.of(), 0);
        ModelTarget a = ModelTarget.builder("a", request -> ChatResponse.of("a")).build();
        ModelTarget b = ModelTarget.builder("b", request -> ChatResponse.of("b")).build();
        List<com.llmrix.model.router.core.routing.RouteCandidate> candidates = List.of(
                new com.llmrix.model.router.core.routing.RouteCandidate(a, true, 0, 0),
                new com.llmrix.model.router.core.routing.RouteCandidate(b, true, 0, 0));

        String first = strategy.select(ChatRequest.user("q"), candidates).id();
        String second = strategy.select(ChatRequest.user("q"), candidates).id();
        assertTrue(!first.equals(second), "untried arms must be explored");
        strategy.observe("a", 1);
        strategy.observe("b", 0);
        assertEquals("a", strategy.select(ChatRequest.user("q"), candidates).id());
        assertTrue(strategy.snapshot().get("a").selections() >= 1);
        assertEquals(1.0, strategy.snapshot().get("a").averageReward());

        ContextualBanditStrategy restored = new ContextualBanditStrategy((request, values) -> Map.of(), 0);
        restored.restore(strategy.snapshot());
        assertEquals(strategy.snapshot(), restored.snapshot());
    }

    @Test
    void retriesThenFallsBackAndCoolsDownFailure() {
        AtomicInteger primaryCalls = new AtomicInteger();
        ChatModel primary = request -> {
            primaryCalls.incrementAndGet();
            throw new RateLimitException("limited");
        };
        ChatModel backup = request -> ChatResponse.of("backup");

        try (RoutedChatModel router = RoutedChatModel.builder()
                .target(ModelTarget.builder("primary", primary).priority(1).build())
                .target(ModelTarget.builder("backup", backup).priority(2).build())
                .strategy("priority", Strategies.priority())

                .maxRetries(1)
                .retryDelay(Duration.ZERO)
                .failureThreshold(1)
                .cooldown(Duration.ofMinutes(1))
                .build()) {
            ChatResponse first = router.chat("hello");
            ChatResponse second = router.chat("hello again");

            assertEquals("backup", first.text());
            assertEquals("backup", second.text());
            assertEquals(2, primaryCalls.get(), "the first request retries once; the next request honors cooldown");
            assertTrue(router.explain(ChatRequest.user("test")).excludedTargets().containsKey("primary"));
        }
    }

    @Test
    void excludesCandidatesWhoseObservedLatencyExceedsHint() {
        ChatModel slow = request -> {
            try {
                Thread.sleep(25);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return ChatResponse.of("slow");
        };
        ChatModel fast = request -> ChatResponse.of("fast");

        try (RoutedChatModel router = RoutedChatModel.builder()
                .target(ModelTarget.builder("slow", slow).priority(1).build())
                .target(ModelTarget.builder("fast", fast).priority(2).build())
                .strategy("priority", Strategies.priority())
                .build()) {
            assertEquals("slow", router.chat("warm up").text());

            ChatRequest constrained = ChatRequest.builder()
                    .userMessage("latency sensitive")
                    .routingHints(RoutingHints.builder().maxLatency(Duration.ofMillis(1)).build())
                    .build();
            assertEquals("fast", router.chat(constrained).text());
            assertEquals("max-latency", router.explain(constrained).excludedTargets().get("slow"));
        }
    }

    @Test
    void streamingFallsBackWhenCandidateFailsBeforeFirstChunk() throws InterruptedException {
        AtomicInteger primarySubscriptions = new AtomicInteger();
        ChatModel primary = new ChatModel() {
            @Override public ChatResponse chat(ChatRequest request) { throw new UnsupportedOperationException(); }
            @Override public Flow.Publisher<ChatChunk> stream(ChatRequest request) {
                return subscriber -> {
                    primarySubscriptions.incrementAndGet();
                    subscriber.onSubscribe(new NoopSubscription());
                    subscriber.onError(new RateLimitException("limited"));
                };
            }
        };
        ChatModel backup = new ChatModel() {
            @Override public ChatResponse chat(ChatRequest request) { throw new UnsupportedOperationException(); }
            @Override public Flow.Publisher<ChatChunk> stream(ChatRequest request) {
                return subscriber -> {
                    subscriber.onSubscribe(new NoopSubscription());
                    subscriber.onNext(new ChatChunk("hello", false, Usage.UNKNOWN));
                    subscriber.onNext(new ChatChunk(" world", true, Usage.UNKNOWN));
                    subscriber.onComplete();
                };
            }
        };

        try (RoutedChatModel router = RoutedChatModel.builder()
                .target(ModelTarget.builder("primary", primary).priority(1).build())
                .target(ModelTarget.builder("backup", backup).priority(2).build())

                .maxRetries(1)
                .retryDelay(Duration.ZERO)
                .strategy("priority", Strategies.priority())
                .build()) {
            List<String> chunks = new ArrayList<>();
            CountDownLatch completed = new CountDownLatch(1);
            router.stream(ChatRequest.user("hello")).subscribe(new Flow.Subscriber<>() {
                @Override public void onSubscribe(Flow.Subscription subscription) { subscription.request(Long.MAX_VALUE); }
                @Override public void onNext(ChatChunk item) { chunks.add(item.text()); }
                @Override public void onError(Throwable throwable) { completed.countDown(); }
                @Override public void onComplete() { completed.countDown(); }
            });

            assertTrue(completed.await(2, TimeUnit.SECONDS));
            assertEquals(List.of("hello", " world"), chunks);
            assertEquals(2, primarySubscriptions.get(), "primary is retried once before fallback");
        }
    }

    @Test
    void streamingDoesNotFallbackAfterFirstChunk() throws InterruptedException {
        ChatModel primary = new ChatModel() {
            @Override public ChatResponse chat(ChatRequest request) { throw new UnsupportedOperationException(); }
            @Override public Flow.Publisher<ChatChunk> stream(ChatRequest request) {
                return subscriber -> {
                    subscriber.onSubscribe(new NoopSubscription());
                    subscriber.onNext(new ChatChunk("partial", false, Usage.UNKNOWN));
                    try { Thread.sleep(25); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                    subscriber.onError(new RateLimitException("failed after output"));
                };
            }
        };
        AtomicInteger backupSubscriptions = new AtomicInteger();
        ChatModel backup = new ChatModel() {
            @Override public ChatResponse chat(ChatRequest request) { throw new UnsupportedOperationException(); }
            @Override public Flow.Publisher<ChatChunk> stream(ChatRequest request) {
                return subscriber -> {
                    backupSubscriptions.incrementAndGet();
                    subscriber.onSubscribe(new NoopSubscription());
                    subscriber.onNext(new ChatChunk("backup", true, Usage.UNKNOWN));
                };
            }
        };
        try (RoutedChatModel router = RoutedChatModel.builder()
                .target(ModelTarget.builder("primary", primary).priority(1).build())
                .target(ModelTarget.builder("backup", backup).priority(2).build())
.maxRetries(1).strategy("priority", Strategies.priority()).build()) {
            List<String> chunks = new ArrayList<>();
            AtomicReference<Throwable> failure = new AtomicReference<>();
            CountDownLatch terminal = new CountDownLatch(1);
            router.stream(ChatRequest.user("hello")).subscribe(new Flow.Subscriber<>() {
                @Override public void onSubscribe(Flow.Subscription subscription) { subscription.request(Long.MAX_VALUE); }
                @Override public void onNext(ChatChunk item) { chunks.add(item.text()); }
                @Override public void onError(Throwable throwable) { failure.set(throwable); terminal.countDown(); }
                @Override public void onComplete() { terminal.countDown(); }
            });

            assertTrue(terminal.await(2, TimeUnit.SECONDS));
            assertEquals(List.of("partial"), chunks);
            assertEquals(0, backupSubscriptions.get());
            assertTrue(failure.get() instanceof RateLimitException);
        }
    }

    @Test
    void enforcesRequestsPerMinuteAndFallsBack() {
        ChatModel primary = request -> ChatResponse.of("primary");
        ChatModel backup = request -> ChatResponse.of("backup");

        try (RoutedChatModel router = RoutedChatModel.builder()
                .target(ModelTarget.builder("primary", primary).priority(1)
                        .limits(new ModelLimits(1L, null, null)).build())
                .target(ModelTarget.builder("backup", backup).priority(2).build())

                .strategy("priority", Strategies.priority())
                .build()) {
            assertEquals("primary", router.chat("first").text());
            assertEquals("backup", router.chat("second").text());
            assertEquals("requests-per-minute",
                    router.explain(ChatRequest.user("third")).excludedTargets().get("primary"));
        }
    }

    @Test
    void enforcesTokensPerMinuteBeforeInvocation() {
        ChatModel primary = request -> ChatResponse.of("primary");
        ChatModel backup = request -> ChatResponse.of("backup");
        ChatRequest request = ChatRequest.builder().userMessage("tokens").estimatedInputTokens(3).build();

        try (RoutedChatModel router = RoutedChatModel.builder()
                .target(ModelTarget.builder("primary", primary).priority(1)
                        .limits(new ModelLimits(null, 2L, null)).build())
                .target(ModelTarget.builder("backup", backup).priority(2).build())

                .strategy("priority", Strategies.priority())
                .build()) {
            assertEquals("backup", router.chat(request).text());
            assertEquals("tokens-per-minute", router.explain(request).excludedTargets().get("primary"));
        }
    }

    @Test
    void sharesHealthStateThroughStateStore() {
        InMemoryRouterStateStore store = new InMemoryRouterStateStore();
        ChatModel failing = request -> { throw new RateLimitException("limited"); };
        ChatModel backup = request -> ChatResponse.of("backup");

        try (RoutedChatModel first = sharedRouter(store, failing, backup);
             RoutedChatModel second = sharedRouter(store, failing, backup)) {
            assertEquals("backup", first.chat("trigger cooldown").text());
            assertEquals("backup", second.chat("shared cooldown").text());
            assertEquals("cooldown", second.explain(ChatRequest.user("inspect"))
                    .excludedTargets().get("primary"));
        }
    }

    @Test
    void settlesAttemptsThroughLeaseAwareHealthState() {
        AtomicBoolean settled = new AtomicBoolean();
        com.llmrix.model.router.core.state.HealthState health = new com.llmrix.model.router.core.state.HealthState() {
            @Override public boolean available(long nowMillis) { return true; }
            @Override public int inFlight() { return 0; }
            @Override public double latencyEwmaMillis() { return 0; }
            @Override public void begin() { throw new AssertionError("legacy begin must not be called"); }
            @Override public void cancel() { throw new AssertionError("legacy cancel must not be called"); }
            @Override public void success(long durationNanos) { throw new AssertionError("legacy success must not be called"); }
            @Override public boolean failure(long durationNanos, int threshold, Duration cooldown) {
                throw new AssertionError("legacy failure must not be called");
            }
            @Override public com.llmrix.model.router.core.state.HealthAttempt beginAttempt() {
                return new com.llmrix.model.router.core.state.HealthAttempt() {
                    @Override public void cancel() { settled.set(true); }
                    @Override public void success(long durationNanos) { settled.set(true); }
                    @Override public boolean failure(long durationNanos, int threshold, Duration cooldown) {
                        settled.set(true);
                        return false;
                    }
                };
            }
        };
        com.llmrix.model.router.core.state.RouterStateStore store = new com.llmrix.model.router.core.state.RouterStateStore() {
            @Override public com.llmrix.model.router.core.state.HealthState health(String namespace, String candidateId) {
                return health;
            }
            @Override public com.llmrix.model.router.core.state.QuotaState quota(
                    String namespace, String candidateId, ModelLimits limits) {
                return new com.llmrix.model.router.core.state.TargetQuota(limits);
            }
        };

        try (RoutedChatModel router = RoutedChatModel.builder()
                .target("model", request -> ChatResponse.of("ok")).stateStore(store).build()) {
            assertEquals("ok", router.chat("hello").text());
            assertTrue(settled.get());
        }
    }

    @Test
    void tries_remaining_model_after_unknown_runtime_failure() {
        AtomicInteger primaryCalls = new AtomicInteger();
        AtomicInteger backupCalls = new AtomicInteger();
        ChatModel broken = request -> {
            primaryCalls.incrementAndGet();
            throw new IllegalStateException("adapter bug");
        };
        ChatModel backup = request -> {
            backupCalls.incrementAndGet();
            return ChatResponse.of("backup");
        };

        try (RoutedChatModel router = RoutedChatModel.builder()
                .target(ModelTarget.builder("primary", broken).priority(1).build())
                .target(ModelTarget.builder("backup", backup).priority(2).build())

                .maxRetries(2)
                .retryDelay(Duration.ZERO)
                .strategy("priority", Strategies.priority())
                .build()) {
            assertEquals("backup", router.chat("hello").text());
            assertEquals(1, primaryCalls.get());
            assertEquals(1, backupCalls.get());
        }
    }

    @Test
    void streamingTimeoutCancelsUpstreamAndReleasesInFlight() throws InterruptedException {
        AtomicBoolean upstreamCancelled = new AtomicBoolean();
        ChatModel hanging = hangingStream(upstreamCancelled, new CountDownLatch(0));

        try (RoutedChatModel router = RoutedChatModel.builder()
                .target("hanging", hanging)
                .timeout(Duration.ofMillis(40))
                .failureThreshold(10)
                .build()) {
            CountDownLatch terminal = new CountDownLatch(1);
            AtomicReference<Throwable> error = new AtomicReference<>();
            router.stream(ChatRequest.user("hello")).subscribe(new Flow.Subscriber<>() {
                @Override public void onSubscribe(Flow.Subscription subscription) { subscription.request(1); }
                @Override public void onNext(ChatChunk item) { }
                @Override public void onError(Throwable throwable) { error.set(throwable); terminal.countDown(); }
                @Override public void onComplete() { terminal.countDown(); }
            });

            assertTrue(terminal.await(2, TimeUnit.SECONDS));
            assertTrue(error.get() instanceof com.llmrix.model.router.core.exception.ModelTimeoutException);
            assertTrue(upstreamCancelled.get());
            assertEquals(0, router.targets().get(0).inFlight());
        }
    }

    @Test
    void downstreamCancellationPropagatesAndReleasesInFlight() throws InterruptedException {
        AtomicBoolean upstreamCancelled = new AtomicBoolean();
        CountDownLatch upstreamSubscribed = new CountDownLatch(1);
        ChatModel hanging = hangingStream(upstreamCancelled, upstreamSubscribed);

        try (RoutedChatModel router = RoutedChatModel.builder()
                .target("hanging", hanging)
                .timeout(Duration.ofSeconds(5))
                .build()) {
            AtomicReference<Flow.Subscription> downstream = new AtomicReference<>();
            CountDownLatch downstreamSubscribed = new CountDownLatch(1);
            router.stream(ChatRequest.user("hello")).subscribe(new Flow.Subscriber<>() {
                @Override public void onSubscribe(Flow.Subscription subscription) {
                    downstream.set(subscription);
                    downstreamSubscribed.countDown();
                    subscription.request(1);
                }
                @Override public void onNext(ChatChunk item) { }
                @Override public void onError(Throwable throwable) { }
                @Override public void onComplete() { }
            });

            assertTrue(upstreamSubscribed.await(2, TimeUnit.SECONDS));
            assertTrue(downstreamSubscribed.await(2, TimeUnit.SECONDS));
            downstream.get().cancel();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (!upstreamCancelled.get() && System.nanoTime() < deadline) Thread.onSpinWait();
            assertTrue(upstreamCancelled.get());
            assertEquals(0, router.targets().get(0).inFlight());
        }
    }

    @Test
    void cumulativeBudgetPreventsFallbackFromExceedingRequestLimit() {
        AtomicInteger backupCalls = new AtomicInteger();
        ChatModel primary = request -> { throw new RateLimitException("charged but failed"); };
        ChatModel backup = request -> {
            backupCalls.incrementAndGet();
            return ChatResponse.of("backup");
        };
        ModelPricing pricing = new ModelPricing(0d, 1_000d);
        ChatRequest request = ChatRequest.builder()
                .userMessage("hello")
                .routingHints(RoutingHints.builder().maxCostUsd(0.60).build())
                .build();

        try (RoutedChatModel router = RoutedChatModel.builder()
                .target(ModelTarget.builder("primary", primary).priority(1).pricing(pricing).build())
                .target(ModelTarget.builder("backup", backup).priority(2).pricing(pricing).build())

                .maxRetries(0)
                .strategy("priority", Strategies.priority())
                .build()) {
            assertThatThrownBy(() -> router.chat(request))
                    .isInstanceOf(ModelUnavailableException.class)
                    .hasCauseInstanceOf(BudgetExceededException.class);
            assertEquals(0, backupCalls.get());
        }
    }

    @Test
    void firstTokenTimeoutTerminatesBeforeTotalTimeout() throws InterruptedException {
        AtomicBoolean upstreamCancelled = new AtomicBoolean();
        ChatModel hanging = hangingStream(upstreamCancelled, new CountDownLatch(0));

        try (RoutedChatModel router = RoutedChatModel.builder()
                .target("hanging", hanging)
                .timeout(Duration.ofSeconds(5))
                .firstTokenTimeout(Duration.ofMillis(40))
                .failureThreshold(10)
                .build()) {
            CountDownLatch terminal = new CountDownLatch(1);
            AtomicReference<Throwable> error = new AtomicReference<>();
            router.stream(ChatRequest.user("hello")).subscribe(new Flow.Subscriber<>() {
                @Override public void onSubscribe(Flow.Subscription subscription) { subscription.request(1); }
                @Override public void onNext(ChatChunk item) { }
                @Override public void onError(Throwable throwable) { error.set(throwable); terminal.countDown(); }
                @Override public void onComplete() { terminal.countDown(); }
            });

            assertTrue(terminal.await(1, TimeUnit.SECONDS));
            assertTrue(error.get() instanceof com.llmrix.model.router.core.exception.ModelTimeoutException);
            assertTrue(upstreamCancelled.get());
        }
    }

    @Test
    void streamingUsesBoundedUpstreamDemand() throws InterruptedException {
        AtomicLong largestRequest = new AtomicLong();
        ChatModel model = new ChatModel() {
            @Override public ChatResponse chat(ChatRequest request) { throw new UnsupportedOperationException(); }
            @Override public Flow.Publisher<ChatChunk> stream(ChatRequest request) {
                return subscriber -> subscriber.onSubscribe(new Flow.Subscription() {
                    private final AtomicBoolean emitted = new AtomicBoolean();
                    @Override public void request(long n) {
                        largestRequest.accumulateAndGet(n, Math::max);
                        if (emitted.compareAndSet(false, true)) {
                            subscriber.onNext(new ChatChunk("done", true, Usage.UNKNOWN));
                            subscriber.onComplete();
                        }
                    }
                    @Override public void cancel() { }
                });
            }
        };

        try (RoutedChatModel router = RoutedChatModel.of(model)) {
            CountDownLatch terminal = new CountDownLatch(1);
            router.stream(ChatRequest.user("hello")).subscribe(new Flow.Subscriber<>() {
                @Override public void onSubscribe(Flow.Subscription subscription) { subscription.request(Long.MAX_VALUE); }
                @Override public void onNext(ChatChunk item) { }
                @Override public void onError(Throwable throwable) { terminal.countDown(); }
                @Override public void onComplete() { terminal.countDown(); }
            });

            assertTrue(terminal.await(2, TimeUnit.SECONDS));
            assertTrue(largestRequest.get() <= 32, "router must not request unbounded upstream demand");
        }
    }

    @Test
    void slowDownstreamKeepsBufferBoundedAndCancellationUnblocksProducer() throws InterruptedException {
        AtomicInteger produced = new AtomicInteger();
        CountDownLatch producerDone = new CountDownLatch(1);
        ChatModel fastProducer = new ChatModel() {
            @Override public ChatResponse chat(ChatRequest request) { throw new UnsupportedOperationException(); }
            @Override public Flow.Publisher<ChatChunk> stream(ChatRequest request) {
                return subscriber -> {
                    SubmissionPublisher<ChatChunk> source = new SubmissionPublisher<>(
                            java.util.concurrent.ForkJoinPool.commonPool(), 32);
                    source.subscribe(subscriber);
                    Thread producer = new Thread(() -> {
                        try {
                            for (int index = 0; index < 1_000; index++) {
                                source.submit(new ChatChunk("x", false, Usage.UNKNOWN));
                                produced.incrementAndGet();
                            }
                        } finally {
                            source.close();
                            producerDone.countDown();
                        }
                    }, "llmrix-model-router-backpressure-test");
                    producer.setDaemon(true);
                    producer.start();
                };
            }
        };

        try (RoutedChatModel router = RoutedChatModel.builder()
                .target("fast", fastProducer)
                .timeout(Duration.ofSeconds(5))
                .build()) {
            AtomicReference<Flow.Subscription> downstream = new AtomicReference<>();
            CountDownLatch firstChunk = new CountDownLatch(1);
            router.stream(ChatRequest.user("hello")).subscribe(new Flow.Subscriber<>() {
                @Override public void onSubscribe(Flow.Subscription subscription) {
                    downstream.set(subscription);
                    subscription.request(1);
                }
                @Override public void onNext(ChatChunk item) { firstChunk.countDown(); }
                @Override public void onError(Throwable throwable) { }
                @Override public void onComplete() { }
            });

            assertTrue(firstChunk.await(2, TimeUnit.SECONDS));
            Thread.sleep(100);
            assertTrue(produced.get() < 1_000, "a slow downstream must apply bounded backpressure");
            downstream.get().cancel();
            assertTrue(producerDone.await(2, TimeUnit.SECONDS), "cancellation must unblock the producer");
        }
    }

    @Test
    void estimatesStructuredMessagesAndToolSchemas() {
        ChatRequest request = ChatRequest.builder()
                .message(Message.user(new TextPart("hello"), new ImagePart("https://example.test/image.png")))
                .message(Message.assistant(new ToolCallPart("call", "weather", "{\"city\":\"Shanghai\"}")))
                .message(Message.tool("call", "{\"temperature\":25}"))
                .tools(new ToolDefinition("weather", "Get weather", Map.of(
                        "type", "object", "required", List.of("city"))))
                .build();

        assertTrue(request.estimatedInputTokens() > ChatRequest.user("hello").estimatedInputTokens());
    }

    @Test
    void rejectsInvalidToolChoiceReferences() {
        ToolDefinition weather = new ToolDefinition("weather", null, Map.of());
        assertThrows(IllegalArgumentException.class, () -> ChatRequest.builder()
                .userMessage("hi").tools(weather, weather).build());
        assertThrows(IllegalArgumentException.class, () -> ChatRequest.builder()
                .userMessage("hi").tools(weather).toolChoice(ToolChoice.named("missing")).build());
    }

    @Test
    void enforcesToolMessageRolesInCore() {
        assertThrows(IllegalArgumentException.class, () -> new Message(
                "user", List.of(new ToolCallPart("call", "weather", "{}"))));
        assertThrows(IllegalArgumentException.class, () -> new Message(
                "assistant", List.of(new ToolResultPart("call", "{}"))));
        assertThrows(IllegalArgumentException.class, () -> new Message("tool", "plain text"));
    }

    @Test
    void accumulatesIndexedStreamingToolCalls() {
        ToolCallAccumulator accumulator = new ToolCallAccumulator();
        accumulator.add(new ToolCallDelta(0, "call_1", "weather", "{\"city\":"));
        accumulator.add(new ToolCallDelta(1, "call_2", "time", "{\"zone\":"));
        accumulator.add(new ToolCallDelta(0, null, null, "\"Shanghai\"}"));
        accumulator.add(new ToolCallDelta(1, null, null, "\"UTC\"}"));

        assertEquals(List.of(
                new ToolCallPart("call_1", "weather", "{\"city\":\"Shanghai\"}"),
                new ToolCallPart("call_2", "time", "{\"zone\":\"UTC\"}")), accumulator.finish());
    }

    @Test
    void enforcesMaxConcurrencyAtomically() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ChatModel blocking = request -> {
            entered.countDown();
            try { release.await(2, TimeUnit.SECONDS); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            return ChatResponse.of("done");
        };
        try (RoutedChatModel router = RoutedChatModel.builder()
                .target(ModelTarget.builder("limited", blocking)
                        .limits(new ModelLimits(null, null, 1)).build())
                .build()) {
            var executor = Executors.newSingleThreadExecutor();
            try {
                var first = executor.submit(() -> router.chat("first"));
                assertTrue(entered.await(2, TimeUnit.SECONDS));
                assertThrows(NoCandidateException.class, () -> router.chat("second"));
                release.countDown();
                assertEquals("done", first.get(2, TimeUnit.SECONDS).text());
                assertEquals("done", router.chat("third").text());
            } finally {
                release.countDown();
                executor.shutdownNow();
            }
        }
    }

    @Test
    void enforcesRpmAtomicallyUnderContention() throws Exception {
        try (RoutedChatModel router = RoutedChatModel.builder()
                .target(ModelTarget.builder("limited", request -> ChatResponse.of("ok"))
                        .limits(new ModelLimits(10L, null, null)).build())
                .build()) {
            assertEquals(10, concurrentSuccesses(20, () -> router.chat("hello")));
        }
    }

    @Test
    void enforcesTpmAtomicallyUnderContention() throws Exception {
        try (RoutedChatModel router = RoutedChatModel.builder()
                .target(ModelTarget.builder("limited", request -> ChatResponse.of("ok"))
                        .limits(new ModelLimits(null, 10L, null)).build())
                .build()) {
            ChatRequest request = ChatRequest.builder().userMessage("hello").estimatedInputTokens(2).build();
            assertEquals(5, concurrentSuccesses(12, () -> router.chat(request)));
        }
    }

    @Test
    void usesConfiguredExecutorWithoutOwningItsLifecycle() {
        AtomicReference<String> threadName = new AtomicReference<>();
        var executor = Executors.newSingleThreadExecutor(runnable -> new Thread(runnable, "custom-router-executor"));
        try {
            RoutedChatModel router = RoutedChatModel.builder()
                    .target("model", request -> {
                        threadName.set(Thread.currentThread().getName());
                        return ChatResponse.of("ok");
                    })
                    .executor(executor)
                    .build();
            router.chat("hello");
            router.close();

            assertEquals("custom-router-executor", threadName.get());
            assertFalse(executor.isShutdown());
        } finally {
            executor.shutdownNow();
        }
    }

    private static int concurrentSuccesses(int count, Callable<ChatResponse> request) throws Exception {
        var executor = Executors.newFixedThreadPool(count);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<java.util.concurrent.Future<Boolean>> futures = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                futures.add(executor.submit(() -> {
                    start.await();
                    try { request.call(); return true; }
                    catch (RuntimeException ignored) { return false; }
                }));
            }
            start.countDown();
            int successes = 0;
            for (var future : futures) if (future.get(3, TimeUnit.SECONDS)) successes++;
            return successes;
        } finally {
            executor.shutdownNow();
        }
    }

    private static ChatModel hangingStream(AtomicBoolean cancelled, CountDownLatch subscribed) {
        return new ChatModel() {
            @Override public ChatResponse chat(ChatRequest request) { throw new UnsupportedOperationException(); }
            @Override public Flow.Publisher<ChatChunk> stream(ChatRequest request) {
                return subscriber -> {
                    subscriber.onSubscribe(new Flow.Subscription() {
                        @Override public void request(long n) { subscribed.countDown(); }
                        @Override public void cancel() { cancelled.set(true); }
                    });
                };
            }
        };
    }

    private static RoutedChatModel sharedRouter(
            InMemoryRouterStateStore store, ChatModel primary, ChatModel backup) {
        return RoutedChatModel.builder()
                .target(ModelTarget.builder("primary", primary).priority(1).build())
                .target(ModelTarget.builder("backup", backup).priority(2).build())

                .strategy("priority", Strategies.priority())
                .maxRetries(0)
                .failureThreshold(1)
                .cooldown(Duration.ofMinutes(1))
                .stateStore(store)
                .stateNamespace("shared")
                .build();
    }

    private static final class NoopSubscription implements Flow.Subscription {
        @Override public void request(long n) { }
        @Override public void cancel() { }
    }
}
