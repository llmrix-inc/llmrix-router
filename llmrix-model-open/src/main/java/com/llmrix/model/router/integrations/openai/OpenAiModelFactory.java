package com.llmrix.model.router.integrations.openai;

import com.llmrix.model.router.core.api.audio.AudioModel;
import com.llmrix.model.router.core.api.chat.ChatModel;
import com.llmrix.model.router.core.api.embedding.EmbeddingModel;
import com.llmrix.model.router.core.api.image.ImageModel;
import com.llmrix.model.router.core.api.rerank.RerankModel;
import com.llmrix.model.router.core.api.video.VideoModel;

/** Creates OpenAI-compatible adapters without exposing transport assembly to callers. */
public final class OpenAiModelFactory {
    private OpenAiModelFactory() { }

    public static ChatModel chat(OpenAiModelOptions options) {
        OpenAiCompatibleChatModel.Builder builder = OpenAiCompatibleChatModel.builder()
                .baseUrl(options.baseUrl())
                .modelName(options.modelName())
                .authenticator(options.authenticator())
                .headers(options.headers())
                .forwardRoutingHints(options.forwardRoutingHints())
                .extensions(options.extensions());
        if (options.httpClient() != null) builder.httpClient(options.httpClient());
        if (options.objectMapper() != null) builder.objectMapper(options.objectMapper());
        if (options.timeout() != null) builder.timeout(options.timeout());
        if (options.responsesApi()) builder.responsesApi();
        return builder.build();
    }

    public static EmbeddingModel embedding(OpenAiModelOptions options) {
        return new OpenAiCompatibleEmbeddingModel(options.modelName(), transport(options));
    }

    public static RerankModel rerank(OpenAiModelOptions options) {
        return new OpenAiCompatibleRerankModel(options.modelName(), transport(options));
    }

    public static AudioModel audio(OpenAiModelOptions options) {
        return new OpenAiCompatibleAudioModel(options.modelName(), transport(options));
    }

    public static ImageModel image(OpenAiModelOptions options) {
        return new OpenAiCompatibleImageModel(options.modelName(), transport(options));
    }

    public static VideoModel video(OpenAiModelOptions options) {
        return new OpenAiCompatibleVideoModel(options.modelName(), transport(options), options.routeModel());
    }

    private static OpenAiTransport transport(OpenAiModelOptions options) {
        return new OpenAiTransport(options.baseUrl(), options.authenticator(), options.headers(),
                options.httpClient(), options.objectMapper(), options.timeout(), options.forwardRoutingHints());
    }
}
