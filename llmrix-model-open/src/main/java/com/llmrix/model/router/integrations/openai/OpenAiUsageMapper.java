package com.llmrix.model.router.integrations.openai;

import com.fasterxml.jackson.databind.JsonNode;
import com.llmrix.model.router.core.api.Usage;

/** Centralizes the small but subtly different usage envelopes used by OpenAI APIs. */
final class OpenAiUsageMapper {
    private OpenAiUsageMapper() {
    }

    static Usage chat(JsonNode usage) {
        return parse(usage, false);
    }

    static Usage responses(JsonNode usage) {
        return parse(usage, true);
    }

    static Usage inputOnly(JsonNode usage) {
        long input = usage.path("prompt_tokens").asLong(
                usage.path("input_tokens").asLong(usage.path("total_tokens").asLong(-1)));
        return new Usage(input, 0);
    }

    static Usage inputOutput(JsonNode usage) {
        return new Usage(usage.path("input_tokens").asLong(-1), usage.path("output_tokens").asLong(-1));
    }

    private static Usage parse(JsonNode usage, boolean responses) {
        long input = usage.path(responses ? "input_tokens" : "prompt_tokens")
                .asLong(usage.path("input_tokens").asLong(-1));
        long output = usage.path(responses ? "output_tokens" : "completion_tokens")
                .asLong(usage.path("output_tokens").asLong(-1));
        JsonNode inputDetails = usage.path(responses ? "input_tokens_details" : "prompt_tokens_details");
        JsonNode outputDetails = usage.path(responses ? "output_tokens_details" : "completion_tokens_details");
        return new Usage(input, output,
                inputDetails.path("cached_tokens").asLong(0),
                inputDetails.path("cache_write_tokens").asLong(0),
                outputDetails.path("reasoning_tokens").asLong(0));
    }
}
