package com.llmrix.model.examples;

import com.llmrix.model.router.core.api.chat.ChatModel;
import com.llmrix.model.router.core.api.chat.ChatResponse;
import com.llmrix.model.router.core.api.chat.ChatRequest;
import com.llmrix.model.router.core.engine.RoutedChatModel;
import com.llmrix.model.router.core.model.Capability;
import com.llmrix.model.router.core.routing.Strategies;

/** Runs without an API key and demonstrates the embedded SDK boundary. */
public final class BasicRoutingExample {
    private BasicRoutingExample() {}

    public static void main(String[] args) {
        ChatModel fast = request -> ChatResponse.of("fast candidate");
        ChatModel reasoning = request -> ChatResponse.of("reasoning candidate");

        try (RoutedChatModel router = RoutedChatModel.builder()
                .target("fast", fast, candidate -> candidate.capabilities(Capability.CHAT))
                .target("reasoning", reasoning, candidate -> candidate.capabilities(Capability.CHAT, Capability.REASONING))
                .strategy("balanced", Strategies.balanced())
                .build()) {
            System.out.println(router.chat("solve this problem").text());
            System.out.println(router.explain(ChatRequest.user("solve this problem")));
        }
    }
}
