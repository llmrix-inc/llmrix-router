package com.llmrix.model.orion.client;

import com.llmrix.model.orion.observation.OrionModelClientListener;
import com.llmrix.model.router.core.api.audio.AudioModel;
import com.llmrix.model.router.core.api.audio.AudioResponse;
import com.llmrix.model.router.core.api.audio.AudioTextRequest;
import com.llmrix.model.router.core.api.audio.SpeechRequest;
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

import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;

final class ObservingModelOperations {
    private ObservingModelOperations() { }

    static EmbeddingModel embedding(EmbeddingModel delegate, OrionModelClientListener listener,
                                    String requestId, String model) {
        if (listener == OrionModelClientListener.NOOP) return delegate;
        return new EmbeddingModel() {
            @Override public EmbeddingResponse embed(EmbeddingRequest request) {
                return call(listener, requestId, "embeddings", model, () -> delegate.embed(request));
            }
        };
    }

    static AudioModel audio(AudioModel delegate, OrionModelClientListener listener,
                            String requestId, String model) {
        if (listener == OrionModelClientListener.NOOP) return delegate;
        return new AudioModel() {
            @Override public AudioResponse transcribe(AudioTextRequest request) {
                return call(listener, requestId, "audio.transcriptions", model, () -> delegate.transcribe(request));
            }

            @Override public AudioResponse translate(AudioTextRequest request) {
                return call(listener, requestId, "audio.translations", model, () -> delegate.translate(request));
            }

            @Override public AudioResponse speech(SpeechRequest request) {
                return call(listener, requestId, "audio.speech", model, () -> delegate.speech(request));
            }
        };
    }

    static RerankModel rerank(RerankModel delegate, OrionModelClientListener listener,
                              String requestId, String model) {
        if (listener == OrionModelClientListener.NOOP) return delegate;
        return request -> call(listener, requestId, "rerank", model, () -> delegate.rerank(request));
    }

    static ImageModel image(ImageModel delegate, OrionModelClientListener listener,
                            String requestId, String model) {
        if (listener == OrionModelClientListener.NOOP) return delegate;
        return new ImageModel() {
            @Override public ImageResponse generate(ImageRequest request) {
                return call(listener, requestId, "images.generations", model, () -> delegate.generate(request));
            }

            @Override public ImageResponse edit(ImageEditRequest request) {
                return call(listener, requestId, "images.edits", model, () -> delegate.edit(request));
            }
        };
    }

    static VideoModel video(VideoModel delegate, OrionModelClientListener listener,
                            String requestId, String model) {
        if (listener == OrionModelClientListener.NOOP) return delegate;
        return new VideoModel() {
            @Override public VideoResponse create(VideoRequest request) {
                return call(listener, requestId, "videos.create", model, () -> delegate.create(request));
            }

            @Override public VideoResponse retrieve(VideoLookupRequest request) {
                return call(listener, requestId, "videos.retrieve", model, () -> delegate.retrieve(request));
            }

            @Override public VideoContent content(VideoLookupRequest request) {
                return call(listener, requestId, "videos.content", model, () -> delegate.content(request));
            }

            @Override public VideoResponse delete(VideoLookupRequest request) {
                return call(listener, requestId, "videos.delete", model, () -> delegate.delete(request));
            }

            @Override public VideoResponse remix(VideoRemixRequest request) {
                return call(listener, requestId, "videos.remix", model, () -> delegate.remix(request));
            }
        };
    }

    private static <T> T call(OrionModelClientListener listener, String requestId,
                              String operation, String model, Supplier<T> action) {
        Objects.requireNonNull(action, "action");
        String invocationId = UUID.randomUUID().toString();
        long started = System.nanoTime();
        notifySafely(() -> listener.onStarted(new OrionModelClientListener.RequestStarted(
                invocationId, requestId, operation, model, started)));
        try {
            T value = action.get();
            complete(listener, invocationId, started, null);
            return value;
        } catch (RuntimeException error) {
            complete(listener, invocationId, started, error);
            throw error;
        }
    }

    private static void complete(OrionModelClientListener listener, String invocationId,
                                 long started, Throwable error) {
        notifySafely(() -> listener.onCompleted(new OrionModelClientListener.RequestCompleted(
                invocationId, System.nanoTime() - started, error == null,
                error == null ? null : error.getClass().getSimpleName())));
    }

    private static void notifySafely(Runnable notification) {
        try { notification.run(); } catch (RuntimeException ignored) { }
    }
}
