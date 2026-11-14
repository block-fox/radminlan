package dev.radminlan.ui;

import dev.radminlan.RadminLanClient;
import dev.radminlan.input.MouseButtons;
import dev.radminlan.network.RadminBackend;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.InputWithModifiers;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/** Public-network browser matching the compact Radmin join-network workflow. */
public final class PublicNetworksScreen extends Screen {
    private static final int ROW_HEIGHT = 24;
    private static final int FOOTER = 42;
    // The list starts immediately below the header separator. Partially
    // visible rows are clipped here as they scroll behind the header/footer.
    private static final int TOP = 70;

    private final RadminScreen parent;
    private final List<PublicNetworkRow> rows = new ArrayList<>();
    private List<String> networks = List.of();
    private final Set<String> selected = new HashSet<>();
    private EditBox searchBox;
    private Button joinButton;
    private Button invertButton;
    private Component status = Component.translatable("screen.radminlan.public_networks_loading");
    private boolean loading;
    private boolean failed;
    private boolean rowsDirty;
    private long loadGeneration;
    private String anchorName = "";
    private int scrollOffset;
    private boolean draggingScrollbar;
    private int dragGrabOffset;
    private List<String> cachedFilteredNetworks = List.of();
    private List<String> cachedFilterSource = List.of();
    private String cachedFilterQuery = "";

