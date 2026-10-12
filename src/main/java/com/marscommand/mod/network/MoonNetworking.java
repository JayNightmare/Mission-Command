package com.marscommand.mod.network;

import com.marscommand.mod.MarsCommandMod;
import com.marscommand.mod.mission.MoonDirector;
import com.mojang.logging.LogUtils;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.PacketDistributor;

public final class MoonNetworking {
    private MoonNetworking() {}

    static <T extends CustomPacketPayload> CustomPacketPayload.Type<T> type(String name) {
        return new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(MarsCommandMod.MOD_ID, name));
    }

    public static void register(RegisterPayloadHandlersEvent event) {
        var registrar = event.registrar("3");
        registrar.playToClient(ArrivalStatePayload.TYPE, ArrivalStatePayload.CODEC,
                (payload, context) -> context.enqueueWork(() -> com.marscommand.mod.client.MoonClient.receiveArrival(payload)));
        registrar.playToServer(ArrivalActionPayload.TYPE, ArrivalActionPayload.CODEC,
                (payload, context) -> context.enqueueWork(() -> server(context,
                        (director, player) -> director.arrivalAction(player, payload))));
        registrar.playToServer(IdentitySettingsPayload.TYPE, IdentitySettingsPayload.CODEC,
                (payload, context) -> context.enqueueWork(() -> server(context,
                        (director, player) -> director.identitySettings(player, payload))));
        registrar.playToClient(TransmissionPayload.TYPE, TransmissionPayload.CODEC,
                (payload, context) -> context.enqueueWork(() -> com.marscommand.mod.client.MoonClient.receive(payload)));
        registrar.playToClient(JournalPagePayload.TYPE, JournalPagePayload.CODEC,
                (payload, context) -> context.enqueueWork(() -> com.marscommand.mod.client.MoonClient.receivePage(payload)));
        registrar.playToClient(MissionNoticePayload.TYPE, MissionNoticePayload.CODEC,
                (payload, context) -> context.enqueueWork(() -> com.marscommand.mod.client.MoonClient.receiveNotice(payload)));
        registrar.playToClient(OpenJournalPayload.TYPE, OpenJournalPayload.CODEC,
                (payload, context) -> context.enqueueWork(() -> com.marscommand.mod.client.MoonClient.openJournal(payload.selectedMission())));
        registrar.playToServer(JournalRequestPayload.TYPE, JournalRequestPayload.CODEC,
                (payload, context) -> context.enqueueWork(() -> server(context,
                        (director, player) -> director.journalPage(player, payload))));
        registrar.playToServer(MissionActionPayload.TYPE, MissionActionPayload.CODEC,
                (payload, context) -> context.enqueueWork(() -> server(context,
                        (director, player) -> director.guiAction(player, payload))));
    }

    @FunctionalInterface
    private interface Action {
        void run(MoonDirector director, ServerPlayer player);
    }

    private static void server(IPayloadContext context, Action action) {
        if (!(context.player() instanceof ServerPlayer player)) {
            LogUtils.getLogger().error("Rejected Mars Command GUI request without a server player");
            return;
        }
        try {
            action.run(MoonDirector.get(player.server), player);
        } catch (IllegalArgumentException exception) {
            LogUtils.getLogger().warn("Rejected Mars Command GUI request from {}: {}",
                    player.getUUID(), exception.getMessage());
            PacketDistributor.sendToPlayer(player, new MissionNoticePayload(
                    Component.literal(exception.getMessage()), false, 0, 0, true));
        } catch (IllegalStateException exception) {
            LogUtils.getLogger().error("Mars Command GUI request failed", exception);
            PacketDistributor.sendToPlayer(player, new MissionNoticePayload(
                    Component.translatable("message.mars_command.unavailable"), false, 0, 0, true));
        }
    }
}
