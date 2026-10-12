package com.marscommand.mod.client;

import com.marscommand.mod.network.ArrivalActionPayload;
import java.util.ArrayList;
import net.minecraft.Util;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.ConfirmLinkScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

public final class ArrivalScreen extends Screen {
    private final Screen previous;
    private Button website;
    private Button confirm;
    private Button settings;
    private long refreshed;
    private Component feedback = Component.empty();

    public ArrivalScreen(Screen previous) {
        super(Component.translatable("arrival.mars_command.title"));
        this.previous = previous;
    }

    @Override
    protected void init() {
        int x = Math.max(16, width / 2 - 130);
        int buttonWidth = Math.min(260, width - 32);
        website = addRenderableWidget(Button.builder(Component.translatable("arrival.mars_command.open_website"), ignored -> {
            var state = MoonClient.arrival();
            if (state != null && !state.verificationUri().isEmpty()) {
                ConfirmLinkScreen.confirmLinkNow(this, state.verificationUri());
            }
        }).bounds(x, height - 94, buttonWidth, 20).build());
        confirm = addRenderableWidget(Button.builder(Component.translatable("arrival.mars_command.confirm_account"),
                ignored -> MoonClient.arrivalAction(ArrivalActionPayload.Action.CONFIRM_ACCOUNT))
                .bounds(x, height - 68, buttonWidth, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("arrival.mars_command.retry"),
                ignored -> MoonClient.arrivalAction(ArrivalActionPayload.Action.RETRY))
                .bounds(12, height - 28, 90, 20).build());
        settings = addRenderableWidget(Button.builder(Component.translatable("arrival.mars_command.settings"),
                ignored -> minecraft.setScreen(new IdentitySettingsScreen(this)))
                .bounds(width / 2 - 50, height - 28, 100, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("gui.mars_command.close"), ignored -> onClose())
                .bounds(width - 92, height - 28, 80, 20).build());
        MoonClient.arrivalAction(ArrivalActionPayload.Action.STATUS);
        controls();
    }

    private void controls() {
        var state = MoonClient.arrival();
        website.active = state != null && !state.verificationUri().isEmpty() && !state.confirmed();
        confirm.active = state != null && state.state().equals("approved") && !state.confirmed();
        confirm.setMessage(state != null && state.state().equals("approved")
                ? Component.translatable("arrival.mars_command.confirm_as", state.accountName())
                : Component.translatable("arrival.mars_command.confirm_account"));
        settings.visible = state != null && state.canConfigure();
    }

    public void notice(Component text) {
        feedback = text;
    }

    @Override
    public void tick() {
        controls();
        if (Util.getMillis() - refreshed > 2000) {
            MoonClient.arrivalAction(ArrivalActionPayload.Action.STATUS);
            refreshed = Util.getMillis();
        }
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderTransparentBackground(graphics);
        graphics.fill(6, 6, width - 6, height - 6, 0xF0081421);
        graphics.renderOutline(6, 6, width - 12, height - 12, 0xFF3583A8);
        graphics.drawString(font, title, 16, 16, 0xFF53C5DB);
        var state = MoonClient.arrival();
        var lines = new ArrayList<Component>();
        if (state == null) {
            lines.add(Component.translatable("gui.mars_command.loading"));
        } else {
            lines.add(Component.translatable("arrival.mars_command.player_id", state.callSign().isEmpty() ? "----" : state.callSign()));
            lines.add(Component.translatable("arrival.mars_command.state." + state.state()));
            if (!state.accountName().isEmpty()) {
                lines.add(Component.translatable("arrival.mars_command.account", state.accountName()));
            }
            if (state.stale()) {
                lines.add(Component.translatable("arrival.mars_command.stale"));
            }
            if (state.state().equals("local")) {
                lines.add(Component.translatable("arrival.mars_command.local_explanation"));
            } else if (!state.confirmed()) {
                lines.add(Component.translatable("arrival.mars_command.instructions"));
            }
        }
        lines.add(feedback);
        graphics.enableScissor(14, 36, width - 14, height - 104);
        int y = 40;
        for (Component text : lines) {
            for (var line : font.split(text, width - 40)) {
                graphics.drawString(font, line, 18, y, 0xFFE6F1F4);
                y += font.lineHeight;
            }
            y += 5;
        }
        graphics.disableScissor();
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