    public PublicNetworksScreen(RadminScreen parent) {
        super(Component.translatable("screen.radminlan.public_networks"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        String previousQuery = searchBox == null ? "" : searchBox.getValue();
        clearWidgets();
        rows.clear();
        int refreshWidth = width < 420 ? 76 : 92;
        int invertWidth = width < 420 ? 72 : 84;
        int refreshX = Math.max(20, width - 20 - refreshWidth);
        int invertX = Math.max(20, refreshX - 8 - invertWidth);
        int searchWidth = Math.max(120, invertX - 8 - 20);
        searchBox = new EditBox(font, 20, 40, searchWidth, 20, Component.translatable("screen.radminlan.search"));
        searchBox.setMaxLength(128);
        searchBox.setHint(Component.translatable("screen.radminlan.public_network_search_hint"));
        searchBox.setValue(previousQuery);
        searchBox.setResponder(value -> {
            // Search is local. The control-pipe enumeration is requested
            // once, matching the official client's join-network page.
            scrollOffset = 0;
            invalidateFilteredNetworks();
            rowsDirty = true;
            updateSelectionControls();
        });
        addRenderableWidget(searchBox);
        addRenderableWidget(Button.builder(Component.translatable("screen.radminlan.refresh"), button -> loadNetworks(""))
                .bounds(refreshX, 40, refreshWidth, 20).build());
        invertButton = Button.builder(Component.translatable("screen.radminlan.invert_selection"), button -> invertSelection())
                .bounds(invertX, 40, invertWidth, 20).build();
        invertButton.visible = !selected.isEmpty();
        addRenderableWidget(invertButton);
        int footerButtonWidth = Math.min(104, Math.max(1, (width - 48) / 2));
        int footerStartX = (width - (footerButtonWidth * 2 + 8)) / 2;
        joinButton = Button.builder(Component.translatable("screen.radminlan.join"), button -> joinSelected())
                .bounds(footerStartX + footerButtonWidth + 8, height - 30, footerButtonWidth, 20).build();
        addRenderableWidget(joinButton);
        addRenderableWidget(Button.builder(Component.translatable("screen.radminlan.cancel"), button -> onClose())
                .bounds(footerStartX, height - 30, footerButtonWidth, 20).build());
        updateSelectionControls();
        rebuildRows();
        if (networks.isEmpty() && !loading) loadNetworks("");
    }

    private String currentQuery() { return searchBox == null ? "" : searchBox.getValue().trim(); }

    private void loadNetworks(String query) {
        String normalized = query == null ? "" : query.trim();
        long generation = ++loadGeneration;
        loading = true;
        failed = false;
        status = Component.translatable("screen.radminlan.public_networks_loading");
        rowsDirty = true;
        RadminLanClient.BACKEND.publicNetworks(normalized).whenComplete((result, error) -> {
            if (minecraft == null) return;
            minecraft.execute(() -> {
                if (generation != loadGeneration) return;
                // Do not replace a failed directory query with the networks that
                // are already joined. That made an enumeration failure look like
                // a working public-network browser and left the user with no way
                // to discover or join a new network.
                applyLoadedNetworks(error == null && result != null ? normalizeNames(result) : List.of(), error != null);
            });
        });
    }

    private static List<String> normalizeNames(List<String> source) {
        return source == null ? List.of() : source.stream().filter(name -> name != null && !name.isBlank())
                .map(String::trim).distinct().sorted(String.CASE_INSENSITIVE_ORDER).toList();
    }

    private void applyLoadedNetworks(List<String> loaded, boolean failedLoad) {
        networks = loaded == null ? List.of() : List.copyOf(loaded);
        invalidateFilteredNetworks();
        loading = false;
        failed = failedLoad;
        status = failed ? Component.translatable("screen.radminlan.public_networks_failed")
                : Component.translatable("screen.radminlan.public_network_count", networks.size());
        selected.retainAll(networks);
        updateSelectionControls();
        scrollOffset = 0;
        rowsDirty = false;
        rebuildRows();
    }

    private void updateSelectionControls() {
        if (joinButton != null) joinButton.active = !loading && !selected.isEmpty() && RadminLanClient.BACKEND.supportsWrite();
        if (invertButton != null) invertButton.visible = !selected.isEmpty();
    }

    private void joinSelected() {
        List<String> targets = filteredNetworks().stream().filter(selected::contains).toList();
        if (targets.isEmpty() || loading) return;
        loading = true;
        updateSelectionControls();
        status = Component.translatable("screen.radminlan.request_pending");
        CompletableFuture<Boolean> chain = joinTargets(targets, 0);
        chain.whenComplete((ok, error) -> {
            if (minecraft == null) return;
            minecraft.execute(() -> {
                loading = false;
                boolean success = error == null && Boolean.TRUE.equals(ok);
                if (success) {
                    parent.refreshAfterOperation("screen.radminlan.request_sent");
                    minecraft.setScreen(parent);
                } else {
                    status = Component.translatable("screen.radminlan.request_failed");
                    updateSelectionControls();
                }
            });
        });
    }

    private CompletableFuture<Boolean> joinTargets(List<String> targets, int index) {
        if (index >= targets.size()) return CompletableFuture.completedFuture(true);
        String name = targets.get(index);
        return RadminLanClient.BACKEND.joinNetwork(name, "")
                .thenCompose(joined -> joined ? joinTargets(targets, index + 1)
                        : CompletableFuture.completedFuture(false));
    }

    private void invertSelection() {
        for (String name : filteredNetworks()) {
            if (!selected.add(name)) selected.remove(name);
        }
        updateSelectionControls();
        rebuildRows();
    }

    private void rebuildRows() {
        for (PublicNetworkRow row : List.copyOf(rows)) removeWidget(row);
        rows.clear();
        clampScroll();
        List<String> filtered = filteredNetworks();
        for (int i = 0; i < filtered.size(); i++) {
            int y = listTop() + i * ROW_HEIGHT - scrollOffset;
            if (y + ROW_HEIGHT <= listTop() || y >= listBottom()) continue;
            PublicNetworkRow row = new PublicNetworkRow(20, y, width - 44, ROW_HEIGHT - 2, filtered.get(i));
            rows.add(row);
            // Keep rows in the input/narration lists; the screen extracts the
            // complete widgets once inside its list scissor below.
            addWidget(row);
        }
    }

    private List<String> filteredNetworks() {
        String query = currentQuery().toLowerCase(Locale.ROOT);
        if (cachedFilterSource == networks && query.equals(cachedFilterQuery)) return cachedFilteredNetworks;
        cachedFilterSource = networks;
        cachedFilterQuery = query;
        cachedFilteredNetworks = query.isBlank() ? networks : networks.stream()
                .filter(name -> name.toLowerCase(Locale.ROOT).contains(query)).toList();
        return cachedFilteredNetworks;
    }

    private void invalidateFilteredNetworks() {
        cachedFilterSource = List.of();
        cachedFilterQuery = "";
        cachedFilteredNetworks = List.of();
    }

    @Override
    public void tick() {
        super.tick();
        clampScroll();
        if (rowsDirty) {
            rowsDirty = false;
            rebuildRows();
        }
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (event.button() == MouseButtons.left() && event.x() >= width - 18 && event.x() <= width - 4 && event.y() >= listTop() && event.y() < listBottom() && maxScroll() > 0) {
            draggingScrollbar = true;
            dragGrabOffset = Math.max(0, (int) event.y() - thumbY());
            return true;
        }
        return super.mouseClicked(event, doubleClick);
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dragX, double dragY) {
        if (!draggingScrollbar) return super.mouseDragged(event, dragX, dragY);
        int travel = Math.max(1, listBottom() - listTop() - thumbHeight());
        int relative = Math.max(0, Math.min(travel, (int) event.y() - listTop() - dragGrabOffset));
        scrollOffset = maxScroll() * relative / travel;
        rebuildRows();
        return true;
    }

    @Override public boolean mouseReleased(MouseButtonEvent event) { draggingScrollbar = false; return super.mouseReleased(event); }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        if (mouseY < listTop() || mouseY >= listBottom()) return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
        int next = Math.max(0, Math.min(maxScroll(), scrollOffset - (verticalAmount > 0 ? ROW_HEIGHT * 3 : -ROW_HEIGHT * 3)));
        if (next == scrollOffset) return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
        scrollOffset = next;
        rebuildRows();
        return true;
    }

    @Override public void onClose() { if (minecraft != null) minecraft.setScreen(parent); }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float delta) {
        renderTransparentBackground(graphics);
        graphics.drawCenteredString(font, title, width / 2, 12, 0xFFFFFFFF);
        graphics.drawString(font, status, 20, 24, 0xFFB9C2CA);
        graphics.enableScissor(20, listTop(), width - 20, listBottom());
        try {
            if (filteredNetworks().isEmpty()) {
                String emptyKey = loading ? "screen.radminlan.public_networks_loading" : failed ? "screen.radminlan.public_networks_failed" : "screen.radminlan.public_networks_empty";
                graphics.drawString(font, Component.translatable(emptyKey), 30, listTop() + 8, 0xFFAAAAAA);
            }
            for (PublicNetworkRow row : rows) row.render(graphics, mouseX, mouseY, delta);
        } finally {
            graphics.disableScissor();
        }
        if (maxScroll() > 0) {
            graphics.fill(width - 11, listTop(), width - 7, listBottom(), 0x66404040);
            graphics.fill(width - 11, thumbY(), width - 7, thumbY() + thumbHeight(), 0xFFB0B0B0);
        }
        super.render(graphics, mouseX, mouseY, delta);
        graphics.blit(net.minecraft.client.renderer.RenderPipelines.GUI_TEXTURED, Screen.HEADER_SEPARATOR,
                0, listTop() - 2, 0.0F, 0.0F, width, 2, 32, 2);
        graphics.blit(net.minecraft.client.renderer.RenderPipelines.GUI_TEXTURED, Screen.FOOTER_SEPARATOR,
                0, listBottom(), 0.0F, 0.0F, width, 2, 32, 2);
    }

