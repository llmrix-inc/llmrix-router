package com.llmrix.model.router.spring.boot.http.security;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

class ApiKeyFilterTest {

    @Test
    void acceptsValidBearerKey() throws Exception {
        ApiKeyFilter filter = new ApiKeyFilter(new BootstrapApiKeyVerifier("secret"));
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/v1/chat/completions");
        request.addHeader("Authorization", "Bearer secret");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(chain.getRequest()).isNotNull();
    }

    @Test
    void acceptsCaseInsensitiveBearerSchemeAndAdditionalLeadingWhitespace() throws Exception {
        ApiKeyFilter filter = new ApiKeyFilter(new BootstrapApiKeyVerifier("secret"));
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/v1/models");
        request.addHeader("Authorization", "bearer    secret");
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, new MockHttpServletResponse(), chain);

        assertThat(chain.getRequest()).isNotNull();
    }

    @Test
    void rejectsInvalidBearerKey() throws Exception {
        ApiKeyFilter filter = new ApiKeyFilter(new BootstrapApiKeyVerifier("secret"));
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/v1/models");
        request.addHeader("Authorization", "Bearer wrong");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentAsString()).contains("authentication_error");
        assertThat(response.getHeader("WWW-Authenticate")).isEqualTo("Bearer");
        assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
        assertThat(response.getCharacterEncoding()).isEqualTo("UTF-8");
    }

    @Test
    void rejectsNonBearerAuthorizationScheme() throws Exception {
        ApiKeyFilter filter = new ApiKeyFilter(new BootstrapApiKeyVerifier("secret"));
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/v1/models");
        request.addHeader("Authorization", "Basic secret");
        MockFilterChain chain = new MockFilterChain();
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(chain.getRequest()).isNull();
    }

    @Test
    void failsClosedAndSanitizesVerifierFailures() throws Exception {
        ApiKeyFilter filter = new ApiKeyFilter(key -> {
            throw new IllegalStateException("secret IAM endpoint detail");
        });
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/v1/models");
        request.addHeader("Authorization", "Bearer key");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(503);
        assertThat(response.getContentAsString()).contains("authentication service unavailable", "server_error");
        assertThat(response.getContentAsString()).doesNotContain("secret IAM endpoint detail");
        assertThat(chain.getRequest()).isNull();
    }
}
