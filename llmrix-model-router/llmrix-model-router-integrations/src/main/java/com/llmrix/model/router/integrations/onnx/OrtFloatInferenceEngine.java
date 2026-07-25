package com.llmrix.model.router.integrations.onnx;

import ai.onnxruntime.OnnxJavaType;
import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;
import ai.onnxruntime.TensorInfo;

import java.nio.FloatBuffer;
import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

final class OrtFloatInferenceEngine implements FloatInferenceEngine {
    private final OrtEnvironment environment = OrtEnvironment.getEnvironment();
    private final OrtSession session;
    private final String inputName;
    private final String outputName;

    OrtFloatInferenceEngine(Path model, String inputName, String outputName, int outputSize) {
        Objects.requireNonNull(model, "model");
        OrtSession created = null;
        try {
            created = environment.createSession(model.toAbsolutePath().toString());
            this.inputName = resolveName(inputName, created.getInputNames(), "input");
            this.outputName = resolveName(outputName, created.getOutputNames(), "output");
            TensorInfo input = requireFloatTensor(created.getInputInfo().get(this.inputName).getInfo(), "input");
            if (input.getShape().length != 2) {
                throw new IllegalArgumentException("ONNX input must have shape [batch, features]");
            }
            TensorInfo output = requireFloatTensor(created.getOutputInfo().get(this.outputName).getInfo(), "output");
            long[] outputShape = output.getShape();
            if (outputShape.length < 1 || outputShape.length > 2) {
                throw new IllegalArgumentException("ONNX output must have shape [labels] or [batch, labels]");
            }
            long labelDimension = outputShape[outputShape.length - 1];
            if (labelDimension > 0 && labelDimension != outputSize) {
                throw new IllegalArgumentException("ONNX output label dimension " + labelDimension
                        + " does not match labels " + outputSize);
            }
            session = created;
        } catch (OrtException error) {
            closeAfterFailedLoad(created);
            throw new IllegalArgumentException("unable to load ONNX routing model: " + model, error);
        } catch (RuntimeException error) {
            closeAfterFailedLoad(created);
            throw error;
        }
    }

    @Override public synchronized float[] infer(float[] features) {
        try (OnnxTensor input = OnnxTensor.createTensor(
                environment, FloatBuffer.wrap(features), new long[]{1, features.length});
             OrtSession.Result result = session.run(Map.of(inputName, input))) {
            Object value = result.get(outputName).orElseThrow(() ->
                    new IllegalStateException("ONNX output is missing: " + outputName)).getValue();
            float[] scores;
            if (value instanceof float[] vector) scores = vector;
            else if (value instanceof float[][] matrix && matrix.length == 1) scores = matrix[0];
            else throw new IllegalStateException("ONNX output must be float[label] or float[1][label]");
            return scores.clone();
        } catch (OrtException error) {
            throw new IllegalStateException("ONNX routing inference failed", error);
        }
    }

    @Override public void close() {
        try { session.close(); }
        catch (OrtException error) { throw new IllegalStateException("unable to close ONNX session", error); }
    }

    private static String resolveName(String configured, Set<String> available, String kind) {
        if (configured != null && !configured.isBlank()) {
            if (!available.contains(configured)) throw new IllegalArgumentException(
                    "ONNX " + kind + " does not exist: " + configured);
            return configured;
        }
        if (available.size() != 1) throw new IllegalArgumentException(
                "ONNX model has multiple " + kind + "s; configure the " + kind + " name");
        return available.iterator().next();
    }

    private static TensorInfo requireFloatTensor(ai.onnxruntime.ValueInfo info, String kind) {
        if (!(info instanceof TensorInfo tensor) || tensor.type != OnnxJavaType.FLOAT) {
            throw new IllegalArgumentException("ONNX " + kind + " must be a float tensor");
        }
        return tensor;
    }

    private static void closeAfterFailedLoad(OrtSession session) {
        if (session == null) return;
        try { session.close(); } catch (OrtException ignored) { }
    }
}
