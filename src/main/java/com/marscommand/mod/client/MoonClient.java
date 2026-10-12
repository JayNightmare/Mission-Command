package com.marscommand.mod.client;

import com.marscommand.mod.network.TransmissionPayload;
import com.marscommand.mod.network.JournalRequestPayload;
import com.marscommand.mod.network.JournalPagePayload;
import com.marscommand.mod.network.MissionActionPayload;
import com.marscommand.mod.network.MissionNoticePayload;
import com.marscommand.mod.mission.MissionJournal;
import com.mojang.logging.LogUtils;
import java.util.List;
import java.util.WeakHashMap;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LightningBolt;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.RenderGuiEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.network.PacketDistributor;

@EventBusSubscriber(modid = "mars_command", value = Dist.CLIENT)
public final class MoonClient {
    private static final java.util.concurrent.atomic.AtomicInteger NEXT_VISUAL_ID =
            new java.util.concurrent.atomic.AtomicInteger(-1);
    private static final WeakHashMap<AbstractContainerScreen<?>, Button> INVENTORY_BUTTONS = new WeakHashMap<>();
    private static final Typewriter TRANSMISSIONS = new Typewriter();
    private static List<MissionJournal.Entry> entries = List.of();
    private static JournalAssembler assembler;
    private static int generation;
    private static int score;
    private static String rank = "";
    private static long snapshotMillis;
    private static long requestedMillis;
    private static long lastRequestMillis;
    private static boolean loaded;
    private static boolean actionPending;
    private static long actionMillis;
    private static long confirmationMission;
    private static long confirmationUntilMillis;

    private MoonClient() {}

