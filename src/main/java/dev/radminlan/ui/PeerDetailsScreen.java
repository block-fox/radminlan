package dev.radminlan.ui;

import dev.radminlan.RadminLanClient;
import dev.radminlan.network.RadminBackend;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;

/** Full-screen properties page for a Radmin peer. */
public final class PeerDetailsScreen extends Screen {
    private final Screen parent;
    private final RadminBackend.Peer peer;
    private String copyStatus = "";

    public PeerDetailsScreen(Screen parent, RadminBackend.Peer peer) {
        super(Component.translatable("screen.radminlan.properties").append(": ").append(peer.name()));
        this.parent = parent;
        this.peer = peer;
    }

    @Override
    protected void init() {
        addRenderableWidget(Button.builder(Component.translatable("screen.radminlan.close"), button -> onClose())
                .bounds(width / 2 - 100, height - 32, 200, 20).build());
    }

    @Override
    public void onClose() {
        if (minecraft != null) minecraft.setScreenAndShow(parent);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        extractTransparentBackground(graphics);
        graphics.fill(0, 0, width, height, 0xB0000000);
        graphics.centeredText(font, Component.literal(truncate(title.getString(), width - 32)), width / 2, 8, 0xFFFFFFFF);
        graphics.blit(RenderPipelines.GUI_TEXTURED, Screen.HEADER_SEPARATOR, 0, 29,
                0.0F, 0.0F, width, 2, 32, 2);

        int panelWidth = Math.min(620, width - 32);
        int x = (width - panelWidth) / 2;
        int y = Math.max(40, (height - 252) / 2);
        graphics.fill(x, y, x + panelWidth, y + 252, 0xF00D1116);
        int gap = 8;
        int identityHeight = 102;
        int networkHeight = 50;
        int connectionHeight = 84;

        drawGroup(graphics, x, y, panelWidth, identityHeight, Component.translatable("screen.radminlan.peer_information"));
        drawProperty(graphics, x, y, panelWidth, "name", peer.name(), 0, 0xFFE6EAF0);
        drawProperty(graphics, x, y, panelWidth, "ipv4", emptyDash(peer.virtualIp()), 1, 0xFFE6EAF0);
        drawProperty(graphics, x, y, panelWidth, "id", peer.nodeId() < 0 ? emptyValue() : Long.toUnsignedString(peer.nodeId()), 2, 0xFFE6EAF0);
        drawProperty(graphics, x, y, panelWidth, "rid", emptyValue(), 3, 0xFFB0BAC4);
        drawProperty(graphics, x, y, panelWidth, "ipv6", emptyDash(peer.virtualIpv6()), 4, 0xFFE6EAF0);
        drawProperty(graphics, x, y, panelWidth, "node_ip", emptyDash(peer.publicIp()), 5, 0xFFE6EAF0);

        y += identityHeight + gap;
        drawGroup(graphics, x, y, panelWidth, networkHeight, Component.translatable("screen.radminlan.network_status"));
        drawProperty(graphics, x, y, panelWidth, "network_name", emptyDash(networkNames()), 0, 0xFFE6EAF0);
        drawProperty(graphics, x, y, panelWidth, "role", roleText(), 1, 0xFFE6EAF0);

        y += networkHeight + gap;
        drawGroup(graphics, x, y, panelWidth, connectionHeight, Component.translatable("screen.radminlan.connection_status"));
        drawProperty(graphics, x, y, panelWidth, "state", emptyDash(peer.state()), 0, 0xFFE6EAF0);
        drawProperty(graphics, x, y, panelWidth, "channel", emptyDash(peer.connectionType()), 1, 0xFFE6EAF0);
        drawProperty(graphics, x, y, panelWidth, "latency", formatLatency(peer.latencyMs(), peer.online()), 2,
                peer.online() ? 0xFFE6EAF0 : 0xFFFF6666);
        drawProperty(graphics, x, y, panelWidth, "state", peer.online() ? "Online" : "Offline", 3,
                peer.online() ? 0xFF72D18A : 0xFFFF6666);
        drawProperty(graphics, x, y, panelWidth, "version", emptyDash(peer.version()), 4, 0xFFE6EAF0);
        if (!copyStatus.isBlank()) graphics.text(font, copyStatus, 20, height - 48, 0xFF9FC8E8);
        super.extractRenderState(graphics, mouseX, mouseY, delta);
    }

