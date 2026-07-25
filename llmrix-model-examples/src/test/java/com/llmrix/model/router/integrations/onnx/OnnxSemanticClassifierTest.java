package com.llmrix.model.router.integrations.onnx;

import com.llmrix.model.router.core.api.ChatRequest;
import com.llmrix.model.router.core.api.ChatResponse;
import com.llmrix.model.router.core.candidate.Candidate;
import com.llmrix.model.router.core.routing.CandidateSnapshot;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OnnxSemanticClassifierTest {
    @Test
    void mapsModelOutputsToEligibleCandidateLabels() {
        OnnxSemanticClassifier classifier = new OnnxSemanticClassifier(
                List.of("small", "large", "offline"),
                (request, candidates) -> new float[]{request.estimatedInputTokens(), candidates.size()},
                features -> new float[]{0.2f, 0.9f, Float.NaN});

        var scores = classifier.score(
                ChatRequest.builder().userMessage("hello").estimatedInputTokens(4).build(),
                List.of(candidate("small"), candidate("large")));

        assertEquals(2, scores.size());
        assertEquals(0.2d, scores.get("small"), 0.00001d);
        assertEquals(0.9d, scores.get("large"), 0.00001d);
    }

    @Test
    void validatesFeaturesOutputDimensionsAndClosesEngine() {
        AtomicBoolean closed = new AtomicBoolean();
        FloatInferenceEngine engine = new FloatInferenceEngine() {
            @Override public float[] infer(float[] features) { return new float[]{1}; }
            @Override public void close() { closed.set(true); }
        };
        OnnxSemanticClassifier classifier = new OnnxSemanticClassifier(
                List.of("one", "two"), (request, candidates) -> new float[]{1}, engine);

        assertThrows(IllegalStateException.class, () -> classifier.score(
                ChatRequest.user("hello"), List.of(candidate("one"))));
        classifier.close();
        assertTrue(closed.get());

        OnnxSemanticClassifier invalidFeatures = new OnnxSemanticClassifier(
                List.of("one"), (request, candidates) -> new float[]{Float.NaN}, engine);
        assertThrows(IllegalArgumentException.class, () -> invalidFeatures.score(
                ChatRequest.user("hello"), List.of(candidate("one"))));
    }

    @Test
    void rejectsDuplicateLabels() {
        assertThrows(IllegalArgumentException.class, () -> new OnnxSemanticClassifier(
                List.of("same", "same"), (request, candidates) -> new float[]{1},
                features -> new float[]{1, 2}));

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> new OnnxSemanticClassifier(Path.of("missing.onnx"),
                        List.of("same", "same"), (request, candidates) -> new float[]{1}));
        assertTrue(error.getMessage().contains("unique candidate IDs"));
    }

    @Test
    void reportsNativeModelLoadFailuresClearly() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> new OnnxSemanticClassifier(Path.of("missing-router-model.onnx"),
                        List.of("one"), (request, candidates) -> new float[]{1}));
        assertTrue(error.getMessage().contains("unable to load ONNX routing model"));
    }

    private static CandidateSnapshot candidate(String id) {
        return new CandidateSnapshot(Candidate.builder(id, request -> ChatResponse.of(id)).build(),
                true, 0, 0);
    }
}
