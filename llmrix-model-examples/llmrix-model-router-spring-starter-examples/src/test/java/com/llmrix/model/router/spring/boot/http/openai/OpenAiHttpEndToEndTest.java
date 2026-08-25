package com.llmrix.model.router.spring.boot.http.openai;

import com.llmrix.model.router.core.api.chat.ChatChunk;
import com.llmrix.model.router.core.api.chat.ChatModel;
import com.llmrix.model.router.core.api.chat.ChatRequest;
import com.llmrix.model.router.core.api.chat.ChatResponse;
import com.llmrix.model.router.core.api.Usage;
import com.llmrix.model.router.core.api.chat.ToolCallDelta;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.ResponseEntity;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import java.util.concurrent.Flow;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(classes = OpenAiHttpEndToEndTest.TestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "llmrix.model.router.http.enabled=true",
        "llmrix.model.router.http.auth.mode=none",
        "llmrix.model.router.default-route=general"
})
@Import(OpenAiHttpEndToEndTest.Models.class)
class OpenAiHttpEndToEndTest {
    @LocalServerPort int port;
    @Autowired TestRestTemplate rest;

    @SpringBootConfiguration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    static class TestApplication {
    }

    @Test
    void servesJsonCompletionOverHttp() {
        ResponseEntity<String> response = rest.postForEntity("/v1/chat/completions", Map.of(
                "model", "general",
                "messages", java.util.List.of(Map.of("role", "user", "content", "hello"))), String.class);

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(response.getBody()).contains("\"content\":\"hello\"");
    }

    @Test
    void servesSseCompletionOverHttp() throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/v1/chat/completions"))
                .header("Content-Type", "application/json")
                .header("Accept", "text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString("""
                        {"model":"general","stream":true,
                         "messages":[{"role":"user","content":"hello"}]}
                        """))
                .build();

        HttpResponse<String> response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("hello", "[DONE]");
    }

    @Test
    void servesResponsesApiSseEventsOverHttp() throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/v1/responses"))
                .header("Content-Type", "application/json")
                .header("Accept", "text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString("""
                        {"model":"general","stream":true,"input":"hello"}
                        """))
                .build();

        HttpResponse<String> response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains(
                "event:response.created",
                "event:response.output_item.added",
                "event:response.content_part.added",
                "event:response.output_text.delta",
                "event:response.output_text.done",
                "event:response.content_part.done",
                "event:response.output_item.done",
                "event:response.completed", "hello");
    }

    @Test
    void servesResponsesFunctionCallEventsOverHttp() throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/v1/responses"))
                .header("Content-Type", "application/json")
                .header("Accept", "text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString("""
                        {"model":"general","stream":true,"input":"call-tool"}
                        """))
                .build();

        HttpResponse<String> response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains(
                "event:response.output_item.added",
                "event:response.function_call_arguments.delta",
                "event:response.function_call_arguments.done",
                "event:response.output_item.done",
                "call_1", "weather", "Shanghai", "event:response.completed");
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class Models {
        @Bean(destroyMethod = "close")
        com.llmrix.model.router.core.engine.RoutedChatModels routedChatModels() {
            ChatModel fakeModel = new ChatModel() {
                @Override public ChatResponse chat(ChatRequest request) { return ChatResponse.of("hello"); }
                @Override public Flow.Publisher<ChatChunk> stream(ChatRequest request) {
                    return subscriber -> {
                        subscriber.onSubscribe(new Flow.Subscription() {
                            private boolean sent;
                            @Override public void request(long n) {
                                if (sent || n <= 0) return;
                                sent = true;
                                if (request.messages().get(0).content().equals("call-tool")) {
                                    subscriber.onNext(new ChatChunk("", false, Usage.UNKNOWN, java.util.List.of(
                                            new ToolCallDelta(0, "call_1", "weather", "{\"city\":"))));
                                    subscriber.onNext(new ChatChunk("", false, Usage.UNKNOWN, java.util.List.of(
                                            new ToolCallDelta(0, null, null, "\"Shanghai\"}"))));
                                    subscriber.onNext(new ChatChunk("", true, Usage.UNKNOWN,
                                            java.util.List.of(), "tool_calls"));
                                } else {
                                    subscriber.onNext(new ChatChunk("hello", false, Usage.UNKNOWN));
                                    subscriber.onNext(new ChatChunk("", true, Usage.UNKNOWN));
                                }
                                subscriber.onComplete();
                            }
                            @Override public void cancel() { sent = true; }
                        });
                    };
                }
            };
            return new com.llmrix.model.router.core.engine.RoutedChatModels(Map.of(
                    "general", com.llmrix.model.router.core.engine.RoutedChatModel.builder()
                            .target("fake", fakeModel).build()));
        }
    }
}
