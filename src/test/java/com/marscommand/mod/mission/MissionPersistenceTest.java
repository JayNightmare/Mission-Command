package com.marscommand.mod.mission;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;

class MissionPersistenceTest {
    private final Gson json = new Gson();
    private final MissionRules rules = MissionRules.defaults(0, 64, 0);

    @Test
    void stateRoundtripPreservesIdentityClockProgressAndScores() {
        MissionState state = new MissionState();
        state.goalId = "supply_network";
        state.activeMillis = 123456;
        state.goalProgress.put("iron_delivery", 1);
        String uuid = UUID.randomUUID().toString();
        state.player(uuid).score = 60;
        state.player(uuid).nextAssignmentMillis = 150000;
        MissionState restored = json.fromJson(json.toJson(state), MissionState.class);
        restored.validate(rules);
        assertEquals(state.activeMillis, restored.activeMillis);
        assertEquals(state.goalId, restored.goalId);
        assertEquals(state.goalProgress, restored.goalProgress);
        assertEquals(60, restored.player(uuid).score);
        assertEquals(150000, restored.player(uuid).nextAssignmentMillis);
    }

    @Test
    void unsupportedOrCorruptStateFailsRatherThanResetting() {
        MissionState state = new MissionState();
        state.schemaVersion = 2;
        assertThrows(IllegalArgumentException.class, () -> state.validate(rules));
        state.schemaVersion = 1;
        state.activeMillis = -1;
        assertThrows(IllegalArgumentException.class, () -> state.validate(rules));
        state.activeMillis = 0;
        state.goalId = "missing_goal";
        assertThrows(IllegalStateException.class, () -> state.validate(rules));
    }

    @Test
    void invalidPoliciesAreRejected() {
        JsonObject object = json.toJsonTree(rules).getAsJsonObject();
        object.addProperty("refusalPenalty", 1);
        assertThrows(IllegalArgumentException.class, () -> json.fromJson(object, MissionRules.class).validate());
        object.addProperty("refusalPenalty", 30);
        object.addProperty("attemptWindowMillis", 1);
        assertThrows(IllegalArgumentException.class, () -> json.fromJson(object, MissionRules.class).validate());
        object.addProperty("attemptWindowMillis", 15000);
        object.getAsJsonArray("ranks").set(0, com.google.gson.JsonNull.INSTANCE);
        assertThrows(IllegalArgumentException.class, () -> json.fromJson(object, MissionRules.class).validate());
    }

    @Test
    void defaultCatalogueRoundtripsWithoutLosingPolicy() {
        MissionRules restored = json.fromJson(json.toJson(rules), MissionRules.class);
        restored.validate();
        assertEquals(rules, restored);
    }

    @Test
    void nbtRoundtripPreservesLiveOwnershipAndDeadline() {
        MoonSavedData data = new MoonSavedData();
        data.state.goalId = "supply_network";
        data.state.activeMillis = 555000;
        data.state.nextMissionId = 2;
        String first = UUID.randomUUID().toString();
        String second = UUID.randomUUID().toString();
        data.state.player(first);
        MissionState.Mission mission = new MissionState.Mission();
        mission.id = 1;
        mission.templateId = "iron_delivery";
        mission.kind = MissionRules.Kind.DELIVERY;
        mission.target = rules.template(mission.templateId).target();
        mission.item = "minecraft:iron_ingot";
        mission.briefing = "Private briefing";
        mission.quantity = 100;
        mission.delivered = 4;
        mission.durationMillis = 600000;
        mission.deadlineMillis = 600000;
        mission.owner = first;
        mission.history.add(new MissionState.Ownership(first, 0, 0));
        data.state.missions.put(1L, mission);
        MissionLogic.progress(mission, 0.04);
        MissionLogic.release(data.state, mission, rules, false);
        MissionLogic.assignReplacement(data.state, mission, second, rules);
        CompoundTag serialized = data.save(new CompoundTag(), null);
        MoonSavedData restored = MoonSavedData.FACTORY.deserializer().apply(serialized, null);
        restored.state.validate(rules);
        MissionState.Mission loaded = restored.state.missions.get(1L);
        assertEquals(second, loaded.owner);
        assertEquals(2, loaded.history.size());
        assertEquals(600000, loaded.deadlineMillis);
        assertEquals(0.04, loaded.progress);
        assertEquals(4, loaded.delivered);
        assertTrue(loaded.abbreviated);
        assertEquals(data.state.player(first).score, restored.state.player(first).score);
    }

    @Test
    void missingSaveFieldsCannotBecomeAFreshPlan() {
        CompoundTag tag = new CompoundTag();
        tag.putString("state", "{}");
        assertThrows(IllegalArgumentException.class, () -> MoonSavedData.FACTORY.deserializer().apply(tag, null));
        assertThrows(IllegalArgumentException.class, () -> MoonSavedData.FACTORY.deserializer().apply(new CompoundTag(), null));
    }
}
