package com.marscommand.mod.network;

import com.marscommand.mod.MarsCommandMod;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

public record TransmissionPayload(double x, double y, double z) implements CustomPacketPayload {
    public static final Type<TransmissionPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(MarsCommandMod.MOD_ID, "transmission"));
    public static final StreamCodec<RegistryFriendlyByteBuf, TransmissionPayload> CODEC = StreamCodec.of(
            (buffer, payload) -> {
                buffer.writeDouble(payload.x);
                buffer.writeDouble(payload.y);
                buffer.writeDouble(payload.z);
            },
            buffer -> new TransmissionPayload(buffer.readDouble(), buffer.readDouble(), buffer.readDouble()));

    @Override
    public Type<TransmissionPayload> type() {
        return TYPE;
    }
}
