package com.llmrix.model.router.integrations.openai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.llmrix.model.router.core.exception.ModelTimeoutException;
import com.llmrix.model.router.core.exception.ModelUnavailableException;
import com.llmrix.model.router.core.spi.auth.RequestAuthenticator;
import com.llmrix.model.router.integrations.JsonTransport;
import com.llmrix.model.router.integrations.RoutingHintsHttpCodec;
import com.llmrix.model.router.core.routing.RoutingHints;

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
public final class OpenAiTransport implements JsonTransport {
    private static final System.Logger LOGGER = System.getLogger(OpenAiTransport.class.getName());
    private final URI baseUri;
    private final RequestAuthenticator authenticator;
    private final Map<String, String> headers;
    private final HttpClient client;
    private final ObjectMapper mapper;
    private final Duration timeout;
    private final boolean forwardRoutingHints;

    public OpenAiTransport(String baseUrl, RequestAuthenticator authenticator,
                           Map<String, String> headers) {
        this(baseUrl, authenticator, headers, null, null, Duration.ofSeconds(60), true);
    }

    public OpenAiTransport(String baseUrl, RequestAuthenticator authenticator,
                           Map<String, String> headers, HttpClient client,
                           ObjectMapper mapper, Duration timeout) {
        this(baseUrl, authenticator, headers, client, mapper, timeout, true);
    }

    public OpenAiTransport(String baseUrl, RequestAuthenticator authenticator,
                           Map<String, String> headers, HttpClient client,
                           ObjectMapper mapper, Duration timeout, boolean forwardRoutingHints) {
        if (baseUrl == null || baseUrl.isBlank()) throw new IllegalArgumentException("baseUrl must not be blank");
        String normalized = baseUrl.endsWith("/") ? baseUrl : baseUrl + "/";
        this.baseUri = URI.create(normalized);
        this.authenticator = authenticator == null ? RequestAuthenticator.NONE : authenticator;
        this.headers = sanitizeHeaders(headers, forwardRoutingHints);
        this.timeout = timeout == null ? Duration.ofSeconds(60) : timeout;
        this.client = client == null ? HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build() : client;
        this.mapper = mapper == null ? new ObjectMapper() : mapper;
        this.forwardRoutingHints = forwardRoutingHints;
    }

    public ObjectMapper mapper() {
        return mapper;
    }

    public JsonNode postJson(String path, JsonNode payload) {
        return postJson(path, payload, null);
    }

    public JsonNode postJson(String path, JsonNode payload, RoutingHints hints) {
        debugPayload(path, payload);
        Response response = send(path, "application/json",
                HttpRequest.BodyPublishers.ofString(write(payload)), "POST", hints);
        try {
            return mapper.readTree(response.body());
        } catch (IOException error) {
            throw new ModelUnavailableException("invalid OpenAI-compatible response", error);
        }
    }

    public Response postJsonBytes(String path, JsonNode payload) {
        return postJsonBytes(path, payload, null);
    }

    public Response postJsonBytes(String path, JsonNode payload, RoutingHints hints) {
        debugPayload(path, payload);
        return send(path, "application/json", HttpRequest.BodyPublishers.ofString(write(payload)), "POST", hints);
    }

    public Response postMultipart(String path, MultipartBody body) {
        return postMultipart(path, body, null);
    }

    public Response postMultipart(String path, MultipartBody body, RoutingHints hints) {
        return send(path, body.contentType(), body.publisher(), "POST", hints);
    }

    public Response get(String path) {
        return get(path, null);
    }

    public Response get(String path, RoutingHints hints) {
        return send(path, null, null, "GET", hints);
    }

    public Response delete(String path) {
        return delete(path, null);
    }

    public Response delete(String path, RoutingHints hints) {
        return send(path, null, null, "DELETE", hints);
    }

    JsonNode readJson(Response response, String errorMessage) {
        try {
            return mapper.readTree(response.body());
        } catch (IOException error) {
            throw new ModelUnavailableException(errorMessage, error);
        }
    }

    private Response send(String path, String contentType, HttpRequest.BodyPublisher body) {
        return send(path, contentType, body, "POST", null);
    }

    private Response send(String path, String contentType, HttpRequest.BodyPublisher body, String method) {
        return send(path, contentType, body, method, null);
    }

    private Response send(String path, String contentType, HttpRequest.BodyPublisher body, String method,
                          RoutingHints hints) {
        HttpRequest.Builder request = HttpRequest.newBuilder(baseUri.resolve(stripLeadingSlash(path)))
                .timeout(timeout);
        if (contentType != null) request.header("Content-Type", contentType);
        if ("GET".equals(method)) request.GET();
        else if ("DELETE".equals(method)) request.DELETE();
        else request.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : body);
        headers.forEach(request::header);
        if (forwardRoutingHints) {
            String encodedHints = RoutingHintsHttpCodec.encode(hints);
            if (encodedHints != null) request.header(RoutingHintsHttpCodec.HEADER, encodedHints);
        }
        if (LOGGER.isLoggable(System.Logger.Level.DEBUG)) {
            LOGGER.log(System.Logger.Level.DEBUG,
                    "OpenAI request: method={0}, path={1}, contentType={2}, bodyPublisher={3}",
                    method, path, contentType, body == null ? "none" : "present");
        }
        Map<String, String> authenticationHeaders = authenticator.headers();
        if (authenticationHeaders == null)
            throw new IllegalStateException("request authenticator returned null headers");
        authenticationHeaders.forEach(request::setHeader);
        try {
            HttpResponse<byte[]> response = client.send(request.build(), HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw OpenAiErrorMapper.map(response.statusCode(),
                        new String(response.body(), java.nio.charset.StandardCharsets.UTF_8), mapper);
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

    private String write(JsonNode payload) {
        try {
            return mapper.writeValueAsString(payload);
        } catch (IOException error) {
            throw new IllegalStateException("cannot serialize request", error);
        }
    }

    private void debugPayload(String path, JsonNode payload) {
        if (!LOGGER.isLoggable(System.Logger.Level.DEBUG)) return;
        java.util.List<String> fields = new java.util.ArrayList<>();
        if (payload != null) payload.fieldNames().forEachRemaining(fields::add);
        String serialized = payload == null ? "" : write(payload);
        LOGGER.log(System.Logger.Level.DEBUG,
                "OpenAI JSON payload: path={0}, bytes={1}, fields={2}",
                path, serialized.getBytes(java.nio.charset.StandardCharsets.UTF_8).length, fields);
    }

    private static String stripLeadingSlash(String value) {
        return value != null && value.startsWith("/") ? value.substring(1) : value;
    }

    private static Map<String, String> sanitizeHeaders(Map<String, String> value, boolean forwardHints) {
        if (value == null || value.isEmpty()) return Map.of();
        if (forwardHints) return Map.copyOf(value);
        Map<String, String> filtered = new java.util.LinkedHashMap<>();
        value.forEach((key, headerValue) -> {
            if (key != null && !RoutingHintsHttpCodec.HEADER.equalsIgnoreCase(key)) {
                filtered.put(key, headerValue);
            }
        });
        return Map.copyOf(filtered);
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
