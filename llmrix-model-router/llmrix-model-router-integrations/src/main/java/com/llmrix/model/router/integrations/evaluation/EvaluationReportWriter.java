package com.llmrix.model.router.integrations.evaluation;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Objects;
import java.util.stream.Collectors;

public final class EvaluationReportWriter {
    private final ObjectMapper mapper;

    public EvaluationReportWriter() {
        this(new ObjectMapper());
    }

    public EvaluationReportWriter(ObjectMapper mapper) {
        this.mapper = Objects.requireNonNull(mapper, "mapper");
    }

    public String toJson(EvaluationReport report) {
        try {
            return mapper.writeValueAsString(report);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("cannot serialize evaluation report", e);
        }
    }

    public String toJsonLines(EvaluationReport report) {
        return report.results().stream().map(result -> {
            try {
                return mapper.writeValueAsString(result);
            } catch (JsonProcessingException e) {
                throw new IllegalStateException("cannot serialize evaluation result", e);
            }
        }).collect(Collectors.joining("\n"));
    }
}
