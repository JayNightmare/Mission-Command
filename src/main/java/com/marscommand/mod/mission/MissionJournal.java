package com.marscommand.mod.mission;

import java.util.Comparator;
import java.util.List;

public final class MissionJournal {
    public static final int PAGE_SIZE = 16;

    public record Entry(long id, MissionRules.Kind kind, boolean completed, boolean abbreviated,
                        String briefing, String item, int quantity, int delivered, double progress,
                        MissionRules.Target target, long remainingMillis) {}

    private MissionJournal() {}

    public static List<Entry> entries(MissionState state, String player) {
        return state.missions.values().stream()
                .filter(mission -> visible(mission, player))
                .sorted(Comparator.comparingLong(mission -> mission.id))
                .map(mission -> entry(mission, state.activeMillis)).toList();
    }

    public static boolean visible(MissionState.Mission mission, String player) {
        return mission.owner.equals(player) && (mission.status == MissionState.Status.ACTIVE
                || mission.status == MissionState.Status.COMPLETED);
    }

    public static Entry entry(MissionState.Mission mission, long activeMillis) {
        return new Entry(mission.id, mission.kind, mission.status == MissionState.Status.COMPLETED,
                mission.abbreviated, mission.abbreviated ? "" : mission.briefing,
                mission.item, mission.quantity, mission.delivered, mission.progress, mission.target,
                mission.status == MissionState.Status.ACTIVE ? Math.max(0, mission.deadlineMillis - activeMillis) : 0);
    }

    public static int pageCount(int size) {
        return Math.max(1, (size + PAGE_SIZE - 1) / PAGE_SIZE);
    }

    public static List<Entry> page(List<Entry> entries, int page) {
        MissionRules.require(page >= 0 && page < pageCount(entries.size()), "Invalid mission journal page");
        int start = Math.min(entries.size(), page * PAGE_SIZE);
        return List.copyOf(entries.subList(start, Math.min(entries.size(), start + PAGE_SIZE)));
    }
}
