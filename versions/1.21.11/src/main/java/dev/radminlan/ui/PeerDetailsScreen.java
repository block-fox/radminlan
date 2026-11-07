package dev.radminlan.ui;

import dev.radminlan.RadminLanClient;
import dev.radminlan.network.RadminBackend;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.List;

/** Full-screen properties page for a Radmin peer. */
public final class PeerDetailsScreen extends Screen {
    private final Screen parent;
    private final RadminBackend.Peer peer;
    private String copyStatus = "";
    private RadminBackend.RadminSnapshot cachedNetworkSnapshot;
    private String[] cachedIdentityValues;
    private String[] cachedIdentityCopyValues;
    private String[] cachedNetworkValues;
    private String[] cachedNetworkCopyValues;
    private String[] cachedConnectionValues;
    private String[] cachedConnectionCopyValues;

    public PeerDetailsScreen(Screen parent, RadminBackend.Peer peer) {
        super(Component.translatable("screen.radminlan.properties").append(": ").append(peer == null || peer.name() == null ? "" : peer.name()));
        this.parent = parent;
        this.peer = peer == null ? new RadminBackend.Peer("", "", "", -1, false) : peer;
    }

    @Override
    protected void init() {
        addRenderableWidget(Button.builder(Component.translatable("screen.radminlan.close"), button -> onClose())
                .bounds(width / 2 - 100, height - 32, 200, 20).build());
    }

    @Override
    public void onClose() {
        if (minecraft != null) minecraft.setScreen(parent);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float delta) {
        ensureCachedValues();
        renderTransparentBackground(graphics);
        graphics.fill(0, 0, width, height, 0xB0000000);
        graphics.drawCenteredString(font, Component.literal(truncate(title.getString(), width - 32)), width / 2, 8, 0xFFFFFFFF);
        graphics.blitSprite(net.minecraft.client.renderer.RenderPipelines.GUI_TEXTURED,
                Screen.HEADER_SEPARATOR, 0, 29, width, 2);

        int panelWidth = Math.min(620, width - 32);
        int x = (width - panelWidth) / 2;
        int y = Math.max(40, (height - 252) / 2);
        graphics.fill(x, y, x + panelWidth, y + 252, 0xF00D1116);
        int gap = 8;
        int identityHeight = 102;
        int networkHeight = 50;
        int connectionHeight = 84;

        drawGroup(graphics, x, y, panelWidth, identityHeight, Component.translatable("screen.radminlan.peer_information"));
        drawProperty(graphics, x, y, panelWidth, "name", cachedIdentityValues[0], 0, 0xFFE6EAF0);
        drawProperty(graphics, x, y, panelWidth, "ipv4", cachedIdentityValues[1], 1, 0xFFE6EAF0);
        drawProperty(graphics, x, y, panelWidth, "id", cachedIdentityValues[2], 2, 0xFFE6EAF0);
        drawProperty(graphics, x, y, panelWidth, "rid", cachedIdentityValues[3], 3, 0xFFB0BAC4);
        drawProperty(graphics, x, y, panelWidth, "ipv6", cachedIdentityValues[4], 4, 0xFFE6EAF0);
        drawProperty(graphics, x, y, panelWidth, "node_ip", cachedIdentityValues[5], 5, 0xFFE6EAF0);

        y += identityHeight + gap;
        drawGroup(graphics, x, y, panelWidth, networkHeight, Component.translatable("screen.radminlan.network_status"));
        drawProperty(graphics, x, y, panelWidth, "network_name", cachedNetworkValues[0], 0, 0xFFE6EAF0);
        drawProperty(graphics, x, y, panelWidth, "role", cachedNetworkValues[1], 1, 0xFFE6EAF0);

        y += networkHeight + gap;
        drawGroup(graphics, x, y, panelWidth, connectionHeight, Component.translatable("screen.radminlan.connection_status"));
        drawProperty(graphics, x, y, panelWidth, "state", cachedConnectionValues[0], 0, 0xFFE6EAF0);
        drawProperty(graphics, x, y, panelWidth, "channel", cachedConnectionValues[1], 1, 0xFFE6EAF0);
        drawProperty(graphics, x, y, panelWidth, "latency", cachedConnectionValues[2], 2,
                peer.online() ? 0xFFE6EAF0 : 0xFFFF6666);
        drawProperty(graphics, x, y, panelWidth, "state", cachedConnectionValues[3], 3,
                peer.online() ? 0xFF72D18A : 0xFFFF6666);
        drawProperty(graphics, x, y, panelWidth, "version", cachedConnectionValues[4], 4, 0xFFE6EAF0);
        if (!copyStatus.isBlank()) graphics.drawString(font, copyStatus, 20, height - 48, 0xFF9FC8E8);
        super.render(graphics, mouseX, mouseY, delta);
    }

    private void drawGroup(GuiGraphics graphics, int x, int y, int width, int height, Component heading) {
        graphics.fill(x, y, x + width, y + height, 0xAA101820);
        graphics.drawString(font, heading, x + 10, y + 5, 0xFF9FC8E8);
        graphics.blitSprite(net.minecraft.client.renderer.RenderPipelines.GUI_TEXTURED,
                Screen.HEADER_SEPARATOR, x + 8, y + 17, width - 16, 2);
    }

