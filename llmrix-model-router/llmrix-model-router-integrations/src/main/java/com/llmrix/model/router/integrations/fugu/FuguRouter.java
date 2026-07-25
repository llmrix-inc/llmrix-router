package com.llmrix.model.router.integrations.fugu;

@FunctionalInterface
public interface FuguRouter {
    FuguAction route(FuguState state);
}
