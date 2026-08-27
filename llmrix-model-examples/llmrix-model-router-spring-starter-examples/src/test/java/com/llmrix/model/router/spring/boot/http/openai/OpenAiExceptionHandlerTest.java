package com.llmrix.model.router.spring.boot.http.openai;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Map;
import com.llmrix.model.router.core.exception.ContextWindowException;
import com.llmrix.model.router.core.exception.ModelUnavailableException;
import com.llmrix.model.router.core.exception.UnknownRouteException;

import static org.assertj.core.api.Assertions.assertThat;

class OpenAiExceptionHandlerTest {

    @Test
    void mapsUnknownModelToOpenAiCompatibleNotFoundError() {
        ResponseEntity<Map<String, Object>> response =
                new OpenAiExceptionHandler().unknownModel(new UnknownRouteException("missing"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        Map<?, ?> error = (Map<?, ?>) response.getBody().get("error");
        assertThat(error.get("type")).isEqualTo("invalid_request_error");
        assertThat(error.get("param")).isEqualTo("model");
        assertThat(error.get("code")).isEqualTo("model_not_found");
    }

    @Test
    void mapsContextWindowToOpenAiCompatibleErrorCode() {
        ResponseEntity<Map<String, Object>> response =
                new OpenAiExceptionHandler().contextWindow(new ContextWindowException("too long"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        Map<?, ?> error = (Map<?, ?>) response.getBody().get("error");
        assertThat(error.get("param")).isEqualTo("messages");
        assertThat(error.get("code")).isEqualTo("context_length_exceeded");
    }

    @Test
    void sanitizesUnexpectedServerErrors() {
        ResponseEntity<Map<String, Object>> response =
                new OpenAiExceptionHandler().serverError(new IllegalStateException("secret provider response"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.getBody().toString()).contains("model execution failed");
        assertThat(response.getBody().toString()).doesNotContain("secret provider response");
    }

    @Test
    void preservesApplicationStatusForAccountCapacityErrors() {
        ModelUnavailableException failure = new ModelUnavailableException("internal detail", false);
        failure.statusCode(HttpStatus.PAYMENT_REQUIRED.value());

        ResponseEntity<Map<String, Object>> response = new OpenAiExceptionHandler().unavailable(failure);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.PAYMENT_REQUIRED);
        assertThat(response.getBody().toString()).contains("account capacity");
        assertThat(response.getBody().toString()).doesNotContain("internal detail");
    }
}
