package dev.radminlan.ui;

import dev.radminlan.RadminLanClient;
import dev.radminlan.input.MouseButtons;
import dev.radminlan.network.RadminBackend;
import dev.radminlan.room.LanRoomFaviconCache;
import dev.radminlan.room.LanRoomSnapshot;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Room properties and actions share one viewport between a fixed header and footer. */
public final class LanRoomContextScreen extends Screen {
    private static final int ROW_HEIGHT = 20;
    private static final int ACTION_HEIGHT = 24;
    private static final int HEADER_HEIGHT = 34;
    private static final int FOOTER_HEIGHT = 38;
    private static final Identifier SCROLLER = Identifier.withDefaultNamespace("widget/scroller");
    private static final Identifier SCROLLER_BACKGROUND = Identifier.withDefaultNamespace("widget/scroller_background");

    private final Screen parent;
    private final LanRoomSnapshot room;
    private final RadminBackend.Peer peer;
    private final List<RadminBackend.Network> networks;
    private final List<ActionEntry> entries = new ArrayList<>();
    private final List<Button> rowButtons = new ArrayList<>();
    private final List<PropertyRow> properties = new ArrayList<>();
    private final List<Group> groups = new ArrayList<>();
    private Button cancelButton;
    private int actionsTop;
    private int contentHeight;
    private int faviconTop;
    private int scrollOffset;
    private boolean draggingScrollbar;
    private int dragGrabOffset;

    public LanRoomContextScreen(Screen parent, LanRoomSnapshot room,
                                RadminBackend.Peer peer, List<RadminBackend.Network> networks) {
        super(Component.translatable("screen.radminlan.room_properties"));
        this.parent = parent;
        this.room = room;
        this.peer = peer;
        this.networks = networks == null ? List.of() : networks.stream().filter(network -> network != null).toList();
    }

    @Override
    protected void init() {
        clearWidgets();
        rowButtons.clear();
        entries.clear();
        draggingScrollbar = false;
        if (peer != null) {
            entries.add(new ActionEntry(Component.translatable("screen.radminlan.peer_properties"),
                    () -> minecraft.setScreenAndShow(new PeerDetailsScreen(this, peer))));
        }
        for (RadminBackend.Network network : networks) {
            Component label = Component.translatable("screen.radminlan.network_properties")
                    .append(": ").append(peerValue(network.name()));
            entries.add(new ActionEntry(label,
                    () -> minecraft.setScreenAndShow(new NetworkPropertiesScreen(this, network))));
        }
        entries.add(new ActionEntry(Component.translatable("screen.radminlan.block_room"), this::blockRoom));
        layoutContent();
        rebuildRows();
        int buttonWidth = Math.min(200, Math.max(1, panelWidth() - 24));
        cancelButton = addRenderableWidget(Button.builder(Component.translatable("screen.radminlan.cancel"), button -> onClose())
                .bounds((width - buttonWidth) / 2, height - 28, buttonWidth, 20).build());
    }

    private void layoutContent() {
        properties.clear();
        groups.clear();
        int x = contentLeft();
        int available = contentWidth();
        int y = 10;

        int roomTop = y;
        y += 24;
        faviconTop = y;
        int summaryX = x + 60;
        int summaryWidth = Math.max(1, available - 60);
        addProperty("room_lan_motd", room == null ? "" : room.motd(), summaryX, y, summaryWidth);
        addProperty("room_server_motd", room == null ? "" : room.displayMotd(), summaryX, y + ROW_HEIGHT, summaryWidth);
        y += 56;
        addProperty("room_address", room == null || room.key() == null ? "" : room.key().toString(), x, y, available);
        y += ROW_HEIGHT;
        y = addGrid(List.of(new Property("room_players", formatPlayers()),
                new Property("room_latency", formatLatency(room == null ? -1 : room.latencyMs()))), y);
        groups.add(new Group(Component.translatable("screen.radminlan.room_information"), roomTop, y - roomTop + 4));

        y += 16;
        int radminTop = y;
        y += 24;
        y = addGrid(List.of(
                new Property("room_user", peer == null ? "" : peer.name()),
                new Property("room_virtual_ip", peer == null ? "" : peer.virtualIp()),
                new Property("room_node_id", peer == null || peer.nodeId() < 0 ? "" : Long.toUnsignedString(peer.nodeId())),
                new Property("room_real_ip", peer == null ? "" : peer.publicIp()),
                new Property("room_connection", peer == null ? "" : peer.connectionType()),
                new Property("room_version", peer == null ? "" : peer.version()),
                new Property("room_state", peerState()),
                new Property("room_latency", peer != null && peer.online() ? formatLatency(peer.latencyMs()) : "-")), y);
        addProperty("room_virtual_ipv6", peer == null ? "" : peer.virtualIpv6(), x, y, available);
        y += ROW_HEIGHT;
        String networkNames = networks.stream().map(RadminBackend.Network::name)
                .filter(value -> value != null && !value.isBlank()).distinct()
                .reduce((left, right) -> left + ", " + right)
                .orElse(peer == null ? "" : safe(peer.networkName()));
        addProperty("room_networks", networkNames, x, y, available);
        y += ROW_HEIGHT;
        groups.add(new Group(Component.translatable("screen.radminlan.room_radmin"), radminTop, y - radminTop + 4));

        y += 16;
        int actionGroupTop = y;
        actionsTop = y + 24;
        y = actionsTop + entries.size() * ACTION_HEIGHT;
        groups.add(new Group(Component.translatable("screen.radminlan.room_operations"), actionGroupTop, y - actionGroupTop + 4));
        contentHeight = y + 14;
        clampScroll();
    }

