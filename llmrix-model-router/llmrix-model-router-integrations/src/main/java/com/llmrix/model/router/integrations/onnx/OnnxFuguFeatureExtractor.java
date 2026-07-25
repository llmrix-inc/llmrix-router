package com.llmrix.model.router.integrations.onnx;

import com.llmrix.model.router.integrations.fugu.FuguState;

@FunctionalInterface
public interface OnnxFuguFeatureExtractor {
    float[] extract(FuguState state);
}
