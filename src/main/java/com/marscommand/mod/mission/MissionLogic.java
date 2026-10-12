package com.marscommand.mod.mission;

import static com.marscommand.mod.mission.MissionState.Status;

public final class MissionLogic {
    private MissionLogic() {}

    public static float strikeHealth(float health, boolean emergency) {
        if (!Float.isFinite(health) || health <= 0) {
            throw new IllegalArgumentException("Invalid strike health");
        }
        return Math.min(health, Math.max(1, health - (emergency ? 1 : 2)));
    }

    public static double readiness(MissionRules rules, double healthFraction, double hungerFraction,
                                   boolean equipped, boolean reachable) {
        if (!reachable || !equipped || healthFraction < rules.minimumHealthFraction()
                || hungerFraction * 20 < rules.minimumFood()) {
            return 0;
        }
        return (rules.healthWeight() * Math.clamp(healthFraction, 0, 1)
                + rules.hungerWeight() * Math.clamp(hungerFraction, 0, 1)
                + rules.equipmentWeight()) / (rules.healthWeight() + rules.hungerWeight() + rules.equipmentWeight());
    }

    public static double distance(MissionRules.Target target, String dimension, double x, double y, double z,
                                  MissionRules rules) {
        if (target.dimension().equals(dimension)) {
            return Math.sqrt(square(x - target.x()) + square(y - target.y()) + square(z - target.z()));
        }
        double sourceScale = rules.dimensionScales().getOrDefault(dimension, 1.0);
        double targetScale = rules.dimensionScales().getOrDefault(target.dimension(), 1.0);
        return rules.crossDimensionCost() + Math.sqrt(square(x * sourceScale - target.x() * targetScale)
                + square(z * sourceScale - target.z() * targetScale) + square(y - target.y()));
    }

    public static boolean handoverDue(MissionState.Mission mission, long now) {
        return mission.status == Status.ACTIVE && now < mission.deadlineMillis
                && now >= mission.nextHandoverMillis && mission.progress < 0.05
                && (double) (mission.deadlineMillis - now) <= mission.durationMillis * 0.1;
    }

    public static int attemptPenalty(int maximum, double contribution) {
        return (int) Math.ceil(maximum * (1 - Math.clamp(contribution, 0, 1)));
    }

    public static int adjustedScore(int score, int delta) {
        return (int) Math.clamp((long) score + delta, Integer.MIN_VALUE, Integer.MAX_VALUE);
    }

    public static void progress(MissionState.Mission mission, double value) {
        if (!Double.isFinite(value) || value < 0 || value > 1) {
            throw new IllegalArgumentException("Invalid mission progress");
        }
        double previous = mission.progress;
        mission.progress = Math.max(previous, value);
        mission.currentOwnership().contribution = Math.min(1,
                mission.currentOwnership().contribution + mission.progress - previous);
    }

    public static boolean complete(MissionState state, MissionState.Mission mission, MissionRules rules) {
        if (mission.status != Status.ACTIVE || mission.progress < 1 || state.activeMillis >= mission.deadlineMillis) {
            return false;
        }
        MissionRules.Requirement requirement = rules.goal(state.goalId).requirements().stream()
                .filter(candidate -> candidate.template().equals(mission.templateId)).findFirst()
                .orElseThrow(() -> new IllegalStateException("Mission does not belong to the selected plan"));
        int previous = state.goalProgress.getOrDefault(mission.templateId, 0);
        if (previous >= requirement.completions()) {
            throw new IllegalStateException("Mission would over-credit a fulfilled plan requirement");
        }
        mission.status = Status.COMPLETED;
        MissionState.Player player = state.player(mission.owner);
        player.completions++;
        player.score = adjustedScore(player.score, rules.completionPoints());
        cooldown(state, player, rules);
        mission.currentOwnership().endReason = "completed";
        mission.currentOwnership().scoreChange = rules.completionPoints();
        state.goalProgress.put(mission.templateId, previous + 1);
        return true;
    }

    public static boolean release(MissionState state, MissionState.Mission mission, MissionRules rules,
                                  boolean refused) {
        if (mission.status != Status.ACTIVE || state.activeMillis >= mission.deadlineMillis) {
            return false;
        }
        int penalty = refused ? rules.refusalPenalty()
                // Normalize against the handover cutoff so small verified attempts affect integer penalties.
                : attemptPenalty(rules.handoverPenalty(), mission.currentOwnership().contribution / 0.05);
        MissionState.Player player = state.player(mission.owner);
        player.score = adjustedScore(player.score, -penalty);
        cooldown(state, player, rules);
        if (refused) {
            player.refusals++;
        } else {
            player.handovers++;
        }
        mission.currentOwnership().endReason = refused ? "refused" : "handover";
        mission.currentOwnership().scoreChange = -penalty;
        mission.status = Status.AWAITING_REPLACEMENT;
        return true;
    }

    public static boolean assignReplacement(MissionState state, MissionState.Mission mission, String player,
                                            MissionRules rules) {
        if (mission.status != Status.AWAITING_REPLACEMENT || state.activeMillis >= mission.deadlineMillis
                || mission.history.stream().anyMatch(ownership -> ownership.player.equals(player))) {
            return false;
        }
        state.player(player);
        mission.owner = player;
        mission.abbreviated = true;
        mission.history.add(new MissionState.Ownership(player, state.activeMillis, mission.progress));
        mission.nextHandoverMillis = state.activeMillis + rules.attemptWindowMillis();
        mission.status = Status.ACTIVE;
        return true;
    }

    public static boolean expire(MissionState state, MissionState.Mission mission, MissionRules rules) {
        if (!mission.live() || state.activeMillis < mission.deadlineMillis) {
            return false;
        }
        if (mission.status == Status.ACTIVE) {
            int penalty = attemptPenalty(rules.expiryPenalty(), mission.currentOwnership().contribution);
            MissionState.Player player = state.player(mission.owner);
            player.expiries++;
            player.score = adjustedScore(player.score, -penalty);
            cooldown(state, player, rules);
            mission.currentOwnership().endReason = "expired";
            mission.currentOwnership().scoreChange = -penalty;
        }
        mission.status = Status.EXPIRED;
        return true;
    }

    private static double square(double value) {
        return value * value;
    }

    private static void cooldown(MissionState state, MissionState.Player player, MissionRules rules) {
        player.nextAssignmentMillis = Math.max(player.nextAssignmentMillis,
                state.activeMillis + rules.rank(player.score).cooldownMillis());
    }
}
