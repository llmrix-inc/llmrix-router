package com.llmrix.model.router.spring.boot.http.openai;

import com.llmrix.model.router.core.exception.AuthenticationException;
import com.llmrix.model.router.core.exception.InvalidRequestException;
import com.llmrix.model.router.core.exception.RateLimitException;
import com.llmrix.model.router.core.exception.ModelUnavailableException;
import com.llmrix.model.router.core.exception.PermissionDeniedException;
import com.llmrix.model.router.core.exception.ContextWindowException;
import com.llmrix.model.router.core.exception.ContentPolicyException;
import com.llmrix.model.router.core.exception.UnknownRouteException;
import com.llmrix.model.router.core.routing.NoCandidateException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;
import java.util.LinkedHashMap;

@RestControllerAdvice
public final class OpenAiExceptionHandler {

    @ExceptionHandler({IllegalArgumentException.class, InvalidRequestException.class})
    ResponseEntity<Map<String, Object>> badRequest(RuntimeException error) {
        return response(HttpStatus.BAD_REQUEST, error, "invalid_request_error");
    }

    @ExceptionHandler(AuthenticationException.class)
    ResponseEntity<Map<String, Object>> unauthorized(RuntimeException error) {
        return response(HttpStatus.UNAUTHORIZED, error, "authentication_error");
    }

    @ExceptionHandler(PermissionDeniedException.class)
    ResponseEntity<Map<String, Object>> forbidden(RuntimeException error) {
        return response(HttpStatus.FORBIDDEN, error, "permission_error");
    }

    @ExceptionHandler(ContextWindowException.class)
    ResponseEntity<Map<String, Object>> contextWindow(RuntimeException error) {
        return response(HttpStatus.BAD_REQUEST, error, "invalid_request_error", "messages", "context_length_exceeded");
    }

    @ExceptionHandler(ContentPolicyException.class)
    ResponseEntity<Map<String, Object>> contentPolicy(RuntimeException error) {
        return response(HttpStatus.BAD_REQUEST, error, "invalid_request_error", null, "content_policy_violation");
    }

    @ExceptionHandler(RateLimitException.class)
    ResponseEntity<Map<String, Object>> rateLimit(RuntimeException error) {
        return response(HttpStatus.TOO_MANY_REQUESTS, error, "rate_limit_error");
    }

    @ExceptionHandler({NoCandidateException.class, ModelUnavailableException.class})
    ResponseEntity<Map<String, Object>> unavailable(RuntimeException error) {
        if (error instanceof ModelUnavailableException model && model.statusCode() >= 100) {
            HttpStatus status = HttpStatus.resolve(model.statusCode());
            if (status != null) {
                return response(status, applicationMessage(status), errorType(status), null, errorCode(status));
            }
        }
        return response(HttpStatus.SERVICE_UNAVAILABLE,
                error instanceof NoCandidateException && error.getMessage() != null
                        ? error.getMessage() : "no model candidate is currently available",
                "server_error", null, null);
    }

    @ExceptionHandler(UnknownRouteException.class)
    ResponseEntity<Map<String, Object>> unknownModel(UnknownRouteException error) {
        return response(HttpStatus.NOT_FOUND,
                "The model '" + error.routeId() + "' does not exist",
                "invalid_request_error", "model", "model_not_found");
    }

    @ExceptionHandler(RuntimeException.class)
    ResponseEntity<Map<String, Object>> serverError(RuntimeException error) {
        return response(HttpStatus.INTERNAL_SERVER_ERROR, "model execution failed", "server_error", null, null);
    }

    private static ResponseEntity<Map<String, Object>> response(HttpStatus status, RuntimeException error, String type) {
        return response(status, error, type, null, null);
    }

    private static ResponseEntity<Map<String, Object>> response(
            HttpStatus status, RuntimeException error, String type, String param, String code) {
        return response(status, error.getMessage(), type, param, code);
    }

    private static ResponseEntity<Map<String, Object>> response(
            HttpStatus status, String message, String type, String param, String code) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("message", message);
        details.put("type", type);
        details.put("param", param);
        details.put("code", code);
        return ResponseEntity.status(status).body(Map.of("error", details));
    }

    private static String applicationMessage(HttpStatus status) {
        return switch (status) {
            case PAYMENT_REQUIRED -> "model service requires account capacity";
            case TOO_MANY_REQUESTS -> "model service rate limit exceeded";
            case UNAUTHORIZED -> "model service authentication failed";
            case FORBIDDEN -> "model service access is denied";
            case NOT_FOUND -> "model service or model was not found";
            default -> "model service is temporarily unavailable";
        };
    }

    private static String errorType(HttpStatus status) {
        return switch (status) {
            case PAYMENT_REQUIRED -> "billing_error";
            case TOO_MANY_REQUESTS -> "rate_limit_error";
            case UNAUTHORIZED -> "authentication_error";
            case FORBIDDEN -> "permission_error";
            default -> "server_error";
        };
    }

    private static String errorCode(HttpStatus status) {
        return switch (status) {
            case PAYMENT_REQUIRED -> "account_capacity_required";
            case TOO_MANY_REQUESTS -> "rate_limit_exceeded";
            default -> null;
        };
    }
}
