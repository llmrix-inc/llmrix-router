package com.llmrix.model.router.core.routing;

public record BanditArmStats(long selections, long rewardObservations,
                             double totalReward, double averageReward) { }
