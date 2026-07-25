package com.llmrix.model.router.core.routing;

import java.util.Map;

/** Atomic state operations required by an online contextual bandit. */
public interface BanditStateStore {
    long totalSelections(String namespace);
    BanditArmStats arm(String namespace, String candidateId);
    void recordSelection(String namespace, String candidateId);
    void recordReward(String namespace, String candidateId, double reward);
    Map<String, BanditArmStats> snapshot(String namespace);
    void restore(String namespace, Map<String, BanditArmStats> snapshot);
}
