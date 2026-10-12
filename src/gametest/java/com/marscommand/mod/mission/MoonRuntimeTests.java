package com.marscommand.mod.mission;

import com.mojang.authlib.GameProfile;
import io.netty.channel.embedded.EmbeddedChannel;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.Connection;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder("mars_command")
@PrefixGameTestTemplate(false)
public final class MoonRuntimeTests {
    @GameTest(template = "empty", timeoutTicks = 100)
    public static void websiteRolesCannotChangeServerIdentitySettings(GameTestHelper helper) {
        TestPlayer player = player(helper, "moon-owner-test", 0);
        try {
            MoonDirector director = director(helper);
            director.state().player(player.getStringUUID()).accountRoles = java.util.List.of("admin");
            boolean rejected = false;
            try {
                director.identitySettings(player, new com.marscommand.mod.network.IdentitySettingsPayload(
                        false, "https://api.example", "https://site.example"));
            } catch (IllegalArgumentException expected) {
                rejected = true;
            }
            helper.assertTrue(rejected, "Website roles must never grant Minecraft configuration authority");
            helper.succeed();
        } finally {
            remove(player);
        }
    }

    private static final class TestPlayer extends ServerPlayer {
        final List<String> messages = new ArrayList<>();

        TestPlayer(MinecraftServer server, GameProfile profile, CommonListenerCookie cookie) {
            super(server, server.overworld(), profile, cookie.clientInformation());
        }

        @Override
        public boolean isCreative() {
            return false;
        }

        @Override
        public boolean isSpectator() {
            return false;
        }

        @Override
        public void sendSystemMessage(Component component) {
            messages.add(component.getString());
        }
    }

    private static TestPlayer player(GameTestHelper helper, String name, double offset) {
        MinecraftServer server = helper.getLevel().getServer();
        GameProfile profile = new GameProfile(UUID.randomUUID(), name);
        CommonListenerCookie cookie = CommonListenerCookie.createInitial(profile, false);
        TestPlayer player = new TestPlayer(server, profile, cookie);
        Connection connection = new Connection(PacketFlow.SERVERBOUND);
        new EmbeddedChannel(connection);
        net.neoforged.neoforge.network.registration.NetworkRegistry.configureMockConnection(connection);
        server.getPlayerList().placeNewPlayer(connection, player, cookie);
        BlockPos position = helper.absolutePos(new BlockPos(2, 1, 2));
        player.moveTo(position.getX() + offset, position.getY(), position.getZ());
        player.setHealth(20);
        player.getFoodData().setFoodLevel(20);
        player.messages.clear();
        return player;
    }

    private static void remove(TestPlayer player) {
        player.server.getPlayerList().remove(player);
        player.serverLevel().removePlayerImmediately(player, Entity.RemovalReason.DISCARDED);
    }