    private void drawProperty(GuiGraphics graphics, int x, int y, int panelWidth,
                              String label, String value, int row, int color) {
        int rowY = y + 23 + row * 13;
        graphics.fill(x + 8, rowY - 2, x + panelWidth - 8, rowY + 10,
                (row & 1) == 0 ? 0x221D2931 : 0x12000000);
        graphics.drawString(font, truncate(Component.translatable("screen.radminlan.property." + label).getString(), 142),
                x + 12, rowY, 0xFFB9C2CA);
        int valueX = x + 164;
        int available = x + panelWidth - 12 - valueX;
        graphics.drawString(font, truncate(value, available), valueX, rowY, color);
    }

    @Override
    public boolean mouseClicked(net.minecraft.client.input.MouseButtonEvent event, boolean doubleClick) {
        if (event.button() == 0) {
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
        ensureCachedValues();
        int panelWidth = Math.min(620, width - 32);
        for (int row = 0; row < cachedIdentityCopyValues.length; row++) {
            if (hitRow(x, y + 23 + row * 13, panelWidth, mouseX, mouseY)) return cachedIdentityCopyValues[row];
        }
        y += 102 + 8;
        for (int row = 0; row < cachedNetworkCopyValues.length; row++) {
            if (hitRow(x, y + 23 + row * 13, panelWidth, mouseX, mouseY)) return cachedNetworkCopyValues[row];
        }
        y += 50 + 8;
        for (int row = 0; row < cachedConnectionCopyValues.length; row++) {
            if (hitRow(x, y + 23 + row * 13, panelWidth, mouseX, mouseY)) return cachedConnectionCopyValues[row];
        }
        return null;
    }

    private static boolean hitRow(int x, int rowY, int panelWidth, int mouseX, int mouseY) {
        return mouseX >= x + 8 && mouseX <= x + panelWidth - 8 && mouseY >= rowY - 2 && mouseY <= rowY + 10;
    }

    private void ensureCachedValues() {
        RadminBackend.RadminSnapshot snapshot = RadminLanClient.cachedSnapshot();
        if (cachedIdentityValues != null && cachedNetworkSnapshot == snapshot) return;
        cachedNetworkSnapshot = snapshot;
        List<RadminBackend.Network> networks = snapshot == null || snapshot.networks() == null
                ? List.of() : snapshot.networks();
        String names = snapshot == null ? safe(peer.networkName()) : networks.stream()
                .filter(network -> network != null && network.peers() != null
                        && network.peers().stream().anyMatch(candidate -> candidate != null && samePeer(candidate, peer)))
                .map(RadminBackend.Network::name)
                .filter(name -> name != null && !name.isBlank())
                .distinct()
                .reduce((left, right) -> left + ", " + right)
                .orElse("");
        String networkNames = names.isBlank() ? safe(peer.networkName()) : names;
        String role = peer.role() == null ? emptyValue() : Component.translatable(switch (peer.role().intValue()) {
            case 1 -> "screen.radminlan.role_member";
            case 2 -> "screen.radminlan.role_admin";
            case 3 -> "screen.radminlan.role_pending";
            default -> "screen.radminlan.property.not_provided";
        }).getString();
        cachedIdentityValues = new String[]{peer.name(), emptyDash(peer.virtualIp()),
                peer.nodeId() < 0 ? emptyValue() : Long.toUnsignedString(peer.nodeId()), emptyValue(),
                emptyDash(peer.virtualIpv6()), emptyDash(peer.publicIp())};
        cachedIdentityCopyValues = new String[]{peer.name(), emptyText(peer.virtualIp()),
                peer.nodeId() < 0 ? "" : Long.toUnsignedString(peer.nodeId()), "",
                emptyText(peer.virtualIpv6()), emptyText(peer.publicIp())};
        cachedNetworkValues = new String[]{emptyDash(networkNames), role};
        cachedNetworkCopyValues = new String[]{networkNames, role};
        cachedConnectionValues = new String[]{emptyDash(peer.state()), emptyDash(peer.connectionType()),
                formatLatency(peer.latencyMs(), peer.online()), peer.online() ? "Online" : "Offline", emptyDash(peer.version())};
        cachedConnectionCopyValues = new String[]{emptyText(peer.state()), emptyText(peer.connectionType()),
                formatLatency(peer.latencyMs(), peer.online()), peer.online() ? "Online" : "Offline", emptyText(peer.version())};
    }

    private static boolean samePeer(RadminBackend.Peer left, RadminBackend.Peer right) {
        if (left == null || right == null) return false;
        if (left.nodeId() >= 0 && right.nodeId() >= 0) return left.nodeId() == right.nodeId();
        String leftIp = safe(left.virtualIp());
        String rightIp = safe(right.virtualIp());
        return !leftIp.isBlank() && leftIp.equalsIgnoreCase(rightIp);
    }

    private static String emptyDash(String value) { return value == null || value.isBlank() ? "-" : value; }
    private static String emptyText(String value) { return value == null ? "" : value; }
    private static String safe(String value) { return value == null ? "" : value; }
    private static String emptyValue() { return Component.translatable("screen.radminlan.property.not_provided").getString(); }
    private String truncate(String value, int maxWidth) {
        if (value == null || font.width(value) <= maxWidth) return value == null ? "" : value;
        return font.plainSubstrByWidth(value, Math.max(1, maxWidth - font.width("..."))) + "...";
    }
    private static String formatLatency(long value, boolean online) { return !online || value < 0 ? "-1 ms" : value + " ms"; }
}
