package com.llmrix.model.router.integrations.onnx;

import com.llmrix.model.router.core.api.chat.ChatRequest;
import com.llmrix.model.router.core.routing.RouteCandidate;
import com.llmrix.model.router.core.routing.SemanticClassifier;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Runs a float feature vector through an ONNX classifier and maps output positions to candidate IDs.
 */
public final class OnnxSemanticClassifier implements SemanticClassifier, AutoCloseable {
    private final List<String> labels;
    private final OnnxFeatureExtractor features;
    private final FloatInferenceEngine engine;

    public OnnxSemanticClassifier(Path model, List<String> labels, OnnxFeatureExtractor features) {
        this(labels, features, createEngine(model, null, null, labels, features));
    }

    public OnnxSemanticClassifier(Path model, String inputName, String outputName,
                                  List<String> labels, OnnxFeatureExtractor features) {
        this(labels, features, createEngine(model, inputName, outputName, labels, features));
    }

    OnnxSemanticClassifier(List<String> labels, OnnxFeatureExtractor features, FloatInferenceEngine engine) {
        if (labels == null || labels.isEmpty()) throw new IllegalArgumentException("labels must not be empty");
        if (labels.stream().anyMatch(value -> value == null || value.isBlank())) {
            throw new IllegalArgumentException("labels must not contain blank candidate IDs");
        }
        if (new LinkedHashSet<>(labels).size() != labels.size()) {
            throw new IllegalArgumentException("labels must contain unique candidate IDs");
        }
        this.labels = List.copyOf(labels);
        this.features = Objects.requireNonNull(features, "features");
        this.engine = Objects.requireNonNull(engine, "engine");
    }

    @Override
    public Map<String, Double> score(ChatRequest request, List<RouteCandidate> candidates) {
        float[] input = Objects.requireNonNull(features.extract(request, candidates),
                "feature extractor returned null");
        if (input.length == 0) throw new IllegalArgumentException("feature vector must not be empty");
        for (float value : input) {
            if (!Float.isFinite(value)) throw new IllegalArgumentException("feature vector must be finite");
        }
        float[] output = engine.infer(input);
        if (output.length != labels.size()) throw new IllegalStateException(
                "ONNX output size " + output.length + " does not match labels " + labels.size());
        Set<String> eligible = candidates.stream().map(RouteCandidate::id)
                .collect(java.util.stream.Collectors.toSet());
        Map<String, Double> scores = new LinkedHashMap<>();
        for (int index = 0; index < labels.size(); index++) {
            if (eligible.contains(labels.get(index)) && Float.isFinite(output[index])) {
                scores.put(labels.get(index), (double) output[index]);
            }
        }
        return Map.copyOf(scores);
    }

    @Override
    public void close() {
        engine.close();
    }

    private static FloatInferenceEngine createEngine(
            Path model, String inputName, String outputName,
            List<String> labels, OnnxFeatureExtractor features) {
        validateConfiguration(labels, features);
        return new OrtFloatInferenceEngine(model, inputName, outputName, labels.size());
    }

    private static void validateConfiguration(List<String> labels, OnnxFeatureExtractor features) {
        if (labels == null || labels.isEmpty()) throw new IllegalArgumentException("labels must not be empty");
        if (labels.stream().anyMatch(value -> value == null || value.isBlank())) {
            throw new IllegalArgumentException("labels must not contain blank candidate IDs");
        }
        if (new LinkedHashSet<>(labels).size() != labels.size()) {
            throw new IllegalArgumentException("labels must contain unique candidate IDs");
        }
        Objects.requireNonNull(features, "features");
    }

}
