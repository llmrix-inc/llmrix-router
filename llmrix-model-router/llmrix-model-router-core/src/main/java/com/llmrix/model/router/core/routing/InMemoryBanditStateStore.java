package com.llmrix.model.router.core.routing;

import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

public final class InMemoryBanditStateStore implements BanditStateStore {
    private final Map<String, Map<String, MutableArm>> states = new ConcurrentHashMap<>();
    private Map<String, MutableArm> state(String namespace) {
        return states.computeIfAbsent(namespace, ignored -> new ConcurrentHashMap<>());
    }
    @Override public long totalSelections(String namespace) {
        return state(namespace).values().stream().mapToLong(arm -> arm.selections).sum();
    }
    @Override public BanditArmStats arm(String namespace, String id) {
        MutableArm arm = state(namespace).computeIfAbsent(id, ignored -> new MutableArm());
        synchronized (arm) { return arm.snapshot(); }
    }
    @Override public void recordSelection(String namespace, String id) {
        MutableArm arm = state(namespace).computeIfAbsent(id, ignored -> new MutableArm());
        synchronized (arm) { arm.selections++; }
    }
    @Override public void recordReward(String namespace, String id, double reward) {
        MutableArm arm = state(namespace).computeIfAbsent(id, ignored -> new MutableArm());
        synchronized (arm) { arm.observations++; arm.reward += reward; }
    }
    @Override public Map<String, BanditArmStats> snapshot(String namespace) {
        Map<String, BanditArmStats> result = new TreeMap<>();
        state(namespace).forEach((id, arm) -> { synchronized (arm) { result.put(id, arm.snapshot()); } });
        return Map.copyOf(result);
    }
    @Override public void restore(String namespace, Map<String, BanditArmStats> snapshot) {
        Map<String, MutableArm> restored = new ConcurrentHashMap<>();
        snapshot.forEach((id, stats) -> restored.put(id,
                new MutableArm(stats.selections(), stats.rewardObservations(), stats.totalReward())));
        states.put(namespace, restored);
    }
    private static final class MutableArm {
        long selections; long observations; double reward;
        MutableArm() { }
        MutableArm(long selections, long observations, double reward) {
            this.selections = selections; this.observations = observations; this.reward = reward;
        }
        BanditArmStats snapshot() { return new BanditArmStats(selections, observations, reward,
                observations == 0 ? Double.NaN : reward / observations); }
    }
}
