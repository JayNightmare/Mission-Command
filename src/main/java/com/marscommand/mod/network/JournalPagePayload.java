package com.marscommand.mod.network;

import com.marscommand.mod.mission.MissionJournal;
import com.marscommand.mod.mission.MissionRules;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

public record JournalPagePayload(int generation, int page, int pageCount, int score,
                                 String rank, List<MissionJournal.Entry> entries) implements CustomPacketPayload {
    public JournalPagePayload {
        MissionRules.require(generation > 0 && page >= 0 && pageCount > page && pageCount <= 1000000,
                "Invalid journal page header");
        MissionRules.require(rank != null && rank.length() <= 64 && entries != null
                && entries.size() <= MissionJournal.PAGE_SIZE, "Invalid journal page");
        entries = List.copyOf(entries);
    }

    public static final Type<JournalPagePayload> TYPE = MoonNetworking.type("journal_page");
    public static final StreamCodec<RegistryFriendlyByteBuf, JournalPagePayload> CODEC = StreamCodec.of(
            JournalPagePayload::encode, JournalPagePayload::decode);

    private static void encode(RegistryFriendlyByteBuf buffer, JournalPagePayload payload) {
        buffer.writeVarInt(payload.generation);
        buffer.writeVarInt(payload.page);
        buffer.writeVarInt(payload.pageCount);
        buffer.writeInt(payload.score);
        buffer.writeUtf(payload.rank, 64);
        buffer.writeVarInt(payload.entries.size());
        for (MissionJournal.Entry entry : payload.entries) {
            buffer.writeVarLong(entry.id());
            buffer.writeEnum(entry.kind());
            buffer.writeBoolean(entry.completed());
            buffer.writeBoolean(entry.abbreviated());
            buffer.writeUtf(entry.briefing(), 2048);
            buffer.writeUtf(entry.item(), 512);
            buffer.writeVarInt(entry.quantity());
            buffer.writeVarInt(entry.delivered());
            buffer.writeDouble(entry.progress());
            buffer.writeUtf(entry.target().dimension(), 512);
            buffer.writeDouble(entry.target().x());
            buffer.writeDouble(entry.target().y());
            buffer.writeDouble(entry.target().z());
            buffer.writeDouble(entry.target().radius());
            buffer.writeVarLong(entry.remainingMillis());
        }
    }

    private static JournalPagePayload decode(RegistryFriendlyByteBuf buffer) {
        int generation = buffer.readVarInt();
        int page = buffer.readVarInt();
        int count = buffer.readVarInt();
        int score = buffer.readInt();
        String rank = buffer.readUtf(64);
        int size = buffer.readVarInt();
        MissionRules.require(size >= 0 && size <= MissionJournal.PAGE_SIZE, "Oversized journal page");
        List<MissionJournal.Entry> entries = new ArrayList<>(size);
        for (int index = 0; index < size; index++) {
            long id = buffer.readVarLong();
            MissionRules.Kind kind = buffer.readEnum(MissionRules.Kind.class);
            boolean completed = buffer.readBoolean();
            boolean abbreviated = buffer.readBoolean();
            String briefing = buffer.readUtf(2048);
            String item = buffer.readUtf(512);
            int quantity = buffer.readVarInt();
            int delivered = buffer.readVarInt();
            double progress = buffer.readDouble();
            MissionRules.Target target = new MissionRules.Target(buffer.readUtf(512),
                    buffer.readDouble(), buffer.readDouble(), buffer.readDouble(), buffer.readDouble());
            long remaining = buffer.readVarLong();
            MissionRules.validateTarget(target);
            MissionRules.require(id > 0 && quantity > 0 && delivered >= 0 && delivered <= quantity
                    && Double.isFinite(progress) && progress >= 0 && progress <= 1 && remaining >= 0
                    && (!abbreviated || briefing.isEmpty()), "Invalid mission journal entry");
            entries.add(new MissionJournal.Entry(id, kind, completed, abbreviated, briefing, item,
                    quantity, delivered, progress, target, remaining));
        }
        return new JournalPagePayload(generation, page, count, score, rank, entries);
    }

    @Override
    public Type<JournalPagePayload> type() {
        return TYPE;
    }
}
