package com.llmrix.model.router.spring.boot.http.config;

import com.llmrix.model.router.spring.boot.autoconfigure.LlmRouterAutoConfiguration;
import com.llmrix.model.router.spring.boot.http.openai.OpenAiAudioController;
import com.llmrix.model.router.spring.boot.http.openai.OpenAiController;
import com.llmrix.model.router.spring.boot.http.openai.OpenAiEmbeddingController;
import com.llmrix.model.router.spring.boot.http.openai.OpenAiRerankController;
import com.llmrix.model.router.spring.boot.http.openai.OpenAiExceptionHandler;
import com.llmrix.model.router.spring.boot.http.openai.OpenAiImageController;
import com.llmrix.model.router.spring.boot.http.openai.OpenAiVideoController;
import com.llmrix.model.router.spring.boot.http.security.ApiKeyFilter;
import com.llmrix.model.router.spring.boot.http.security.ApiKeyVerifier;
import com.llmrix.model.router.spring.boot.http.security.BootstrapApiKeyVerifier;
import com.llmrix.model.router.spring.boot.http.web.RequestIdFilter;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.web.bind.annotation.RestController;

@AutoConfiguration(after = LlmRouterAutoConfiguration.class)
@EnableConfigurationProperties(LlmRouterHttpProperties.class)
@ConditionalOnClass(RestController.class)
@ConditionalOnProperty(prefix = "llmrix.model.router.http", name = "enabled", havingValue = "true")
@Import({OpenAiController.class, OpenAiEmbeddingController.class, OpenAiRerankController.class, OpenAiAudioController.class,
        OpenAiImageController.class, OpenAiVideoController.class, OpenAiExceptionHandler.class})
public class LlmRouterHttpAutoConfiguration {

    @Bean
    FilterRegistrationBean<RequestIdFilter> requestIdFilter() {
        FilterRegistrationBean<RequestIdFilter> registration = new FilterRegistrationBean<>();
        registration.setFilter(new RequestIdFilter());
        registration.addUrlPatterns("/v1/*");
        registration.setOrder(-200);
        return registration;
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "llmrix.model.router.http.auth", name = "mode", havingValue = "api-key", matchIfMissing = true)
    ApiKeyVerifier apiKeyVerifier(LlmRouterHttpProperties properties) {
        return new BootstrapApiKeyVerifier(properties.getAuth().getBootstrapKey());
    }

    @Bean
    @ConditionalOnProperty(prefix = "llmrix.model.router.http.auth", name = "mode", havingValue = "api-key", matchIfMissing = true)
    FilterRegistrationBean<ApiKeyFilter> apiKeyFilter(ApiKeyVerifier verifier) {
        FilterRegistrationBean<ApiKeyFilter> registration = new FilterRegistrationBean<>();
        registration.setFilter(new ApiKeyFilter(verifier));
        registration.addUrlPatterns("/v1/*");
        registration.setOrder(-100);
        return registration;
    }
}
