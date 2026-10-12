package com.marscommand.mod.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

public record MissionNoticePayload(Component message, boolean typewriter, long confirmationMission,
                                   long confirmationMillis, boolean error) implements CustomPacketPayload {
    public static final Type<MissionNoticePayload> TYPE = MoonNetworking.type("mission_notice");
    public static final StreamCodec<RegistryFriendlyByteBuf, MissionNoticePayload> CODEC = StreamCodec.of(
            (buffer, payload) -> {
                ComponentSerialization.STREAM_CODEC.encode(buffer, payload.message);
                buffer.writeBoolean(payload.typewriter);
                buffer.writeVarLong(payload.confirmationMission);
                buffer.writeVarLong(payload.confirmationMillis);
                buffer.writeBoolean(payload.error);
            }, buffer -> new MissionNoticePayload(ComponentSerialization.STREAM_CODEC.decode(buffer),
                    buffer.readBoolean(), buffer.readVarLong(), buffer.readVarLong(), buffer.readBoolean()));

    @Override
    public Type<MissionNoticePayload> type() {
        return TYPE;
    }
}
