package com.marscommand.mod.identity;

import com.google.gson.JsonParseException;
import com.marscommand.mod.mission.MissionRules;
import com.marscommand.mod.mission.MissionState;
import com.marscommand.mod.mission.MoonSavedData;
import com.marscommand.mod.network.ArrivalActionPayload;
import com.marscommand.mod.network.ArrivalStatePayload;
import com.marscommand.mod.network.IdentitySettingsPayload;
import com.marscommand.mod.network.MissionNoticePayload;
import com.mojang.logging.LogUtils;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.BiConsumer;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.network.PacketDistributor;

public final class ArrivalService implements AutoCloseable {
    private static final class Session {
        String phase = "loading";
        ArrivalApi.Start challenge;
        String accountName = "";
        boolean stale;
        long nextRequest;
        long expiresAt;
        CompletableFuture<?> future;
    }

    private final MinecraftServer server;
    private final MissionState state;
    private final Runnable dirty;
    private final ArrivalApi api = new ArrivalApi(System.getenv("MARS_MINECRAFT_SERVER_TOKEN"));
    private final boolean allowLocalHttp = Boolean.parseBoolean(System.getenv("MARS_COMMAND_ALLOW_LOCAL_HTTP"));
    private final Map<UUID, Session> sessions = new HashMap<>();
    private final long clockStart = System.nanoTime();
    private final Path configuration;
    private IdentitySettings settings;
    private String configurationError = "";
    private volatile boolean closed;

    public ArrivalService(MinecraftServer server, MissionState state, Runnable dirty) {
        this.server = server;
        this.state = state;
        this.dirty = dirty;
        configuration = server.getWorldPath(LevelResource.ROOT).resolve("serverconfig").resolve("mars-command-identity.json");
        try {
            if (!Files.exists(configuration)) {
                Files.createDirectories(configuration.getParent());
                Files.writeString(configuration, MoonSavedData.JSON.toJson(IdentitySettings.defaults()), StandardOpenOption.CREATE_NEW);
            }
            settings = MoonSavedData.JSON.fromJson(Files.readString(configuration), IdentitySettings.class);
            MissionRules.require(settings != null, "Empty identity configuration");
            settings = settings.validated(allowLocalHttp);
        } catch (IOException | JsonParseException | IllegalArgumentException exception) {
            configurationError = "invalid_configuration";
            LogUtils.getLogger().error("Arrival configuration is invalid; server missions will wait. File: {}", configuration, exception);
            settings = IdentitySettings.defaults();
        }
    }

    private long now() {
        return (System.nanoTime() - clockStart) / 1000000;
    }

    public boolean websiteRequired() {
        return server.isDedicatedServer() && (settings.websiteLinkEnabled() || !configurationError.isEmpty());
    }

    public static boolean confirmedFor(MissionState.Player player, String authority) {
        return player.arrivalConfirmed && player.identityAuthority.equals(authority) && !player.remoteCallSign.isEmpty();
    }

    public boolean ready(ServerPlayer player) {
        if (!websiteRequired()) {
            Session session = sessions.get(player.getUUID());
            if (session != null && session.phase.equals("unavailable") && state.player(player.getStringUUID()).callSign.isEmpty()) {
                return false;
            }
            try {
                Callsigns.local(state, player.getStringUUID());
                dirty.run();
                return true;
            } catch (IllegalStateException exception) {
                Session unavailable = sessions.computeIfAbsent(player.getUUID(), ignored -> new Session());
                unavailable.phase = "unavailable";
                LogUtils.getLogger().error("Local callsign registry is exhausted; mission assignment refused");
                return false;
            }
        }
        return server.usesAuthentication() && confirmedFor(state.player(player.getStringUUID()), settings.apiOrigin())
                && configurationError.isEmpty();
    }

    public void login(ServerPlayer player) {
        if (player instanceof FakePlayer) {
            return;
        }
        Session session = new Session();
        sessions.put(player.getUUID(), session);
        if (!websiteRequired()) {
            local(player, session);
        } else {
            start(player, session);
        }
    }

    private void local(ServerPlayer player, Session session) {
        try {
            String sign = Callsigns.local(state, player.getStringUUID());
            MissionState.Player record = state.player(player.getStringUUID());
            session.phase = "local";
            if (!record.arrivalWelcomed) {
                message(player, Component.translatable("arrival.mars_command.welcome_local", sign), true, false);
                record.arrivalWelcomed = true;
            }
            dirty.run();
        } catch (IllegalStateException exception) {
            session.phase = "unavailable";
            message(player, Component.translatable("arrival.mars_command.capacity"), false, true);
            LogUtils.getLogger().error("Local callsign registry is exhausted");
        }
        send(player, session);
    }

    public void logout(ServerPlayer player) {
        Session removed = sessions.remove(player.getUUID());
        if (removed != null && removed.future != null) {
            removed.future.cancel(true);
        }
    }

