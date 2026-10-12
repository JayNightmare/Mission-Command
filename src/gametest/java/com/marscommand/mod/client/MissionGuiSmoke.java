package com.marscommand.mod.client;

import com.marscommand.mod.mission.MissionRules;
import com.marscommand.mod.mission.MoonDirector;
import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;

@EventBusSubscriber(modid = "mars_command", value = Dist.CLIENT)
public final class MissionGuiSmoke {
    private static volatile int stage;
    private static volatile long mission;
    private static int ticks;
    private static int age;

    private MissionGuiSmoke() {}

    @SubscribeEvent
    public static void tick(ClientTickEvent.Post event) {
        if (!Boolean.getBoolean("marscommand.guiSmoke")) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (++age > 2400) {
            fail(minecraft, "GUI smoke test timed out");
            return;
        }
        if (minecraft.player == null || minecraft.getSingleplayerServer() == null) {
            return;
        }
        ticks++;
        try {
            switch (stage) {
                case 0 -> {
                    stage = 1;
                    ticks = 0;
                    server(minecraft, (director, player) -> {
                        prepare(director, player, "iron_delivery");
                        mission = director.assignTest(player, "iron_delivery");
                        stage = 2;
                    });
                }
                case 2 -> {
                    if (ticks >= 40 && minecraft.screen == null) {
                        capture(minecraft, "mars-command-typewriter.png");
                        stage = 3;
                        server(minecraft, (director, player) -> {
                            var assigned = director.state().missions.get(mission);
                            player.getInventory().add(new ItemStack(Items.IRON_INGOT, assigned.quantity));
                            director.submit(player, mission);
                            prepare(director, player, "survey_point");
                            mission = director.assignTest(player, "survey_point");
                            var target = director.state().missions.get(mission).target;
                            player.connection.teleport(target.x(), target.y() + 1, target.z(), 0, 0);
                            stage = 4;
                        });
                    }
                }
                case 4 -> {
                    stage = 5;
                    server(minecraft, (director, player) -> {
                        if (director.state().missions.get(mission).status
                                != com.marscommand.mod.mission.MissionState.Status.COMPLETED) {
                            stage = 4;
                            return;
                        }
                        prepare(director, player, "iron_delivery");
                        mission = director.assignTest(player, "iron_delivery");
                        player.getInventory().add(new ItemStack(Items.IRON_INGOT,
                                director.state().missions.get(mission).quantity));
                        stage = 6;
                    });
                }
                case 6 -> {
                    MissionRules.require(MoonClient.arrival() != null && MoonClient.arrival().state().equals("local")
                            && MoonClient.arrival().confirmed() && MoonClient.arrival().callSign().matches("[0-9]{4}"),
                            "Single-player must receive a local callsign without website confirmation");
                    minecraft.setScreen(new InventoryScreen(minecraft.player));
                    minecraft.player.inventoryMenu.setCarried(new ItemStack(Items.STONE));
                    stage = 7;
                    ticks = 0;
                }
                case 7 -> {
                    if (ticks >= 10) {
                        Button button = inventoryButton(minecraft);
                        MissionRules.require(!button.active, "Inventory button must protect a carried item stack");
                        minecraft.player.inventoryMenu.setCarried(ItemStack.EMPTY);
                        stage = 8;
                        ticks = 0;
                    }
                }
                case 8 -> {
                    if (ticks >= 5) {
                        Button button = inventoryButton(minecraft);
                        MissionRules.require(button.active, "Inventory button must be enabled after placing the stack");
                        capture(minecraft, "mars-command-inventory.png");
                        button.onPress();
                        stage = 9;
                        ticks = 0;
                    }
                }
                case 9 -> {
                    if (MoonClient.loaded() && ticks >= 20) {
                        MissionRules.require(minecraft.screen instanceof MissionScreen, "Inventory button must open the mission tree");
                        long completed = MoonClient.entries().stream().filter(entry -> entry.completed()).count();
                        MissionRules.require(MoonClient.entries().size() == 3 && completed >= 2,
                                "Tree must contain all three issued personal missions, including both prior completions");
                        minecraft.screen.mouseScrolled(28, 96, 0, -1);
                        stage = 12;
                        ticks = 0;
                    }
                }
                case 10 -> {
                    if (MoonClient.entries().size() == 3 && MoonClient.entries().stream().allMatch(entry -> entry.completed())) {
                        stage = 14;
                        ticks = 0;
                    }
                }
                case 12 -> {
                    if (ticks >= 5) {
                        stage = 13;
                        Screenshot.grab(minecraft.gameDirectory, "mars-command-tree.png", minecraft.getMainRenderTarget(),
                                message -> minecraft.execute(() -> {
                                    LogUtils.getLogger().info("GUI smoke screenshot: {}", message.getString());
                                    Button submit = minecraft.screen.children().stream().filter(Button.class::isInstance)
                                            .map(Button.class::cast).filter(button -> button.getMessage().getString().equals("Submit Supplies"))
                                            .findFirst().orElseThrow(() -> new IllegalStateException("Missing GUI submit control"));
                                    long completed = MoonClient.entries().stream().filter(entry -> entry.completed()).count();
                                    if (completed == 2) {
                                        MissionRules.require(submit.active, "Ongoing delivery must have an active submit control");
                                        submit.onPress();
                                    } else {
                                        MissionRules.require(!submit.active, "Completed delivery must disable the submit control");
                                    }
                                    stage = 10;
                                }));
                    }
                }
                case 14 -> {
                    if (ticks >= 5) {
                        stage = 15;
                        Screenshot.grab(minecraft.gameDirectory, "mars-command-completed.png", minecraft.getMainRenderTarget(),
                                message -> minecraft.execute(() -> {
                                    LogUtils.getLogger().info("GUI smoke screenshot: {}", message.getString());
                                    minecraft.setScreen(new ArrivalScreen(minecraft.screen));
                                    ticks = 0;
                                    stage = 16;
                                }));
                    }
                }
                case 16 -> {
                    if (ticks >= 10) {
                        Button website = minecraft.screen.children().stream().filter(Button.class::isInstance)
                                .map(Button.class::cast).filter(button -> button.getMessage().getString().equals("Open Confirmation Website"))
                                .findFirst().orElseThrow(() -> new IllegalStateException("Missing arrival website control"));
                        MissionRules.require(!website.active, "Single-player must not request website linking");
                        stage = 11;
                        Screenshot.grab(minecraft.gameDirectory, "mars-command-player-id.png", minecraft.getMainRenderTarget(),
                                message -> minecraft.execute(() -> {
                                    LogUtils.getLogger().info("MARS_COMMAND_GUI_SMOKE_PASSED: inventory, tree, typewriter, "
                                            + "GUI submission and local player callsign. {}", message.getString());
                                    minecraft.stop();
                                }));
                    }
                }
                default -> {}
            }
        } catch (IllegalArgumentException | IllegalStateException exception) {
            fail(minecraft, exception.getMessage());
        }
    }

