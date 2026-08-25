package com.llmrix.model.router.core.runtime;

import com.llmrix.model.router.core.api.audio.AudioModel;
import com.llmrix.model.router.core.api.audio.AudioResponse;
import com.llmrix.model.router.core.api.audio.AudioTextRequest;
import com.llmrix.model.router.core.api.audio.SpeechRequest;
import com.llmrix.model.router.core.api.chat.ChatChunk;
import com.llmrix.model.router.core.api.chat.ChatModel;
import com.llmrix.model.router.core.api.chat.ChatRequest;
import com.llmrix.model.router.core.api.chat.ChatResponse;
import com.llmrix.model.router.core.api.embedding.EmbeddingModel;
import com.llmrix.model.router.core.api.embedding.EmbeddingRequest;
import com.llmrix.model.router.core.api.embedding.EmbeddingResponse;
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
import com.llmrix.model.router.core.engine.RoutedChatModel;
import com.llmrix.model.router.core.engine.RoutedChatModels;
import com.llmrix.model.router.core.engine.RoutedModelOperations;
import com.llmrix.model.router.core.engine.RoutedModelOperationsRegistry;
import com.llmrix.model.router.core.model.ModelTarget;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Provider-neutral runtime facade for programmatically configured model routes.
 */
public final class LlmRouter implements ChatModel, EmbeddingModel, AudioModel, ImageModel, VideoModel, AutoCloseable {
    private final String defaultRoute;
    private final Map<String, ModelTarget> targets;
    private final RoutedChatModels chatRoutes;
    private final RoutedModelOperationsRegistry operationRoutes;
    private final AtomicBoolean closed = new AtomicBoolean();

    LlmRouter(String defaultRoute, Map<String, ModelTarget> targets,
              RoutedChatModels chatRoutes, RoutedModelOperationsRegistry operationRoutes) {
        this.defaultRoute = defaultRoute;
        this.targets = Map.copyOf(targets);
        this.chatRoutes = chatRoutes;
        this.operationRoutes = operationRoutes;
    }

    public static LlmRouterBuilder builder() {
        return new LlmRouterBuilder();
    }

    public String defaultRoute() {
        return defaultRoute;
    }

    public Set<String> routeIds() {
        return chatRoutes.routeIds();
    }

    public Map<String, ModelTarget> targets() {
        return targets;
    }

    public RoutedChatModel chatRoute(String routeId) {
        return chatRoutes.get(routeId);
    }

    public RoutedModelOperations operationRoute(String routeId) {
        return operationRoutes.get(routeId);
    }

    public RoutedChatModels chatRoutes() {
        return chatRoutes;
    }

    public RoutedModelOperationsRegistry operationRoutes() {
        return operationRoutes;
    }

    @Override
    public ChatResponse chat(ChatRequest request) {
        return chatRoute(defaultRoute).chat(request);
    }

    @Override
    public CompletionStage<ChatResponse> chatAsync(ChatRequest request) {
        return chatRoute(defaultRoute).chatAsync(request);
    }

    @Override
    public Flow.Publisher<ChatChunk> stream(ChatRequest request) {
        return chatRoute(defaultRoute).stream(request);
    }

    @Override
    public EmbeddingResponse embed(EmbeddingRequest request) {
        return operationRoute(defaultRoute).embed(request);
    }

    @Override
    public AudioResponse transcribe(AudioTextRequest request) {
        return operationRoute(defaultRoute).transcribe(request);
    }

    @Override
    public AudioResponse translate(AudioTextRequest request) {
        return operationRoute(defaultRoute).translate(request);
    }

    @Override
    public AudioResponse speech(SpeechRequest request) {
        return operationRoute(defaultRoute).speech(request);
    }

    @Override
    public ImageResponse generate(ImageRequest request) {
        return operationRoute(defaultRoute).generate(request);
    }

    @Override
    public ImageResponse edit(ImageEditRequest request) {
        return operationRoute(defaultRoute).edit(request);
    }

    @Override
    public VideoResponse create(VideoRequest request) {
        return operationRoute(defaultRoute).create(request);
    }

    @Override
    public VideoResponse retrieve(VideoLookupRequest request) {
        return operationRoute(defaultRoute).retrieve(request);
    }

    @Override
    public VideoContent content(VideoLookupRequest request) {
        return operationRoute(defaultRoute).content(request);
    }

    @Override
    public VideoResponse delete(VideoLookupRequest request) {
        return operationRoute(defaultRoute).delete(request);
    }

    @Override
    public VideoResponse remix(VideoRemixRequest request) {
        return operationRoute(defaultRoute).remix(request);
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) return;
        chatRoutes.close();
        operationRoutes.close();
    }
}
