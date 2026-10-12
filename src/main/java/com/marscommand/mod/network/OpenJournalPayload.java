package com.marscommand.mod.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

public record OpenJournalPayload(long selectedMission) implements CustomPacketPayload {
    public static final Type<OpenJournalPayload> TYPE = MoonNetworking.type("open_journal");
    public static final StreamCodec<RegistryFriendlyByteBuf, OpenJournalPayload> CODEC = StreamCodec.of(
            (buffer, payload) -> buffer.writeVarLong(payload.selectedMission),
            buffer -> new OpenJournalPayload(buffer.readVarLong()));

    @Override
    public Type<OpenJournalPayload> type() {
        return TYPE;
    }
}
