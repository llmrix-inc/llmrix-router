package com.llmrix.model.router.core.event;

import lombok.Value;
import lombok.experimental.Accessors;

import java.time.Duration;

@Value
@Accessors(fluent = true)
public class TargetCooldown {
    String requestId;
    String targetId;
    Duration cooldown;
}
