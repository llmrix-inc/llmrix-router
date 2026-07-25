package com.llmrix.model.router.core.routing;

import com.llmrix.model.router.core.api.ChatRequest;
import com.llmrix.model.router.core.candidate.Candidate;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.DoubleAdder;
import java.util.concurrent.atomic.LongAdder;

/** Context score plus UCB exploration; rewards must be supplied by the application. */
public final class ContextualBanditStrategy implements RoutingStrategy {
    private final SemanticClassifier context;
    private final double exploration;
    private final Map<String, ArmState> arms = new ConcurrentHashMap<>();
    private final LongAdder totalSelections = new LongAdder();
    private final BanditStateStore stateStore;
    private final String namespace;

    public ContextualBanditStrategy(SemanticClassifier context, double exploration) {
        this(context, exploration, null, "default");
    }

    public ContextualBanditStrategy(SemanticClassifier context, double exploration,
                                    BanditStateStore stateStore, String namespace) {
        if (context == null) throw new IllegalArgumentException("context classifier is required");
        if (!Double.isFinite(exploration) || exploration < 0) {
            throw new IllegalArgumentException("exploration must be finite and >= 0");
        }
        this.context = context;
        this.exploration = exploration;
        this.stateStore = stateStore;
        this.namespace = namespace == null || namespace.isBlank() ? "default" : namespace;
    }

    @Override public Candidate select(ChatRequest request, List<CandidateSnapshot> candidates) {
        if (candidates.isEmpty()) throw new NoCandidateException("no candidate is available");
        Map<String, Double> contextScores = context.score(request, List.copyOf(candidates));
        long total = Math.max(1, stateStore == null ? totalSelections.sum() : stateStore.totalSelections(namespace));
        CandidateSnapshot selected = candidates.stream().max(java.util.Comparator.comparingDouble(candidate -> {
            ArmState arm = arms.computeIfAbsent(candidate.id(), ignored -> new ArmState());
            BanditArmStats shared = stateStore == null ? null : stateStore.arm(namespace, candidate.id());
            long count = shared == null ? arm.selections.sum() : shared.selections();
            if (count == 0) return Double.POSITIVE_INFINITY;
            double prior = contextScores == null ? 0 : contextScores.getOrDefault(candidate.id(), 0d);
            if (!Double.isFinite(prior)) prior = 0;
            long observations = shared == null ? arm.rewardObservations.sum() : shared.rewardObservations();
            double averageReward = observations == 0 ? 0 : (shared == null ? arm.rewards.sum() / observations : shared.averageReward());
            return prior + averageReward
                    + exploration * Math.sqrt(Math.log(total + 1d) / count);
        })).orElseThrow();
        if (stateStore == null) { arms.computeIfAbsent(selected.id(), ignored -> new ArmState()).selections.increment(); totalSelections.increment(); }
        else stateStore.recordSelection(namespace, selected.id());
        return selected.candidate();
    }

    public void observe(String candidateId, double reward) {
        if (candidateId == null || candidateId.isBlank()) throw new IllegalArgumentException("candidateId must not be blank");
        if (!Double.isFinite(reward) || reward < 0 || reward > 1) {
            throw new IllegalArgumentException("reward must be between 0 and 1");
        }
        if (stateStore != null) stateStore.recordReward(namespace, candidateId, reward);
        else { ArmState arm = arms.computeIfAbsent(candidateId, ignored -> new ArmState()); arm.rewards.add(reward); arm.rewardObservations.increment(); }
    }

    public Map<String, BanditArmStats> snapshot() {
        if (stateStore != null) return stateStore.snapshot(namespace);
        Map<String, BanditArmStats> snapshot = new java.util.TreeMap<>();
        arms.forEach((id, arm) -> {
            long selections = arm.selections.sum();
            long observations = arm.rewardObservations.sum();
            double totalReward = arm.rewards.sum();
            snapshot.put(id, new BanditArmStats(selections, observations, totalReward,
                    observations == 0 ? Double.NaN : totalReward / observations));
        });
        return Map.copyOf(snapshot);
    }

    public void restore(Map<String, BanditArmStats> snapshot) {
        if (snapshot == null) throw new IllegalArgumentException("snapshot must not be null");
        validateSnapshot(snapshot);
        if (stateStore != null) { stateStore.restore(namespace, snapshot); return; }
        Map<String, ArmState> restored = new ConcurrentHashMap<>();
        long selectionsTotal = 0;
        for (var entry : snapshot.entrySet()) {
            String id = entry.getKey();
            BanditArmStats stats = entry.getValue();
            if (id == null || id.isBlank() || stats == null || stats.selections() < 0
                    || stats.rewardObservations() < 0 || stats.rewardObservations() > stats.selections()
                    || !Double.isFinite(stats.totalReward()) || stats.totalReward() < 0
                    || stats.totalReward() > stats.rewardObservations()) {
                throw new IllegalArgumentException("invalid bandit snapshot entry: " + id);
            }
            ArmState arm = new ArmState();
            arm.selections.add(stats.selections());
            arm.rewardObservations.add(stats.rewardObservations());
            arm.rewards.add(stats.totalReward());
            restored.put(id, arm);
            selectionsTotal = Math.addExact(selectionsTotal, stats.selections());
        }
        arms.clear();
        arms.putAll(restored);
        totalSelections.reset();
        totalSelections.add(selectionsTotal);
    }

    private static void validateSnapshot(Map<String, BanditArmStats> snapshot) {
        snapshot.forEach((id, stats) -> {
            if (id == null || id.isBlank() || stats == null || stats.selections() < 0
                    || stats.rewardObservations() < 0 || stats.rewardObservations() > stats.selections()
                    || !Double.isFinite(stats.totalReward()) || stats.totalReward() < 0
                    || stats.totalReward() > stats.rewardObservations()) {
                throw new IllegalArgumentException("invalid bandit snapshot entry: " + id);
            }
        });
    }

    private static final class ArmState {
        private final LongAdder selections = new LongAdder();
        private final DoubleAdder rewards = new DoubleAdder();
        private final LongAdder rewardObservations = new LongAdder();
    }
}
