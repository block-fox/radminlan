package dev.radminlan.ui;

import dev.radminlan.network.RadminBackend;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

/** Full page for the properties of one Radmin network. */
public final class NetworkPropertiesScreen extends Screen {
    private final Screen parent;
    private final RadminBackend.Network network;
    private String copyStatus = "";
    private String[] cachedValues;
    private String[] cachedOptionValues;

    public NetworkPropertiesScreen(Screen parent, RadminBackend.Network network) {
        super(Component.translatable("screen.radminlan.network_properties"));
        this.parent = parent;
        this.network = network;
    }

    @Override
    protected void init() {
        int buttonY = panelY() + panelHeight() - 32;
        addRenderableWidget(Button.builder(Component.translatable("screen.radminlan.close"), button -> onClose())
                .bounds(width / 2 - 90, buttonY, 180, 20).build());
    }

    @Override
    public void onClose() {
        if (minecraft != null) minecraft.setScreenAndShow(parent);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        ensureCachedValues();
        extractTransparentBackground(graphics);
        graphics.fill(0, 0, width, height, 0x99000000);
        int x = panelX();
        int y = panelY();
        int panelWidth = panelWidth();
        int panelHeight = panelHeight();
        graphics.fill(x, y, x + panelWidth, y + panelHeight, 0xF0101010);
        graphics.fill(x, y, x + panelWidth, y + 28, 0xFF202830);
        graphics.text(font, truncate(network.name(), panelWidth - 24), x + 12, y + 9, 0xFFFFFFFF);
        graphics.text(font, Component.translatable("screen.radminlan.network_information"), x + 12, y + 39, 0xFF9FC8E8);
        drawProperty(graphics, x, y + 54, panelWidth, "network_name", cachedValues[0]);
        drawProperty(graphics, x, y + 72, panelWidth, "network_type", cachedValues[1]);
        drawProperty(graphics, x, y + 90, panelWidth, "role", cachedValues[2]);
        drawProperty(graphics, x, y + 108, panelWidth, "members", cachedValues[3]);
        drawProperty(graphics, x, y + 126, panelWidth, "network_state", cachedValues[4]);
        drawProperty(graphics, x, y + 144, panelWidth, "guid", cachedValues[5]);
        if (!network.publicNetwork()) {
            graphics.text(font, Component.translatable("screen.radminlan.network_options"), x + 12, y + 168, 0xFF9FC8E8);
            if (Boolean.TRUE.equals(network.admin())) {
                drawProperty(graphics, x, y + 186, panelWidth, "allow_passwordless", cachedOptionValues[0]);
                drawProperty(graphics, x, y + 204, panelWidth, "block_new_members", cachedOptionValues[1]);
                graphics.text(font, truncate(Component.translatable("screen.radminlan.property_write_unavailable").getString(), panelWidth - 24),
                        x + 12, y + panelHeight - 42, 0xFFAAAAAA);
            } else {
                String note = Component.translatable(network.admin() == null
                        ? "screen.radminlan.admin_unknown" : "screen.radminlan.admin_required").getString();
                graphics.text(font, truncate(note, panelWidth - 24), x + 12, y + 188, 0xFFAAAAAA);
            }
        }
        if (!copyStatus.isBlank()) graphics.text(font, copyStatus, x + 12, height - 48, 0xFF9FC8E8);
        super.extractRenderState(graphics, mouseX, mouseY, delta);
    }

    private void drawProperty(GuiGraphicsExtractor graphics, int x, int y, int panelWidth, String key, String value) {
        graphics.fill(x + 8, y - 2, x + panelWidth - 8, y + 11, (y / 18 & 1) == 0 ? 0x221D2931 : 0x12000000);
        graphics.text(font, Component.translatable("screen.radminlan.property." + key).append(":"), x + 12, y, 0xFFB9C2CA);
        graphics.text(font, truncate(value, panelWidth - 170), x + 134, y, 0xFFE6EAF0);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (event.button() == 1 && minecraft != null) {
            int x = panelX();
            int y = panelY();
            String value = valueAt(x, y, (int) event.x(), (int) event.y());
            if (value != null && !value.isBlank()) {
                minecraft.keyboardHandler.setClipboard(value);
                copyStatus = "Copied: " + value;
                return true;
            }
        }
        return super.mouseClicked(event, doubleClick);
    }

    private String valueAt(int x, int y, int mouseX, int mouseY) {
        int width = panelWidth();
        ensureCachedValues();
        int[] ys = {y + 54, y + 72, y + 90, y + 108, y + 126, y + 144};
        for (int i = 0; i < ys.length; i++) if (hitRow(x, ys[i], width, mouseX, mouseY)) return cachedValues[i];
        if (!network.publicNetwork()) {
            int[] optionY = {y + 186, y + 204};
            for (int i = 0; i < optionY.length; i++) if (hitRow(x, optionY[i], width, mouseX, mouseY)) return cachedOptionValues[i];
        }
        return null;
    }

    private static boolean hitRow(int x, int rowY, int width, int mouseX, int mouseY) {
        return mouseX >= x + 8 && mouseX <= x + width - 8 && mouseY >= rowY - 2 && mouseY <= rowY + 11;
    }

    private void ensureCachedValues() {
        if (cachedValues != null) return;
        cachedValues = new String[]{network.name(),
                Component.translatable(network.publicNetwork()
                        ? "screen.radminlan.public_network" : "screen.radminlan.private_network").getString(),
                roleText(), Integer.toString(network.peers().size()),
                network.enabled() == null ? Component.translatable("screen.radminlan.property_unknown").getString()
                        : Component.translatable(Boolean.TRUE.equals(network.enabled())
                        ? "screen.radminlan.network_enabled" : "screen.radminlan.network_disabled").getString(),
                network.guid()};
        cachedOptionValues = new String[]{flagText(network.allowPasswordless()), flagText(network.blockNewMembers())};
    }

    private String roleText() {
        return network.admin() == null ? Component.translatable("screen.radminlan.property_unknown").getString()
                : Component.translatable(Boolean.TRUE.equals(network.admin())
                ? "screen.radminlan.network_admin" : "screen.radminlan.network_member").getString();
    }
    private static String flagText(Boolean value) {
        return Component.translatable(value == null ? "screen.radminlan.property_unknown"
                : Boolean.TRUE.equals(value) ? "screen.radminlan.enabled" : "screen.radminlan.disabled").getString();
    }
    private int panelWidth() { return Math.min(500, width - 32); }
    private int panelX() { return (width - panelWidth()) / 2; }
    private int panelHeight() { return network.publicNetwork() ? 196 : Boolean.TRUE.equals(network.admin()) ? 258 : 246; }
    private int panelY() { return Math.max(12, (height - panelHeight()) / 2); }
    private String truncate(String value, int maxWidth) {
        if (value == null || font.width(value) <= maxWidth) return value == null ? "" : value;
        return font.plainSubstrByWidth(value, Math.max(1, maxWidth - font.width("…"))) + "…";
    }
}
