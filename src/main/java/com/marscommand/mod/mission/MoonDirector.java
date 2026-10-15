package com.marscommand.mod.mission;

import com.google.gson.JsonParseException;
import com.marscommand.mod.network.TransmissionPayload;
import com.marscommand.mod.network.JournalRequestPayload;
import com.marscommand.mod.network.JournalPagePayload;
import com.marscommand.mod.network.MissionActionPayload;
import com.marscommand.mod.network.MissionNoticePayload;
import com.marscommand.mod.network.OpenJournalPayload;
import com.marscommand.mod.identity.ArrivalService;
import com.marscommand.mod.network.ArrivalActionPayload;
import com.marscommand.mod.network.IdentitySettingsPayload;
import com.mojang.logging.LogUtils;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import org.slf4j.Logger;

public final class MoonDirector {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static MoonDirector current;
    private final MinecraftServer server;
    private final MoonSavedData data;
    private final MissionRules rules;
    private final ArrivalService arrivals;
    private final ActiveClock clock = new ActiveClock(System.nanoTime());
    private final MissionPlanner planner = RemotePlanner.fromEnvironment()
            .map(remote -> (MissionPlanner) remote).orElseGet(MissionPlanner.Heuristic::new);
    private long nextSchedule;
    private final Map<String, Refusal> confirmations = new java.util.HashMap<>();
    private final Map<String, JournalSession> journals = new java.util.HashMap<>();
    private final Map<String, Long> journalRequests = new java.util.HashMap<>();
    private final Map<String, Long> actionRequests = new java.util.HashMap<>();

    private record Refusal(long missionId, long expiresMillis) {}
    private static final class JournalSession {
        final int generation;
        final List<MissionJournal.Entry> entries;
        final int score;
        final String rank;
        final long expiresMillis;
        int nextPage;

        JournalSession(int generation, List<MissionJournal.Entry> entries, int score, String rank, long expiresMillis) {
            this.generation = generation;
            this.entries = entries;
            this.score = score;
            this.rank = rank;
            this.expiresMillis = expiresMillis;
        }
    }

    MoonDirector(MinecraftServer server, MissionRules rules, MoonSavedData data) {
        this.server = server;
        this.rules = rules;
        this.data = data;
        arrivals = new ArrivalService(server, data.state, data::setDirty);
        nextSchedule = data.state.activeMillis + rules.schedulerMillis();
    }

