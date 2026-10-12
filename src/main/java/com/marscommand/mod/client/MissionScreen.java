package com.marscommand.mod.client;

import com.marscommand.mod.mission.MissionJournal;
import com.marscommand.mod.mission.MissionRules;
import com.marscommand.mod.network.MissionActionPayload;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.Util;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

public final class MissionScreen extends Screen {
    private static final int ROW_HEIGHT = 28;
    private final Screen previous;
    private final EnumSet<MissionRules.Kind> collapsed = EnumSet.noneOf(MissionRules.Kind.class);
    private final Map<Long, Button> nodes = new HashMap<>();
    private long selected;
    private int treeScroll;
    private int treeHeight;
    private int detailScroll;
    private int detailHeight;
    private long refreshMillis;
    private Button submit;
    private Button refuse;
    private Component feedback = Component.empty();
    private boolean feedbackError;
    private long feedbackUntil;

    public MissionScreen(Screen previous, long selected) {
        super(Component.translatable("gui.mars_command.title"));
        this.previous = previous;
        this.selected = selected;
    }

    @Override
    protected void init() {
        updated();
        MoonClient.requestJournal();
        refreshMillis = Util.getMillis();
    }

    public void select(long id) {
        if (id > 0) {
            selected = id;
            detailScroll = 0;
            rebuild();
        }
    }

    public void updated() {
        if (MoonClient.loaded() && MoonClient.entries().stream().noneMatch(entry -> entry.id() == selected)) {
            selected = MoonClient.entries().stream().filter(entry -> !entry.completed()).findFirst()
                    .or(() -> MoonClient.entries().stream().findFirst()).map(MissionJournal.Entry::id).orElse(0L);
            detailScroll = 0;
        }
        rebuild();
    }

    public void notice(Component message, boolean error) {
        feedback = message;
        feedbackError = error;
        feedbackUntil = Util.getMillis() + 8000;
        controls();
    }

    private int treeWidth() {
        return Math.max(110, (width - 36) * 2 / 5);
    }

    private int right() {
        return 24 + treeWidth();
    }

    private int rightWidth() {
        return width - right() - 12;
    }

    private int treeTop() {
        return 56;
    }

    private int treeBottom() {
        return height - 40;
    }

    private int detailBottom() {
        return height - 76;
    }

    private MissionJournal.Entry entry() {
        return MoonClient.entries().stream().filter(value -> value.id() == selected).findFirst().orElse(null);
    }

    private void rebuild() {
        long focused = nodes.entrySet().stream().filter(node -> node.getValue() == getFocused())
                .map(Map.Entry::getKey).findFirst().orElse(0L);
        nodes.clear();
        clearWidgets();
        int rows = 0;
        for (MissionRules.Kind kind : MissionRules.Kind.values()) {
            List<MissionJournal.Entry> group = group(kind);
            if (!group.isEmpty()) {
                rows += 1 + (collapsed.contains(kind) ? 0 : group.size());
            }
        }
        treeHeight = 32 + rows * ROW_HEIGHT;
        treeScroll = Math.clamp(treeScroll, 0, Math.max(0, treeHeight - (treeBottom() - treeTop())));
        int y = treeTop() + 32 - treeScroll;
        for (MissionRules.Kind kind : MissionRules.Kind.values()) {
            List<MissionJournal.Entry> group = group(kind);
            if (group.isEmpty()) {
                continue;
            }
            if (rowVisible(y)) {
                Component label = Component.literal(collapsed.contains(kind) ? "+ " : "- ")
                        .append(Component.translatable(kind == MissionRules.Kind.DELIVERY
                                ? "gui.mars_command.deliveries" : "gui.mars_command.surveys"));
                addRenderableWidget(Button.builder(label, ignored -> {
                    if (!collapsed.remove(kind)) {
                        collapsed.add(kind);
                    }
                    rebuild();
                }).bounds(28, y, treeWidth() - 24, 22).build());
            }
            y += ROW_HEIGHT;
            if (!collapsed.contains(kind)) {
                for (MissionJournal.Entry mission : group) {
                    if (rowVisible(y)) {
                        Button node = addRenderableWidget(Button.builder(Component.translatable("gui.mars_command.node",
                                mission.id(), Component.translatable(mission.completed()
                                        ? "gui.mars_command.completed" : "gui.mars_command.ongoing")), ignored -> {
                            selected = mission.id();
                            detailScroll = 0;
                            rebuild();
                        }).bounds(44, y, treeWidth() - 40, 22).build());
                        nodes.put(mission.id(), node);
                    }
                    y += ROW_HEIGHT;
                }
            }
        }
        int buttonWidth = (rightWidth() - 6) / 2;
        submit = addRenderableWidget(Button.builder(Component.translatable("gui.mars_command.submit"),
                ignored -> MoonClient.action(selected, MissionActionPayload.Action.SUBMIT))
                .bounds(right(), height - 66, buttonWidth, 20).build());
        refuse = addRenderableWidget(Button.builder(Component.translatable("gui.mars_command.refuse"),
                ignored -> MoonClient.action(selected, MoonClient.confirming(selected)
                        ? MissionActionPayload.Action.CONFIRM_REFUSAL : MissionActionPayload.Action.REFUSE))
                .bounds(right() + buttonWidth + 6, height - 66, buttonWidth, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("gui.mars_command.refresh"),
                ignored -> MoonClient.requestJournal()).bounds(12, height - 28, 80, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("gui.mars_command.close"),
                ignored -> onClose()).bounds(width - 92, height - 28, 80, 20).build());
        controls();
        if (focused > 0 && nodes.containsKey(focused)) {
            setFocused(nodes.get(focused));
        }
    }

