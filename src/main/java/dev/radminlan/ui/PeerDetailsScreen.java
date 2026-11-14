package dev.radminlan.ui;

import dev.radminlan.RadminLanClient;
import dev.radminlan.input.MouseButtons;
import dev.radminlan.network.RadminBackend;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;

import java.util.List;

/** Full-screen properties page for a Radmin peer. */
public final class PeerDetailsScreen extends Screen {
    private final Screen parent;
    private final RadminBackend.Peer peer;
    private RadminBackend.Peer displayedPeer;
    private String copyStatus = "";
    private RadminBackend.RadminSnapshot cachedNetworkSnapshot;
    private String[] cachedIdentityValues;
    private String[] cachedIdentityCopyValues;
    private String[] cachedNetworkValues;
    private String[] cachedNetworkCopyValues;
    private String[] cachedConnectionValues;
    private String[] cachedConnectionCopyValues;

    public PeerDetailsScreen(Screen parent, RadminBackend.Peer peer) {
        super(Component.translatable("screen.radminlan.properties").append(": ")
                .append(peer == null || peer.name() == null ? "" : peer.name()));
        this.parent = parent;
        this.peer = peer == null ? new RadminBackend.Peer("", "", "", -1, false) : peer;
        this.displayedPeer = this.peer;
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
        ensureCachedValues();
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
                displayedPeer.online() ? 0xFFE6EAF0 : 0xFFB0BAC4);
        drawProperty(graphics, x, y, panelWidth, "state", cachedConnectionValues[3], 3,
                displayedPeer.online() ? 0xFF72D18A : 0xFFB0BAC4);
        drawProperty(graphics, x, y, panelWidth, "version", cachedConnectionValues[4], 4, 0xFFE6EAF0);
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
        if (event.button() == MouseButtons.left()) {
            int panelWidth = Math.min(620, width - 32);
            int x = (width - panelWidth) / 2;
            int y = Math.max(40, (height - 252) / 2);
            String value = valueAt(x, y, (int) event.x(), (int) event.y());
            if (value != null && !value.isBlank() && minecraft != null) {
                minecraft.keyboardHandler.setClipboard(value);
                copyStatus = Component.translatable("screen.radminlan.peer.copied", value).getString();
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
        displayedPeer = networks.stream().filter(network -> network != null && network.peers() != null)
                .flatMap(network -> network.peers().stream())
                .filter(candidate -> candidate != null && samePeer(candidate, peer))
                .findFirst().orElse(peer);
        RadminBackend.Peer current = displayedPeer;
        String names = snapshot == null ? safe(peer.networkName()) : networks.stream()
                .filter(network -> network != null && network.peers() != null
                        && network.peers().stream().anyMatch(candidate -> candidate != null && samePeer(candidate, peer)))
                .map(RadminBackend.Network::name)
                .filter(name -> name != null && !name.isBlank())
                .distinct()
                .reduce((left, right) -> left + ", " + right)
                .orElse("");
        String networkNames = names.isBlank() ? safe(current.networkName()) : names;
        String role = current.role() == null ? emptyValue() : Component.translatable(switch (current.role().intValue()) {
            case 1 -> "screen.radminlan.role_member";
            case 2 -> "screen.radminlan.role_admin";
            case 3 -> "screen.radminlan.role_pending";
            default -> "screen.radminlan.property.not_provided";
        }).getString();
        cachedIdentityValues = new String[]{current.name(), emptyDash(current.virtualIp()),
                current.nodeId() < 0 ? emptyValue() : Long.toUnsignedString(current.nodeId()), emptyValue(),
                emptyDash(current.virtualIpv6()), emptyDash(current.publicIp())};
        cachedIdentityCopyValues = new String[]{current.name(), emptyText(current.virtualIp()),
                current.nodeId() < 0 ? "" : Long.toUnsignedString(current.nodeId()), "",
                emptyText(current.virtualIpv6()), emptyText(current.publicIp())};
        cachedNetworkValues = new String[]{emptyDash(networkNames), role};
        cachedNetworkCopyValues = new String[]{networkNames, role};
        String state = statusText(current);
        String latency = formatLatency(current.latencyMs(), current.online());
        cachedConnectionValues = new String[]{state, emptyDash(current.connectionType()),
                latency, state, emptyDash(current.version())};
        cachedConnectionCopyValues = new String[]{state, emptyText(current.connectionType()),
                current.online() && current.latencyMs() >= 0 ? latency : "", state, emptyText(current.version())};
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
    private static String statusText(RadminBackend.Peer peer) {
        String key = peer.online() ? "online" : "offline".equalsIgnoreCase(peer.state()) ? "offline" : "unknown";
        return Component.translatable("screen.radminlan.peer." + key).getString();
    }

    private static String formatLatency(long value, boolean online) {
        if (!online) return "-";
        return Component.translatable(value < 0 ? "screen.radminlan.peer.latency_unknown"
                : "screen.radminlan.peer.latency", value).getString();
    }
}
