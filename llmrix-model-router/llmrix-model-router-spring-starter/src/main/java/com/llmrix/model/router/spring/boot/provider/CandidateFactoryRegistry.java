package com.llmrix.model.router.spring.boot.provider;

import com.llmrix.model.router.spring.boot.properties.LlmRouterProperties;

import com.llmrix.model.router.core.api.ChatModel;
import com.llmrix.model.router.core.candidate.Candidate;
import com.llmrix.model.router.core.candidate.Capability;
import com.llmrix.model.router.core.candidate.ModelLimits;
import com.llmrix.model.router.core.candidate.ModelPricing;
import com.llmrix.model.router.integrations.openai.OpenAiCompatibleChatModel;
import com.llmrix.model.router.integrations.springai.SpringAiModels;
import com.llmrix.model.router.integrations.langchain4j.LangChain4jModels;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.util.ClassUtils;

import java.util.Locale;

public final class CandidateFactoryRegistry {
    private final BeanFactory beanFactory;

    public CandidateFactoryRegistry(BeanFactory beanFactory) {
        this.beanFactory = beanFactory;
    }

    public Candidate create(String id, LlmRouterProperties.Candidate properties) {
        ChatModel model = createModel(properties);
        Capability[] capabilities = properties.getCapabilities().stream()
                .map(value -> Capability.valueOf(value.replace('-', '_').toUpperCase(Locale.ROOT)))
                .toArray(Capability[]::new);
        return Candidate.builder(id, model)
                .capabilities(capabilities)
                .maxInputTokens(properties.getMaxInputTokens())
                .pricing(new ModelPricing(properties.getInputCostPerMillion(), properties.getOutputCostPerMillion()))
                .limits(new ModelLimits(properties.getLimits().getRequestsPerMinute(),
                        properties.getLimits().getTokensPerMinute(), properties.getLimits().getMaxConcurrency()))
                .priority(properties.getPriority())
                .weight(properties.getWeight())
                .metadata(properties.getMetadata())
                .build();
    }

    private ChatModel createModel(LlmRouterProperties.Candidate properties) {
        String provider = properties.getProvider();
        if (provider == null || provider.isBlank()) throw new IllegalArgumentException("candidate provider is required");
        if (provider.equals("openai-compatible")) {
            OpenAiCompatibleChatModel.Builder builder = OpenAiCompatibleChatModel.builder()
                    .modelName(properties.getModelName())
                    .apiKey(properties.getApiKey())
                    .extensions(properties.getExtensions());
            if (properties.getBaseUrl() != null && !properties.getBaseUrl().isBlank()) builder.baseUrl(properties.getBaseUrl());
            if (properties.getApiMode() == LlmRouterProperties.OpenAiApiMode.RESPONSES) builder.responsesApi();
            return builder.build();
        }
        if (properties.getApiMode() != LlmRouterProperties.OpenAiApiMode.CHAT_COMPLETIONS) {
            throw new IllegalArgumentException("api-mode is only supported for provider openai-compatible");
        }
        if (!properties.getExtensions().isEmpty()) {
            throw new IllegalArgumentException("extensions are not supported for provider " + provider);
        }
        if (provider.equals("spring-ai-bean")) {
            requireBeanName(properties, provider);
            if (!ClassUtils.isPresent("org.springframework.ai.chat.model.ChatModel", getClass().getClassLoader())) {
                throw new IllegalArgumentException("spring-ai-model is required for provider spring-ai-bean");
            }
            return SpringAiModels.adaptObject(beanFactory.getBean(properties.getBeanName()));
        }
        if (provider.equals("langchain4j-bean")) {
            requireBeanName(properties, provider);
            if (!ClassUtils.isPresent("dev.langchain4j.model.chat.ChatModel", getClass().getClassLoader())) {
                throw new IllegalArgumentException("langchain4j is required for provider langchain4j-bean");
            }
            return LangChain4jModels.adaptObject(beanFactory.getBean(properties.getBeanName()));
        }
        if (provider.equals("bean")) {
            requireBeanName(properties, provider);
            return beanFactory.getBean(properties.getBeanName(), ChatModel.class);
        }
        throw new IllegalArgumentException("unsupported provider: " + provider);
    }

    private static void requireBeanName(LlmRouterProperties.Candidate properties, String provider) {
            if (properties.getBeanName() == null || properties.getBeanName().isBlank()) {
                throw new IllegalArgumentException("bean-name is required for provider " + provider);
            }
    }
}
