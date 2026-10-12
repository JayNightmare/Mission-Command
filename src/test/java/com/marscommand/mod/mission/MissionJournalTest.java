package com.marscommand.mod.mission;

import static org.junit.jupiter.api.Assertions.*;

import com.marscommand.mod.client.JournalAssembler;
import com.marscommand.mod.network.JournalPagePayload;
import io.netty.buffer.Unpooled;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.RegistryFriendlyByteBuf;
import org.junit.jupiter.api.Test;

class MissionJournalTest {
    private final String owner = UUID.randomUUID().toString();
    private final MissionState state = new MissionState();

    private MissionState.Mission add(long id, String player, MissionState.Status status, boolean abbreviated) {
        MissionState.Mission mission = new MissionState.Mission();
        mission.id = id;
        mission.owner = player;
        mission.status = status;
        mission.abbreviated = abbreviated;
        mission.kind = MissionRules.Kind.DELIVERY;
        mission.target = new MissionRules.Target("minecraft:overworld", 1, 64, 2, 8);
        mission.item = "minecraft:iron_ingot";
        mission.quantity = 16;
        mission.briefing = "Private full instructions";
        mission.deadlineMillis = 600000;
        state.missions.put(id, mission);
        return mission;
    }

    @Test
    void journalShowsOnlyOwnOngoingAndCompletedMissions() {
        add(1, owner, MissionState.Status.ACTIVE, false);
        add(2, owner, MissionState.Status.COMPLETED, false);
        add(3, UUID.randomUUID().toString(), MissionState.Status.COMPLETED, false);
        add(4, owner, MissionState.Status.EXPIRED, false);
        add(5, owner, MissionState.Status.AWAITING_REPLACEMENT, false);
        assertEquals(List.of(1L, 2L), MissionJournal.entries(state, owner).stream().map(MissionJournal.Entry::id).toList());
        assertEquals(5, state.missions.size());
    }

    @Test
    void emergencyBriefingNeverAppearsEvenAfterCompletion() {
        add(1, owner, MissionState.Status.COMPLETED, true);
        MissionJournal.Entry entry = MissionJournal.entries(state, owner).getFirst();
        assertEquals("", entry.briefing());
        assertTrue(entry.abbreviated());
        assertTrue(entry.completed());
        assertEquals(0, entry.remainingMillis());
    }

    @Test
    void emptyHistoryDoesNotCreateFuturePlaceholders() {
        assertEquals(List.of(), MissionJournal.entries(state, owner));
        assertEquals(1, MissionJournal.pageCount(0));
        assertEquals(List.of(), MissionJournal.page(List.of(), 0));
    }

    @Test
    void allRecordsRemainAccessibleAcrossBoundedPages() {
        for (int index = 1; index <= 65; index++) {
            add(index, owner, MissionState.Status.COMPLETED, false);
        }
        List<MissionJournal.Entry> entries = MissionJournal.entries(state, owner);
        assertEquals(5, MissionJournal.pageCount(entries.size()));
        JournalAssembler assembler = new JournalAssembler(7);
        for (int page = 0; page < 5; page++) {
            List<MissionJournal.Entry> slice = MissionJournal.page(entries, page);
            assertTrue(slice.size() <= MissionJournal.PAGE_SIZE);
            assertTrue(assembler.accept(new JournalPagePayload(7, page, 5, 10, "Operative", slice)));
        }
        assertTrue(assembler.complete());
        assertEquals(entries, assembler.entries());
        assertThrows(IllegalArgumentException.class, () -> MissionJournal.page(entries, 5));
    }

    @Test
    void assemblerRejectsMixedDuplicatedOrOutOfOrderSnapshots() {
        add(1, owner, MissionState.Status.ACTIVE, false);
        List<MissionJournal.Entry> entries = MissionJournal.entries(state, owner);
        JournalAssembler assembler = new JournalAssembler(3);
        assertFalse(assembler.accept(new JournalPagePayload(2, 0, 1, 0, "Operative", entries)));
        assertThrows(IllegalArgumentException.class, () -> assembler.accept(
                new JournalPagePayload(3, 1, 2, 0, "Operative", entries)));
        assertTrue(assembler.accept(new JournalPagePayload(3, 0, 2, 0, "Operative", entries)));
        assertThrows(IllegalArgumentException.class, () -> assembler.accept(
                new JournalPagePayload(3, 1, 2, 10, "Operative", entries)));
        assertThrows(IllegalArgumentException.class, () -> assembler.accept(
                new JournalPagePayload(3, 1, 2, 0, "Operative", entries)));
    }

    @Test
    void pageCodecRoundtripsWithoutInternalPlanOrOwnershipFields() {
        add(1, owner, MissionState.Status.ACTIVE, true);
        JournalPagePayload payload = new JournalPagePayload(1, 0, 1, 10, "Operative", MissionJournal.entries(state, owner));
        var buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
        try {
            JournalPagePayload.CODEC.encode(buffer, payload);
            assertEquals(payload, JournalPagePayload.CODEC.decode(buffer));
            assertEquals(0, buffer.readableBytes());
        } finally {
            buffer.release();
        }
        var components = java.util.Arrays.stream(MissionJournal.Entry.class.getRecordComponents())
                .map(java.lang.reflect.RecordComponent::getName).toList();
        assertFalse(components.contains("goalId"));
        assertFalse(components.contains("templateId"));
        assertFalse(components.contains("history"));
        assertFalse(components.contains("owner"));
    }
}
