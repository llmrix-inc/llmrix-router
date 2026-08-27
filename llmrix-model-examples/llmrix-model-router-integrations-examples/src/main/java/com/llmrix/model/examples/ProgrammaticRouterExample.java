package com.llmrix.model.examples;

import com.llmrix.model.router.core.api.chat.ChatResponse;
import com.llmrix.model.router.core.model.ModelFeature;
import com.llmrix.model.router.core.model.ModelOperation;
import com.llmrix.model.router.core.model.InputModality;
import com.llmrix.model.router.core.model.ModelTrait;
import com.llmrix.model.router.core.runtime.LlmRouter;

import java.time.Duration;

/** Builds the same provider and route structure as YAML, using only Java. */
public final class ProgrammaticRouterExample {
    private ProgrammaticRouterExample() {
    }

    public static void main(String[] args) {
        try (LlmRouter router = LlmRouter.builder()
                .integration("openai", integration -> integration
                        .apiKey(System.getenv("OPENAI_API_KEY"))
                        .model("gpt-4.1-mini", model -> model
                                .operations(ModelOperation.CHAT).features(ModelFeature.TOOLS)
                                .inputModalities(InputModality.VISION)))
                .integration("deepseek", integration -> integration
                        .apiKey(System.getenv("DEEPSEEK_API_KEY"))
                        .model("deepseek-chat", model -> model
                                .operations(ModelOperation.CHAT).features(ModelFeature.TOOLS).traits(ModelTrait.CODE)))
                .integration("openrouter", integration -> integration
                        .apiKey(System.getenv("OPENROUTER_API_KEY"))
                        .appName("LLMRix Router")
                        .model("openai/gpt-4.1-mini", model -> model
                                .operations(ModelOperation.CHAT).features(ModelFeature.TOOLS)))
                .route("general", route -> route
                        .strategy("balanced")
                        .models("openai/gpt-4.1-mini", "deepseek/deepseek-chat",
                                "openrouter/openai/gpt-4.1-mini"))
                .timeout(Duration.ofSeconds(30))
                .maxRetries(1)
                .build()) {
            ChatResponse response = router.chat("Explain why model routing is useful.");
            System.out.println(response.text());
        }
    }
}
