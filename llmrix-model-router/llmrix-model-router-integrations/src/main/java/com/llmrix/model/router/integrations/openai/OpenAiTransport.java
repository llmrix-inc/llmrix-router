package com.llmrix.model.router.integrations.openai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.llmrix.model.router.core.exception.AuthenticationException;
import com.llmrix.model.router.core.exception.ContentPolicyException;
import com.llmrix.model.router.core.exception.ContextWindowException;
import com.llmrix.model.router.core.exception.InvalidRequestException;
import com.llmrix.model.router.core.exception.ModelTimeoutException;
import com.llmrix.model.router.core.exception.ModelUnavailableException;
import com.llmrix.model.router.core.exception.PermissionDeniedException;
import com.llmrix.model.router.core.exception.RateLimitException;
import com.llmrix.model.router.core.spi.auth.RequestAuthenticator;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

/**
 * Shared HTTP, authentication and error handling for OpenAI-compatible operations.
 */
public final class OpenAiTransport {
    private final URI baseUri;
    private final RequestAuthenticator authenticator;
    private final Map<String, String> headers;
    private final HttpClient client;
    private final ObjectMapper mapper;
    private final Duration timeout;

    public OpenAiTransport(String baseUrl, RequestAuthenticator authenticator,
                           Map<String, String> headers) {
        this(baseUrl, authenticator, headers, null, null, Duration.ofSeconds(60));
    }

    public OpenAiTransport(String baseUrl, RequestAuthenticator authenticator,
                           Map<String, String> headers, HttpClient client,
                           ObjectMapper mapper, Duration timeout) {
        if (baseUrl == null || baseUrl.isBlank()) throw new IllegalArgumentException("baseUrl must not be blank");
        String normalized = baseUrl.endsWith("/") ? baseUrl : baseUrl + "/";
        this.baseUri = URI.create(normalized);
        this.authenticator = authenticator == null ? RequestAuthenticator.NONE : authenticator;
        this.headers = headers == null ? Map.of() : Map.copyOf(headers);
        this.timeout = timeout == null ? Duration.ofSeconds(60) : timeout;
        this.client = client == null ? HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build() : client;
        this.mapper = mapper == null ? new ObjectMapper() : mapper;
    }

    public ObjectMapper mapper() {
        return mapper;
    }

    public JsonNode postJson(String path, JsonNode payload) {
        Response response = send(path, "application/json",
                HttpRequest.BodyPublishers.ofString(write(payload)));
        try {
            return mapper.readTree(response.body());
        } catch (IOException error) {
            throw new ModelUnavailableException("invalid OpenAI-compatible response", error);
        }
    }

    public Response postJsonBytes(String path, JsonNode payload) {
        return send(path, "application/json", HttpRequest.BodyPublishers.ofString(write(payload)));
    }

    public Response postMultipart(String path, MultipartBody body) {
        return send(path, body.contentType(), body.publisher());
    }

    public Response get(String path) {
        return send(path, null, null, "GET");
    }

    public Response delete(String path) {
        return send(path, null, null, "DELETE");
    }

    private Response send(String path, String contentType, HttpRequest.BodyPublisher body) {
        return send(path, contentType, body, "POST");
    }

    private Response send(String path, String contentType, HttpRequest.BodyPublisher body, String method) {
        HttpRequest.Builder request = HttpRequest.newBuilder(baseUri.resolve(stripLeadingSlash(path)))
                .timeout(timeout);
        if (contentType != null) request.header("Content-Type", contentType);
        if ("GET".equals(method)) request.GET();
        else if ("DELETE".equals(method)) request.DELETE();
        else request.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : body);
        headers.forEach(request::header);
        Map<String, String> authenticationHeaders = authenticator.headers();
        if (authenticationHeaders == null)
            throw new IllegalStateException("request authenticator returned null headers");
        authenticationHeaders.forEach(request::setHeader);
        try {
            HttpResponse<byte[]> response = client.send(request.build(), HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw mapError(response.statusCode(), new String(response.body(), java.nio.charset.StandardCharsets.UTF_8));
            }
            return new Response(response.body(), response.headers().firstValue("Content-Type")
                    .orElse("application/octet-stream"));
        } catch (java.net.http.HttpTimeoutException error) {
            throw new ModelTimeoutException("OpenAI-compatible request timed out", error);
        } catch (IOException error) {
            throw new ModelUnavailableException("OpenAI-compatible request failed", error);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new ModelUnavailableException("OpenAI-compatible request interrupted", error);
        }
    }

    private RuntimeException mapError(int status, String body) {
        String message = applicationMessage(status);
        String code = errorCode(body);
        if (status == 401) return new AuthenticationException(message).statusCode(status);
        if (status == 403) return new PermissionDeniedException(message).statusCode(status);
        if ("context_length_exceeded".equals(code) || "context_window_exceeded".equals(code)) {
            return new ContextWindowException(message).statusCode(status);
        }
        if ("content_filter".equals(code) || "content_policy_violation".equals(code)) {
            return new ContentPolicyException(message).statusCode(status);
        }
        if (status == 400 || status == 404 || status == 422)
            return new InvalidRequestException(message).statusCode(status);
        if (status == 429) return new RateLimitException(message).statusCode(status);
        if (status == 402) return new ModelUnavailableException(message, false).statusCode(status);
        return new ModelUnavailableException(message).statusCode(status);
    }

    private static String applicationMessage(int status) {
        return switch (status) {
            case 400, 422 -> "model request was rejected";
            case 401 -> "model service authentication failed";
            case 402 -> "model service requires account capacity";
            case 403 -> "model service access is denied";
            case 404 -> "model service or model was not found";
            case 429 -> "model service rate limit exceeded";
            default -> status >= 500 ? "model service is temporarily unavailable"
                    : "model request failed";
        };
    }

    private String errorCode(String body) {
        if (body == null || body.isBlank()) return null;
        try {
            JsonNode error = mapper.readTree(body).path("error");
            String code = error.path("code").asText(null);
            return code == null ? error.path("type").asText(null) : code;
        } catch (IOException ignored) {
            return null;
        }
    }

    private String write(JsonNode payload) {
        try {
            return mapper.writeValueAsString(payload);
        } catch (IOException error) {
            throw new IllegalStateException("cannot serialize request", error);
        }
    }

    private static String stripLeadingSlash(String value) {
        return value != null && value.startsWith("/") ? value.substring(1) : value;
    }

    public static final class Response {
        private final byte[] body;
        private final String mediaType;

        private Response(byte[] body, String mediaType) {
            this.body = body;
            this.mediaType = mediaType;
        }

        public byte[] body() {
            return java.util.Arrays.copyOf(body, body.length);
        }

        public String mediaType() {
            return mediaType;
        }
    }
}
