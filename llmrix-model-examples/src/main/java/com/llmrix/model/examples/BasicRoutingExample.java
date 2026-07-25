package com.llmrix.model.examples;

import com.llmrix.model.router.core.api.ChatModel;
import com.llmrix.model.router.core.api.ChatResponse;
import com.llmrix.model.router.core.api.ChatRequest;
import com.llmrix.model.router.core.api.RoutedChatModel;
import com.llmrix.model.router.core.candidate.Capability;
import com.llmrix.model.router.core.routing.Strategies;

/** Runs without an API key and demonstrates the embedded SDK boundary. */
public final class BasicRoutingExample {
    private BasicRoutingExample() {}

    public static void main(String[] args) {
        ChatModel fast = request -> ChatResponse.of("fast candidate");
        ChatModel reasoning = request -> ChatResponse.of("reasoning candidate");

        try (RoutedChatModel router = RoutedChatModel.builder()
                .candidate("fast", fast, candidate -> candidate.capabilities(Capability.CHAT))
                .candidate("reasoning", reasoning, candidate -> candidate.capabilities(Capability.CHAT, Capability.REASONING))
                .strategy("balanced", Strategies.balanced())
                .build()) {
            System.out.println(router.chat("solve this problem").text());
            System.out.println(router.explain(ChatRequest.user("solve this problem")));
        }
    }
}
