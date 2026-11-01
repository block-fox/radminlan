package dev.radminlan.ui;

import dev.radminlan.RadminLanClient;
import dev.radminlan.config.RadminConfig;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Small dependency-free configuration screen exposed through Mod Menu. */
public final class RadminConfigScreen extends Screen {
    private final Screen parent;

    public RadminConfigScreen(Screen parent) {
        super(Component.translatable("category.radminlan"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        int center = width / 2;
        addRenderableWidget(Button.builder(positionText(), button -> {
            RadminLanClient.CONFIG.buttonAnchor = nextAnchor(RadminLanClient.CONFIG.buttonAnchor);
            rebuildConfigWidgets();
        }).bounds(center - 150, 55, 300, 20).build());
        addRenderableWidget(Button.builder(Component.literal("LAN timeout: " + RadminLanClient.CONFIG.queryTimeoutMs + " ms"), button -> {
            RadminLanClient.CONFIG.queryTimeoutMs = RadminLanClient.CONFIG.queryTimeoutMs >= 2000 ? 100 : RadminLanClient.CONFIG.queryTimeoutMs + 100;
            rebuildConfigWidgets();
        }).bounds(center - 150, 80, 300, 20).build());
        addRenderableWidget(Button.builder(Component.literal("CLI fallback: " + (RadminLanClient.CONFIG.enableCli ? "ON" : "OFF")), button -> {
            RadminLanClient.CONFIG.enableCli = !RadminLanClient.CONFIG.enableCli;
            rebuildConfigWidgets();
        }).bounds(center - 150, 105, 300, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("gui.done"), button -> onClose()).bounds(center - 100, height - 35, 200, 20).build());
    }

    private static RadminConfig.ButtonAnchor nextAnchor(RadminConfig.ButtonAnchor current) {
        RadminConfig.ButtonAnchor[] values = {
                RadminConfig.ButtonAnchor.TITLE_BEFORE,
                RadminConfig.ButtonAnchor.TITLE_AFTER,
                RadminConfig.ButtonAnchor.BOTTOM_LEFT,
                RadminConfig.ButtonAnchor.BOTTOM_RIGHT
        };
        for (int i = 0; i < values.length; i++) if (values[i] == current || (current == RadminConfig.ButtonAnchor.MULTIPLAYER_TITLE && i == 0)) return values[(i + 1) % values.length];
        return values[0];
    }

    private static Component positionText() {
        return Component.translatable("screen.radminlan.button_position")
                .append(": ")
                .append(Component.translatable("screen.radminlan.button_position." + positionKey()));
    }

    private static String positionKey() {
        return switch (RadminLanClient.CONFIG.buttonAnchor) {
            case TITLE_AFTER -> "title_after";
            case BOTTOM_LEFT -> "bottom_left";
            case BOTTOM_RIGHT -> "bottom_right";
            case MULTIPLAYER_TITLE, TITLE_BEFORE -> "title_before";
        };
    }

    private void rebuildConfigWidgets() {
        clearWidgets();
        init();
        RadminLanClient.CONFIG.save();
    }

    @Override
    public void onClose() {
        RadminLanClient.CONFIG.save();
        minecraft.setScreen(parent);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float delta) {
        renderBackground(graphics, mouseX, mouseY, delta);
        graphics.drawCenteredString(font, title, width / 2, 20, 0xFFFFFFFF);
        graphics.drawCenteredString(font, Component.literal("RadminLAN settings"), width / 2, 38, 0xFFAAAAAA);
        super.render(graphics, mouseX, mouseY, delta);
    }
}