    private boolean rowVisible(int y) {
        return y >= treeTop() && y + 22 <= treeBottom();
    }

    private List<MissionJournal.Entry> group(MissionRules.Kind kind) {
        return MoonClient.entries().stream().filter(entry -> entry.kind() == kind)
                .sorted(java.util.Comparator.comparing(MissionJournal.Entry::completed)
                        .thenComparingLong(MissionJournal.Entry::id)).toList();
    }

    private void controls() {
        if (submit == null || refuse == null) {
            return;
        }
        MissionJournal.Entry mission = entry();
        boolean active = mission != null && !mission.completed() && MoonClient.remaining(mission) > 0
                && !MoonClient.actionPending();
        submit.active = active && mission.kind() == MissionRules.Kind.DELIVERY
                && minecraft.player != null && !minecraft.player.isCreative() && !minecraft.player.isSpectator();
        refuse.active = active;
        refuse.setMessage(Component.translatable(MoonClient.confirming(selected)
                ? "gui.mars_command.confirm_refusal" : "gui.mars_command.refuse"));
    }

    @Override
    public void tick() {
        controls();
        if (Util.getMillis() - refreshMillis >= 2000 && !MoonClient.loading()) {
            MoonClient.requestJournal();
            refreshMillis = Util.getMillis();
        }
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderTransparentBackground(graphics);
        graphics.fill(6, 6, width - 6, height - 6, 0xF0081421);
        graphics.renderOutline(6, 6, width - 12, height - 12, 0xFF3583A8);
        graphics.drawString(font, title, 14, 14, 0xFF53C5DB);
        graphics.drawString(font, MoonClient.loaded()
                ? Component.translatable("message.mars_command.status", MoonClient.score(), MoonClient.rank())
                : Component.translatable("gui.mars_command.loading"), 14, 34, 0xFFE6F1F4);
        graphics.fill(12, treeTop(), 12 + treeWidth(), treeBottom(), 0xFF0D2030);
        graphics.fill(right(), treeTop(), width - 12, detailBottom(), 0xFF0D2030);
        graphics.enableScissor(12, treeTop(), 12 + treeWidth(), treeBottom());
        graphics.drawString(font, Component.translatable("gui.mars_command.root"),
                20, treeTop() + 10 - treeScroll, 0xFF53C5DB);
        int y = treeTop() + 32 - treeScroll;
        for (MissionRules.Kind kind : MissionRules.Kind.values()) {
            List<MissionJournal.Entry> group = group(kind);
            if (group.isEmpty()) {
                continue;
            }
            graphics.vLine(20, treeTop() + 22 - treeScroll, y + 11, 0xFF3583A8);
            graphics.hLine(20, 27, y + 11, 0xFF3583A8);
            y += ROW_HEIGHT;
            if (!collapsed.contains(kind)) {
                for (MissionJournal.Entry mission : group) {
                    graphics.vLine(36, y - ROW_HEIGHT + 21, y + 11, 0xFF3583A8);
                    graphics.hLine(36, 43, y + 11, mission.completed() ? 0xFF71D7A1 : 0xFFF0C76B);
                    if (mission.id() == selected && rowVisible(y)) {
                        graphics.renderOutline(42, y - 2, treeWidth() - 36, 26, 0xFF53C5DB);
                    }
                    y += ROW_HEIGHT;
                }
            }
        }
        if (MoonClient.entries().isEmpty()) {
            drawWrapped(graphics, Component.translatable(MoonClient.loaded()
                    ? "gui.mars_command.empty" : "gui.mars_command.loading"), 20, treeTop() + 38, treeWidth() - 16, 0xFFACBDC8);
        }
        graphics.disableScissor();
        int viewport = treeBottom() - treeTop();
        if (treeHeight > viewport) {
            int thumb = Math.max(16, viewport * viewport / treeHeight);
            int top = treeTop() + treeScroll * (viewport - thumb) / (treeHeight - viewport);
            graphics.fill(12 + treeWidth() - 4, treeTop(), 12 + treeWidth() - 2, treeBottom(), 0xFF213C4C);
            graphics.fill(12 + treeWidth() - 4, top, 12 + treeWidth() - 2, top + thumb, 0xFF53C5DB);
        }
        renderDetails(graphics);
        if (feedbackUntil > Util.getMillis()) {
            graphics.drawString(font, font.plainSubstrByWidth(feedback.getString(), width - 28), 14, height - 40,
                    feedbackError ? 0xFFFF7777 : 0xFFBFE4EC);
        }
        super.render(graphics, mouseX, mouseY, partialTick);
    }

