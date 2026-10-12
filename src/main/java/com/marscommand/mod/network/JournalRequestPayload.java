package com.marscommand.mod.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

public record JournalRequestPayload(int generation, int page) implements CustomPacketPayload {
    public static final Type<JournalRequestPayload> TYPE = MoonNetworking.type("journal_request");
    public static final StreamCodec<RegistryFriendlyByteBuf, JournalRequestPayload> CODEC = StreamCodec.of(
            (buffer, payload) -> {
                buffer.writeVarInt(payload.generation);
                buffer.writeVarInt(payload.page);
            }, buffer -> new JournalRequestPayload(buffer.readVarInt(), buffer.readVarInt()));

    @Override
    public Type<JournalRequestPayload> type() {
        return TYPE;
    }
}