    public static void started(ServerStartedEvent event) {
        MinecraftServer server = event.getServer();
        current = null;
        Path root = server.getWorldPath(LevelResource.ROOT);
        Path configuration = root.resolve("serverconfig").resolve("mars-command.json");
        try {
            if (!Files.exists(configuration)) {
                Files.createDirectories(configuration.getParent());
                BlockPos spawn = server.overworld().getSharedSpawnPos();
                Files.writeString(configuration, MoonSavedData.JSON.toJson(
                        MissionRules.defaults(spawn.getX(), spawn.getY(), spawn.getZ())),
                        StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
            }
            MissionRules rules = MoonSavedData.JSON.fromJson(Files.readString(configuration), MissionRules.class);
            MissionRules.require(rules != null, "Empty Mars Command configuration");
            rules.validate();
            validateCatalogue(server, rules);
            // Minecraft's SavedData loader otherwise catches corrupt files and creates fresh data.
            Path save = root.resolve("data").resolve("mars_command.dat");
            if (Files.exists(save)) {
                var tag = NbtIo.readCompressed(save, NbtAccounter.unlimitedHeap());
                MoonSavedData.FACTORY.deserializer().apply(tag.getCompound("data"), server.registryAccess())
                        .state.validate(rules);
            }
            MoonSavedData data = server.overworld().getDataStorage()
                    .computeIfAbsent(MoonSavedData.FACTORY, "mars_command");
            data.state.validate(rules);
            for (MissionState.Mission mission : data.state.missions.values()) {
                validateWorldTarget(server, mission.target, Long.toString(mission.id));
                if (mission.kind == MissionRules.Kind.DELIVERY) {
                    validateItem(mission.item);
                }
            }
            if (data.state.goalId.isEmpty()) {
                int index = Math.floorMod(server.overworld().getSeed(), rules.goals().size());
                data.state.goalId = rules.goals().stream().filter(GrandPlan::phased).findFirst()
                        .orElse(rules.goals().get(index)).id();
            }
            current = new MoonDirector(server, rules, data);
            data.setDirty();
            LOGGER.info("Mars Command mission director ready. Operator configuration: {}", configuration);
        } catch (IOException | JsonParseException | IllegalArgumentException | IllegalStateException exception) {
            LOGGER.error("Mars Command mission director disabled: configuration or saved state is invalid. "
                    + "No missions will be issued and existing data will not be replaced. Check {}.",
                    configuration, exception);
        }
    }

    public static void tick(ServerTickEvent.Post event) {
        if (current != null && current.server == event.getServer()) {
            current.updateClock();
            current.arrivals.tick();
            if (current.state().activeMillis >= current.nextSchedule) {
                current.nextSchedule = current.state().activeMillis + current.rules.schedulerMillis();
                current.schedule();
            }
        }
    }

    public static void stopping(ServerStoppingEvent event) {
        if (current != null && current.server == event.getServer()) {
            current.updateClock();
            current.data.setDirty();
            current.arrivals.close();
            current = null;
        }
    }

    public static void login(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player && current != null && current.server == player.getServer()) {
            MoonDirector director = current;
            director.updateClock();
            director.expireMissions();
            director.arrivals.login(player);
        }
    }

