package com.llmrix.model.router.core.api;

import com.llmrix.model.router.core.api.audio.AudioModel;
import com.llmrix.model.router.core.api.chat.ChatModel;
import com.llmrix.model.router.core.api.embedding.EmbeddingModel;
import com.llmrix.model.router.core.api.image.ImageModel;
import com.llmrix.model.router.core.api.video.VideoModel;
import com.llmrix.model.router.core.model.Capability;

import java.util.Objects;
import java.util.Optional;

/**
 * Typed capabilities exposed by one configured provider model.
 */
public final class ModelClient {
    private final ChatModel chat;
    private final EmbeddingModel embeddings;
    private final AudioModel audio;
    private final ImageModel images;
    private final VideoModel videos;

    private ModelClient(Builder builder) {
        this.chat = builder.chat;
        this.embeddings = builder.embeddings;
        this.audio = builder.audio;
        this.images = builder.images;
        this.videos = builder.videos;
        if (chat == null && embeddings == null && audio == null && images == null && videos == null) {
            throw new IllegalArgumentException("at least one model capability is required");
        }
    }

    public static Builder builder() {
        return new Builder();
    }

    public static ModelClient chat(ChatModel model) {
        return builder().chat(model).build();
    }

    public Optional<ChatModel> chat() {
        return Optional.ofNullable(chat);
    }

    public Optional<EmbeddingModel> embeddings() {
        return Optional.ofNullable(embeddings);
    }

    public Optional<AudioModel> audio() {
        return Optional.ofNullable(audio);
    }

    public Optional<ImageModel> images() {
        return Optional.ofNullable(images);
    }

    public Optional<VideoModel> videos() {
        return Optional.ofNullable(videos);
    }

    public ChatModel requireChat() {
        return require(chat, "chat");
    }

    public EmbeddingModel requireEmbeddings() {
        return require(embeddings, "embeddings");
    }

    public AudioModel requireAudio() {
        return require(audio, "audio");
    }

    public ImageModel requireImages() {
        return require(images, "images");
    }

    public VideoModel requireVideos() {
        return require(videos, "videos");
    }

    public boolean supports(Capability capability) {
        return switch (capability) {
            case CHAT, CODE, TOOLS, VISION, REASONING, LONG_CONTEXT, VIDEO_INPUT, FILE_INPUT, AUDIO_INPUT -> chat != null;
            case EMBEDDINGS -> embeddings != null;
            case AUDIO_TRANSCRIPTION, AUDIO_TRANSLATION, TEXT_TO_SPEECH -> audio != null;
            case IMAGE_GENERATION, IMAGE_EDIT -> images != null;
            case VIDEO_GENERATION -> videos != null;
        };
    }

    private static <T> T require(T capability, String name) {
        if (capability == null) throw new UnsupportedOperationException("model does not support " + name);
        return capability;
    }

    public static final class Builder {
        private ChatModel chat;
        private EmbeddingModel embeddings;
        private AudioModel audio;
        private ImageModel images;
        private VideoModel videos;

        public Builder chat(ChatModel value) {
            chat = Objects.requireNonNull(value);
            return this;
        }

        public Builder embeddings(EmbeddingModel value) {
            embeddings = Objects.requireNonNull(value);
            return this;
        }

        public Builder audio(AudioModel value) {
            audio = Objects.requireNonNull(value);
            return this;
        }

        public Builder images(ImageModel value) {
            images = Objects.requireNonNull(value);
            return this;
        }

        public Builder videos(VideoModel value) {
            videos = Objects.requireNonNull(value);
            return this;
        }

        public ModelClient build() {
            return new ModelClient(this);
        }
    }
}
