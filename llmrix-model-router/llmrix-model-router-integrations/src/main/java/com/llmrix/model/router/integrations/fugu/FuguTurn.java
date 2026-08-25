package com.llmrix.model.router.integrations.fugu;

import lombok.Value;
import lombok.experimental.Accessors;

@Value
@Accessors(fluent = true)
public class FuguTurn {
    int index;
    FuguAction action;
    String response;
}

