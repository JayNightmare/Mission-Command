package com.marscommand.mod.mission;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Pure phase accounting for phased goals; mission verification stays in {@link MoonDirector}. */
public final class GrandPlan {
    private GrandPlan() {}

    public static List<MissionRules.Phase> phases(MissionRules.Goal goal) {
        return goal.phases() == null ? List.of() : goal.phases();
    }

    public static boolean phased(MissionRules.Goal goal) {
        return !phases(goal).isEmpty();
    }

    /** Returns null once every phase is complete. */
    public static MissionRules.Phase current(MissionRules rules, MissionState state) {
        List<MissionRules.Phase> phases = phases(rules.goal(state.goalId));
        return state.phaseIndex < phases.size() ? phases.get(state.phaseIndex) : null;
    }

    public static void credit(MissionState state, String item, long amount) {
        MissionRules.require(amount > 0, "Invalid resource credit");
        state.phaseTotals.merge(item, amount, Math::addExact);
    }

    /** Advances through every phase whose needs are met; surplus carries into the next phase. */
    public static boolean advance(MissionRules rules, MissionState state) {
        boolean advanced = false;
        MissionRules.Phase phase = current(rules, state);
        while (phase != null && met(rules, state, phase)) {
            for (MissionRules.ResourceNeed need : phase.resources()) {
                state.phaseTotals.merge(rules.template(need.template()).item(), -(long) need.quantity(), Long::sum);
            }
            state.phaseIndex++;
            advanced = true;
            phase = current(rules, state);
        }
        return advanced;
    }

    /** Template id to quantity still unassigned in the current phase, after delivered and outstanding supplies. */
    public static Map<String, Long> shortfalls(MissionRules rules, MissionState state) {
        Map<String, Long> result = new LinkedHashMap<>();
        MissionRules.Phase phase = current(rules, state);
        if (phase == null) {
            return result;
        }
        for (MissionRules.ResourceNeed need : phase.resources()) {
            String item = rules.template(need.template()).item();
            long outstanding = state.missions.values().stream()
                    .filter(mission -> mission.live() && mission.item.equals(item))
                    .mapToLong(mission -> mission.quantity - mission.delivered).sum();
            long remaining = need.quantity() - state.phaseTotals.getOrDefault(item, 0L) - outstanding;
            if (remaining > 0) {
                result.put(need.template(), remaining);
            }
        }
        return result;
    }

    public static boolean acceptable(MissionRules rules, Map<String, Long> shortfalls, MissionPlanner.Proposal proposal) {
        return proposal != null && proposal.templateId() != null && shortfalls.containsKey(proposal.templateId())
                && proposal.quantity() >= 1
                && proposal.quantity() <= Math.min(rules.maximumQuantity(), shortfalls.get(proposal.templateId()))
                && (proposal.briefing() == null || proposal.briefing().length() <= 2048);
    }

    private static boolean met(MissionRules rules, MissionState state, MissionRules.Phase phase) {
        return phase.resources().stream().allMatch(need ->
                state.phaseTotals.getOrDefault(rules.template(need.template()).item(), 0L) >= need.quantity());
    }
}
