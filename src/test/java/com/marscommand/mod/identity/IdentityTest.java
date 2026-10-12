package com.marscommand.mod.identity;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.Gson;
import com.marscommand.mod.mission.MissionState;
import com.marscommand.mod.network.ArrivalStatePayload;
import java.util.HashSet;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class IdentityTest {
    @Test
    void localCallsignsAreFourDigitUniqueStableAndPreserveLeadingZeros() {
        MissionState state = new MissionState();
        var signs = new HashSet<String>();
        for (int index = 0; index < 100; index++) {
            String uuid = UUID.randomUUID().toString();
            String sign = Callsigns.local(state, uuid);
            assertTrue(sign.matches("[0-9]{4}"));
            assertTrue(signs.add(sign));
            assertEquals(sign, Callsigns.local(state, uuid));
        }
        String uuid = UUID.randomUUID().toString();
        state.player(uuid).callSign = "0007";
        assertEquals("0007", Callsigns.local(state, uuid));
    }

    @Test
    void exhaustedLocalRegistryNeverRecyclesAnId() {
        MissionState state = new MissionState();
        for (int index = 0; index < 10000; index++) {
            state.player(UUID.randomUUID().toString()).callSign = String.format(java.util.Locale.ROOT, "%04d", index);
        }
        assertThrows(IllegalStateException.class, () -> Callsigns.local(state, UUID.randomUUID().toString()));
    }

    @Test
    void cachedConfirmationIsBoundToTheSameApiAuthority() {
        MissionState.Player player = new MissionState.Player();
        player.remoteCallSign = "0427";
        player.identityAuthority = "https://api.example";
        assertFalse(ArrivalService.confirmedFor(player, "https://api.example"));
        player.arrivalConfirmed = true;
        assertTrue(ArrivalService.confirmedFor(player, "https://api.example"));
        assertFalse(ArrivalService.confirmedFor(player, "https://different.example"));
    }

    @Test
    void settingsRejectUnsafeOriginsAndOnlyAllowDeliberateLoopbackHttp() {
        assertEquals("https://api.example", new IdentitySettings(true, "https://api.example/", "https://site.example").validated(false).apiOrigin());
        for (String url : new String[]{"http://api.example", "https://api.example/path", "https://user:secret@api.example",
                "https://api.example?token=secret", "//api.example", "https://api.example#secret"}) {
            assertThrows(IllegalArgumentException.class, () -> new IdentitySettings(true, url, "https://site.example").validated(false));
        }
        assertThrows(IllegalArgumentException.class, () -> new IdentitySettings(true, "http://localhost:8000", "http://localhost:5173").validated(false));
        assertEquals("http://localhost:8000",
                new IdentitySettings(true, "http://localhost:8000", "http://localhost:5173").validated(true).apiOrigin());
    }

    @Test
    void legacySavedPlayersReceiveIdentityDefaultsWithoutResettingScore() {
        MissionState.Player restored = new Gson().fromJson("{\"score\":50,\"completions\":5}", MissionState.Player.class);
        assertEquals(50, restored.score);
        assertEquals(5, restored.completions);
        assertEquals("", restored.callSign);
        assertEquals("", restored.identityAuthority);
        assertFalse(restored.arrivalConfirmed);
        assertEquals(java.util.List.of(), restored.accountRoles);
    }

    @Test
    void identityPacketsContainNoCredentialAndRejectUnsafeLinks() {
        var fields = java.util.Arrays.stream(ArrivalStatePayload.class.getRecordComponents())
                .map(java.lang.reflect.RecordComponent::getName).toList();
        assertFalse(fields.contains("deviceCode"));
        assertFalse(fields.contains("token"));
        assertFalse(fields.contains("accountRoles"));
        String request = "a".repeat(32);
        assertThrows(IllegalArgumentException.class, () -> new ArrivalStatePayload("0427", "pending", "file:///secret",
                request, "", false, false, false, true, "", ""));
        assertThrows(IllegalArgumentException.class, () -> new ArrivalStatePayload("0427", "pending",
                "http://attacker.example/auth/login?requestId=" + request, request, "", false, false, false, true, "", ""));
    }
}
