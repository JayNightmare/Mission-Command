package com.marscommand.mod.identity;

import com.marscommand.mod.mission.MissionState;
import java.util.HashSet;
import java.util.Locale;
import java.util.UUID;

public final class Callsigns {
    private Callsigns() {}

    public static String local(MissionState state, String uuid) {
        MissionState.Player player = state.player(uuid);
        if (!player.callSign.isEmpty()) {
            return player.callSign;
        }
        var used = new HashSet<String>();
        state.players.values().forEach(record -> used.add(record.callSign));
        int start = Math.floorMod(UUID.fromString(uuid).hashCode(), 10000);
        for (int offset = 0; offset < 10000; offset++) {
            String candidate = String.format(Locale.ROOT, "%04d", (start + offset) % 10000);
            if (used.add(candidate)) {
                player.callSign = candidate;
                return candidate;
            }
        }
        throw new IllegalStateException("All 10,000 permanent local callsigns are reserved");
    }
}
