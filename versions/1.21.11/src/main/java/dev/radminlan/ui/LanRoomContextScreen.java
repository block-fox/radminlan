package dev.radminlan.ui;

import dev.radminlan.RadminLanClient;
import dev.radminlan.network.RadminBackend;
import dev.radminlan.room.LanRoomFaviconCache;
import dev.radminlan.room.LanRoomSnapshot;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

import java.util.ArrayList;
import java.util.List;

/** Scrollable action page for a LAN room. */
public final class LanRoomContextScreen extends Screen {
    private static final int ROW_HEIGHT = 22;
    private static final int FOOTER_HEIGHT = 34;
    private static final int HEADER_HEIGHT = 202;

    private final Screen parent;
    private final LanRoomSnapshot room;
    private final RadminBackend.Peer peer;
    private final List<RadminBackend.Network> networks;
    private final List<ActionEntry> entries = new ArrayList<>();
    private final List<Button> rowButtons = new ArrayList<>();
    private Button cancelButton;
    private int scrollOffset;
    private boolean draggingScrollbar;
    private int dragGrabOffset;

    public LanRoomContextScreen(Screen parent, LanRoomSnapshot room,
                                RadminBackend.Peer peer, List<RadminBackend.Network> networks) {
        super(Component.translatable("screen.radminlan.room_actions"));
        this.parent = parent;
        this.room = room;
        this.peer = peer;
        this.networks = networks == null ? List.of() : networks.stream().filter(java.util.Objects::nonNull).toList();
    }

    @Override
    protected void init() {
        entries.clear();
        if (peer != null) {
            entries.add(new ActionEntry(Component.translatable("screen.radminlan.peer_properties"),
                    () -> minecraft.setScreen(new PeerDetailsScreen(this, peer))));
        }
        for (RadminBackend.Network network : networks) {
            if (network == null) continue;
            String label = Component.translatable("screen.radminlan.network_properties").getString()
                    + ": " + network.name();
            entries.add(new ActionEntry(Component.literal(label),
                    () -> minecraft.setScreen(new NetworkPropertiesScreen(this, network))));
        }
        entries.add(new ActionEntry(Component.translatable("screen.radminlan.block_room"), this::blockRoom));

        rebuildRows();
        cancelButton = addRenderableWidget(Button.builder(Component.translatable("screen.radminlan.cancel"), button -> onClose())
                .bounds(panelX() + 12, panelY() + panelHeight() - 27, panelWidth() - 24, 20).build());
    }

    private void rebuildRows() {
        for (Button button : List.copyOf(rowButtons)) removeWidget(button);
        rowButtons.clear();
        clampScroll();
        int first = Math.max(0, scrollOffset / ROW_HEIGHT - 1);
        int last = Math.min(entries.size(), (scrollOffset + listViewportHeight()) / ROW_HEIGHT + 1);
        for (int i = first; i < last; i++) {
            int rowY = listTop() + i * ROW_HEIGHT - scrollOffset;
            // Widgets render after the screen's scissor state is restored.
            // Never create a row that reaches into the fixed footer.
            if (rowY < listTop() || rowY + 20 > listBottom()) continue;
            ActionEntry entry = entries.get(i);
            Button button = Button.builder(entry.label(), ignored -> entry.action().run())
                    .bounds(panelX() + 12, rowY, panelWidth() - 36, 20).build();
            rowButtons.add(button);
            addRenderableWidget(button);
        }
    }

    private void blockRoom() {
        if (room != null && room.key() != null && RadminLanClient.CONFIG != null) {
            RadminLanClient.CONFIG.block(room.key().ip(), room.key().port(), room.motd());
            if (RadminLanClient.ROOMS != null) RadminLanClient.ROOMS.remove(room.key());
        }
        onClose();
    }

