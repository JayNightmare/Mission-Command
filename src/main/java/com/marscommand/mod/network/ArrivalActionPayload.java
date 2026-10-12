package com.marscommand.mod.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

public record ArrivalActionPayload(Action action, String requestId, String accountName) implements CustomPacketPayload {
    public enum Action { STATUS, RETRY, CONFIRM_ACCOUNT }

    public static final Type<ArrivalActionPayload> TYPE = MoonNetworking.type("arrival_action");
    public static final StreamCodec<RegistryFriendlyByteBuf, ArrivalActionPayload> CODEC = StreamCodec.of(
            (buffer, payload) -> {
                buffer.writeEnum(payload.action);
                buffer.writeUtf(payload.requestId, 32);
                buffer.writeUtf(payload.accountName, 100);
            }, buffer -> new ArrivalActionPayload(buffer.readEnum(Action.class), buffer.readUtf(32), buffer.readUtf(100)));

    @Override
    public Type<ArrivalActionPayload> type() {
        return TYPE;
    }
}
