package com.llmrix.model.router.server;

import com.llmrix.model.router.core.exception.AuthenticationException;
import com.llmrix.model.router.core.exception.InvalidRequestException;
import com.llmrix.model.router.core.exception.RateLimitException;
import com.llmrix.model.router.core.exception.PermissionDeniedException;
import com.llmrix.model.router.core.exception.ContextWindowException;
import com.llmrix.model.router.core.exception.ContentPolicyException;
import com.llmrix.model.router.core.routing.NoCandidateException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;
import java.util.LinkedHashMap;

@RestControllerAdvice
final class OpenAiExceptionHandler {
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

    @ExceptionHandler(NoCandidateException.class)
    ResponseEntity<Map<String, Object>> unavailable(RuntimeException error) {
        return response(HttpStatus.SERVICE_UNAVAILABLE, "no model candidate is available", "server_error", null, null);
    }

    @ExceptionHandler(UnknownModelException.class)
    ResponseEntity<Map<String, Object>> unknownModel(UnknownModelException error) {
        return response(HttpStatus.NOT_FOUND, error, "invalid_request_error", "model", "model_not_found");
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
}
