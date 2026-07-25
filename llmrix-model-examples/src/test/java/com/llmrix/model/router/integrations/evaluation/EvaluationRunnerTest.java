package com.llmrix.model.router.integrations.evaluation;

import com.llmrix.model.router.core.api.ChatRequest;
import com.llmrix.model.router.core.api.ChatResponse;
import com.llmrix.model.router.core.api.Usage;
import com.llmrix.model.router.core.candidate.ModelPricing;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EvaluationRunnerTest {
    @Test
    void replaysSamplesAcrossModelsAndBuildsBaselines() {
        EvaluationRunner runner = new EvaluationRunner(Map.of(
                "good", request -> new ChatResponse("correct", "good", new Usage(100, 20), Map.of()),
                "broken", request -> { throw new IllegalStateException("failed"); }),
                Map.of("good", new ModelPricing(1.0, 2.0)),
                (sample, response) -> "correct".equals(response.text()) ? 1 : 0);

        EvaluationReport report = runner.run(List.of(
                new EvaluationSample("sample-1", ChatRequest.user("question"))));

        assertEquals(2, report.results().size());
        assertEquals(0.5, report.successRate());
        EvaluationResult success = report.results().stream().filter(EvaluationResult::success).findFirst().orElseThrow();
        assertEquals(1.0, success.quality());
        assertTrue(success.costUsd() > 0);
        assertTrue(success.latencyNanos() >= 0);
        ModelEvaluationSummary summary = report.byModel().get("good");
        assertEquals(1.0, summary.successRate());
        assertEquals(1.0, summary.averageQuality());
        assertTrue(summary.averageCostUsd() > 0);
    }

    @Test
    void replaysPrimaryAndShadowModelsOnSameSamples() {
        ShadowEvaluationRunner runner = new ShadowEvaluationRunner(
                "primary", request -> ChatResponse.of("primary"),
                Map.of("shadow", request -> ChatResponse.of("shadow")), Map.of(), QualityScorer.unscored());

        EvaluationReport report = runner.run(List.of(
                new EvaluationSample("sample-1", ChatRequest.user("question"))));

        assertEquals(2, report.results().size());
        assertTrue(report.results().stream().anyMatch(result -> "primary".equals(result.modelId())));
        assertTrue(report.results().stream().anyMatch(result -> "shadow".equals(result.modelId())));
    }

    @Test
    void assignsExactlyOneAbVariantDeterministically() {
        AbEvaluationRunner runner = new AbEvaluationRunner(
                Map.of("a", request -> ChatResponse.of("a"), "b", request -> ChatResponse.of("b")),
                Map.of("a", 70, "b", 30), Map.of(), QualityScorer.unscored());
        List<EvaluationSample> samples = java.util.stream.IntStream.range(0, 100)
                .mapToObj(index -> new EvaluationSample("sample-" + index, ChatRequest.user("question"))).toList();

        EvaluationReport report = runner.run(samples);

        assertEquals(100, report.results().size());
        assertEquals(runner.assign("sample-42"), runner.assign("sample-42"));
        assertTrue(report.results().stream().map(EvaluationResult::modelId).distinct().count() == 2);
    }

    @Test
    void exportsReportsAsJsonAndJsonLines() {
        EvaluationReport report = new EvaluationReport(List.of(
                new EvaluationResult("sample", "model", true, 10, 0.1, 0.9, null)));
        EvaluationReportWriter writer = new EvaluationReportWriter();

        assertTrue(writer.toJson(report).contains("\"sampleId\":\"sample\""));
        assertEquals(1, writer.toJsonLines(report).lines().count());
    }
}