    private int listTop() { return TOP; }
    private int listBottom() { return Math.max(listTop() + 1, height - FOOTER); }
    private int maxScroll() { return Math.max(0, filteredNetworks().size() * ROW_HEIGHT - (listBottom() - listTop())); }
    private void clampScroll() { scrollOffset = Math.min(scrollOffset, maxScroll()); }
    private int thumbHeight() { int view = Math.max(1, listBottom() - listTop()); return Math.min(view, Math.max(18, view * view / Math.max(view, filteredNetworks().size() * ROW_HEIGHT))); }
    private int thumbY() { int track = Math.max(0, listBottom() - listTop() - thumbHeight()); return listTop() + (maxScroll() == 0 ? 0 : track * scrollOffset / maxScroll()); }

    private final class PublicNetworkRow extends AbstractButton {
        private final String name;
        private PublicNetworkRow(int x, int y, int width, int height, String name) { super(x, y, width, height, Component.literal(name)); this.name = name; }
        @Override public boolean isMouseOver(double mouseX, double mouseY) {
            return mouseY >= listTop() && mouseY < listBottom() && super.isMouseOver(mouseX, mouseY);
        }
        @Override public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
            return isMouseOver(event.x(), event.y()) && super.mouseClicked(event, doubleClick);
        }
        @Override public void onPress(InputWithModifiers input) {
            if (input.hasShiftDown() && !anchorName.isBlank()) {
                List<String> all = filteredNetworks();
                int from = all.indexOf(anchorName), to = all.indexOf(name);
                if (from >= 0 && to >= 0) for (int i = Math.min(from, to); i <= Math.max(from, to); i++) selected.add(all.get(i)); else selected.add(name);
            } else if (!selected.add(name)) {
                selected.remove(name);
            }
            anchorName = name;
            updateSelectionControls();
            rowsDirty = true;
        }
        @Override protected void renderContents(GuiGraphics graphics, int mouseX, int mouseY, float delta) {
            boolean checked = selected.contains(name);
            if (checked) graphics.fill(getX(), getY(), getRight(), getBottom(), 0x553E6B9E);
            else if (isHoveredOrFocused()) graphics.fill(getX(), getY(), getRight(), getBottom(), 0x332C4C70);
            int checkboxY = getY() + (getHeight() - 11) / 2;
            graphics.fill(getX() + 8, checkboxY, getX() + 19, checkboxY + 11, checked ? 0xFF4D86B8 : 0xAA151A1F);
            graphics.renderOutline(getX() + 8, checkboxY, 11, 11, checked ? 0xFFB8D8F5 : 0xFF87939D);
            if (checked) {
                graphics.fill(getX() + 11, checkboxY + 5, getX() + 13, checkboxY + 8, 0xFFFFFFFF);
                graphics.fill(getX() + 13, checkboxY + 3, getX() + 16, checkboxY + 7, 0xFFFFFFFF);
            }
            graphics.drawString(font, name, getX() + 34, getY() + 5, 0xFFFFFFFF);
        }
        @Override public void updateWidgetNarration(NarrationElementOutput output) { defaultButtonNarrationText(output); }
    }
}
