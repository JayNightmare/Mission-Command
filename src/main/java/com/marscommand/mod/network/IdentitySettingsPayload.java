package com.marscommand.mod.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

public record IdentitySettingsPayload(boolean enabled, String apiOrigin, String websiteOrigin) implements CustomPacketPayload {
    public static final Type<IdentitySettingsPayload> TYPE = MoonNetworking.type("identity_settings");
    public static final StreamCodec<RegistryFriendlyByteBuf, IdentitySettingsPayload> CODEC = StreamCodec.of(
            (buffer, payload) -> {
                buffer.writeBoolean(payload.enabled);
                buffer.writeUtf(payload.apiOrigin, 512);
                buffer.writeUtf(payload.websiteOrigin, 512);
            }, buffer -> new IdentitySettingsPayload(buffer.readBoolean(), buffer.readUtf(512), buffer.readUtf(512)));

    @Override
    public Type<IdentitySettingsPayload> type() {
        return TYPE;
    }
}
