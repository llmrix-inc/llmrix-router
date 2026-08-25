package com.llmrix.model.router.core.exception;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ModelExceptionTest {
    @Test
    void preservesHttpStatusAndDefaultsToUnknown() {
        ModelException exception = new ModelException("rate limited", true);

        assertEquals(-1, exception.statusCode());
        assertEquals(429, exception.statusCode(429).statusCode());
        assertEquals(-1, new ModelException("transport", false).statusCode());
    }

    @Test
    void rejectsNonHttpStatus() {
        ModelException exception = new ModelException("invalid", false);

        assertThrows(IllegalArgumentException.class, () -> exception.statusCode(99));
        assertThrows(IllegalArgumentException.class, () -> exception.statusCode(600));
    }
}
