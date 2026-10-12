package com.marscommand.mod.network;

import com.marscommand.mod.mission.MissionRules;
import java.net.URI;
import java.util.Set;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

public record ArrivalStatePayload(String callSign, String state, String verificationUri, String requestId,
                                  String accountName, boolean confirmed, boolean stale, boolean canConfigure,
                                  boolean websiteEnabled, String apiOrigin, String websiteOrigin) implements CustomPacketPayload {
    public ArrivalStatePayload {
        MissionRules.require(callSign != null && (callSign.isEmpty() || callSign.matches("[0-9]{4}"))
                && state != null && Set.of("local", "loading", "pending", "approved", "confirmed", "unavailable",
                "expired", "denied").contains(state) && requestId != null && (requestId.isEmpty() || requestId.matches("[a-f0-9]{32}"))
                && accountName != null && accountName.length() <= 100 && accountName.chars().noneMatch(Character::isISOControl),
                "Invalid arrival state");
        MissionRules.require(verificationUri != null && verificationUri.length() <= 1024
                && apiOrigin != null && apiOrigin.length() <= 512 && websiteOrigin != null && websiteOrigin.length() <= 512,
                "Invalid arrival URLs");
        if (!verificationUri.isEmpty()) {
            URI link = URI.create(verificationUri);
            boolean local = link.getHost() != null && Set.of("localhost", "127.0.0.1", "[::1]", "::1").contains(link.getHost());
            MissionRules.require(link.getHost() != null && link.getUserInfo() == null && link.getFragment() == null
                    && ("https".equals(link.getScheme()) || (local && "http".equals(link.getScheme())))
                    && "/auth/login".equals(link.getPath()) && ("requestId=" + requestId).equals(link.getQuery())
                    && !requestId.isEmpty(), "Unsafe arrival confirmation link");
        }
    }

    public static final Type<ArrivalStatePayload> TYPE = MoonNetworking.type("arrival_state");
    public static final StreamCodec<RegistryFriendlyByteBuf, ArrivalStatePayload> CODEC = StreamCodec.of(
            (buffer, payload) -> {
                buffer.writeUtf(payload.callSign, 4);
                buffer.writeUtf(payload.state, 32);
                buffer.writeUtf(payload.verificationUri, 1024);
                buffer.writeUtf(payload.requestId, 32);
                buffer.writeUtf(payload.accountName, 100);
                buffer.writeBoolean(payload.confirmed);
                buffer.writeBoolean(payload.stale);
                buffer.writeBoolean(payload.canConfigure);
                buffer.writeBoolean(payload.websiteEnabled);
                buffer.writeUtf(payload.apiOrigin, 512);
                buffer.writeUtf(payload.websiteOrigin, 512);
            }, buffer -> new ArrivalStatePayload(buffer.readUtf(4), buffer.readUtf(32), buffer.readUtf(1024),
                    buffer.readUtf(32), buffer.readUtf(100), buffer.readBoolean(), buffer.readBoolean(),
                    buffer.readBoolean(), buffer.readBoolean(), buffer.readUtf(512), buffer.readUtf(512)));

    @Override
    public Type<ArrivalStatePayload> type() {
        return TYPE;
    }
}
