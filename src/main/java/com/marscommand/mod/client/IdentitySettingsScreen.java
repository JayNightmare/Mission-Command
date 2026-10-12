package com.marscommand.mod.client;

import com.marscommand.mod.network.IdentitySettingsPayload;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Checkbox;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;

public final class IdentitySettingsScreen extends Screen {
    private final Screen previous;
    private Checkbox enabled;
    private EditBox api;
    private EditBox website;
    private Component feedback = Component.empty();

    public IdentitySettingsScreen(Screen previous) {
        super(Component.translatable("arrival.mars_command.settings"));
        this.previous = previous;
    }

    @Override
    protected void init() {
        var state = MoonClient.arrival();
        int x = 16;
        enabled = addRenderableWidget(Checkbox.builder(Component.translatable("arrival.mars_command.enable"), font)
                .pos(x, 42).maxWidth(width - 32).selected(state != null && state.websiteEnabled()).build());
        api = addRenderableWidget(new EditBox(font, x, 88, width - 32, 20, Component.translatable("arrival.mars_command.api_origin")));
        api.setMaxLength(512);
        api.setValue(state == null ? "" : state.apiOrigin());
        website = addRenderableWidget(new EditBox(font, x, 132, width - 32, 20, Component.translatable("arrival.mars_command.website_origin")));
        website.setMaxLength(512);
        website.setValue(state == null ? "" : state.websiteOrigin());
        Button save = addRenderableWidget(Button.builder(Component.translatable("arrival.mars_command.save"), ignored ->
                PacketDistributor.sendToServer(new IdentitySettingsPayload(enabled.selected(), api.getValue(), website.getValue())))
                .bounds(x, height - 28, 80, 20).build());
        save.active = state != null && state.canConfigure();
        addRenderableWidget(Button.builder(Component.translatable("gui.mars_command.close"), ignored -> onClose())
                .bounds(width - 92, height - 28, 80, 20).build());
    }

    public void notice(Component message) {
        feedback = message;
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderTransparentBackground(graphics);
        graphics.fill(6, 6, width - 6, height - 6, 0xF0081421);
        graphics.renderOutline(6, 6, width - 12, height - 12, 0xFF3583A8);
        graphics.drawString(font, title, 16, 16, 0xFF53C5DB);
        graphics.drawString(font, Component.translatable("arrival.mars_command.api_origin"), 16, 74, 0xFFE6F1F4);
        graphics.drawString(font, Component.translatable("arrival.mars_command.website_origin"), 16, 118, 0xFFE6F1F4);
        int y = 160;
        for (var line : font.split(Component.translatable("arrival.mars_command.settings_help"), width - 32)) {
            graphics.drawString(font, line, 16, y, 0xFFACBDC8);
            y += font.lineHeight;
        }
        if (!feedback.getString().isEmpty()) {
            graphics.drawString(font, font.plainSubstrByWidth(feedback.getString(), width - 32), 16, height - 42, 0xFFFFC76B);
        }
        super.render(graphics, mouseX, mouseY, partialTick);
    }

    @Override
    public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {}

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void onClose() {
        minecraft.setScreen(previous);
    }
}
