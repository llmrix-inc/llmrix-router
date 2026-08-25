package com.llmrix.model.router.integrations.onnx;

import com.llmrix.model.router.core.api.chat.ChatRequest;
import com.llmrix.model.router.integrations.fugu.FuguAction;
import com.llmrix.model.router.integrations.fugu.FuguRole;
import com.llmrix.model.router.integrations.fugu.FuguState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OnnxFuguRouterTest {
    private static final List<FuguAction> ACTIONS = List.of(
            new FuguAction("fast", FuguRole.WORKER),
            new FuguAction("quality", FuguRole.WORKER),
            new FuguAction("quality", FuguRole.VERIFIER));

    @Test
    void selectsHighestScoringEligibleActionAndUsesManifestOrderForTies() {
        OnnxFuguRouter router = new OnnxFuguRouter(ACTIONS, 2,
                state -> new float[]{state.turns().size(), state.request().estimatedInputTokens()},
                features -> new float[]{0.9f, 0.8f, 1.0f});

        assertEquals(new FuguAction("quality", FuguRole.VERIFIER),
                router.route(state(List.of("fast", "quality"))));
        assertEquals(new FuguAction("fast", FuguRole.WORKER),
                router.route(state(List.of("fast"))));

        OnnxFuguRouter tie = new OnnxFuguRouter(ACTIONS, 1,
                state -> new float[]{1}, features -> new float[]{0.5f, 0.5f, 0.1f});
        assertEquals(ACTIONS.get(0), tie.route(state(List.of("fast", "quality"))));
    }

    @Test
    void validatesFeaturesOutputsAndClosesEngine() {
        AtomicBoolean closed = new AtomicBoolean();
        FloatInferenceEngine engine = new FloatInferenceEngine() {
            @Override public float[] infer(float[] features) { return new float[]{Float.NaN, Float.NaN, Float.NaN}; }
            @Override public void close() { closed.set(true); }
        };
        OnnxFuguRouter router = new OnnxFuguRouter(ACTIONS, 1, state -> new float[]{1}, engine);

        assertThrows(IllegalStateException.class, () -> router.route(state(List.of("fast", "quality"))));
        router.close();
        assertTrue(closed.get());

        OnnxFuguRouter wrongFeatures = new OnnxFuguRouter(
                ACTIONS, 2, state -> new float[]{1}, features -> new float[]{1, 2, 3});
        assertThrows(IllegalArgumentException.class,
                () -> wrongFeatures.route(state(List.of("fast", "quality"))));

        OnnxFuguRouter wrongOutput = new OnnxFuguRouter(
                ACTIONS, 1, state -> new float[]{1}, features -> new float[]{1});
        assertThrows(IllegalStateException.class,
                () -> wrongOutput.route(state(List.of("fast", "quality"))));
    }

    @Test
    void validatesManifestBeforeLoadingModel(@TempDir Path directory) throws Exception {
        Path manifest = directory.resolve("policy.json");
        Files.writeString(manifest, """
                {"version":2,"model":"missing.onnx","featureCount":2,
                 "actions":[{"candidateId":"fast","role":"WORKER"}]}
                """);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> OnnxFuguRouter.load(manifest, state -> new float[]{1, 2}));
        assertTrue(error.getMessage().contains("unsupported Fugu policy manifest version"));
    }

    private static FuguState state(List<String> candidates) {
        return new FuguState(ChatRequest.builder().userMessage("question")
                .estimatedInputTokens(4).build(), candidates, List.of(), null, null);
    }
}