    public static void receive(TransmissionPayload payload) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null || !Double.isFinite(payload.x()) || !Double.isFinite(payload.y())
                || !Double.isFinite(payload.z())) {
            LogUtils.getLogger().warn("Rejected Mars Command visual: missing client world or invalid coordinates");
            return;
        }
        LightningBolt bolt = EntityType.LIGHTNING_BOLT.create(minecraft.level);
        if (bolt == null) {
            LogUtils.getLogger().error("Cannot create Mars Command lightning visual");
            return;
        }
        bolt.setId(NEXT_VISUAL_ID.getAndDecrement());
        bolt.setVisualOnly(true);
        bolt.moveTo(payload.x(), payload.y(), payload.z());
        minecraft.level.addEntity(bolt);
    }

    public static void openJournal(long selectedMission) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || minecraft.getConnection() == null) {
            LogUtils.getLogger().warn("Cannot open Mars Command journal without a connected player");
            return;
        }
        if (minecraft.screen instanceof MissionScreen screen) {
            screen.select(selectedMission);
            requestJournal();
        } else {
            minecraft.setScreen(new MissionScreen(minecraft.screen, selectedMission));
        }
    }

    public static void requestJournal() {
        Minecraft minecraft = Minecraft.getInstance();
        if (assembler != null || minecraft.getConnection() == null || Util.getMillis() - lastRequestMillis < 1000) {
            return;
        }
        assembler = new JournalAssembler(++generation);
        requestedMillis = lastRequestMillis = Util.getMillis();
        PacketDistributor.sendToServer(new JournalRequestPayload(generation, 0));
    }

    public static void receivePage(JournalPagePayload payload) {
        if (assembler == null) {
            return;
        }
        try {
            if (!assembler.accept(payload)) {
                return;
            }
            if (!assembler.complete()) {
                PacketDistributor.sendToServer(new JournalRequestPayload(generation, assembler.nextPage()));
                return;
            }
            entries = assembler.entries();
            score = assembler.score();
            rank = assembler.rank();
            snapshotMillis = requestedMillis;
            loaded = true;
            assembler = null;
            if (Minecraft.getInstance().screen instanceof MissionScreen screen) {
                screen.updated();
            }
        } catch (IllegalArgumentException exception) {
            LogUtils.getLogger().error("Rejected inconsistent Mars Command journal snapshot", exception);
            assembler = null;
            receiveNotice(new MissionNoticePayload(Component.translatable("gui.mars_command.sync_error"), false, 0, 0, true));
        }
    }

    public static void receiveNotice(MissionNoticePayload payload) {
        actionPending = false;
        confirmationMission = payload.confirmationMission();
        confirmationUntilMillis = Util.getMillis() + payload.confirmationMillis();
        try {
            TRANSMISSIONS.offer(payload.message().getString(), payload.typewriter(), payload.error());
        } catch (IllegalArgumentException exception) {
            LogUtils.getLogger().error("Rejected oversized Mars Command transmission", exception);
        }
        if (Minecraft.getInstance().screen instanceof MissionScreen screen) {
            screen.notice(payload.message(), payload.error());
        }
    }

    public static void action(long mission, MissionActionPayload.Action action) {
        if (actionPending || Minecraft.getInstance().getConnection() == null) {
            return;
        }
        actionPending = true;
        actionMillis = Util.getMillis();
        PacketDistributor.sendToServer(new MissionActionPayload(mission, action));
    }

    public static boolean confirming(long mission) {
        return confirmationMission == mission && confirmationUntilMillis > Util.getMillis();
    }

    public static boolean actionPending() {
        return actionPending;
    }

    public static List<MissionJournal.Entry> entries() {
        return entries;
    }

    public static int score() {
        return score;
    }

    public static String rank() {
        return rank;
    }

    public static boolean loaded() {
        return loaded;
    }

    public static boolean loading() {
        return assembler != null;
    }

    public static long remaining(MissionJournal.Entry entry) {
        return Math.max(0, entry.remainingMillis() - (Util.getMillis() - snapshotMillis));
    }

    @SubscribeEvent
    public static void tick(ClientTickEvent.Post event) {
        TRANSMISSIONS.tick();
        if (assembler != null && Util.getMillis() - requestedMillis > 15000) {
            assembler = null;
            LogUtils.getLogger().warn("Mars Command journal request timed out");
            receiveNotice(new MissionNoticePayload(Component.translatable("gui.mars_command.sync_error"), false, 0, 0, true));
        }
        if (actionPending && Util.getMillis() - actionMillis > 15000) {
            actionPending = false;
            LogUtils.getLogger().warn("Mars Command mission action timed out");
            receiveNotice(new MissionNoticePayload(Component.translatable("gui.mars_command.action_timeout"), false, 0, 0, true));
        }
    }

    @SubscribeEvent
    public static void disconnected(ClientPlayerNetworkEvent.LoggingOut event) {
        entries = List.of();
        assembler = null;
        loaded = false;
        rank = "";
        score = 0;
        snapshotMillis = lastRequestMillis = 0;
        confirmationMission = confirmationUntilMillis = 0;
        actionPending = false;
        TRANSMISSIONS.clear();
        INVENTORY_BUTTONS.clear();
    }

    @SubscribeEvent
    public static void inventory(ScreenEvent.Init.Post event) {
        if (event.getScreen() instanceof AbstractContainerScreen<?> inventory
                && (inventory instanceof InventoryScreen || inventory instanceof CreativeModeInventoryScreen)) {
            Button button = Button.builder(Component.literal("MC"), ignored -> openJournal(0))
                    .bounds(0, 0, 24, 20).tooltip(Tooltip.create(Component.translatable("gui.mars_command.inventory"))).build();
            position(inventory, button);
            event.addListener(button);
            INVENTORY_BUTTONS.put(inventory, button);
        }
    }

    @SubscribeEvent
    public static void beforeScreen(ScreenEvent.Render.Pre event) {
        if (event.getScreen() instanceof AbstractContainerScreen<?> inventory) {
            Button button = INVENTORY_BUTTONS.get(inventory);
            if (button != null) {
                position(inventory, button);
            }
        }
    }

    private static void position(AbstractContainerScreen<?> inventory, Button button) {
        int x = inventory.getGuiLeft() + inventory.getXSize() + 4;
        button.setPosition(Math.clamp(x, 4, Math.max(4, inventory.width - 28)),
                Math.clamp(inventory.getGuiTop() + inventory.getYSize() - 24, 4, Math.max(4, inventory.height - 24)));
        button.active = inventory.getMenu().getCarried().isEmpty();
        button.setTooltip(Tooltip.create(Component.translatable(button.active
                ? "gui.mars_command.inventory" : "gui.mars_command.held_stack")));
    }

    @SubscribeEvent
    public static void hud(RenderGuiEvent.Post event) {
        if (Minecraft.getInstance().screen == null) {
            renderTransmission(event.getGuiGraphics());
        }
    }

    @SubscribeEvent
    public static void screenHud(ScreenEvent.Render.Post event) {
        if (Minecraft.getInstance().level != null) {
            renderTransmission(event.getGuiGraphics());
        }
    }

    private static void renderTransmission(GuiGraphics graphics) {
        Minecraft minecraft = Minecraft.getInstance();
        if (!TRANSMISSIONS.visible() || minecraft.player == null || minecraft.options.hideGui) {
            return;
        }
        int width = Math.min(420, graphics.guiWidth() - 16);
        int height = Math.min(minecraft.screen instanceof MissionScreen ? 48 : 124, graphics.guiHeight() / 3);
        int x = (graphics.guiWidth() - width) / 2;
        int y = 8;
        graphics.pose().pushPose();
        graphics.pose().translate(0, 0, 600);
        graphics.fill(x, y, x + width, y + height, 0xE8081421);
        graphics.renderOutline(x, y, width, height, TRANSMISSIONS.error() ? 0xFFFF7777 : 0xFF53C5DB);
        graphics.drawString(minecraft.font, Component.translatable("gui.mars_command.transmission"), x + 8, y + 7, 0xFF53C5DB);
        String text = TRANSMISSIONS.text() + (TRANSMISSIONS.typing() ? "|" : "");
        var lines = minecraft.font.split(Component.literal(text), width - 16);
        int visible = Math.max(1, (height - 28) / minecraft.font.lineHeight);
        int start = Math.max(0, lines.size() - visible);
        for (int index = start; index < lines.size(); index++) {
            graphics.drawString(minecraft.font, lines.get(index), x + 8,
                    y + 22 + (index - start) * minecraft.font.lineHeight, 0xFFE6F1F4);
        }
        graphics.pose().popPose();
    }
}