    private static MoonDirector director(GameTestHelper helper) {
        BlockPos target = helper.absolutePos(new BlockPos(2, 1, 2));
        MissionRules rules = MissionRules.defaults(target.getX(), target.getY(), target.getZ());
        MoonSavedData data = new MoonSavedData();
        data.state.goalId = "supply_network";
        return new MoonDirector(helper.getLevel().getServer(), rules, data);
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void deliveryConsumesItemsAndRefusalPreservesProgress(GameTestHelper helper) {
        TestPlayer first = player(helper, "moon-supply-a", 0);
        TestPlayer second = player(helper, "moon-supply-b", 1);
        try {
            MoonDirector director = director(helper);
            long id = director.assignTest(first, "iron_delivery");
            first.getInventory().items.set(0, new ItemStack(Items.IRON_INGOT, 8));
            helper.assertTrue(director.submit(first, id) == 8, "Partial turn-in count");
            helper.assertTrue(first.getInventory().items.getFirst().isEmpty(), "Supplies must be consumed");
            MissionState.Mission mission = director.state().missions.get(id);
            boolean privateAssignmentRejected = false;
            try {
                director.owned(second, id);
            } catch (IllegalArgumentException expected) {
                privateAssignmentRejected = true;
            }
            helper.assertTrue(privateAssignmentRejected, "Another player cannot inspect or submit this assignment");
            helper.assertTrue(mission.delivered == 8 && mission.progress == 0.5, "Partial progress must be exact");
            helper.assertTrue(!director.refuse(first, id, false), "Refusal must require confirmation");
            helper.assertTrue(director.refuse(first, id, true), "Confirmed refusal must succeed");
            helper.assertTrue(mission.owner.equals(second.getStringUUID()), "Refusal must transfer the obligation");
            helper.assertTrue(mission.delivered == 8 && mission.abbreviated, "Transfer must preserve progress and abbreviate briefing");
            helper.assertTrue(second.messages.isEmpty(), "Mission interactions must not post chat messages");
            helper.assertTrue(MissionJournal.entries(director.state(), second.getStringUUID()).getFirst().briefing().isEmpty(),
                    "Emergency journal entry must not expose the full briefing");
            second.getInventory().items.set(0, new ItemStack(Items.IRON_INGOT, 16));
            helper.assertTrue(director.submit(second, id) == 8, "Only remaining quantity is consumed");
            helper.assertTrue(second.getInventory().items.getFirst().getCount() == 8, "Extra inventory must be preserved");
            helper.assertTrue(mission.status == MissionState.Status.COMPLETED, "Supply mission must complete");
            helper.assertTrue(director.state().player(second.getStringUUID()).score == 10, "Completion must credit score");
            helper.assertTrue(director.state().player(first.getStringUUID()).score == -30, "Refusal must incur heavy penalty only once");
            boolean rejected = false;
            try {
                director.submit(second, id);
            } catch (IllegalArgumentException expected) {
                rejected = true;
            }
            helper.assertTrue(rejected, "Completed mission cannot consume supplies or score again");
            helper.succeed();
        } finally {
            remove(first);
            remove(second);
        }
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void guiRequestsArePrivateAndActionsAreServerValidated(GameTestHelper helper) {
        TestPlayer first = player(helper, "moon-gui-a", 0);
        TestPlayer second = player(helper, "moon-gui-b", 1);
        try {
            MoonDirector director = director(helper);
            long id = director.assignTest(first, "iron_delivery");
            director.journalPage(first, new com.marscommand.mod.network.JournalRequestPayload(1, 0));
            director.journalPage(second, new com.marscommand.mod.network.JournalRequestPayload(1, 0));
            helper.assertTrue(MissionJournal.entries(director.state(), second.getStringUUID()).isEmpty(),
                    "Journal requests cannot disclose another player's missions");
            first.getInventory().items.set(0, new ItemStack(Items.IRON_INGOT, 16));
            boolean refused = false;
            try {
                director.guiAction(second, new com.marscommand.mod.network.MissionActionPayload(id,
                        com.marscommand.mod.network.MissionActionPayload.Action.SUBMIT));
            } catch (IllegalArgumentException expected) {
                refused = true;
            }
            helper.assertTrue(refused, "Forged GUI actions must not submit someone else's mission");
            director.guiAction(first, new com.marscommand.mod.network.MissionActionPayload(id,
                    com.marscommand.mod.network.MissionActionPayload.Action.SUBMIT));
            helper.assertTrue(director.state().missions.get(id).status == MissionState.Status.COMPLETED,
                    "Valid GUI submission must complete the mission");
            helper.assertTrue(first.messages.isEmpty() && second.messages.isEmpty(), "GUI interactions must not use chat");
            director.state().activeMillis += 1000;
            director.journalPage(first, new com.marscommand.mod.network.JournalRequestPayload(2, 0));
            helper.assertTrue(MissionJournal.entries(director.state(), first.getStringUUID()).getFirst().completed(),
                    "Completed mission must remain visible in the player's tree");
            helper.succeed();
        } finally {
            remove(first);
            remove(second);
        }
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void arrivalTracksApproachAndNormalSchedulingWaitsForRecovery(GameTestHelper helper) {
        TestPlayer first = player(helper, "moon-arrive-a", 0);
        TestPlayer second = player(helper, "moon-arrive-b", 1);
        try {
            MoonDirector director = director(helper);
            second.setHealth(2);
            long id = director.assignTest(first, "survey_point");
            MissionState.Mission mission = director.state().missions.get(id);
            first.moveTo(mission.target.x() - 54, mission.target.y(), mission.target.z());
            director.schedule();
            helper.assertTrue(Math.abs(mission.progress - 0.5) < 0.000001, "Arrival must track verified approach");
            helper.assertTrue(!director.state().hasAssignment(second.getStringUUID()),
                    "Normal scheduling must not strike or assign an unrecovered player");
            first.moveTo(mission.target.x(), mission.target.y(), mission.target.z());
            director.schedule();
            helper.assertTrue(mission.status == MissionState.Status.COMPLETED, "Arrival must complete at its coordinates");
            helper.assertTrue(director.state().player(first.getStringUUID()).score == 10, "Arrival completion must earn points");
            boolean forbidden = false;
            try {
                helper.getLevel().getServer().getCommands().getDispatcher().execute("marscommand admin inspect",
                        second.createCommandSourceStack().withPermission(0));
            } catch (com.mojang.brigadier.exceptions.CommandSyntaxException expected) {
                forbidden = true;
            }
            helper.assertTrue(forbidden, "Ordinary players cannot execute secret-plan operator commands");
            helper.succeed();
        } finally {
            remove(first);
            remove(second);
        }
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void repeatedHandoverSelectsBusyWeakPlayers(GameTestHelper helper) {
        TestPlayer first = player(helper, "moon-route-a", 64);
        TestPlayer second = player(helper, "moon-route-b", 32);
        TestPlayer third = player(helper, "moon-route-c", 48);
        try {
            MoonDirector director = director(helper);
            long id = director.assignTest(first, "iron_delivery");
            long busyId = director.assignTest(second, "survey_point");
            MissionState.Mission mission = director.state().missions.get(id);
            MissionLogic.progress(director.state().missions.get(busyId), 0.1);
            second.setHealth(1);
            third.setHealth(0.5F);
            director.state().activeMillis = 540000;
            director.schedule();
            helper.assertTrue(mission.owner.equals(second.getStringUUID()), "Nearest busy, weak candidate must receive handover");
            helper.assertTrue(director.owned(second).size() == 2, "Forced assignments must support concurrent missions");
            helper.assertTrue(second.getHealth() == 1, "Half-heart floor must preserve health");
            helper.assertTrue(director.state().player(first.getStringUUID()).score == -5, "Zero-attempt handover penalty");
            long deadline = mission.deadlineMillis;
            director.state().activeMillis += director.rules().attemptWindowMillis() - 1;
            director.schedule();
            helper.assertTrue(mission.owner.equals(second.getStringUUID()), "Replacement must get an attempt window");
            director.state().activeMillis++;
            director.schedule();
            helper.assertTrue(mission.owner.equals(third.getStringUUID()), "Next transfer must exclude all previous owners");
            helper.assertTrue(third.getHealth() == 0.5F, "Emergency strike must not heal or kill a below-floor player");
            helper.assertTrue(mission.deadlineMillis == deadline && mission.history.size() == 3,
                    "Repeated handovers must retain original deadline and ownership history");
            director.state().activeMillis = deadline;
            director.schedule();
            helper.assertTrue(mission.status == MissionState.Status.EXPIRED, "Original deadline must win over handover grace");
            helper.succeed();
        } finally {
            remove(first);
            remove(second);
            remove(third);
        }
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void transmissionsHaveNoServerLightningSideEffects(GameTestHelper helper) {
        TestPlayer player = player(helper, "moon-strike-a", 0);
        MoonDirector director = director(helper);
        helper.setBlock(new BlockPos(2, 0, 2), Blocks.OXIDIZED_COPPER);
        helper.setBlock(new BlockPos(3, 0, 2), Blocks.LIGHTNING_ROD);
        var pig = helper.spawn(EntityType.PIG, new BlockPos(1, 1, 2));
        float pigHealth = pig.getHealth();
        player.getInventory().armor.set(2, new ItemStack(Items.NETHERITE_CHESTPLATE));
        player.addEffect(new net.minecraft.world.effect.MobEffectInstance(
                net.minecraft.world.effect.MobEffects.ABSORPTION, 200));
        helper.assertTrue(player.getAbsorptionAmount() == 4, "Test player must have real absorption hearts");
        director.testStrike(player, false);
        helper.assertTrue(player.getHealth() == 18 && player.getAbsorptionAmount() == 4,
                "Ordinary transmission removes exactly one heart independently of armour/absorption");
        director.testStrike(player, true);
        helper.assertTrue(player.getHealth() == 17, "Emergency transmission removes exactly half a heart");
        helper.runAfterDelay(5, () -> {
            try {
                helper.assertTrue(helper.getBlockState(new BlockPos(2, 0, 2)).is(Blocks.OXIDIZED_COPPER),
                        "Transmission must not clean copper");
                helper.assertTrue(!helper.getBlockState(new BlockPos(3, 0, 2))
                        .getValue(net.minecraft.world.level.block.LightningRodBlock.POWERED), "Transmission must not power rods");
                helper.assertTrue(pig.isAlive() && pig.getHealth() == pigHealth, "Transmission must not hit or transform mobs");
                helper.assertTrue(!player.isOnFire(), "Transmission must not ignite the player");
                helper.assertTrue(helper.getLevel().getEntities(EntityType.LIGHTNING_BOLT,
                        entity -> entity.distanceToSqr(player) < 256).isEmpty(), "No server lightning entity may exist");
                helper.succeed();
            } finally {
                remove(player);
            }
        });
    }
}
