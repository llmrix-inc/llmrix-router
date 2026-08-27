package com.llmrix.model.router.integrations.openai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.llmrix.model.router.core.exception.AuthenticationException;
import com.llmrix.model.router.core.exception.ContentPolicyException;
import com.llmrix.model.router.core.exception.ContextWindowException;
import com.llmrix.model.router.core.exception.InvalidRequestException;
import com.llmrix.model.router.core.exception.ModelUnavailableException;
import com.llmrix.model.router.core.exception.PermissionDeniedException;
import com.llmrix.model.router.core.exception.RateLimitException;

import java.io.IOException;

/** Maps provider error envelopes to the router's stable exception taxonomy. */
final class OpenAiErrorMapper {
    private static final System.Logger LOGGER = System.getLogger(OpenAiErrorMapper.class.getName());

    private OpenAiErrorMapper() {
    }

    static RuntimeException map(int status, String body, ObjectMapper mapper) {
        String message = message(status, body, mapper);
        String code = errorCode(body, mapper);
        LOGGER.log(System.Logger.Level.WARNING,
                "OpenAI-compatible provider error: status={0}, type={1}, message={2}",
                status, code == null ? "unknown" : code, message);
        if (LOGGER.isLoggable(System.Logger.Level.DEBUG) && body != null && !body.isBlank()) {
            LOGGER.log(System.Logger.Level.DEBUG,
                    "OpenAI-compatible provider error envelope: {0}", truncate(body));
        }
        if (status == 401) return new AuthenticationException(message).statusCode(status);
        if (status == 403) return new PermissionDeniedException(message).statusCode(status);
        if ("context_length_exceeded".equals(code) || "context_window_exceeded".equals(code)) {
            return new ContextWindowException(message).statusCode(status);
        }
        if ("content_filter".equals(code) || "content_policy_violation".equals(code)) {
            return new ContentPolicyException(message).statusCode(status);
        }
        // Some OpenAI-compatible gateways (notably model aggregators) return
        // transient capacity failures as HTTP 400 with a server_error envelope.
        // The semantic error type must win over the transport status so routing
        // can retry or fall back to another target.
        if ("server_error".equals(code) || "temporarily_unavailable".equals(code)) {
            return new ModelUnavailableException(message).statusCode(status);
        }
        if (status == 400 || status == 404 || status == 422) {
            return new InvalidRequestException(message).statusCode(status);
        }
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

    /** Keeps the provider's actionable error detail without exposing an unbounded response body. */
    private static String message(int status, String body, ObjectMapper mapper) {
        String detail = errorMessage(body, mapper);
        if (detail == null || detail.isBlank()) return applicationMessage(status);
        return applicationMessage(status) + ": " + truncate(detail);
    }

    private static String errorMessage(String body, ObjectMapper mapper) {
        if (body == null || body.isBlank()) return null;
        try {
            JsonNode root = mapper.readTree(body);
            JsonNode error = root.path("error");
            String message = error.path("message").asText(null);
            if (message == null || message.isBlank()) message = root.path("message").asText(null);
            return message;
        } catch (IOException ignored) {
            return null;
        }
    }

    private static String truncate(String value) {
        String normalized = value.replaceAll("\\s+", " ").trim();
        return normalized.length() <= 512 ? normalized : normalized.substring(0, 512) + "...";
    }

    private static String errorCode(String body, ObjectMapper mapper) {
        if (body == null || body.isBlank()) return null;
        try {
            JsonNode root = mapper.readTree(body);
            JsonNode error = root.path("error");
            // Providers vary between an {error:{...}} envelope and a flat
            // {type,code,message} object. Inspect both forms consistently.
            if (!error.isObject()) error = root;
            String code = error.path("code").asText(null);
            return code == null ? error.path("type").asText(null) : code;
        } catch (IOException ignored) {
            return null;
        }
    }
}