    @Override
    public void onClose() {
        if (minecraft != null) minecraft.setScreen(parent);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (event.button() == 0 && maxScroll() > 0 && event.x() >= panelX() + panelWidth() - 22
                && event.x() <= panelX() + panelWidth() - 8 && event.y() >= listTop() && event.y() <= listBottom()) {
            draggingScrollbar = true;
            dragGrabOffset = Math.max(0, (int) event.y() - thumbY());
            return true;
        }
        return super.mouseClicked(event, doubleClick);
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dragX, double dragY) {
        if (!draggingScrollbar) return super.mouseDragged(event, dragX, dragY);
        int travel = Math.max(1, listViewportHeight() - thumbHeight());
        int relative = Math.max(0, Math.min(travel, (int) event.y() - listTop() - dragGrabOffset));
        scrollOffset = maxScroll() * relative / travel;
        rebuildRows();
        return true;
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        draggingScrollbar = false;
        return super.mouseReleased(event);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        if (mouseX < panelX() || mouseX > panelX() + panelWidth() || mouseY < listTop() || mouseY > listBottom()) {
            return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
        }
        int next = Math.max(0, Math.min(maxScroll(), scrollOffset
                - (verticalAmount > 0 ? ROW_HEIGHT * 3 : -ROW_HEIGHT * 3)));
        if (next == scrollOffset) return true;
        scrollOffset = next;
        rebuildRows();
        return true;
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float delta) {
        renderTransparentBackground(graphics);
        graphics.fill(0, 0, width, height, 0x88000000);
        int x = panelX();
        int y = panelY();
        graphics.fill(x, y, x + panelWidth(), y + panelHeight(), 0xF0101010);
        graphics.fill(x, y, x + panelWidth(), y + 26, 0xFF202830);
        graphics.drawString(font, title, x + 12, y + 9, 0xFFFFFFFF);
        drawRoomSummary(graphics, x, y);
        graphics.fill(x + 8, listTop() - 2, x + panelWidth() - 8, listTop(), 0xFF53616D);
        if (maxScroll() > 0) {
            int scrollbarX = x + panelWidth() - 18;
            graphics.fill(scrollbarX, listTop(), scrollbarX + 5, listBottom(), 0x88404040);
            graphics.fill(scrollbarX, thumbY(), scrollbarX + 5, thumbY() + thumbHeight(), 0xFFB0B0B0);
        }
        super.render(graphics, mouseX, mouseY, delta);
    }

    private void drawRoomSummary(GuiGraphics graphics, int x, int y) {
        if (room == null) return;

        int coverX = x + 12;
        int coverY = y + 34;
        String roomAddress = room.key() == null ? "" : room.key().toString();
        Identifier favicon = LanRoomFaviconCache.texture(minecraft, roomAddress, room.favicon());
        if (favicon != null) {
            graphics.blit(RenderPipelines.GUI_TEXTURED, favicon, coverX, coverY,
                    0.0F, 0.0F, 64, 64, 64, 64);
        } else {
            graphics.fill(coverX, coverY, coverX + 64, coverY + 64, 0xFF202830);
            graphics.drawString(font, "?", coverX + 27, coverY + 24, 0xFF7E8B96);
        }

        int textX = x + 86;
        drawRoomLine(graphics, "screen.radminlan.room_lan_motd", room.motd(), textX, y + 35);
        drawRoomLine(graphics, "screen.radminlan.room_server_motd",
                room.displayMotd(), textX, y + 49);
        drawRoomLine(graphics, "screen.radminlan.room_address", roomAddress, textX, y + 63);
        drawRoomLine(graphics, "screen.radminlan.room_players", formatPlayers(), textX, y + 77);
        drawRoomLine(graphics, "screen.radminlan.room_latency", formatLatency(), textX, y + 91);

        graphics.fill(x + 8, y + 105, x + panelWidth() - 8, y + 106, 0xFF53616D);
        graphics.drawString(font, Component.translatable("screen.radminlan.room_radmin"), x + 12, y + 112, 0xFF9FC8E8);

        int leftX = x + 12;
        int rightX = x + Math.max(220, panelWidth() / 2);
        drawRoomLine(graphics, "screen.radminlan.room_user", peerValue(peer == null ? "" : peer.name()), leftX, y + 128);
        drawRoomLine(graphics, "screen.radminlan.room_virtual_ip", peerValue(peer == null ? "" : peer.virtualIp()), rightX, y + 128);
        drawRoomLine(graphics, "screen.radminlan.room_node_id", peer == null || peer.nodeId() < 0
                ? valueUnknown() : Long.toUnsignedString(peer.nodeId()), leftX, y + 142);
        drawRoomLine(graphics, "screen.radminlan.room_real_ip", peerValue(peer == null ? "" : peer.publicIp()), rightX, y + 142);
        drawRoomLine(graphics, "screen.radminlan.room_virtual_ipv6", peerValue(peer == null ? "" : peer.virtualIpv6()), leftX, y + 156);
        drawRoomLine(graphics, "screen.radminlan.room_connection", peerValue(peer == null ? "" : peer.connectionType()), rightX, y + 156);
        String peerState = peer == null ? valueUnknown()
                : (peer.online() ? "Online, " + formatPeerLatency() : "Offline, -1 ms");
        drawRoomLine(graphics, "screen.radminlan.room_state", peerState, leftX, y + 170);
        drawRoomLine(graphics, "screen.radminlan.room_version", peerValue(peer == null ? "" : peer.version()), rightX, y + 170);

        String networkText = networks.stream().filter(network -> network != null).map(RadminBackend.Network::name)
                .filter(value -> value != null && !value.isBlank()).distinct()
                .reduce((a, b) -> a + ", " + b)
                .orElse(peer == null ? valueUnknown() : peerValue(peer.networkName()));
        drawRoomLine(graphics, "screen.radminlan.room_networks", networkText, x + 12, y + 184);
    }