    @Override
    public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        // The custom render method already draws the background; the default would blur the mission text.
    }

    private void renderDetails(GuiGraphics graphics) {
        MissionJournal.Entry mission = entry();
        graphics.enableScissor(right(), treeTop(), width - 12, detailBottom());
        int y = treeTop() + 8 - detailScroll;
        if (mission == null) {
            drawWrapped(graphics, Component.translatable("gui.mars_command.select"),
                    right() + 8, y, rightWidth() - 16, 0xFFACBDC8);
            graphics.disableScissor();
            return;
        }
        List<Component> text = new ArrayList<>();
        text.add(Component.translatable("gui.mars_command.mission_title", mission.id()));
        text.add(Component.translatable(mission.completed() ? "gui.mars_command.completed" : "gui.mars_command.ongoing"));
        text.add(mission.kind() == MissionRules.Kind.DELIVERY
                ? Component.translatable("objective.mars_command.delivery", mission.quantity(), mission.item())
                : Component.translatable("objective.mars_command.arrival"));
        text.add(Component.literal(mission.target().dimension()));
        text.add(Component.translatable("gui.mars_command.coordinates", Math.round(mission.target().x()),
                Math.round(mission.target().y()), Math.round(mission.target().z())));
        text.add(Component.translatable("gui.mars_command.progress", (int) Math.floor(mission.progress() * 100)));
        if (mission.kind() == MissionRules.Kind.DELIVERY) {
            text.add(Component.translatable("gui.mars_command.supplies", mission.delivered(), mission.quantity()));
        }
        if (!mission.completed()) {
            text.add(Component.translatable("gui.mars_command.remaining", (MoonClient.remaining(mission) + 999) / 1000));
        }
        if (mission.abbreviated()) {
            text.add(Component.translatable("gui.mars_command.abbreviated"));
        } else {
            text.add(Component.literal(mission.briefing()));
        }
        if (MoonClient.confirming(selected)) {
            text.add(feedback);
        }
        int start = y;
        for (int index = 0; index < text.size(); index++) {
            y += drawWrapped(graphics, text.get(index), right() + 8, y, rightWidth() - 16,
                    index == 0 ? 0xFF53C5DB : 0xFFE6F1F4) + 5;
        }
        detailHeight = y - start;
        graphics.disableScissor();
    }

    private int drawWrapped(GuiGraphics graphics, Component text, int x, int y, int width, int color) {
        var lines = font.split(text, Math.max(1, width));
        for (var line : lines) {
            graphics.drawString(font, line, x, y, color);
            y += font.lineHeight;
        }
        return lines.size() * font.lineHeight;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontal, double vertical) {
        if (mouseY >= treeTop() && mouseY < treeBottom() && mouseX >= 12 && mouseX < 12 + treeWidth()) {
            treeScroll = Math.clamp(treeScroll - (int) (vertical * ROW_HEIGHT), 0,
                    Math.max(0, treeHeight - (treeBottom() - treeTop())));
            rebuild();
            return true;
        }
        if (mouseX >= right() && mouseX < width - 12 && mouseY >= treeTop() && mouseY < detailBottom()) {
            detailScroll = Math.clamp(detailScroll - (int) (vertical * 18), 0,
                    Math.max(0, detailHeight - (detailBottom() - treeTop()) + 16));
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, horizontal, vertical);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void onClose() {
        minecraft.setScreen(previous);
    }
}
