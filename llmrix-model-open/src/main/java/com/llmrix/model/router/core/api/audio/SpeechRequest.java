package com.llmrix.model.router.core.api.audio;

import com.llmrix.model.router.core.api.ModelRequest;
import com.llmrix.model.router.core.routing.RoutingHints;
import lombok.Getter;
import lombok.experimental.Accessors;

@Getter
@Accessors(fluent = true)
public final class SpeechRequest implements ModelRequest {
    private final String input;
    private final String voice;
    private final String responseFormat;
    private final Double speed;
    private final String instructions;
    private final RoutingHints routingHints;

    public SpeechRequest(String input, String voice, String responseFormat, Double speed,
                         String instructions, RoutingHints routingHints) {
        if (input == null || input.isBlank()) throw new IllegalArgumentException("speech input must not be blank");
        if (voice == null || voice.isBlank()) throw new IllegalArgumentException("voice must not be blank");
        if (speed != null && (!Double.isFinite(speed) || speed < 0.25 || speed > 4)) {
            throw new IllegalArgumentException("speed must be between 0.25 and 4");
        }
        this.input = input;
        this.voice = voice;
        this.responseFormat = responseFormat == null || responseFormat.isBlank() ? "mp3" : responseFormat;
        this.speed = speed;
        this.instructions = instructions;
        this.routingHints = routingHints == null ? RoutingHints.none() : routingHints;
    }

    @Override
    public int estimatedInputTokens() {
        return Math.max(1, (input.length() + 3) / 4);
    }
}
