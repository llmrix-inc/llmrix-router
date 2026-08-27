package com.llmrix.model.router.core.spi.auth;

import java.util.Map;
import java.util.Collections;

/**
 * Supplies authentication headers for each outbound model request.
 */
@FunctionalInterface
public interface RequestAuthenticator {
    RequestAuthenticator NONE = Collections::emptyMap;

    Map<String, String> headers();
}
