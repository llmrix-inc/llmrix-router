package com.llmrix.model.router.integrations.onnx;

interface FloatInferenceEngine extends AutoCloseable {
    float[] infer(float[] features);

    @Override
    default void close() {
    }
}
