package com.marscommand.mod.mission;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class MissionState {
    public int schemaVersion = 1;
    public long activeMillis;
    public long nextMissionId = 1;
    public String goalId = "";
    public Map<String, Integer> goalProgress = new LinkedHashMap<>();
    public int phaseIndex;
    public Map<String, Long> phaseTotals = new LinkedHashMap<>();
    public Map<String, Player> players = new LinkedHashMap<>();
    public Map<Long, Mission> missions = new LinkedHashMap<>();

    public enum Status { ACTIVE, AWAITING_REPLACEMENT, COMPLETED, EXPIRED }

    public static final class Player {
        public int score;
        public int completions;
        public int refusals;
        public int handovers;
        public int expiries;
        public long nextAssignmentMillis;
        public String callSign = "";
        public String remoteCallSign = "";
        public String identityAuthority = "";
        public boolean arrivalConfirmed;
        public boolean arrivalWelcomed;
        public String remoteWelcomeAuthority = "";
        public String accountName = "";
        public List<String> accountRoles = new ArrayList<>();
    }

    public static final class Ownership {
        public String player;
        public long startedMillis;
        public double startingProgress;
        public double contribution;
        public String endReason = "";
        public int scoreChange;

        public Ownership(String player, long startedMillis, double startingProgress) {
            this.player = player;
            this.startedMillis = startedMillis;
            this.startingProgress = startingProgress;
        }
    }

    public static final class Mission {
        public long id;
        public String templateId;
        public MissionRules.Kind kind;
        public MissionRules.Target target;
        public String item;
        public String briefing;
        public int quantity;
        public int delivered;
        public double progress;
        public double initialDistance;
        public long createdMillis;
        public long durationMillis;
        public long deadlineMillis;
        public long nextHandoverMillis;
        public boolean abbreviated;
        public Status status = Status.ACTIVE;
        public String owner;
        public List<Ownership> history = new ArrayList<>();

        public Ownership currentOwnership() {
            return history.getLast();
        }

        public boolean live() {
            return status == Status.ACTIVE || status == Status.AWAITING_REPLACEMENT;
        }
    }

    public Player player(String uuid) {
        return players.computeIfAbsent(uuid, ignored -> new Player());
    }

    public boolean hasAssignment(String uuid) {
        return missions.values().stream().anyMatch(mission -> mission.status == Status.ACTIVE && uuid.equals(mission.owner));
    }

    public void validate(MissionRules rules) {
        MissionRules.require(schemaVersion == 1, "Unsupported mission-state schema: " + schemaVersion);
        MissionRules.require(activeMillis >= 0 && nextMissionId >= 1 && goalId != null, "Invalid persisted clock/goal");
        MissionRules.require(goalProgress != null && players != null && missions != null, "Missing mission-state maps");
        MissionRules.require(phaseIndex >= 0 && phaseTotals != null && phaseTotals.entrySet().stream()
                .allMatch(entry -> entry.getKey() != null && entry.getValue() != null && entry.getValue() >= 0),
                "Invalid persisted phase state");
        if (!goalId.isEmpty()) {
            MissionRules.require(phaseIndex <= GrandPlan.phases(rules.goal(goalId)).size(), "Invalid persisted phase index");
        }
        if (!goalId.isEmpty()) {
            MissionRules.Goal goal = rules.goal(goalId);
            goalProgress.forEach((template, count) -> MissionRules.require(count != null && count >= 0
                    && goal.requirements().stream().anyMatch(requirement -> requirement.template().equals(template)
                    && count <= requirement.completions()), "Invalid goal progress"));
        }
        players.forEach((uuid, player) -> {
            MissionRules.require(uuid != null, "Null player UUID");
            UUID.fromString(uuid);
            MissionRules.require(player != null && player.nextAssignmentMillis >= 0 && player.completions >= 0
                    && player.refusals >= 0 && player.handovers >= 0 && player.expiries >= 0, "Invalid player statistics");
            MissionRules.require(player.callSign != null && (player.callSign.isEmpty() || player.callSign.matches("[0-9]{4}"))
                    && player.remoteCallSign != null && (player.remoteCallSign.isEmpty() || player.remoteCallSign.matches("[0-9]{4}"))
                    && player.identityAuthority != null && player.remoteWelcomeAuthority != null && player.accountName != null
                    && player.accountName.length() <= 100 && player.accountRoles != null && player.accountRoles.size() <= 64
                    && player.accountRoles.stream().allMatch(role -> role != null && role.length() <= 64),
                    "Invalid persisted player identity");
            MissionRules.require(!player.arrivalConfirmed || (!player.remoteCallSign.isEmpty()
                    && !player.identityAuthority.isEmpty() && !player.accountName.isEmpty()), "Incomplete confirmed player identity");
        });
        List<String> localSigns = players.values().stream().map(player -> player.callSign).filter(sign -> !sign.isEmpty()).toList();
        MissionRules.require(localSigns.stream().distinct().count() == localSigns.size(), "Duplicate local callsign");
        missions.forEach((id, mission) -> {
            MissionRules.require(mission != null && id != null && id == mission.id && id > 0 && id < nextMissionId,
                    "Invalid persisted mission ID");
            rules.template(mission.templateId);
            MissionRules.validateTarget(mission.target);
            MissionRules.require(mission.kind != null && mission.target != null && mission.item != null
                    && mission.briefing != null && mission.briefing.length() <= 2048 && mission.status != null
                    && mission.quantity > 0 && mission.quantity <= rules.maximumQuantity()
                    && mission.delivered >= 0 && mission.delivered <= mission.quantity
                    && Double.isFinite(mission.progress) && mission.progress >= 0 && mission.progress <= 1
                    && Double.isFinite(mission.initialDistance) && mission.initialDistance >= 0
                    && mission.durationMillis >= rules.minimumDurationMillis()
                    && mission.durationMillis <= rules.maximumDurationMillis()
                    && mission.createdMillis >= 0 && mission.createdMillis <= activeMillis
                    && mission.deadlineMillis == mission.createdMillis + mission.durationMillis
                    && mission.nextHandoverMillis >= mission.createdMillis
                    && mission.history != null && !mission.history.isEmpty(), "Invalid persisted mission: " + id);
            MissionRules.require(mission.owner != null, "Missing mission owner");
            UUID.fromString(mission.owner);
            List<String> owners = new ArrayList<>();
            for (Ownership ownership : mission.history) {
                MissionRules.require(ownership != null && ownership.player != null && ownership.endReason != null,
                        "Invalid ownership record");
                UUID.fromString(ownership.player);
                MissionRules.require(!owners.contains(ownership.player) && players.containsKey(ownership.player)
                        && ownership.startedMillis >= mission.createdMillis && ownership.startedMillis <= activeMillis
                        && Double.isFinite(ownership.startingProgress) && ownership.startingProgress >= 0
                        && ownership.startingProgress <= 1 && Double.isFinite(ownership.contribution)
                        && ownership.contribution >= 0 && ownership.contribution <= 1, "Invalid ownership history");
                owners.add(ownership.player);
            }
            MissionRules.require(mission.currentOwnership().player.equals(mission.owner), "Owner/history mismatch");
            MissionRules.require(mission.kind != MissionRules.Kind.DELIVERY
                            || Math.abs(mission.progress - (double) mission.delivered / mission.quantity) < 0.0000001,
                    "Delivery progress/accounting mismatch");
            MissionRules.require(mission.status != Status.ACTIVE || mission.currentOwnership().endReason.isEmpty(),
                    "Active ownership already ended");
            MissionRules.require(mission.status != Status.COMPLETED || (mission.progress == 1
                    && mission.currentOwnership().endReason.equals("completed")), "Invalid completed mission");
        });
    }
}
