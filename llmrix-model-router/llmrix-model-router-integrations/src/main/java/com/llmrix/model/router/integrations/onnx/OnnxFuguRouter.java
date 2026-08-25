package com.llmrix.model.router.integrations.onnx;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.llmrix.model.router.integrations.fugu.FuguAction;
import com.llmrix.model.router.integrations.fugu.FuguRouter;
import com.llmrix.model.router.integrations.fugu.FuguState;

import java.io.IOException;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Loads a versioned Fugu action manifest and selects the highest-scoring eligible ONNX action.
 */
public final class OnnxFuguRouter implements FuguRouter, AutoCloseable {
    private static final ObjectMapper JSON = new ObjectMapper();

    private final List<FuguAction> actions;
    private final int featureCount;
    private final OnnxFuguFeatureExtractor features;
    private final FloatInferenceEngine engine;

    public static OnnxFuguRouter load(Path manifestPath, OnnxFuguFeatureExtractor features) {
        Objects.requireNonNull(manifestPath, "manifestPath");
        Objects.requireNonNull(features, "features");
        OnnxFuguPolicyManifest manifest;
        try {
            manifest = JSON.readValue(manifestPath.toFile(), OnnxFuguPolicyManifest.class);
        } catch (IOException error) {
            throw new IllegalArgumentException("unable to load Fugu policy manifest: " + manifestPath, error);
        }
        validate(manifest);
        Path parent = manifestPath.toAbsolutePath().getParent();
        Path model = parent == null ? Path.of(manifest.model()) : parent.resolve(manifest.model()).normalize();
        List<FuguAction> actions = manifest.actions().stream()
                .map(action -> new FuguAction(action.candidateId(), action.role())).toList();
        return new OnnxFuguRouter(actions, manifest.featureCount(), features,
                new OrtFloatInferenceEngine(model, manifest.inputName(), manifest.outputName(), actions.size()));
    }

    OnnxFuguRouter(List<FuguAction> actions, int featureCount,
                   OnnxFuguFeatureExtractor features, FloatInferenceEngine engine) {
        if (actions == null || actions.isEmpty()) throw new IllegalArgumentException("actions must not be empty");
        if (new HashSet<>(actions).size() != actions.size())
            throw new IllegalArgumentException("actions must be unique");
        if (featureCount < 1) throw new IllegalArgumentException("featureCount must be positive");
        this.actions = List.copyOf(actions);
        this.featureCount = featureCount;
        this.features = Objects.requireNonNull(features, "features");
        this.engine = Objects.requireNonNull(engine, "engine");
    }

    @Override
    public FuguAction route(FuguState state) {
        float[] input = Objects.requireNonNull(features.extract(state), "feature extractor returned null");
        if (input.length != featureCount) throw new IllegalArgumentException(
                "Fugu feature size " + input.length + " does not match manifest " + featureCount);
        for (float value : input) {
            if (!Float.isFinite(value)) throw new IllegalArgumentException("Fugu features must be finite");
        }
        float[] scores = engine.infer(input);
        if (scores.length != actions.size()) throw new IllegalStateException(
                "Fugu policy output size " + scores.length + " does not match actions " + actions.size());
        Set<String> eligible = Set.copyOf(state.candidateIds());
        FuguAction best = null;
        float bestScore = Float.NEGATIVE_INFINITY;
        for (int index = 0; index < actions.size(); index++) {
            if (eligible.contains(actions.get(index).candidateId())
                    && Float.isFinite(scores[index]) && (best == null || scores[index] > bestScore)) {
                best = actions.get(index);
                bestScore = scores[index];
            }
        }
        if (best == null) throw new IllegalStateException("Fugu policy produced no eligible finite action");
        return best;
    }

    @Override
    public void close() {
        engine.close();
    }

    private static void validate(OnnxFuguPolicyManifest manifest) {
        if (manifest.version() != 1) throw new IllegalArgumentException(
                "unsupported Fugu policy manifest version: " + manifest.version());
        if (manifest.model() == null || manifest.model().isBlank()) {
            throw new IllegalArgumentException("Fugu policy model must not be blank");
        }
        if (manifest.featureCount() < 1) throw new IllegalArgumentException("featureCount must be positive");
        if (manifest.actions() == null || manifest.actions().isEmpty()) {
            throw new IllegalArgumentException("Fugu policy actions must not be empty");
        }
        Set<FuguAction> unique = new HashSet<>();
        manifest.actions().forEach(action -> {
            FuguAction converted = new FuguAction(action.candidateId(), action.role());
            if (!unique.add(converted))
                throw new IllegalArgumentException("duplicate Fugu policy action: " + converted);
        });
    }
}
