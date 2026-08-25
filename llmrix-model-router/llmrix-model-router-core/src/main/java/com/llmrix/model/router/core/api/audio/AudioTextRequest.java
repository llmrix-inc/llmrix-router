package com.llmrix.model.router.core.api.audio;

import com.llmrix.model.router.core.api.ModelRequest;
import com.llmrix.model.router.core.routing.RoutingHints;
import lombok.Getter;
import lombok.experimental.Accessors;

import java.util.List;

@Getter
@Accessors(fluent = true)
public final class AudioTextRequest implements ModelRequest {
    public enum ResponseFormat {JSON, TEXT, SRT, VERBOSE_JSON, VTT}

    private final AudioInput input;
    private final String language;
    private final String prompt;
    private final ResponseFormat responseFormat;
    private final Double temperature;
    private final List<String> timestampGranularities;
    private final RoutingHints routingHints;

    public AudioTextRequest(AudioInput input, String language, String prompt,
                            ResponseFormat responseFormat, Double temperature,
                            List<String> timestampGranularities, RoutingHints routingHints) {
        if (input == null) throw new IllegalArgumentException("audio input is required");
        if (temperature != null && (!Double.isFinite(temperature) || temperature < 0)) {
            throw new IllegalArgumentException("temperature must be finite and >= 0");
        }
        this.input = input;
        this.language = language;
        this.prompt = prompt;
        this.responseFormat = responseFormat == null ? ResponseFormat.JSON : responseFormat;
        this.temperature = temperature;
        this.timestampGranularities = timestampGranularities == null ? List.of() : List.copyOf(timestampGranularities);
        this.routingHints = routingHints == null ? RoutingHints.none() : routingHints;
    }

    @Override
    public int estimatedInputTokens() {
        return Math.max(1, input.data().length / 4);
    }
}