    private int addGrid(List<Property> values, int y) {
        int columns = contentWidth() >= 600 ? 2 : 1;
        int gap = 20;
        int columnWidth = Math.max(1, (contentWidth() - gap * (columns - 1)) / columns);
        for (int index = 0; index < values.size(); index++) {
            Property value = values.get(index);
            addProperty(value.label(), value.value(), contentLeft() + index % columns * (columnWidth + gap),
                    y + index / columns * ROW_HEIGHT, columnWidth);
        }
        return y + (values.size() + columns - 1) / columns * ROW_HEIGHT;
    }

    private void addProperty(String label, String value, int x, int y, int rowWidth) {
        properties.add(new PropertyRow(Component.translatable("screen.radminlan." + label),
                peerValue(value), x, y, rowWidth));
    }

    private void rebuildRows() {
        for (Button button : List.copyOf(rowButtons)) removeWidget(button);
        rowButtons.clear();
        clampScroll();
        for (int index = 0; index < entries.size(); index++) {
            int y = listTop() + actionsTop + index * ACTION_HEIGHT - scrollOffset;
            if (y + 20 <= listTop() || y >= listBottom()) continue;
            ActionEntry entry = entries.get(index);
            Button button = Button.builder(entry.label(), ignored -> entry.action().run())
                    .bounds(contentLeft() + 8, y, Math.max(1, contentWidth() - 16), 20).build();
            button.setTooltip(Tooltip.create(entry.label()));
            rowButtons.add(button);
            addWidget(button);
        }
    }

    private void blockRoom() {
        if (room != null && room.key() != null) {
            RadminLanClient.CONFIG.block(room.key().ip(), room.key().port(), room.motd());
            if (RadminLanClient.ROOMS != null) RadminLanClient.ROOMS.remove(room.key());
        }
        onClose();
    }

    @Override
    public void onClose() {
        if (minecraft != null) minecraft.setScreenAndShow(parent);
    }

    @Override
    public Optional<GuiEventListener> getChildAt(double mouseX, double mouseY) {
        if (cancelButton != null && cancelButton.isMouseOver(mouseX, mouseY)) return Optional.of(cancelButton);
        if (!insideViewport(mouseX, mouseY)) return Optional.empty();
        for (Button button : rowButtons) {
            if (button.isMouseOver(mouseX, mouseY)) return Optional.of(button);
        }
        return Optional.empty();
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (event.button() == MouseButtons.left() && maxScroll() > 0 && overScrollbar(event.x(), event.y())) {
            draggingScrollbar = true;
            int thumb = thumbHeight();
            int y = (int) event.y();
            if (y >= thumbY() && y < thumbY() + thumb) dragGrabOffset = y - thumbY();
            else {
                dragGrabOffset = thumb / 2;
                dragScrollbar(y);
            }
            return true;
        }
        if (!insideViewport(event.x(), event.y())
                && (cancelButton == null || !cancelButton.isMouseOver(event.x(), event.y()))) return false;
        return super.mouseClicked(event, doubleClick);
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dragX, double dragY) {
        if (!draggingScrollbar) return super.mouseDragged(event, dragX, dragY);
        dragScrollbar((int) event.y());
        return true;
    }

