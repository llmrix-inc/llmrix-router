package com.llmrix.model.router.integrations.fugu;

import com.llmrix.model.router.core.api.chat.ChatRequest;
import com.llmrix.model.router.core.api.chat.Message;

public final class FuguPromptTemplates {
    private FuguPromptTemplates() {
    }

    public static FuguPromptTemplate defaultTemplate() {
        return (original, role, latestAnswer, suggestion) -> {
            String query = original.messages().stream()
                    .filter(message -> "user".equals(message.role()))
                    .reduce((first, second) -> second)
                    .map(Message::content)
                    .orElseGet(() -> original.messages().get(original.messages().size() - 1).content());
            String prompt = switch (role) {
                case WORKER -> query + (suggestion == null ? "" : "\n\nSuggestion from a coordinator:\n" + suggestion);
                case THINKER -> "Analyze how to improve the current answer. Do not answer the user directly."
                        + "\n\nQuery:\n" + query + "\n\nCurrent answer:\n" + valueOrEmpty(latestAnswer);
                case VERIFIER ->
                        "Review the answer. Start with ACCEPT if it is correct and complete; otherwise start with REJECT."
                                + "\n\nQuery:\n" + query + "\n\nAnswer:\n" + valueOrEmpty(latestAnswer);
            };
            ChatRequest.Builder builder = ChatRequest.builder()
                    .message(Message.system("You are a helpful assistant."))
                    .userMessage(prompt)
                    .generationOptions(original.generationOptions())
                    .streamOptions(original.streamOptions());
            if (!original.tools().isEmpty()) builder.tools(original.tools());
            if (original.toolChoice() != null) builder.toolChoice(original.toolChoice());
            if (original.responseFormat() != null) builder.responseFormat(original.responseFormat());
            return builder.build();
        };
    }

    private static String valueOrEmpty(String value) {
        return value == null ? "" : value;
    }
}