    private void drawGroup(GuiGraphicsExtractor graphics, int x, int y, int width, int height, Component heading) {
        graphics.fill(x, y, x + width, y + height, 0xAA101820);
        graphics.text(font, heading, x + 10, y + 5, 0xFF9FC8E8);
        graphics.blit(RenderPipelines.GUI_TEXTURED, Screen.HEADER_SEPARATOR, x + 8, y + 17,
                0.0F, 0.0F, width - 16, 2, 32, 2);
    }

    private void drawProperty(GuiGraphicsExtractor graphics, int x, int y, int panelWidth,
                              String label, String value, int row, int color) {
        int rowY = y + 23 + row * 13;
        graphics.fill(x + 8, rowY - 2, x + panelWidth - 8, rowY + 10,
                (row & 1) == 0 ? 0x221D2931 : 0x12000000);
        graphics.text(font, truncate(Component.translatable("screen.radminlan.property." + label).getString(), 142),
                x + 12, rowY, 0xFFB9C2CA);
        int valueX = x + 164;
        int available = x + panelWidth - 12 - valueX;
        graphics.text(font, truncate(value, available), valueX, rowY, color);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (event.button() == 1) {
            int panelWidth = Math.min(620, width - 32);
            int x = (width - panelWidth) / 2;
            int y = Math.max(40, (height - 252) / 2);
            String value = valueAt(x, y, (int) event.x(), (int) event.y());
            if (value != null && !value.isBlank() && minecraft != null) {
                minecraft.keyboardHandler.setClipboard(value);
                copyStatus = "Copied: " + value;
                return true;
            }
        }
        return super.mouseClicked(event, doubleClick);
    }

    private String valueAt(int x, int y, int mouseX, int mouseY) {
        int panelWidth = Math.min(620, width - 32);
        String[] identity = {peer.name(), peer.virtualIp(), peer.nodeId() < 0 ? "" : Long.toUnsignedString(peer.nodeId()), "",
                peer.virtualIpv6(), peer.publicIp()};
        for (int row = 0; row < identity.length; row++) {
            if (hitRow(x, y + 23 + row * 13, panelWidth, mouseX, mouseY)) return identity[row];
        }
        y += 102 + 8;
        String[] network = {networkNames(), roleText()};
        for (int row = 0; row < network.length; row++) {
            if (hitRow(x, y + 23 + row * 13, panelWidth, mouseX, mouseY)) return network[row];
        }
        y += 50 + 8;
        String[] connection = {peer.state(), peer.connectionType(), formatLatency(peer.latencyMs(), peer.online()),
                peer.online() ? "Online" : "Offline", peer.version()};
        for (int row = 0; row < connection.length; row++) {
            if (hitRow(x, y + 23 + row * 13, panelWidth, mouseX, mouseY)) return connection[row];
        }
        return null;
    }

    private static boolean hitRow(int x, int rowY, int panelWidth, int mouseX, int mouseY) {
        return mouseX >= x + 8 && mouseX <= x + panelWidth - 8 && mouseY >= rowY - 2 && mouseY <= rowY + 10;
    }

    private String networkNames() {
        if (RadminLanClient.cachedSnapshot() == null) return peer.networkName();
        String names = RadminLanClient.cachedSnapshot().networks().stream()
                .filter(network -> network.peers().stream().anyMatch(candidate -> samePeer(candidate, peer)))
                .map(RadminBackend.Network::name)
                .distinct()
                .reduce((left, right) -> left + ", " + right)
                .orElse("");
        return names.isBlank() ? peer.networkName() : names;
    }

    private static boolean samePeer(RadminBackend.Peer left, RadminBackend.Peer right) {
        if (left.nodeId() >= 0 && right.nodeId() >= 0) return left.nodeId() == right.nodeId();
        return !left.virtualIp().isBlank() && left.virtualIp().equalsIgnoreCase(right.virtualIp());
    }

    private static String emptyDash(String value) { return value == null || value.isBlank() ? "-" : value; }
    private static String emptyValue() { return Component.translatable("screen.radminlan.property.not_provided").getString(); }
    private String roleText() {
        return peer.role() == null ? emptyValue() : Component.translatable(switch (peer.role().intValue()) {
            case 1 -> "screen.radminlan.role_member";
            case 2 -> "screen.radminlan.role_admin";
            case 3 -> "screen.radminlan.role_pending";
            default -> "screen.radminlan.property.not_provided";
        }).getString();
    }
    private String truncate(String value, int maxWidth) {
        if (value == null || font.width(value) <= maxWidth) return value == null ? "" : value;
        return font.plainSubstrByWidth(value, Math.max(1, maxWidth - font.width("..."))) + "...";
    }
    private static String formatLatency(long value, boolean online) { return !online || value < 0 ? "-1 ms" : value + " ms"; }
}
