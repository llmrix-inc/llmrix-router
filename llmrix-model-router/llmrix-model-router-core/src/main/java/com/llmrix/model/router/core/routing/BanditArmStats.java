package com.llmrix.model.router.core.routing;

import lombok.Value;
import lombok.experimental.Accessors;

@Value
@Accessors(fluent = true)
public class BanditArmStats {
    long selections;
    long rewardObservations;
    double totalReward;
    double averageReward;
}
