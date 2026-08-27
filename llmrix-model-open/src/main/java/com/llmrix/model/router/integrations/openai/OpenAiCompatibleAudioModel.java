package com.llmrix.model.router.integrations.openai;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.llmrix.model.router.core.api.audio.AudioModel;
import com.llmrix.model.router.core.api.audio.AudioResponse;
import com.llmrix.model.router.core.api.audio.AudioTextRequest;
import com.llmrix.model.router.core.api.audio.SpeechRequest;
import com.llmrix.model.router.core.api.Usage;

import java.util.Locale;
import java.util.Objects;

public final class OpenAiCompatibleAudioModel implements AudioModel {
    private final String modelName;
    private final OpenAiTransport transport;

    public OpenAiCompatibleAudioModel(String modelName, OpenAiTransport transport) {
        if (modelName == null || modelName.isBlank()) throw new IllegalArgumentException("modelName must not be blank");
        this.modelName = modelName;
        this.transport = Objects.requireNonNull(transport, "transport");
    }

    @Override
    public AudioResponse transcribe(AudioTextRequest request) {
        return audioText("audio/transcriptions", request, true);
    }

    @Override
    public AudioResponse translate(AudioTextRequest request) {
        return audioText("audio/translations", request, false);
    }

    @Override
    public AudioResponse speech(SpeechRequest request) {
        ObjectNode payload = transport.mapper().createObjectNode();
        payload.put("model", modelName);
        payload.put("input", request.input());
        payload.put("voice", request.voice());
        payload.put("response_format", request.responseFormat());
        if (request.speed() != null) payload.put("speed", request.speed());
        if (request.instructions() != null) payload.put("instructions", request.instructions());
        OpenAiTransport.Response response = transport.postJsonBytes("audio/speech", payload, request.routingHints());
        return new AudioResponse(response.body(), response.mediaType(), modelName, Usage.UNKNOWN);
    }

    private AudioResponse audioText(String path, AudioTextRequest request, boolean includeLanguage) {
        MultipartBody body = new MultipartBody()
                .file("file", request.input().filename(), request.input().mediaType(), request.input().data())
                .text("model", modelName)
                .text("response_format", request.responseFormat().name().toLowerCase(Locale.ROOT));
        if (includeLanguage) body.text("language", request.language());
        body.text("prompt", request.prompt()).text("temperature", request.temperature());
        request.timestampGranularities().forEach(value -> body.text("timestamp_granularities[]", value));
        OpenAiTransport.Response response = transport.postMultipart(path, body, request.routingHints());
        return new AudioResponse(response.body(), response.mediaType(), modelName, Usage.UNKNOWN);
    }
}
