package com.llmrix.model.router.integrations.onnx;

import com.llmrix.model.router.integrations.fugu.FuguRole;

import java.util.List;

public record OnnxFuguPolicyManifest(
        int version,
        String model,
        String inputName,
        String outputName,
        int featureCount,
        List<Action> actions) {

    public record Action(String candidateId, FuguRole role) { }
}
