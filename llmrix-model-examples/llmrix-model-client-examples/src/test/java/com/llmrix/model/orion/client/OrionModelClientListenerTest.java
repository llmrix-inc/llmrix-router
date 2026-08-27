package com.llmrix.model.orion.client;

import com.llmrix.model.orion.observation.OrionModelClientListener;

import com.llmrix.model.router.core.api.chat.ChatModel;
import com.llmrix.model.router.core.api.chat.ChatRequest;
import com.llmrix.model.router.core.api.chat.ChatResponse;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class OrionModelClientListenerTest {
    @Test
    void observesSuccessfulInvocationWithoutChangingResult() {
        List<String> events = new ArrayList<>();
        OrionModelClientListener listener = new OrionModelClientListener() {
            @Override public void onStarted(RequestStarted event) {
                events.add("start:" + event.operation() + ":" + event.model() + ":" + event.requestId());
            }
            @Override public void onCompleted(RequestCompleted event) {
                events.add("complete:" + event.success());
            }
        };
        ObservingChatModel model = new ObservingChatModel(
                request -> ChatResponse.of("ok"), listener, "req-1", "chat.completions", "general");

        assertThat(model.chat(ChatRequest.user("hello")).text()).isEqualTo("ok");
        assertThat(events).containsExactly("start:chat.completions:general:req-1", "complete:true");
    }

    @Test
    void listenerFailureDoesNotBreakModelCall() {
        OrionModelClientListener listener = new OrionModelClientListener() {
            @Override public void onStarted(RequestStarted event) { throw new IllegalStateException("listener"); }
        };
        ObservingChatModel model = new ObservingChatModel(
                request -> ChatResponse.of("ok"), listener, null, "responses", "general");

        assertThat(model.chat(ChatRequest.user("hello")).text()).isEqualTo("ok");
    }

    @Test
    void preservesDelegateCapabilities() {
        ChatModel delegate = new ChatModel() {
            @Override public ChatResponse chat(ChatRequest request) { return ChatResponse.of("ok"); }
            @Override public boolean supportsStreaming() { return true; }
            @Override public boolean supportsTools() { return true; }
            @Override public boolean supportsStructuredOutput() { return true; }
            @Override public boolean supportsPromptCache() { return true; }
        };
        ObservingChatModel model = new ObservingChatModel(
                delegate, OrionModelClientListener.NOOP, null, "chat.completions", "general");

        assertThat(model.supportsStreaming()).isTrue();
        assertThat(model.supportsTools()).isTrue();
        assertThat(model.supportsStructuredOutput()).isTrue();
        assertThat(model.supportsPromptCache()).isTrue();
    }
}
