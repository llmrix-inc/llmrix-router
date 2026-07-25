package com.llmrix.model.router.integrations.openai;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SseEventDecoderTest {

    @Test
    void joinsDataLinesAndIgnoresCommentsAndOtherFields() {
        SseEventDecoder decoder = new SseEventDecoder();

        assertTrue(decoder.accept(": heartbeat").isEmpty());
        assertTrue(decoder.accept("event: message").isEmpty());
        assertTrue(decoder.accept("data: {\"value\":" ).isEmpty());
        assertTrue(decoder.accept("data: 1}").isEmpty());

        assertEquals("{\"value\":\n1}", decoder.accept("").orElseThrow());
    }

    @Test
    void flushesPendingEventAtEndOfStream() {
        SseEventDecoder decoder = new SseEventDecoder();
        decoder.accept("data: [DONE]");

        assertEquals("[DONE]", decoder.finish().orElseThrow());
        assertTrue(decoder.finish().isEmpty());
    }
}
