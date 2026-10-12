package com.marscommand.mod.mission;

import static org.junit.jupiter.api.Assertions.*;

import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class MissionLogicTest {
    private MissionRules rules;
    private MissionState state;
    private MissionState.Mission mission;
    private String first;
    private String second;

    @BeforeEach
    void setup() {
        rules = MissionRules.defaults(0, 64, 0);
        rules.validate();
        first = UUID.randomUUID().toString();
        second = UUID.randomUUID().toString();
        state = new MissionState();
        state.goalId = "supply_network";
        state.nextMissionId = 2;
        state.player(first);
        mission = new MissionState.Mission();
        mission.id = 1;
        mission.templateId = "iron_delivery";
        mission.kind = MissionRules.Kind.DELIVERY;
        mission.target = rules.template(mission.templateId).target();
        mission.item = "minecraft:iron_ingot";
        mission.briefing = "Private mission, not a plan";
        mission.quantity = 100;
        mission.durationMillis = 600000;
        mission.deadlineMillis = 600000;
        mission.owner = first;
        mission.history.add(new MissionState.Ownership(first, 0, 0));
        state.missions.put(1L, mission);
    }

    @ParameterizedTest
    @CsvSource({"20,false,18", "3,false,1", "20,true,19", "2,true,1", "1,true,1", "0.5,true,0.5"})
    void strikesAreExactAndNeverLethal(float before, boolean emergency, float after) {
        assertEquals(after, MissionLogic.strikeHealth(before, emergency));
    }

    @Test
    void invalidHealthIsExplicitlyRejected() {
        assertThrows(IllegalArgumentException.class, () -> MissionLogic.strikeHealth(Float.NaN, false));
        assertThrows(IllegalArgumentException.class, () -> MissionLogic.strikeHealth(0, true));
    }

    @ParameterizedTest
    @CsvSource({"539999,0,false", "540000,0,true", "540000,0.049999,true",
            "540000,0.05,false", "540000,0.06,false", "599999,0,true", "600000,0,false"})
    void handoverThresholdsUseExactProgress(long now, double progress, boolean expected) {
        mission.progress = progress;
        assertEquals(expected, MissionLogic.handoverDue(mission, now));
    }

    @Test
    void replacementRetainsDeadlineAndGetsAttemptWindow() {
        state.activeMillis = 540000;
        MissionLogic.progress(mission, 0.04);
        assertTrue(MissionLogic.release(state, mission, rules, false));
        assertTrue(MissionLogic.assignReplacement(state, mission, second, rules));
        assertEquals(600000, mission.deadlineMillis);
        assertEquals(600000, mission.durationMillis);
        assertEquals(0.04, mission.progress);
        assertEquals(second, mission.owner);
        assertTrue(mission.abbreviated);
        assertFalse(MissionLogic.handoverDue(mission, 554999));
        assertTrue(MissionLogic.handoverDue(mission, 555000));
        assertTrue(state.hasAssignment(second));
    }

    @Test
    void repeatedHandoversNeverReturnToPriorOwners() {
        state.activeMillis = 540000;
        MissionLogic.release(state, mission, rules, false);
        assertFalse(MissionLogic.assignReplacement(state, mission, first, rules));
        assertTrue(MissionLogic.assignReplacement(state, mission, second, rules));
        state.activeMillis += rules.attemptWindowMillis();
        assertTrue(MissionLogic.release(state, mission, rules, false));
        assertFalse(MissionLogic.assignReplacement(state, mission, first, rules));
        assertFalse(MissionLogic.assignReplacement(state, mission, second, rules));
        assertTrue(MissionLogic.assignReplacement(state, mission, UUID.randomUUID().toString(), rules));
        assertEquals(3, mission.history.size());
    }

    @Test
    void refusalPenaltyOccursOnceAndDoesNotCancelObligation() {
        assertTrue(MissionLogic.release(state, mission, rules, true));
        assertFalse(MissionLogic.release(state, mission, rules, true));
        assertFalse(MissionLogic.release(state, mission, rules, false));
        assertEquals(-rules.refusalPenalty(), state.player(first).score);
        assertEquals(1, state.player(first).refusals);
        assertTrue(mission.live());
        assertFalse(state.hasAssignment(first));
        assertEquals(rules.rank(-rules.refusalPenalty()).cooldownMillis(), state.player(first).nextAssignmentMillis);
        assertTrue(MissionLogic.assignReplacement(state, mission, second, rules));
    }

    @Test
    void progressAndContributionsAreMonotonicAndNotRecounted() {
        MissionLogic.progress(mission, 0.03);
        MissionLogic.progress(mission, 0.01);
        MissionLogic.progress(mission, 0.03);
        assertEquals(0.03, mission.progress);
        assertEquals(0.03, mission.currentOwnership().contribution);
        MissionLogic.release(state, mission, rules, false);
        MissionLogic.assignReplacement(state, mission, second, rules);
        MissionLogic.progress(mission, 0.5);
        assertEquals(0.47, mission.currentOwnership().contribution, 0.000001);
    }

    @Test
    void completionScoresAndCreditsPlanOnlyOnce() {
        MissionLogic.progress(mission, 1);
        assertTrue(MissionLogic.complete(state, mission, rules));
        assertFalse(MissionLogic.complete(state, mission, rules));
        assertFalse(MissionLogic.release(state, mission, rules, true));
        assertEquals(rules.completionPoints(), state.player(first).score);
        assertEquals(1, state.goalProgress.get("iron_delivery"));
        assertEquals(1, state.player(first).completions);
        assertEquals(rules.rank(rules.completionPoints()).cooldownMillis(), state.player(first).nextAssignmentMillis);
    }

    @Test
    void completionAtDeadlineIsRejectedAndExpiresOnce() {
        MissionLogic.progress(mission, 1);
        state.activeMillis = mission.deadlineMillis;
        assertFalse(MissionLogic.complete(state, mission, rules));
        assertTrue(MissionLogic.expire(state, mission, rules));
        assertFalse(MissionLogic.expire(state, mission, rules));
        assertEquals(MissionState.Status.EXPIRED, mission.status);
        assertTrue(state.goalProgress.isEmpty());
    }

    @Test
    void pendingReplacementExpiryDoesNotDoublePenalizeOldOwner() {
        MissionLogic.release(state, mission, rules, true);
        state.activeMillis = mission.deadlineMillis;
        assertTrue(MissionLogic.expire(state, mission, rules));
        assertEquals(-rules.refusalPenalty(), state.player(first).score);
        assertFalse(MissionLogic.assignReplacement(state, mission, second, rules));
    }

    @Test
    void penaltiesReflectAttemptAndScoresCannotOverflow() {
        assertEquals(10, MissionLogic.attemptPenalty(10, 0));
        assertEquals(5, MissionLogic.attemptPenalty(10, 0.5));
        assertEquals(0, MissionLogic.attemptPenalty(10, 1));
        assertEquals(Integer.MAX_VALUE, MissionLogic.adjustedScore(Integer.MAX_VALUE, 10));
        assertEquals(Integer.MIN_VALUE, MissionLogic.adjustedScore(Integer.MIN_VALUE, -30));
    }

    @Test
    void smallAttemptReducesAutomaticHandoverPenalty() {
        MissionLogic.progress(mission, 0.03);
        assertTrue(MissionLogic.release(state, mission, rules, false));
        assertTrue(state.player(first).score > -rules.handoverPenalty());
        assertTrue(state.player(first).score < 0);
    }

    @Test
    void readinessRequiresRecoveryEquipmentAndReachability() {
        assertEquals(1, MissionLogic.readiness(rules, 1, 1, true, true));
        assertEquals(0, MissionLogic.readiness(rules, 0.5, 1, true, true));
        assertEquals(0, MissionLogic.readiness(rules, 1, 0.5, true, true));
        assertEquals(0, MissionLogic.readiness(rules, 1, 1, false, true));
        assertEquals(0, MissionLogic.readiness(rules, 1, 1, true, false));
        assertTrue(MissionLogic.readiness(rules, 0.9, 0.9, true, true) >= rules.readinessThreshold());
    }

    @Test
    void dimensionCostUsesConfiguredScalesAndCrossingCost() {
        MissionRules.Target target = new MissionRules.Target("minecraft:overworld", 80, 64, 0, 8);
        assertEquals(80, MissionLogic.distance(target, "minecraft:overworld", 0, 64, 0, rules));
        assertEquals(rules.crossDimensionCost(), MissionLogic.distance(target, "minecraft:the_nether", 10, 64, 0, rules));
        assertTrue(MissionLogic.distance(target, "minecraft:the_end", 0, 64, 0, rules) > 80);
    }

    @Test
    void ranksCoverNegativeScoresAndExactBoundaries() {
        assertEquals("Initiate", rules.rank(-1).name());
        assertEquals("Operative", rules.rank(0).name());
        assertEquals("Pathfinder", rules.rank(50).name());
        assertEquals("Commander", rules.rank(150).name());
        assertTrue(rules.rank(150).cooldownMillis() < rules.rank(0).cooldownMillis());
        assertTrue(rules.rank(150).quantityMultiplier() > rules.rank(0).quantityMultiplier());
        assertTrue(rules.rank(150).durationMultiplier() < rules.rank(0).durationMultiplier());
    }
}