    private void dragScrollbar(int mouseY) {
        int travel = Math.max(1, listViewportHeight() - thumbHeight());
        int relative = Math.max(0, Math.min(travel, mouseY - listTop() - dragGrabOffset));
        scrollOffset = (int) ((long) maxScroll() * relative / travel);
        rebuildRows();
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        boolean wasDragging = draggingScrollbar;
        draggingScrollbar = false;
        return super.mouseReleased(event) || wasDragging;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        if (!insideViewport(mouseX, mouseY) || verticalAmount == 0) {
            return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
        }
        int movement = Math.max(1, (int) Math.round(Math.abs(verticalAmount) * ROW_HEIGHT * 2));
        scrollOffset += verticalAmount > 0 ? -movement : movement;
        rebuildRows();
        return true;
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        extractTransparentBackground(graphics);
        graphics.fill(0, 0, width, height, 0x88000000);
        graphics.centeredText(font, Component.literal(truncate(title.getString(), Math.max(1, width - 24))),
                width / 2, 12, 0xFFFFFFFF);

        graphics.enableScissor(contentLeft(), listTop(), contentRight(), listBottom());
        try {
            for (Group group : groups) drawGroup(graphics, group);
            drawFavicon(graphics);
            for (PropertyRow property : properties) drawProperty(graphics, property, mouseX, mouseY);
            boolean overContent = insideViewport(mouseX, mouseY);
            for (Button button : rowButtons) {
                button.extractRenderState(graphics, overContent ? mouseX : -1, overContent ? mouseY : -1, delta);
            }
        } finally {
            graphics.disableScissor();
        }
        if (maxScroll() > 0) {
            graphics.blitSprite(RenderPipelines.GUI_TEXTURED, SCROLLER_BACKGROUND,
                    scrollbarX(), listTop(), 6, listViewportHeight());
            graphics.blitSprite(RenderPipelines.GUI_TEXTURED, SCROLLER,
                    scrollbarX(), thumbY(), 6, thumbHeight());
        }
        graphics.blit(RenderPipelines.GUI_TEXTURED, Screen.HEADER_SEPARATOR,
                0, listTop() - 2, 0.0F, 0.0F, width, 2, 32, 2);
        graphics.blit(RenderPipelines.GUI_TEXTURED, Screen.FOOTER_SEPARATOR,
                0, listBottom(), 0.0F, 0.0F, width, 2, 32, 2);
        super.extractRenderState(graphics, mouseX, mouseY, delta);
    }

    private void drawGroup(GuiGraphicsExtractor graphics, Group group) {
        int y = listTop() + group.y() - scrollOffset;
        if (y + group.height() <= listTop() || y >= listBottom()) return;
        graphics.fill(contentLeft(), y, contentRight(), y + group.height(), 0xAD101820);
        graphics.text(font, truncate(group.label().getString(), Math.max(1, contentWidth() - 16)),
                contentLeft() + 8, y + 5, 0xFF9FC8E8);
        graphics.fill(contentLeft() + 8, y + 19, contentRight() - 8, y + 20, 0x554F6476);
    }

    private void drawFavicon(GuiGraphicsExtractor graphics) {
        int x = contentLeft() + 8;
        int y = listTop() + faviconTop - scrollOffset;
        if (y + 48 <= listTop() || y >= listBottom()) return;
        Identifier favicon = room == null || room.key() == null ? null
                : LanRoomFaviconCache.texture(minecraft, room.key().toString(), room.favicon());
        if (favicon != null) {
            graphics.blit(RenderPipelines.GUI_TEXTURED, favicon, x, y, 0.0F, 0.0F, 48, 48, 48, 48);
        } else {
            graphics.fill(x, y, x + 48, y + 48, 0xFF202830);
            graphics.text(font, "?", x + 21, y + 20, 0xFF7E8B96);
        }
    }

