package com.llmrix.model.router.integrations.onnx;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.llmrix.model.router.integrations.fugu.FuguRole;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.experimental.Accessors;

import java.util.List;

@Getter
@EqualsAndHashCode
@Accessors(fluent = true)
public final class OnnxFuguPolicyManifest {
    private final int version;
    private final String model;
    private final String inputName;
    private final String outputName;
    private final int featureCount;
    private final List<Action> actions;

    @JsonCreator
    public OnnxFuguPolicyManifest(
            @JsonProperty("version") int version,
            @JsonProperty("model") String model,
            @JsonProperty("inputName") String inputName,
            @JsonProperty("outputName") String outputName,
            @JsonProperty("featureCount") int featureCount,
            @JsonProperty("actions") List<Action> actions) {
        this.version = version;
        this.model = model;
        this.inputName = inputName;
        this.outputName = outputName;
        this.featureCount = featureCount;
        this.actions = List.copyOf(actions);
    }

    @Getter
    @EqualsAndHashCode
    @Accessors(fluent = true)
    public static final class Action {
        private final String candidateId;
        private final FuguRole role;

        @JsonCreator
        public Action(@JsonProperty("candidateId") String candidateId,
                      @JsonProperty("role") FuguRole role) {
            this.candidateId = candidateId;
            this.role = role;
        }

    }
}
