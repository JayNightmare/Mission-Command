package com.marscommand.mod.mission;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParser;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.saveddata.SavedData;

public final class MoonSavedData extends SavedData {
    public static final Gson JSON = new GsonBuilder().setPrettyPrinting().create();
    public static final Factory<MoonSavedData> FACTORY = new Factory<>(MoonSavedData::new, MoonSavedData::load);
    public final MissionState state;

    public MoonSavedData() {
        state = new MissionState();
    }

    private MoonSavedData(MissionState state) {
        this.state = state;
    }

    private static MoonSavedData load(CompoundTag tag, HolderLookup.Provider lookup) {
        MissionRules.require(tag.contains("state", 8), "Mars Command save is missing its state");
        var value = JsonParser.parseString(tag.getString("state"));
        MissionRules.require(value.isJsonObject(), "Mars Command state must be a JSON object");
        for (String field : java.util.List.of("schemaVersion", "activeMillis", "nextMissionId",
                "goalId", "goalProgress", "players", "missions")) {
            MissionRules.require(value.getAsJsonObject().has(field)
                    && !value.getAsJsonObject().get(field).isJsonNull(), "Missing saved state field: " + field);
        }
        MissionState state = JSON.fromJson(value, MissionState.class);
        MissionRules.require(state != null && state.schemaVersion == 1, "Invalid/unsupported Mars Command save schema");
        return new MoonSavedData(state);
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider lookup) {
        tag.putString("state", JSON.toJson(state));
        return tag;
    }
}
