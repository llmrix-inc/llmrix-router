package com.llmrix.model.router.server;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(LlmRouterServerProperties.class)
@ConditionalOnProperty(prefix = "llmrix.model.router.server", name = "enabled", havingValue = "true")
class ServerConfiguration {
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
    @ConditionalOnProperty(prefix = "llmrix.model.router.server.auth", name = "mode", havingValue = "api-key", matchIfMissing = true)
    ApiKeyVerifier apiKeyVerifier(LlmRouterServerProperties properties) {
        return new BootstrapApiKeyVerifier(properties.getAuth().getBootstrapKey());
    }

    @Bean
    @ConditionalOnProperty(prefix = "llmrix.model.router.server.auth", name = "mode", havingValue = "api-key", matchIfMissing = true)
    FilterRegistrationBean<ApiKeyFilter> apiKeyFilter(ApiKeyVerifier verifier) {
        FilterRegistrationBean<ApiKeyFilter> registration = new FilterRegistrationBean<>();
        registration.setFilter(new ApiKeyFilter(verifier));
        registration.addUrlPatterns("/v1/*");
        registration.setOrder(-100);
        return registration;
    }
}
