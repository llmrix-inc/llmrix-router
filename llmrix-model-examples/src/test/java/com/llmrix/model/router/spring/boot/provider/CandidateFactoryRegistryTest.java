package com.llmrix.model.router.spring.boot.provider;

import com.llmrix.model.router.spring.boot.properties.LlmRouterProperties;

import com.llmrix.model.router.integrations.openai.OpenAiCompatibleChatModel;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CandidateFactoryRegistryTest {
    @Test
    void configuresOpenAiApiModeWithoutCustomBean() {
        CandidateFactoryRegistry registry = new CandidateFactoryRegistry(new DefaultListableBeanFactory());
        LlmRouterProperties.Candidate responses = openAiCandidate();
        responses.setApiMode(LlmRouterProperties.OpenAiApiMode.RESPONSES);
        LlmRouterProperties.Candidate completions = openAiCandidate();

        OpenAiCompatibleChatModel responsesModel = (OpenAiCompatibleChatModel)
                registry.create("responses", responses).model();
        OpenAiCompatibleChatModel completionsModel = (OpenAiCompatibleChatModel)
                registry.create("completions", completions).model();

        assertThat(responsesModel.api()).isEqualTo(OpenAiCompatibleChatModel.Api.RESPONSES);
        assertThat(completionsModel.api()).isEqualTo(OpenAiCompatibleChatModel.Api.CHAT_COMPLETIONS);
    }

    @Test
    void rejectsResponsesApiModeForNonOpenAiProvider() {
        CandidateFactoryRegistry registry = new CandidateFactoryRegistry(new DefaultListableBeanFactory());
        LlmRouterProperties.Candidate candidate = new LlmRouterProperties.Candidate();
        candidate.setProvider("bean");
        candidate.setBeanName("model");
        candidate.setApiMode(LlmRouterProperties.OpenAiApiMode.RESPONSES);

        assertThatThrownBy(() -> registry.create("invalid", candidate))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("only supported for provider openai-compatible");
    }

    private static LlmRouterProperties.Candidate openAiCandidate() {
        LlmRouterProperties.Candidate candidate = new LlmRouterProperties.Candidate();
        candidate.setProvider("openai-compatible");
        candidate.setModelName("test-model");
        candidate.setBaseUrl("https://example.test/v1");
        return candidate;
    }
}
