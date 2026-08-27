package com.llmrix.model.router.core.api.embedding;

import com.llmrix.model.router.core.api.ModelRequest;
import com.llmrix.model.router.core.routing.RoutingHints;
import lombok.Getter;
import lombok.experimental.Accessors;

import java.util.List;
import java.util.Objects;

@Getter
@Accessors(fluent = true)
public final class EmbeddingRequest implements ModelRequest {
    public enum EncodingFormat {FLOAT, BASE64}

    private final List<EmbeddingInput> inputs;
    private final EncodingFormat encodingFormat;
    private final Integer dimensions;
    private final String user;
    private final RoutingHints routingHints;

    public EmbeddingRequest(List<EmbeddingInput> inputs, EncodingFormat encodingFormat,
                            Integer dimensions, String user, RoutingHints routingHints) {
        if (inputs == null || inputs.isEmpty()) throw new IllegalArgumentException("embedding input must not be empty");
        this.inputs = List.copyOf(inputs);
        if (this.inputs.stream().anyMatch(Objects::isNull))
            throw new IllegalArgumentException("embedding input contains null");
        if (dimensions != null && dimensions < 1) throw new IllegalArgumentException("dimensions must be > 0");
        this.encodingFormat = encodingFormat == null ? EncodingFormat.FLOAT : encodingFormat;
        this.dimensions = dimensions;
        this.user = user;
        this.routingHints = routingHints == null ? RoutingHints.none() : routingHints;
    }

    public static EmbeddingRequest text(String text) {
        return new EmbeddingRequest(List.of(EmbeddingInput.text(text)), null, null, null, null);
    }

    @Override
    public int estimatedInputTokens() {
        long estimate = inputs.stream().mapToLong(input -> input.tokenized()
                ? input.tokens().size() : Math.max(1, (input.text().length() + 3L) / 4L)).sum();
        return (int) Math.min(Integer.MAX_VALUE, estimate);
    }
}