    public static void logout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (current != null) {
            if (event.getEntity() instanceof ServerPlayer player) {
                current.arrivals.logout(player);
            }
            String uuid = event.getEntity().getStringUUID();
            current.journals.remove(uuid);
            current.journalRequests.remove(uuid);
            current.actionRequests.remove(uuid);
            current.confirmations.remove(uuid);
        }
    }

    public static MoonDirector get(MinecraftServer server) {
        if (current == null || current.server != server) {
            throw new IllegalStateException("Mars Command director is unavailable; operators should check the server log");
        }
        current.updateClock();
        current.expireMissions();
        return current;
    }

    public MissionState state() {
        return data.state;
    }

    public MissionRules rules() {
        return rules;
    }

    public void arrivalAction(ServerPlayer player, ArrivalActionPayload payload) {
        arrivals.action(player, payload);
    }

    public void identitySettings(ServerPlayer player, IdentitySettingsPayload payload) {
        arrivals.configure(player, payload);
    }

    private void updateClock() {
        long elapsed = clock.sample(System.nanoTime());
        if (elapsed > 0) {
            state().activeMillis = Math.addExact(state().activeMillis, elapsed);
            data.setDirty();
        }
    }

    void schedule() {
        for (MissionState.Mission mission : new ArrayList<>(state().missions.values())) {
            if (!mission.live()) {
                continue;
            }
            if (MissionLogic.expire(state(), mission, rules)) {
                notifyOwner(mission, "message.mars_command.expired");
                data.setDirty();
                continue;
            }
            if (mission.status == MissionState.Status.ACTIVE) {
                updateArrival(mission);
                if (MissionLogic.complete(state(), mission, rules)) {
                    finished(mission);
                    continue;
                }
                if (MissionLogic.handoverDue(mission, state().activeMillis)) {
                    MissionLogic.release(state(), mission, rules, false);
                    notifyOwner(mission, "message.mars_command.reassigned");
                    data.setDirty();
                }
            }
            if (mission.status == MissionState.Status.AWAITING_REPLACEMENT) {
                replace(mission);
            }
        }
        confirmations.entrySet().removeIf(entry -> entry.getValue().expiresMillis() <= state().activeMillis);
        journals.entrySet().removeIf(entry -> entry.getValue().expiresMillis <= state().activeMillis);
        assignNormal();
    }

    private void expireMissions() {
        for (MissionState.Mission mission : state().missions.values()) {
            if (MissionLogic.expire(state(), mission, rules)) {
                notifyOwner(mission, "message.mars_command.expired");
                data.setDirty();
            }
        }
    }

    private void assignNormal() {
        MissionRules.Goal goal = rules.goal(state().goalId);
        if (GrandPlan.phased(goal)) {
            assignPhased();
            return;
        }
        for (MissionRules.Requirement requirement : goal.requirements()) {
            long outstanding = state().missions.values().stream()
                    .filter(mission -> mission.live() && mission.templateId.equals(requirement.template())).count();
            if (state().goalProgress.getOrDefault(requirement.template(), 0) + outstanding >= requirement.completions()) {
                continue;
            }
            MissionRules.Template template = rules.template(requirement.template());
            ServerPlayer candidate = server.getPlayerList().getPlayers().stream()
                    .filter(MoonDirector::gameplayPlayer)
                    .filter(player -> ready(player, template))
                    .min(Comparator.comparingLong((ServerPlayer player) ->
                                    state().player(player.getStringUUID()).nextAssignmentMillis)
                            .thenComparing(ServerPlayer::getStringUUID)).orElse(null);
            if (candidate != null && ready(candidate, template)) {
                create(candidate, template);
                return;
            }
        }
    }

    private void assignPhased() {
        GrandPlan.advance(rules, state());
        Map<String, Long> shortfalls = GrandPlan.shortfalls(rules, state());
        MissionPlanner.Context context = new MissionPlanner.Context(GrandPlan.current(rules, state()) == null
                ? "" : GrandPlan.current(rules, state()).id(), (int) server.getPlayerList().getPlayers().stream()
                .filter(MoonDirector::gameplayPlayer).count(), shortfalls);
        MissionPlanner.Proposal proposal = planner.propose(rules, context)
                .filter(value -> GrandPlan.acceptable(rules, shortfalls, value)).orElse(null);
        if (proposal == null) {
            if (planner.pending()) {
                return;
            }
            proposal = new MissionPlanner.Heuristic().propose(rules, context).orElse(null);
        }
        if (proposal == null) {
            return;
        }
        MissionRules.Template template = rules.template(proposal.templateId());
        ServerPlayer candidate = server.getPlayerList().getPlayers().stream()
                .filter(MoonDirector::gameplayPlayer)
                .filter(player -> ready(player, template))
                .min(Comparator.comparingLong((ServerPlayer player) ->
                                state().player(player.getStringUUID()).nextAssignmentMillis)
                        .thenComparing(ServerPlayer::getStringUUID)).orElse(null);
        if (candidate != null) {
            create(candidate, template, proposal.quantity(), proposal.briefing());
        }
    }

    public double readiness(ServerPlayer player, MissionRules.Template template) {
        boolean equipped = template.equipment().stream().allMatch(item -> hasItem(player, item));
        boolean reachable = player.level().dimension().location().toString().equals(template.target().dimension())
                && template.difficulty() <= rules.rank(state().player(player.getStringUUID()).score).maximumDifficulty();
        return MissionLogic.readiness(rules, player.getHealth() / player.getMaxHealth(),
                player.getFoodData().getFoodLevel() / 20.0, equipped, reachable);
    }

    private boolean ready(ServerPlayer player, MissionRules.Template template) {
        return gameplayPlayer(player) && player.getHealth() >= 3
                && arrivals.ready(player)
                && !state().hasAssignment(player.getStringUUID())
                && state().player(player.getStringUUID()).nextAssignmentMillis <= state().activeMillis
                && readiness(player, template) >= rules.readinessThreshold();
    }

    public long assignTest(ServerPlayer player, String templateId) {
        MissionRules.require(rules.templates().stream().anyMatch(template -> template.id().equals(templateId)),
                "Unknown mission template");
        MissionRules.Template template = rules.template(templateId);
        MissionRules.require(ready(player, template), "Player is not ready, has active work, or is on cooldown");
        if (GrandPlan.phased(rules.goal(state().goalId))) {
            Map<String, Long> shortfalls = GrandPlan.shortfalls(rules, state());
            MissionRules.require(shortfalls.containsKey(templateId),
                    "No unfulfilled resource need is available for this template");
            return create(player, template, (int) Math.min(shortfalls.get(templateId), template.quantity()), null).id;
        }
        MissionRules.Requirement requirement = rules.goal(state().goalId).requirements().stream()
                .filter(value -> value.template().equals(templateId)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Template does not belong to the selected goal"));
        long outstanding = state().missions.values().stream()
                .filter(mission -> mission.live() && mission.templateId.equals(templateId)).count();
        MissionRules.require(state().goalProgress.getOrDefault(templateId, 0) + outstanding < requirement.completions(),
                "No unfulfilled requirement is available for this template");
        return create(player, template).id;
    }

    private MissionState.Mission create(ServerPlayer player, MissionRules.Template template) {
        return create(player, template, 0, null);
    }

    private MissionState.Mission create(ServerPlayer player, MissionRules.Template template, int quantity, String briefing) {
        MissionRules.Rank rank = rules.rank(state().player(player.getStringUUID()).score);
        MissionState.Mission mission = new MissionState.Mission();
        mission.id = state().nextMissionId++;
        mission.templateId = template.id();
        mission.kind = template.kind();
        mission.target = template.target();
        mission.item = template.item();
        mission.briefing = briefing == null || briefing.isBlank() ? template.briefing() : briefing;
        mission.quantity = quantity > 0 ? quantity
                : (int) Math.clamp(Math.ceil(template.quantity() * rank.quantityMultiplier()), 1, rules.maximumQuantity());
        mission.createdMillis = state().activeMillis;
        mission.durationMillis = (long) Math.clamp(template.durationMillis() * rank.durationMultiplier(),
                rules.minimumDurationMillis(), rules.maximumDurationMillis());
        mission.deadlineMillis = mission.createdMillis + mission.durationMillis;
        mission.nextHandoverMillis = mission.createdMillis;
        mission.owner = player.getStringUUID();
        mission.initialDistance = MissionLogic.distance(mission.target, player.level().dimension().location().toString(),
                player.getX(), player.getY(), player.getZ(), rules);
        mission.history.add(new MissionState.Ownership(mission.owner, state().activeMillis, 0));
        state().missions.put(mission.id, mission);
        state().player(mission.owner).nextAssignmentMillis = state().activeMillis + rank.cooldownMillis();
        data.setDirty();
        transmit(player, mission, false);
        return mission;
    }

    private void replace(MissionState.Mission mission) {
        ServerPlayer candidate = server.getPlayerList().getPlayers().stream()
                .filter(MoonDirector::gameplayPlayer)
                .filter(arrivals::ready)
                .filter(player -> mission.history.stream().noneMatch(owner -> owner.player.equals(player.getStringUUID())))
                .min(Comparator.comparingDouble((ServerPlayer player) -> MissionLogic.distance(mission.target,
                                player.level().dimension().location().toString(), player.getX(), player.getY(), player.getZ(), rules))
                        .thenComparing(ServerPlayer::getStringUUID)).orElse(null);
        if (candidate != null && MissionLogic.assignReplacement(state(), mission, candidate.getStringUUID(), rules)) {
            state().player(mission.owner).nextAssignmentMillis = Math.max(
                    state().player(mission.owner).nextAssignmentMillis,
                    state().activeMillis + rules.rank(state().player(mission.owner).score).cooldownMillis());
            data.setDirty();
            transmit(candidate, mission, true);
        }
    }

    private void updateArrival(MissionState.Mission mission) {
        if (mission.kind != MissionRules.Kind.ARRIVAL) {
            return;
        }
        ServerPlayer player = server.getPlayerList().getPlayer(UUID.fromString(mission.owner));
        if (player == null || !gameplayPlayer(player)
                || !player.level().dimension().location().toString().equals(mission.target.dimension())) {
            return;
        }
        double distance = MissionLogic.distance(mission.target, mission.target.dimension(),
                player.getX(), player.getY(), player.getZ(), rules);
        double progress = distance <= mission.target.radius() ? 1
                : Math.min(0.99, Math.max(0, (mission.initialDistance - distance)
                        / Math.max(1, mission.initialDistance - mission.target.radius())));
        if (progress > mission.progress) {
            MissionLogic.progress(mission, progress);
            data.setDirty();
        }
    }

    public List<MissionState.Mission> owned(ServerPlayer player) {
        return state().missions.values().stream().filter(mission -> mission.status == MissionState.Status.ACTIVE
                && mission.owner.equals(player.getStringUUID())).toList();
    }

    public MissionState.Mission owned(ServerPlayer player, long id) {
        MissionState.Mission mission = state().missions.get(id);
        MissionRules.require(mission != null && mission.status == MissionState.Status.ACTIVE
                && mission.owner.equals(player.getStringUUID()), "No active mission with that ID belongs to you");
        return mission;
    }

    public int submit(ServerPlayer player, long id) {
        MissionState.Mission mission = owned(player, id);
        MissionRules.require(gameplayPlayer(player), "Only living Survival/Adventure players may submit supplies");
        MissionRules.require(mission.kind == MissionRules.Kind.DELIVERY, "This is not a supply-delivery mission");
        MissionRules.require(player.level().dimension().location().toString().equals(mission.target.dimension())
                && MissionLogic.distance(mission.target, mission.target.dimension(), player.getX(), player.getY(), player.getZ(), rules)
                <= mission.target.radius(), "You must be at the mission's delivery coordinates");
        int needed = mission.quantity - mission.delivered;
        int submitted = 0;
        for (var compartment : List.of(player.getInventory().items, player.getInventory().offhand)) {
            for (ItemStack stack : compartment) {
                if (needed > 0 && matches(stack, mission.item)) {
                    int amount = Math.min(needed, stack.getCount());
                    stack.shrink(amount);
                    submitted += amount;
                    needed -= amount;
                }
            }
        }
        MissionRules.require(submitted > 0, "You have no matching supplies to submit");
        player.getInventory().setChanged();
        player.containerMenu.broadcastChanges();
        mission.delivered += submitted;
        if (GrandPlan.phased(rules.goal(state().goalId))) {
            GrandPlan.credit(state(), mission.item, submitted);
            GrandPlan.advance(rules, state());
        }
        MissionLogic.progress(mission, (double) mission.delivered / mission.quantity);
        if (MissionLogic.complete(state(), mission, rules)) {
            finished(mission);
        }
        data.setDirty();
        notice(player, Component.translatable("message.mars_command.submitted", submitted), false, 0, 0, false);
        return submitted;
    }

    public boolean refuse(ServerPlayer player, long id, boolean confirm) {
        MissionState.Mission mission = owned(player, id);
        String uuid = player.getStringUUID();
        if (!confirm) {
            confirmations.put(uuid, new Refusal(id, state().activeMillis + rules.confirmationMillis()));
            notice(player, Component.translatable("message.mars_command.refusal_warning", id, rules.refusalPenalty()),
                    false, id, rules.confirmationMillis(), false);
            return false;
        }
        Refusal refusal = confirmations.get(uuid);
        MissionRules.require(refusal != null && refusal.missionId() == id && refusal.expiresMillis() > state().activeMillis,
                "Request refusal first; confirmation has expired or is for a different mission");
        confirmations.remove(uuid);
        MissionRules.require(MissionLogic.release(state(), mission, rules, true), "Mission can no longer be refused");
        notifyOwner(mission, "message.mars_command.refused");
        data.setDirty();
        replace(mission);
        return true;
    }

    public void describe(ServerPlayer player, MissionState.Mission mission) {
        MissionRules.require(MissionJournal.visible(mission, player.getStringUUID()),
                "No visible mission with that ID belongs to you");
        openJournal(player, mission.id);
    }

    public void openJournal(ServerPlayer player, long selectedMission) {
        if (selectedMission > 0) {
            MissionState.Mission mission = state().missions.get(selectedMission);
            MissionRules.require(mission != null && MissionJournal.visible(mission, player.getStringUUID()),
                    "No visible mission with that ID belongs to you");
        }
        PacketDistributor.sendToPlayer(player, new OpenJournalPayload(selectedMission));
    }

    public void journalPage(ServerPlayer player, JournalRequestPayload request) {
        String uuid = player.getStringUUID();
        MissionRules.require(request.generation() > 0 && request.page() >= 0, "Invalid journal request");
        JournalSession session = journals.get(uuid);
        if (request.page() == 0) {
            MissionRules.require(state().activeMillis - journalRequests.getOrDefault(uuid, -1000L) >= 500,
                    "Please wait before refreshing the mission journal");
            MissionRules.require(session == null || request.generation() > session.generation, "Stale journal request");
            var statistics = state().player(uuid);
            session = new JournalSession(request.generation(), MissionJournal.entries(state(), uuid),
                    statistics.score, rules.rank(statistics.score).name(), state().activeMillis + 30000);
            journals.put(uuid, session);
            journalRequests.put(uuid, state().activeMillis);
        }
        MissionRules.require(session != null && session.generation == request.generation()
                && session.expiresMillis > state().activeMillis && session.nextPage == request.page(),
                "Journal refresh expired or arrived out of order; refresh again");
        List<MissionJournal.Entry> entries = MissionJournal.page(session.entries, request.page());
        PacketDistributor.sendToPlayer(player, new JournalPagePayload(session.generation, request.page(),
                MissionJournal.pageCount(session.entries.size()), session.score, session.rank, entries));
        session.nextPage++;
    }

    public void guiAction(ServerPlayer player, MissionActionPayload request) {
        MissionRules.require(request.missionId() > 0 && request.action() != null, "Invalid mission action");
        String uuid = player.getStringUUID();
        MissionRules.require(state().activeMillis - actionRequests.getOrDefault(uuid, -1000L) >= 50,
                "Please wait before repeating a mission action");
        actionRequests.put(uuid, state().activeMillis);
        switch (request.action()) {
            case SUBMIT -> submit(player, request.missionId());
            case REFUSE -> refuse(player, request.missionId(), false);
            case CONFIRM_REFUSAL -> refuse(player, request.missionId(), true);
        }
    }

    public void testStrike(ServerPlayer player, boolean emergency) {
        MissionRules.require(player.isAlive() && (emergency || player.getHealth() >= 3),
                "Player must be alive and have enough health for an ordinary transmission");
        strike(player, emergency);
        notice(player, Component.translatable("message.mars_command.test_transmission"), true, 0, 0, false);
    }

    private void transmit(ServerPlayer player, MissionState.Mission mission, boolean emergency) {
        strike(player, emergency);
        MissionJournal.Entry entry = MissionJournal.entry(mission, state().activeMillis);
        Component objective = entry.kind() == MissionRules.Kind.DELIVERY
                ? Component.translatable("objective.mars_command.delivery", entry.quantity() - entry.delivered(), entry.item())
                : Component.translatable("objective.mars_command.arrival");
        var text = Component.empty();
        if (!entry.abbreviated()) {
            text.append(entry.briefing()).append("\n");
        }
        text.append(Component.translatable("message.mars_command.mission", entry.id(), objective,
                entry.target().dimension(), formatCoordinate(entry.target().x()), formatCoordinate(entry.target().y()),
                formatCoordinate(entry.target().z()), (int) Math.floor(entry.progress() * 100),
                (entry.remainingMillis() + 999) / 1000));
        notice(player, text, true, 0, 0, false);
    }

    private void strike(ServerPlayer player, boolean emergency) {
        player.setHealth(MissionLogic.strikeHealth(player.getHealth(), emergency));
        PacketDistributor.sendToPlayer(player, new TransmissionPayload(player.getX(), player.getY(), player.getZ()));
    }

    private void finished(MissionState.Mission mission) {
        notifyOwner(mission, "message.mars_command.completed");
        data.setDirty();
    }

    private void notifyOwner(MissionState.Mission mission, String key) {
        ServerPlayer player = server.getPlayerList().getPlayer(UUID.fromString(mission.owner));
        if (player != null) {
            notice(player, Component.translatable(key, mission.id, state().player(mission.owner).score,
                    rules.rank(state().player(mission.owner).score).name()), false, 0, 0, false);
        }
    }

    private static void notice(ServerPlayer player, Component message, boolean typewriter,
                               long confirmation, long confirmationMillis, boolean error) {
        PacketDistributor.sendToPlayer(player, new MissionNoticePayload(message, typewriter,
                confirmation, confirmationMillis, error));
    }

    private static String formatCoordinate(double value) {
        return Long.toString(Math.round(value));
    }

    private static boolean gameplayPlayer(ServerPlayer player) {
        return !(player instanceof FakePlayer) && player.isAlive() && !player.isSpectator() && !player.isCreative();
    }

    private static boolean hasItem(ServerPlayer player, String item) {
        for (ItemStack stack : player.getInventory().items) {
            if (matches(stack, item)) {
                return true;
            }
        }
        for (ItemStack stack : player.getInventory().armor) {
            if (matches(stack, item)) {
                return true;
            }
        }
        return matches(player.getOffhandItem(), item);
    }

    private static boolean matches(ItemStack stack, String item) {
        if (stack.isEmpty()) {
            return false;
        }
        if (item.startsWith("#")) {
            return stack.is(TagKey.create(Registries.ITEM, ResourceLocation.parse(item.substring(1))));
        }
        return BuiltInRegistries.ITEM.getKey(stack.getItem()).equals(ResourceLocation.parse(item));
    }

    private static void validateCatalogue(MinecraftServer server, MissionRules rules) {
        for (MissionRules.Template template : rules.templates()) {
            validateWorldTarget(server, template.target(), template.id());
            List<String> items = new ArrayList<>(template.equipment());
            if (template.kind() == MissionRules.Kind.DELIVERY) {
                items.add(template.item());
            }
            for (String item : items) {
                validateItem(item);
            }
        }
    }

    private static void validateWorldTarget(MinecraftServer server, MissionRules.Target target, String id) {
        ServerLevel level = server.getLevel(ResourceKey.create(Registries.DIMENSION,
                ResourceLocation.parse(target.dimension())));
        MissionRules.require(level != null, "Unknown target dimension for mission " + id);
        MissionRules.require(target.y() >= level.getMinBuildHeight() && target.y() < level.getMaxBuildHeight()
                        && level.getWorldBorder().isWithinBounds(BlockPos.containing(target.x(), target.y(), target.z())),
                "Target outside dimension height/world border: " + id);
    }

    private static void validateItem(String item) {
        MissionRules.require(item.length() <= 512, "Supply/equipment identifier exceeds the GUI protocol limit");
        ResourceLocation id = ResourceLocation.tryParse(item.startsWith("#") ? item.substring(1) : item);
        MissionRules.require(id != null, "Invalid supply/equipment identifier: " + item);
        if (item.startsWith("#")) {
            var tag = BuiltInRegistries.ITEM.getTag(TagKey.create(Registries.ITEM,
                    id));
            MissionRules.require(tag.isPresent() && tag.get().size() > 0, "Missing/empty item tag: " + item);
        } else {
            MissionRules.require(BuiltInRegistries.ITEM.containsKey(id)
                            && !id.equals(ResourceLocation.withDefaultNamespace("air")), "Unknown supply/equipment: " + item);
        }
    }
}