    private void drawProperty(GuiGraphicsExtractor graphics, PropertyRow row, int mouseX, int mouseY) {
        int y = listTop() + row.y() - scrollOffset;
        if (y + ROW_HEIGHT <= listTop() || y >= listBottom()) return;
        int left = row.x() + 8;
        int available = Math.max(1, row.width() - 16);
        int labelWidth = Math.min(116, Math.max(1, available * 2 / 5));
        int valueX = left + labelWidth + 8;
        int valueWidth = Math.max(1, row.x() + row.width() - 8 - valueX);
        String label = row.label().getString();
        String value = singleLine(row.value());
        graphics.text(font, truncate(label, labelWidth), left, y + 5, 0xFFB9C2CA);
        graphics.text(font, truncate(value, valueWidth), valueX, y + 5, 0xFFE6EAF0);
        if (insideViewport(mouseX, mouseY) && mouseX >= left && mouseX < row.x() + row.width() - 8
                && mouseY >= y && mouseY < y + ROW_HEIGHT
                && (font.width(label) > labelWidth || font.width(value) > valueWidth || !value.equals(row.value()))) {
            Component full = row.label().copy().append(": ").append(row.value());
            graphics.setTooltipForNextFrame(font, font.split(full, Math.max(1, Math.min(360, width - 32))), mouseX, mouseY);
        }
    }

    private String formatPlayers() {
        return room == null || room.players() < 0 || room.maxPlayers() < 0
                ? valueUnknown() : room.players() + "/" + room.maxPlayers();
    }

    private String formatLatency(long latency) {
        return Component.translatable(latency < 0 ? "screen.radminlan.peer.latency_unknown"
                : "screen.radminlan.peer.latency", latency).getString();
    }

    private String peerState() {
        String key = peer == null ? "unknown" : peer.online() ? "online"
                : "offline".equalsIgnoreCase(peer.state()) ? "offline" : "unknown";
        return Component.translatable("screen.radminlan.peer." + key).getString();
    }

    private String peerValue(String value) { return value == null || value.isBlank() ? valueUnknown() : value; }
    private String valueUnknown() { return Component.translatable("screen.radminlan.property.not_provided").getString(); }
    private static String safe(String value) { return value == null ? "" : value; }
    private static String singleLine(String value) { return safe(value).replace('\n', ' ').replace('\r', ' '); }
    private int panelWidth() { return Math.max(1, Math.min(760, width - 24)); }
    private int contentLeft() { return (width - panelWidth()) / 2; }
    private int contentRight() { return Math.max(contentLeft() + 1, scrollbarX() - 8); }
    private int contentWidth() { return contentRight() - contentLeft(); }
    private int scrollbarX() { return contentLeft() + panelWidth() - 6; }
    private int listTop() { return Math.min(HEADER_HEIGHT, Math.max(0, height - FOOTER_HEIGHT - 1)); }
    private int listBottom() { return Math.max(listTop() + 1, height - FOOTER_HEIGHT); }
    private int listViewportHeight() { return Math.max(1, listBottom() - listTop()); }
    private int maxScroll() { return Math.max(0, contentHeight - listViewportHeight()); }
    private void clampScroll() { scrollOffset = Math.max(0, Math.min(scrollOffset, maxScroll())); }
    private boolean insideViewport(double x, double y) {
        return x >= contentLeft() && x < contentLeft() + panelWidth() && y >= listTop() && y < listBottom();
    }
    private boolean overScrollbar(double x, double y) {
        return insideViewport(x, y) && x >= scrollbarX() - 3 && x < scrollbarX() + 9;
    }
    private int thumbHeight() {
        int viewport = listViewportHeight();
        return Math.min(viewport, Math.max(18, (int) ((long) viewport * viewport / Math.max(viewport, contentHeight))));
    }
    private int thumbY() {
        int travel = Math.max(0, listViewportHeight() - thumbHeight());
        return listTop() + (maxScroll() == 0 ? 0 : (int) ((long) travel * scrollOffset / maxScroll()));
    }
    private String truncate(String value, int maxWidth) {
        if (value == null || font.width(value) <= maxWidth) return safe(value);
        String ellipsis = "…";
        if (font.width(ellipsis) > maxWidth) return "";
        return font.plainSubstrByWidth(value, Math.max(0, maxWidth - font.width(ellipsis))) + ellipsis;
    }

    private record Property(String label, String value) {}
    private record PropertyRow(Component label, String value, int x, int y, int width) {}
    private record Group(Component label, int y, int height) {}
    private record ActionEntry(Component label, Runnable action) {}
}