    public void tick() {
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            Session session = sessions.get(player.getUUID());
            if (session == null || session.future != null || now() < session.nextRequest || !websiteRequired()) {
                continue;
            }
            if (session.phase.equals("pending") || session.phase.equals("approved")) {
                if (now() >= session.expiresAt) {
                    session.phase = "expired";
                    session.challenge = null;
                    send(player, session);
                } else {
                    poll(player, session);
                }
            } else if (session.phase.equals("confirmed") || session.phase.equals("unavailable")) {
                refresh(player, session);
            }
        }
    }

    private void start(ServerPlayer player, Session session) {
        if (!configurationError.isEmpty() || !api.configured() || !server.usesAuthentication()) {
            session.phase = "unavailable";
            session.stale = true;
            session.nextRequest = now() + 60000;
            send(player, session);
            message(player, Component.translatable("arrival.mars_command.not_configured"), false, true);
            LogUtils.getLogger().warn("Website arrival requires valid configuration, a server-only integration key and online-mode=true");
            return;
        }
        session.phase = "loading";
        session.stale = true;
        send(player, session);
        IdentitySettings requestedSettings = settings;
        request(player, session, api.start(requestedSettings, player.getUUID(), player.getGameProfile().getName()), (result, online) -> {
            remember(player.getStringUUID(), requestedSettings.apiOrigin(), result.context());
            if (result.context().confirmed()) {
                session.phase = "confirmed";
                session.challenge = null;
                session.nextRequest = now() + 60000;
            } else {
                session.phase = "pending";
                session.challenge = result;
                session.accountName = "";
                session.expiresAt = now() + result.expiresIn() * 1000L;
                session.nextRequest = now() + result.pollInterval() * 1000L;
            }
            welcome(online, requestedSettings.apiOrigin(), result.context().callSign());
            send(online, session);
        });
    }

    private void poll(ServerPlayer player, Session session) {
        ArrivalApi.Start challenge = session.challenge;
        String previousAccount = session.accountName;
        String previousPhase = session.phase;
        request(player, session, api.poll(settings, player.getUUID(), challenge.deviceCode()), (result, online) -> {
            session.phase = result.status();
            session.nextRequest = now() + challenge.pollInterval() * 1000L;
            if (result.status().equals("approved")) {
                session.accountName = result.accountName();
                if (!previousPhase.equals("approved") || !previousAccount.equals(result.accountName())) {
                    message(online, Component.translatable("arrival.mars_command.review_account", result.accountName()), false, false);
                }
            }
            if (result.status().equals("expired") || result.status().equals("denied")) {
                session.challenge = null;
            }
            send(online, session);
        });
    }

    private void refresh(ServerPlayer player, Session session) {
        if (!api.configured() || !configurationError.isEmpty() || !server.usesAuthentication()) {
            session.nextRequest = now() + 60000;
            return;
        }
        IdentitySettings requestedSettings = settings;
        request(player, session, api.context(requestedSettings, player.getUUID()), (result, online) -> {
            remember(player.getStringUUID(), requestedSettings.apiOrigin(), result);
            session.nextRequest = now() + 60000;
            if (result.confirmed()) {
                session.phase = "confirmed";
            } else {
                session.phase = "expired";
            }
            send(online, session);
        });
    }

    private <T> void request(ServerPlayer player, Session session, CompletableFuture<T> future,
                             BiConsumer<T, ServerPlayer> completed) {
        session.future = future;
        UUID uuid = player.getUUID();
        future.whenComplete((result, error) -> {
            if (closed) {
                return;
            }
            server.execute(() -> {
                if (closed || sessions.get(uuid) != session) {
                    return;
                }
                session.future = null;
                ServerPlayer online = server.getPlayerList().getPlayer(uuid);
                if (online == null) {
                    return;
                }
                if (error != null) {
                    Throwable cause = error instanceof CompletionException && error.getCause() != null ? error.getCause() : error;
                    long delay = cause instanceof ArrivalApi.Failure failure ? failure.retrySeconds : 30;
                    session.nextRequest = now() + delay * 1000;
                    session.stale = true;
                    if (!session.phase.equals("pending") && !session.phase.equals("approved")) {
                        session.phase = "unavailable";
                    }
                    LogUtils.getLogger().warn("Arrival API request failed for {} ({}); remote context is stale",
                            uuid, cause instanceof ArrivalApi.Failure failure ? "HTTP " + failure.status : cause.getClass().getSimpleName());
                    message(online, Component.translatable("arrival.mars_command.remote_error"), false, true);
                    send(online, session);
                    return;
                }
                session.stale = false;
                completed.accept(result, online);
            });
        });
    }

    private void remember(String uuid, String authority, ArrivalApi.Context context) {
        MissionState.Player record = state.player(uuid);
        record.remoteCallSign = context.callSign();
        record.identityAuthority = authority;
        record.arrivalConfirmed = context.confirmed();
        record.accountName = context.accountName() == null ? "" : context.accountName();
        record.accountRoles = List.copyOf(context.roles());
        dirty.run();
    }

    private void welcome(ServerPlayer player, String authority, String sign) {
        MissionState.Player record = state.player(player.getStringUUID());
        if (!record.remoteWelcomeAuthority.equals(authority)) {
            message(player, Component.translatable("arrival.mars_command.welcome", sign), true, false);
            record.remoteWelcomeAuthority = authority;
            dirty.run();
        }
    }

    public void action(ServerPlayer player, ArrivalActionPayload payload) {
        Session session = sessions.get(player.getUUID());
        if (session == null) {
            login(player);
            session = sessions.get(player.getUUID());
        }
        MissionRules.require(session != null, "Arrival is unavailable for this player");
        if (payload.action() == ArrivalActionPayload.Action.STATUS) {
            send(player, session);
            return;
        }
        MissionRules.require(session.future == null && (!session.stale || now() >= session.nextRequest),
                "An arrival request is already running or waiting for its retry interval");
        if (payload.action() == ArrivalActionPayload.Action.RETRY) {
            MissionRules.require(now() >= session.nextRequest, "Wait for the arrival retry interval");
            if (websiteRequired()) {
                start(player, session);
            } else {
                local(player, session);
            }
            return;
        }
        MissionRules.require(payload.action() == ArrivalActionPayload.Action.CONFIRM_ACCOUNT && websiteRequired()
                && session.phase.equals("approved") && session.challenge != null && session.expiresAt > now()
                && session.challenge.requestId().equals(payload.requestId()) && session.accountName.equals(payload.accountName()),
                "Review the current approved website account before confirming");
        IdentitySettings requestedSettings = settings;
        Session currentSession = session;
        request(player, session, api.confirm(requestedSettings, player.getUUID(), session.challenge.deviceCode(), session.accountName),
                (context, online) -> {
                    remember(player.getStringUUID(), requestedSettings.apiOrigin(), context);
                    currentSession.phase = "confirmed";
                    currentSession.challenge = null;
                    currentSession.nextRequest = now() + 60000;
                    message(online, Component.translatable("arrival.mars_command.confirmed", context.callSign()), true, false);
                    send(online, currentSession);
                });
    }

    public void configure(ServerPlayer player, IdentitySettingsPayload payload) {
        MissionRules.require(player.hasPermissions(4), "Only a level-4 server operator may change arrival settings");
        IdentitySettings updated = new IdentitySettings(payload.enabled(), payload.apiOrigin(), payload.websiteOrigin()).validated(allowLocalHttp);
        MissionRules.require(!updated.websiteLinkEnabled() || (api.configured() && (!server.isDedicatedServer() || server.usesAuthentication())),
                "Set a valid server environment integration key and online-mode=true before enabling website arrival");
        try {
            Path temporary = Files.createTempFile(configuration.getParent(), "mars-identity-", ".tmp");
            try {
                Files.writeString(temporary, MoonSavedData.JSON.toJson(updated));
                try {
                    Files.move(temporary, configuration, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                } catch (AtomicMoveNotSupportedException exception) {
                    LogUtils.getLogger().warn("Atomic identity configuration replacement is unavailable; using filesystem replacement");
                    Files.move(temporary, configuration, StandardCopyOption.REPLACE_EXISTING);
                }
            } finally {
                Files.deleteIfExists(temporary);
            }
        } catch (IOException exception) {
            LogUtils.getLogger().error("Could not persist server arrival configuration", exception);
            throw new IllegalStateException("Arrival configuration could not be saved");
        }
        settings = updated;
        configurationError = "";
        for (Session session : sessions.values()) {
            if (session.future != null) {
                session.future.cancel(true);
            }
        }
        sessions.clear();
        server.getPlayerList().getPlayers().forEach(this::login);
        message(player, Component.translatable("arrival.mars_command.settings_saved"), false, false);
    }

    private void send(ServerPlayer player, Session session) {
        MissionState.Player record = state.player(player.getStringUUID());
        boolean required = websiteRequired();
        boolean sameAuthority = record.identityAuthority.equals(settings.apiOrigin());
        String sign = required ? (sameAuthority ? record.remoteCallSign : "") : record.callSign;
        boolean confirmed = required ? server.usesAuthentication() && configurationError.isEmpty()
                && confirmedFor(record, settings.apiOrigin()) : !record.callSign.isEmpty();
        boolean owner = player.hasPermissions(4);
        PacketDistributor.sendToPlayer(player, new ArrivalStatePayload(sign, session.phase,
                session.challenge == null ? "" : session.challenge.verificationUri(),
                session.challenge == null ? "" : session.challenge.requestId(),
                session.phase.equals("approved") ? session.accountName : (sameAuthority ? record.accountName : ""),
                confirmed, session.stale, owner, settings.websiteLinkEnabled(),
                owner ? settings.apiOrigin() : "", owner ? settings.websiteOrigin() : ""));
    }

    private static void message(ServerPlayer player, Component text, boolean typed, boolean error) {
        PacketDistributor.sendToPlayer(player, new MissionNoticePayload(text, typed, 0, 0, error));
    }

    @Override
    public void close() {
        closed = true;
        sessions.values().forEach(session -> {
            if (session.future != null) {
                session.future.cancel(true);
            }
        });
        sessions.clear();
        api.close();
    }
}
