package com.llmrix.model.router.spring.boot.http.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

public final class ApiKeyFilter extends OncePerRequestFilter {
    private final ApiKeyVerifier verifier;

    public ApiKeyFilter(ApiKeyVerifier verifier) {
        this.verifier = verifier;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String authorization = request.getHeader("Authorization");
        String actual = bearerToken(authorization);
        final AuthenticationResult authentication;
        try {
            authentication = verifier.authenticate(actual);
        } catch (RuntimeException failure) {
            writeError(response, HttpServletResponse.SC_SERVICE_UNAVAILABLE,
                    "authentication service unavailable", "server_error", false);
            return;
        }
        if (authentication == null || !authentication.authenticated()) {
            writeError(response, HttpServletResponse.SC_UNAUTHORIZED,
                    "invalid API key", "authentication_error", true);
            return;
        }
        request.setAttribute(AuthenticationResult.REQUEST_ATTRIBUTE, authentication);
        chain.doFilter(request, response);
    }

    private static void writeError(HttpServletResponse response, int status, String message,
                                   String type, boolean challenge) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        if (challenge) response.setHeader("WWW-Authenticate", "Bearer");
        response.setHeader("Cache-Control", "no-store");
        response.getWriter().write("{\"error\":{\"message\":\"" + message
                + "\",\"type\":\"" + type + "\"}}");
    }

    private static String bearerToken(String authorization) {
        if (authorization == null || authorization.length() <= 6
                || !authorization.regionMatches(true, 0, "Bearer", 0, 6)
                || !Character.isWhitespace(authorization.charAt(6))) {
            return "";
        }
        String token = authorization.substring(7).stripLeading();
        return token.isBlank() ? "" : token;
    }
}
