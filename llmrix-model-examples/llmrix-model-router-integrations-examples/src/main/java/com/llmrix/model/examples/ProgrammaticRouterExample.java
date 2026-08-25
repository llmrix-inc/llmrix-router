package com.llmrix.model.examples;

import com.llmrix.model.router.core.api.chat.ChatResponse;
import com.llmrix.model.router.core.model.Capability;
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
                                .capabilities(Capability.CHAT, Capability.TOOLS, Capability.VISION)))
                .integration("deepseek", integration -> integration
                        .apiKey(System.getenv("DEEPSEEK_API_KEY"))
                        .model("deepseek-chat", model -> model
                                .capabilities(Capability.CHAT, Capability.TOOLS, Capability.CODE)))
                .integration("openrouter", integration -> integration
                        .apiKey(System.getenv("OPENROUTER_API_KEY"))
                        .appName("LLMRix Router")
                        .model("openai/gpt-4.1-mini", model -> model
                                .capabilities(Capability.CHAT, Capability.TOOLS)))
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
