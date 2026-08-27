package com.llmrix.model.router.integrations.ollama;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.llmrix.model.router.core.exception.ModelUnavailableException;
import com.llmrix.model.router.core.exception.ModelTimeoutException;
import com.llmrix.model.router.core.spi.auth.RequestAuthenticator;
import com.llmrix.model.router.integrations.JsonTransport;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/** Native JSON transport for Ollama endpoints. */
public final class OllamaTransport implements JsonTransport {
    private static final System.Logger LOGGER = System.getLogger(OllamaTransport.class.getName());

    private final URI baseUri;
    private final RequestAuthenticator authenticator;
    private final ObjectMapper mapper;
    private final HttpClient client;
    private final Duration timeout;

    public OllamaTransport(String baseUrl, RequestAuthenticator authenticator) {
        if (baseUrl == null || baseUrl.isBlank()) throw new IllegalArgumentException("baseUrl must not be blank");
        this.baseUri = URI.create(baseUrl.endsWith("/") ? baseUrl : baseUrl + "/");
        this.authenticator = authenticator == null ? RequestAuthenticator.NONE : authenticator;
        this.mapper = new ObjectMapper();
        this.timeout = Duration.ofSeconds(60);
        this.client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    }

    @Override public ObjectMapper mapper() { return mapper; }

    @Override public JsonNode postJson(String path, JsonNode payload) {
        HttpRequest.Builder request = HttpRequest.newBuilder(baseUri.resolve(stripLeadingSlash(path)))
                .timeout(timeout)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(write(payload)));
        authenticator.headers().forEach(request::setHeader);
        try {
            HttpResponse<String> response = client.send(request.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                LOGGER.log(System.Logger.Level.WARNING,
                        "Ollama provider error: status={0}, body={1}",
                        response.statusCode(), truncate(response.body()));
                throw new ModelUnavailableException("Ollama request failed with status " + response.statusCode());
            }
            return mapper.readTree(response.body());
        } catch (java.net.http.HttpTimeoutException error) {
            throw new ModelTimeoutException("Ollama request timed out", error);
        } catch (IOException error) {
            throw new ModelUnavailableException("Ollama request failed", error);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new ModelUnavailableException("Ollama request interrupted", error);
        }
    }

    private String write(JsonNode payload) {
        try {
            return mapper.writeValueAsString(payload);
        } catch (IOException error) {
            throw new IllegalStateException("cannot serialize Ollama request", error);
        }
    }

    private static String stripLeadingSlash(String value) {
        return value != null && value.startsWith("/") ? value.substring(1) : value;
    }

    private static String truncate(String value) {
        if (value == null) return "";
        String normalized = value.replaceAll("\\s+", " ").trim();
        return normalized.length() <= 512 ? normalized : normalized.substring(0, 512) + "...";
    }
}