    private void drawRoomLine(GuiGraphics graphics, String labelKey, String value, int x, int y) {
        String label = Component.translatable(labelKey).getString();
        graphics.drawString(font, label + ": ", x, y, 0xFFB9C2CA);
        int valueX = x + font.width(label + ": ");
        graphics.drawString(font, value == null || value.isBlank() ? valueUnknown() : value, valueX, y, 0xFFE6EAF0);
    }

    private String formatPlayers() {
        if (room.players() < 0 || room.maxPlayers() < 0) return valueUnknown();
        return room.players() + "/" + room.maxPlayers();
    }

    private String formatLatency() {
        return room.latencyMs() < 0 ? "-1 ms" : room.latencyMs() + " ms";
    }

    private String formatPeerLatency() {
        return peer == null || peer.latencyMs() < 0 ? "-1 ms" : peer.latencyMs() + " ms";
    }

    private String peerValue(String value) { return value == null || value.isBlank() ? valueUnknown() : value; }

    private String valueUnknown() { return Component.translatable("screen.radminlan.property.not_provided").getString(); }

    private int panelWidth() { return Math.min(420, width - 24); }
    private int panelHeight() {
        int desired = HEADER_HEIGHT + FOOTER_HEIGHT + Math.min(220, Math.max(4, entries.size()) * ROW_HEIGHT);
        return Math.min(Math.max(170, desired), Math.max(170, height - 24));
    }
    private int panelX() { return (width - panelWidth()) / 2; }
    private int panelY() { return Math.max(12, (height - panelHeight()) / 2); }
    private int listTop() { return panelY() + HEADER_HEIGHT; }
    private int listBottom() { return panelY() + panelHeight() - FOOTER_HEIGHT; }
    private int listViewportHeight() { return Math.max(1, listBottom() - listTop()); }
    private int maxScroll() { return Math.max(0, entries.size() * ROW_HEIGHT - listViewportHeight()); }
    private void clampScroll() { scrollOffset = Math.min(scrollOffset, maxScroll()); }
    private int thumbHeight() {
        return Math.max(18, listViewportHeight() * listViewportHeight()
                / Math.max(listViewportHeight(), entries.size() * ROW_HEIGHT));
    }
    private int thumbY() {
        int track = Math.max(0, listViewportHeight() - thumbHeight());
        return listTop() + (maxScroll() == 0 ? 0 : track * scrollOffset / maxScroll());
    }
    private String truncate(String value, int maxWidth) {
        if (value == null || font.width(value) <= maxWidth) return value == null ? "" : value;
        return font.plainSubstrByWidth(value, Math.max(1, maxWidth - font.width("…"))) + "…";
    }

    private record ActionEntry(Component label, Runnable action) {}
}
