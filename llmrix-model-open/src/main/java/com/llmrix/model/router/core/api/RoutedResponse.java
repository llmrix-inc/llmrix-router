package com.llmrix.model.router.core.api;

/**
 * Response that can be decorated with the target selected by the router.
 */
public interface RoutedResponse<T extends RoutedResponse<T>> {
    Usage usage();

    T routedBy(String targetId);
}
