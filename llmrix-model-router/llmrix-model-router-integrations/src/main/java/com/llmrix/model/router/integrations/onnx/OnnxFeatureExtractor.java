package com.llmrix.model.router.integrations.onnx;

import com.llmrix.model.router.core.api.ChatRequest;
import com.llmrix.model.router.core.routing.CandidateSnapshot;

import java.util.List;

@FunctionalInterface
public interface OnnxFeatureExtractor {
    /** Returns the fixed-length float feature vector expected by the ONNX model. */
    float[] extract(ChatRequest request, List<CandidateSnapshot> candidates);
}
