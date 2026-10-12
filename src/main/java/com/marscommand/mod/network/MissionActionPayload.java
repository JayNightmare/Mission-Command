package com.marscommand.mod.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

public record MissionActionPayload(long missionId, Action action) implements CustomPacketPayload {
    public enum Action { SUBMIT, REFUSE, CONFIRM_REFUSAL }

    public static final Type<MissionActionPayload> TYPE = MoonNetworking.type("mission_action");
    public static final StreamCodec<RegistryFriendlyByteBuf, MissionActionPayload> CODEC = StreamCodec.of(
            (buffer, payload) -> {
                buffer.writeVarLong(payload.missionId);
                buffer.writeEnum(payload.action);
            }, buffer -> new MissionActionPayload(buffer.readVarLong(), buffer.readEnum(Action.class)));

    @Override
    public Type<MissionActionPayload> type() {
        return TYPE;
    }
}
