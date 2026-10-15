package com.marscommand.mod.mission;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.Gson;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class GrandPlanTest {
    private final Gson json = new Gson();
    private MissionRules rules;
    private MissionState state;

    @BeforeEach
    void setup() {
        rules = MissionRules.defaults(0, 64, 0);
        rules.validate();
        state = new MissionState();
        state.goalId = "space_elevator";
    }

    @Test
    void defaultsDefineAPhasedSpaceElevator() {
        assertTrue(GrandPlan.phased(rules.goal("space_elevator")));
        assertFalse(GrandPlan.phased(rules.goal("supply_network")));
        assertEquals("foundations", GrandPlan.current(rules, state).id());
    }

    @Test
    void shortfallsShrinkWithDeliveriesAndOutstandingMissions() {
        assertEquals(Map.of("iron_delivery", 256L, "copper_delivery", 128L), GrandPlan.shortfalls(rules, state));
        GrandPlan.credit(state, "minecraft:iron_ingot", 100);
        MissionState.Mission open = mission(1, "iron_delivery", "minecraft:iron_ingot", 50, 10);
        state.missions.put(1L, open);
        assertEquals(256L - 100 - 40, GrandPlan.shortfalls(rules, state).get("iron_delivery"));
    }

    @Test
    void phaseAdvancesOnlyWhenEveryNeedIsMetAndSurplusCarriesOver() {
        GrandPlan.credit(state, "minecraft:iron_ingot", 300);
        assertFalse(GrandPlan.advance(rules, state));
        GrandPlan.credit(state, "minecraft:copper_ingot", 128);
        assertTrue(GrandPlan.advance(rules, state));
        assertEquals(1, state.phaseIndex);
        assertEquals(44L, state.phaseTotals.get("minecraft:iron_ingot"));
        assertEquals("power", GrandPlan.current(rules, state).id());
    }

    @Test
    void finishedPlanHasNoFurtherNeeds() {
        state.phaseIndex = 3;
        assertNull(GrandPlan.current(rules, state));
        assertTrue(GrandPlan.shortfalls(rules, state).isEmpty());
        state.validate(rules);
        state.phaseIndex = 4;
        assertThrows(IllegalArgumentException.class, () -> state.validate(rules));
    }

    @Test
    void heuristicTargetsTheLargestShortfallWithinTemplateChunk() {
        Optional<MissionPlanner.Proposal> proposal = new MissionPlanner.Heuristic()
                .propose(rules, new MissionPlanner.Context("foundations", 1, GrandPlan.shortfalls(rules, state)));
        assertEquals("iron_delivery", proposal.orElseThrow().templateId());
        assertEquals(16, proposal.orElseThrow().quantity());
        assertTrue(new MissionPlanner.Heuristic().propose(rules,
                new MissionPlanner.Context("foundations", 1, Map.of())).isEmpty());
    }

    @Test
    void remoteResponsesAreParsedStrictly() {
        MissionPlanner.Proposal proposal = RemotePlanner.parse(
                "{\"proposal\":{\"template\":\"iron_delivery\",\"quantity\":8,\"briefing\":\"Bring iron.\"},\"source\":\"model\"}");
        assertEquals(new MissionPlanner.Proposal("iron_delivery", 8, "Bring iron."), proposal);
        for (String bad : List.of("[]", "{}", "not json", "{\"proposal\":{\"template\":1,\"quantity\":8}}",
                "{\"proposal\":{\"template\":\"a\",\"quantity\":-1}}",
                "{\"proposal\":{\"template\":\"a\",\"quantity\":1.5}}",
                "{\"proposal\":{\"template\":\"a\",\"quantity\":1,\"briefing\":\"x\\u0007\"}}")) {
            assertThrows(IllegalArgumentException.class, () -> RemotePlanner.parse(bad), bad);
        }
    }

    @Test
    void unsafeProposalsAreRejected() {
        Map<String, Long> shortfalls = Map.of("iron_delivery", 20L);
        assertTrue(GrandPlan.acceptable(rules, shortfalls, new MissionPlanner.Proposal("iron_delivery", 20, null)));
        assertFalse(GrandPlan.acceptable(rules, shortfalls, new MissionPlanner.Proposal("iron_delivery", 21, null)));
        assertFalse(GrandPlan.acceptable(rules, shortfalls, new MissionPlanner.Proposal("iron_delivery", 0, null)));
        assertFalse(GrandPlan.acceptable(rules, shortfalls, new MissionPlanner.Proposal("gold_delivery", 1, null)));
        assertFalse(GrandPlan.acceptable(rules, shortfalls, new MissionPlanner.Proposal("iron_delivery", 1, "x".repeat(2049))));
        assertFalse(GrandPlan.acceptable(rules, shortfalls, null));
    }

    @Test
    void phasedMissionsCompleteWithoutLegacyRequirementAccounting() {
        String owner = UUID.randomUUID().toString();
        state.player(owner);
        state.nextMissionId = 2;
        MissionState.Mission mission = mission(1, "iron_delivery", "minecraft:iron_ingot", 16, 16);
        mission.owner = owner;
        mission.progress = 1;
        mission.history.add(new MissionState.Ownership(owner, 0, 0));
        state.missions.put(1L, mission);
        assertTrue(MissionLogic.complete(state, mission, rules));
        assertTrue(state.goalProgress.isEmpty());
        state.validate(rules);
    }

    @Test
    void invalidPhasesAreRejectedAndLegacyConfigsStillLoad() {
        var object = json.toJsonTree(rules).getAsJsonObject();
        var goals = object.getAsJsonArray("goals");
        goals.get(2).getAsJsonObject().getAsJsonArray("phases").get(0).getAsJsonObject()
                .getAsJsonArray("resources").get(0).getAsJsonObject().addProperty("template", "survey_point");
        assertThrows(IllegalArgumentException.class, () -> json.fromJson(object, MissionRules.class).validate());
        var legacy = json.toJsonTree(rules).getAsJsonObject();
        legacy.getAsJsonArray("goals").forEach(goal -> goal.getAsJsonObject().remove("phases"));
        legacy.getAsJsonArray("goals").remove(2);
        MissionRules loaded = json.fromJson(legacy, MissionRules.class);
        loaded.validate();
        assertFalse(GrandPlan.phased(loaded.goal("supply_network")));
    }

    @Test
    void oldSavesWithoutPhaseFieldsLoad() {
        var old = json.toJsonTree(state).getAsJsonObject();
        old.remove("phaseIndex");
        old.remove("phaseTotals");
        MissionState restored = json.fromJson(old, MissionState.class);
        restored.validate(rules);
        assertEquals(0, restored.phaseIndex);
        assertEquals(List.of(), List.copyOf(restored.phaseTotals.keySet()));
    }

    private MissionState.Mission mission(long id, String template, String item, int quantity, int delivered) {
        MissionState.Mission mission = new MissionState.Mission();
        mission.id = id;
        mission.templateId = template;
        mission.kind = MissionRules.Kind.DELIVERY;
        mission.target = rules.template(template).target();
        mission.item = item;
        mission.briefing = "Test";
        mission.quantity = quantity;
        mission.delivered = delivered;
        mission.durationMillis = 600000;
        mission.deadlineMillis = 600000;
        mission.owner = UUID.randomUUID().toString();
        return mission;
    }
}