    private static Button inventoryButton(Minecraft minecraft) {
        MissionRules.require(minecraft.screen instanceof InventoryScreen, "Expected the player inventory");
        return minecraft.screen.children().stream().filter(Button.class::isInstance).map(Button.class::cast)
                .filter(button -> button.getMessage().getString().equals("MC")).findFirst()
                .orElseThrow(() -> new IllegalStateException("Missing inventory mission button"));
    }

    private static void prepare(MoonDirector director, ServerPlayer player, String template) {
        player.setGameMode(GameType.SURVIVAL);
        player.setHealth(20);
        player.getFoodData().setFoodLevel(20);
        director.state().player(player.getStringUUID()).nextAssignmentMillis = 0;
        var target = director.rules().template(template).target();
        var floor = net.minecraft.core.BlockPos.containing(target.x(), target.y(), target.z());
        for (int x = -1; x <= 1; x++) {
            for (int z = -1; z <= 1; z++) {
                player.serverLevel().setBlockAndUpdate(floor.offset(x, 0, z),
                        net.minecraft.world.level.block.Blocks.STONE.defaultBlockState());
            }
        }
        player.connection.teleport(target.x(), target.y() + 1, target.z(), 0, 0);
    }

    @FunctionalInterface
    private interface Operation {
        void run(MoonDirector director, ServerPlayer player);
    }

    private static void server(Minecraft minecraft, Operation operation) {
        var server = minecraft.getSingleplayerServer();
        MissionRules.require(server != null, "GUI smoke test needs an integrated server");
        var uuid = minecraft.player.getUUID();
        server.execute(() -> {
            try {
                MissionRules.require(server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize()
                        .getFileName().toString().equals("MarsCommandGuiTest"), "Refusing to modify a non-test world");
                var player = server.getPlayerList().getPlayer(uuid);
                MissionRules.require(player != null, "GUI smoke player is not connected");
                operation.run(MoonDirector.get(server), player);
            } catch (IllegalArgumentException | IllegalStateException exception) {
                minecraft.execute(() -> fail(minecraft, exception.getMessage()));
            }
        });
    }

    private static void capture(Minecraft minecraft, String name) {
        Screenshot.grab(minecraft.gameDirectory, name, minecraft.getMainRenderTarget(),
                message -> LogUtils.getLogger().info("GUI smoke screenshot: {}", message.getString()));
    }

    private static void fail(Minecraft minecraft, String message) {
        LogUtils.getLogger().error("MARS_COMMAND_GUI_SMOKE_FAILED: {}", message);
        stage = 11;
        minecraft.stop();
    }
}
