package dev.radminlan.ui;

import dev.radminlan.RadminLanClient;
import dev.radminlan.network.RadminBackend;
import dev.radminlan.room.LanRoomSnapshot;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.components.tabs.GridLayoutTab;
import net.minecraft.client.gui.components.tabs.MenuTabBar;
import net.minecraft.client.gui.components.tabs.TabManager;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.InputWithModifiers;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Radmin management workspace built from vanilla Minecraft widgets. */
public final class RadminScreen extends Screen {
    /** Roomier rows keep the network name, type and member count readable. */
    private static final int ROW_HEIGHT = 36;
    private static final int FOOTER_HEIGHT = 44;

    private final Screen parent;
    private Tab tab = Tab.NETWORKS;
    private volatile RadminBackend.RadminSnapshot snapshot = RadminBackend.RadminSnapshot.unavailable("Loading...");
    private String lastAction = "";
    private String listSignature = "";
    private int scrollOffset;
    private int refreshTicks;
    private boolean networkActionsExpanded;
    private boolean rebuildingTabs;
    private boolean draggingScrollbar;
    private int dragGrabOffset;
    private boolean dynamicWidgetsDirty;
    private boolean multiSelectMode;
    private final Set<String> selectedNetworkGuids = new HashSet<>();
    private final Set<Long> selectedPeerIds = new HashSet<>();
    private final Set<String> selectedBlockedRoomKeys = new HashSet<>();
    private final Set<Long> selectedBlockedPeerIds = new HashSet<>();
    private boolean refreshInFlight;
    private RadminBackend.Network selectedNetwork;
    private RadminBackend.Peer selectedPeer;
    private RadminBackend.Network contextNetwork;
    private RadminBackend.Peer contextPeer;
    private int contextMenuX;
    private int contextMenuY;
    private EditBox searchBox;
    private Button powerButton;
    private final List<GuiEventListener> dynamicWidgets = new ArrayList<>();
    private final List<AbstractButton> contextButtons = new ArrayList<>();

    // Filtering is used by both layout and rendering. Keep one result per UI
    // state so a frame does not repeatedly allocate equivalent stream results.
    private String cachedFilterQuery;
    private RadminBackend.RadminSnapshot cachedFilterSnapshot;
    private String cachedFilterNetworkGuid;
    private List<LanRoomSnapshot> cachedFilterBlockedSource = List.of();
    private List<RadminBackend.Network> cachedFilteredNetworks = List.of();
    private List<RadminBackend.Peer> cachedFilteredPeers = List.of();
    private List<LanRoomSnapshot> cachedFilteredBlockedRooms = List.of();
    private List<RadminBackend.Peer> cachedFilteredBlockedPeers = List.of();
    private int cachedContentHeight = -1;
    private final Map<Long, String> cachedPeerNetworkNames = new HashMap<>();
    private long filterGeneration;
    private long blockedRoomsGeneration = Long.MIN_VALUE;
    private List<LanRoomSnapshot> observedBlockedRooms = List.of();
    private int observedBlockedRoomsHash;
    private int snapshotNetworksHash;

    public RadminScreen(Screen parent) {
        super(Component.translatable("screen.radminlan.title"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        rebuildWidgets();
        refresh();
    }

    @Override
    protected void rebuildWidgets() {
        String previousSearch = searchBox == null ? "" : searchBox.getValue();
        powerButton = null;
        clearWidgets();
        dynamicWidgets.clear();
        clampScrollOffset();

        RadminMenuTab networksTab = new RadminMenuTab(Tab.NETWORKS, Component.translatable("screen.radminlan.networks"));
        RadminMenuTab peersTab = new RadminMenuTab(Tab.PEERS, Component.translatable("screen.radminlan.peers"));
        RadminMenuTab blockedTab = new RadminMenuTab(Tab.BLOCKED, Component.translatable("screen.radminlan.blocked"));
        rebuildingTabs = true;
        TabManager manager = new TabManager(
                widget -> addRenderableWidget(widget),
                this::removeWidget,
                selected -> {
                    if (rebuildingTabs || !(selected instanceof RadminMenuTab menuTab) || tab == menuTab.page) return;
                    tab = menuTab.page;
                    selectedNetwork = null;
                    selectedPeer = null;
                    clearContext();
                    scrollOffset = 0;
                    rebuildWidgets();
                },
                ignored -> {}
        );
        MenuTabBar tabBar = MenuTabBar.builder(manager, width)
                .addTabs(networksTab, peersTab, blockedTab)
                .build();
        tabBar.arrangeElements(width);
        addRenderableWidget(tabBar);
        manager.setCurrentTab(switch (tab) {
            case NETWORKS -> networksTab;
            case PEERS -> peersTab;
            case BLOCKED -> blockedTab;
        }, false);
        rebuildingTabs = false;

        int searchY = 68;
        int searchWidth = searchWidth();
        searchBox = new EditBox(font, 20, searchY, searchWidth, 20, Component.translatable("screen.radminlan.search"));
        searchBox.setMaxLength(128);
        searchBox.setHint(Component.translatable("screen.radminlan.search_hint"));
        searchBox.setValue(previousSearch);
        searchBox.setResponder(value -> {
            scrollOffset = 0;
            dynamicWidgetsDirty = true;
        });
        addRenderableWidget(searchBox);

        if (tab == Tab.NETWORKS) {
            // Keep selection tools after the search field on wide screens.
            // Narrow screens move them to their own row before network actions.
            int controlY = width < 620 ? 92 : searchY;
            int controlX = width < 620 ? 20 : 20 + searchWidth + 6;
            int selectWidth = 92;
            int invertWidth = 82;
            addRenderableWidget(Button.builder(Component.translatable(multiSelectMode ? "screen.radminlan.single_select" : "screen.radminlan.multi_select"), button -> {
                multiSelectMode = !multiSelectMode;
                if (!multiSelectMode) {
                    selectedNetworkGuids.clear();
                    selectedPeerIds.clear();
                    selectedBlockedRoomKeys.clear();
                    selectedBlockedPeerIds.clear();
                }
                rebuildWidgets();
            }).bounds(controlX, controlY, selectWidth, 20).build());
            if (multiSelectMode) {
                addRenderableWidget(Button.builder(Component.translatable("screen.radminlan.invert_selection"), button -> invertSelection())
                        .bounds(controlX + selectWidth + 6, controlY, invertWidth, 20).build());
            }
        } else if (width >= 420) {
            int controlX = 20 + searchWidth + 6;
            int selectWidth = 92;
            int invertWidth = 82;
            addRenderableWidget(Button.builder(Component.translatable(multiSelectMode ? "screen.radminlan.single_select" : "screen.radminlan.multi_select"), button -> {
                multiSelectMode = !multiSelectMode;
                if (!multiSelectMode) {
                    selectedNetworkGuids.clear();
                    selectedPeerIds.clear();
                    selectedBlockedRoomKeys.clear();
                    selectedBlockedPeerIds.clear();
                }
                rebuildWidgets();
            }).bounds(controlX, searchY, selectWidth, 20).build());
            if (multiSelectMode) {
                addRenderableWidget(Button.builder(Component.translatable("screen.radminlan.invert_selection"), button -> invertSelection())
                        .bounds(controlX + selectWidth + 6, searchY, invertWidth, 20).build());
            }
        }

        if (tab == Tab.NETWORKS) addNetworkControls();
        if (snapshot.localName() != null && !snapshot.localName().isBlank()) {
            addRenderableWidget(Button.builder(Component.translatable("screen.radminlan.edit_alias"), button ->
                    minecraft.setScreenAndShow(new AliasEditScreen(this, snapshot.localName())))
                    .bounds(width - 128, 42, 108, 20).build());
        }
        addRenderableWidget(Button.builder(Component.translatable("screen.radminlan.close"), button -> onClose())
                .bounds(20, height - 30, 108, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("screen.radminlan.refresh"), button -> refresh())
                .bounds(width - 128, height - 30, 108, 20).build());
        refreshDynamicWidgets();
    }

    private int searchWidth() {
        if (tab != Tab.NETWORKS) return Math.min(360, Math.max(140, width - 180));
        if (width < 620) return Math.max(140, width - 40);
        return Math.max(180, Math.min(280, width - 470));
    }

    private void addBatchControls() {
        int y = Math.max(68, listTop() - 24);
        if (tab == Tab.NETWORKS) {
            int x = 20;
            addDynamic(Button.builder(Component.translatable("screen.radminlan.enable_network"), button -> batchNetworkEnabled(true))
                    .bounds(x, y, 84, 20).build());
            x += 90;
            addDynamic(Button.builder(Component.translatable("screen.radminlan.disable_network"), button -> batchNetworkEnabled(false))
                    .bounds(x, y, 84, 20).build());
            x += 90;
            addDynamic(Button.builder(Component.translatable("screen.radminlan.leave"), button -> batchLeaveNetworks())
                    .bounds(x, y, 76, 20).build());
        } else if (tab == Tab.PEERS) {
            addDynamic(Button.builder(Component.translatable("screen.radminlan.block_user"), button -> batchBlockPeers())
                    .bounds(20, y, 110, 20).build());
            addDynamic(Button.builder(Component.translatable("screen.radminlan.kick"), button -> batchKickPeers())
                    .bounds(136, y, 90, 20).build());
        } else if (tab == Tab.BLOCKED) {
            addDynamic(Button.builder(Component.translatable("screen.radminlan.unblock"), button -> batchUnblockBlocked())
                    .bounds(20, y, 112, 20).build());
        }
    }

    private void addNetworkControls() {
        int y = width < 620 ? 116 : 92;
        int publicWidth = width < 620 ? 112 : 126;
        int networkWidth = width < 620 ? 96 : 104;
        int powerWidth = width < 620 ? 92 : 106;
        int powerX = width - 20 - powerWidth;
        int networkX = powerX - 6 - networkWidth;
        int publicX = networkX - 6 - publicWidth;
        addRenderableWidget(Button.builder(Component.translatable("screen.radminlan.public_networks"), button -> {
            if (minecraft != null) minecraft.setScreenAndShow(new PublicNetworksScreen(this));
        }).bounds(publicX, y, publicWidth, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("screen.radminlan.network_menu"), button -> {
            networkActionsExpanded = !networkActionsExpanded;
            rebuildWidgets();
        }).bounds(networkX, y, networkWidth, 20).build());
        Component powerLabel = Component.translatable(snapshot.power() == null ? "screen.radminlan.power_unknown"
                : Boolean.TRUE.equals(snapshot.power()) ? "screen.radminlan.power_on" : "screen.radminlan.power_off");
        Button power = Button.builder(powerLabel, button -> setPower(!Boolean.TRUE.equals(snapshot.power())))
                .bounds(powerX, y, powerWidth, 20).build();
        // When the service is powered off the pipe may report read-only even
        // though the Windows service can be started again. Keep this control
        // available so the user can recover Radmin from inside the game.
        power.active = snapshot.canWrite() || snapshot.state() == RadminBackend.ServiceState.DISABLED
                || snapshot.state() == RadminBackend.ServiceState.UNAVAILABLE;
        powerButton = power;
        addRenderableWidget(power);

        if (networkActionsExpanded) {
            int actionY = y + 24;
            int actionX = width < 620 ? 20 : networkX;
            addRenderableWidget(Button.builder(Component.translatable("screen.radminlan.join_network"), button ->
                    minecraft.setScreenAndShow(new NetworkOperationScreen(this, NetworkOperationScreen.Mode.JOIN)))
                    .bounds(actionX, actionY, 116, 20).build());
            addRenderableWidget(Button.builder(Component.translatable("screen.radminlan.create_network"), button ->
                    minecraft.setScreenAndShow(new NetworkOperationScreen(this, NetworkOperationScreen.Mode.CREATE)))
                    .bounds(actionX + 122, actionY, 124, 20).build());
        }
    }

    private <T extends GuiEventListener & net.minecraft.client.gui.components.Renderable & net.minecraft.client.gui.narration.NarratableEntry> T addDynamic(T widget) {
        dynamicWidgets.add(widget);
        return addRenderableWidget(widget);
    }

    private void refreshDynamicWidgets() {
        invalidateFilterCache();
        for (GuiEventListener widget : List.copyOf(dynamicWidgets)) removeWidget(widget);
        dynamicWidgets.clear();
        contextButtons.clear();
        clampScrollOffset();
        if (multiSelectMode && hasAnySelection()) addBatchControls();
        switch (tab) {
            case NETWORKS -> addNetworkRows(filteredNetworks());
            case PEERS -> addPeerRows(filteredPeers());
            case BLOCKED -> addBlockedRows();
        }
        if (contextNetwork != null) addNetworkContextButtons();
        else if (contextPeer != null) addPeerContextButtons();
    }

    private void addNetworkRows(List<RadminBackend.Network> networks) {
        for (int index = 0; index < networks.size(); index++) {
            int y = listContentTop() + index * ROW_HEIGHT - scrollOffset;
            if (!rowVisible(y, ROW_HEIGHT)) continue;
            addDynamic(new NetworkRowButton(20, y, width - 48, ROW_HEIGHT - 2, networks.get(index)));
        }
    }

    private void addPeerRows(List<RadminBackend.Peer> peers) {
        for (int index = 0; index < peers.size(); index++) {
            int y = listContentTop() + index * ROW_HEIGHT - scrollOffset;
            if (!rowVisible(y, ROW_HEIGHT)) continue;
            addDynamic(new PeerRowButton(20, y, width - 48, ROW_HEIGHT - 2, peers.get(index)));
        }
    }

    private void addBlockedRows() {
        List<LanRoomSnapshot> rooms = filteredBlockedRooms();
        List<RadminBackend.Peer> peers = filteredBlockedPeers();
        int peerRowsTop = listContentTop() - scrollOffset;
        if (!rooms.isEmpty()) peerRowsTop += 22 + rooms.size() * ROW_HEIGHT;
        if (!peers.isEmpty()) peerRowsTop += 22;
        for (int i = 0; i < peers.size(); i++) {
            int rowY = peerRowsTop + i * ROW_HEIGHT;
            if (!rowVisible(rowY, ROW_HEIGHT)) continue;
            RadminBackend.Peer peer = peers.get(i);
            addDynamic(new BlockedPeerRowButton(20, rowY, width - 176, ROW_HEIGHT - 2, peer));
            addDynamic(Button.builder(Component.translatable("screen.radminlan.unblock"), button -> {
                RadminLanClient.CONFIG.unblockPeer(peer.nodeId(), peer.name(), peer.publicIp(), peer.virtualIp());
                selectedBlockedPeerIds.remove(peerKey(peer));
                refreshDynamicWidgets();
            }).bounds(width - 124, rowY + 2, 104, 20).build());
        }
        for (int i = 0; i < rooms.size(); i++) {
            int rowY = listContentTop() + 22 + i * ROW_HEIGHT - scrollOffset;
            if (!rowVisible(rowY, ROW_HEIGHT)) continue;
            LanRoomSnapshot room = rooms.get(i);
            addDynamic(new BlockedRoomRowButton(20, rowY, width - 176, ROW_HEIGHT - 2, room));
            addDynamic(Button.builder(Component.translatable("screen.radminlan.unblock"), button -> unblockRoom(room))
                    .bounds(width - 124, rowY + 2, 104, 20).build());
        }
    }

    private void addNetworkContextButtons() {
        int x = contextPanelX();
        int y = contextPanelY() + 23;
        boolean enabled = !isNetworkDisabled(contextNetwork);
        addContextButton(Component.translatable("screen.radminlan.view_members"), x, y,
                () -> showNetworkMembers(contextNetwork));
        addContextButton(Component.translatable(enabled ? "screen.radminlan.disable_network" : "screen.radminlan.enable_network"), x, y + 20,
                () -> setNetworkEnabled(contextNetwork, !enabled));
        addContextButton(Component.translatable("screen.radminlan.copy_guid"), x, y + 40, () -> {
            if (minecraft != null) minecraft.keyboardHandler.setClipboard(contextNetwork.guid());
            lastAction = "screen.radminlan.copied";
            clearContext();
            refreshDynamicWidgets();
        });
        addContextButton(Component.translatable("screen.radminlan.leave"), x, y + 60, () -> leaveNetwork(contextNetwork));
        addContextButton(Component.translatable("screen.radminlan.properties"), x, y + 80, () -> showNetworkProperties(contextNetwork));
        if (Boolean.TRUE.equals(contextNetwork.admin())) {
            addContextButton(Component.translatable("screen.radminlan.delete_network"), x, y + 100,
                    () -> deleteNetwork(contextNetwork));
        }
    }

    private void addPeerContextButtons() {
        int x = contextPanelX();
        int y = contextPanelY() + 23;
        addContextButton(Component.translatable("screen.radminlan.properties"), x, y, this::showPeerDetails);
        addContextButton(Component.translatable("screen.radminlan.block_user"), x, y + 20, this::blockPeer);
        addContextButton(Component.translatable("screen.radminlan.kick"), x, y + 40, this::kickPeer);
        addContextButton(Component.translatable("screen.radminlan.cancel"), x, y + 60, () -> { clearContext(); refreshDynamicWidgets(); });
    }

    private void addContextButton(Component label, int x, int y, Runnable action) {
        Button button = addDynamic(Button.builder(label, widget -> action.run()).bounds(x + 6, y, 184, 20).build());
        contextButtons.add(button);
    }

    private void refresh() {
        if (refreshInFlight) return;
        refreshInFlight = true;
        lastAction = "screen.radminlan.request_pending";
        RadminLanClient.BACKEND.snapshot().whenComplete((result, error) -> {
            if (minecraft == null) return;
            minecraft.execute(() -> {
                refreshInFlight = false;
                if (error != null) {
                    snapshot = RadminBackend.RadminSnapshot.unavailable(error.getMessage() == null ? "Radmin request failed" : error.getMessage());
                    lastAction = "screen.radminlan.request_failed";
                } else {
                    snapshot = result == null ? RadminBackend.RadminSnapshot.unavailable("Radmin returned no snapshot") : result;
                    snapshotNetworksHash = snapshot.networks().hashCode();
                    RadminLanClient.publishSnapshot(snapshot);
                    lastAction = "screen.radminlan.refreshed";
                }
                listSignature = currentSignature();
                updatePowerButton();
                refreshDynamicWidgets();
            });
        });
        refreshDynamicWidgets();
    }

    private void updatePowerButton() {
        if (powerButton == null) return;
        powerButton.setMessage(Component.translatable(snapshot.power() == null ? "screen.radminlan.power_unknown"
                : Boolean.TRUE.equals(snapshot.power()) ? "screen.radminlan.power_on" : "screen.radminlan.power_off"));
        powerButton.active = snapshot.canWrite() || snapshot.state() == RadminBackend.ServiceState.DISABLED
                || snapshot.state() == RadminBackend.ServiceState.UNAVAILABLE;
    }

    public void refreshAfterOperation(String message) {
        lastAction = message;
        refresh();
    }

    @Override
    public void tick() {
        super.tick();
        if (++refreshTicks % 40 == 0) refresh();
        // Room discovery is owned by the vanilla detector and has no cheap
        // revision callback. Poll its blocked view at a low cadence; explicit
        // block/unblock actions invalidate it immediately.
        if (tab == Tab.BLOCKED && refreshTicks % 10 == 0) invalidateFilterCache();
        boolean changed = clampScrollOffset();
        String signature = currentSignature();
        if (changed || dynamicWidgetsDirty || !signature.equals(listSignature)) {
            dynamicWidgetsDirty = false;
            listSignature = signature;
            refreshDynamicWidgets();
        }
    }

    private void setPower(boolean enabled) {
        lastAction = "screen.radminlan.request_pending";
        RadminLanClient.BACKEND.setPower(enabled).whenComplete((ok, error) -> runOnClient(() -> {
            lastAction = error == null && Boolean.TRUE.equals(ok) ? "screen.radminlan.request_sent" : "screen.radminlan.request_failed";
            refresh();
        }));
    }

    private void setNetworkEnabled(RadminBackend.Network network, boolean enabled) {
        if (network == null) return;
        clearContext();
        refreshDynamicWidgets();
        lastAction = "screen.radminlan.request_pending";
        RadminLanClient.BACKEND.setNetworkEnabled(network.guid(), enabled).whenComplete((ok, error) -> runOnClient(() -> {
            if (error == null && Boolean.TRUE.equals(ok) && RadminLanClient.CONFIG != null) {
                RadminLanClient.CONFIG.setNetworkDisabled(network.guid(), !enabled);
            }
            lastAction = error == null && Boolean.TRUE.equals(ok) ? "screen.radminlan.request_sent" : "screen.radminlan.request_failed";
            refresh();
        }));
    }

    private void leaveNetwork(RadminBackend.Network network) {
        if (network == null) return;
        clearContext();
        refreshDynamicWidgets();
        lastAction = "screen.radminlan.request_pending";
        RadminLanClient.BACKEND.leaveNetworkByGuid(network.guid()).whenComplete((ok, error) -> runOnClient(() -> {
            lastAction = error == null && Boolean.TRUE.equals(ok) ? "screen.radminlan.request_sent" : "screen.radminlan.request_failed";
            refresh();
        }));
    }

    private void deleteNetwork(RadminBackend.Network network) {
        if (network == null) return;
        clearContext();
        refreshDynamicWidgets();
        lastAction = "screen.radminlan.request_pending";
        RadminLanClient.BACKEND.deleteNetwork(network.guid()).whenComplete((ok, error) -> runOnClient(() -> {
            lastAction = error == null && Boolean.TRUE.equals(ok) ? "screen.radminlan.request_sent" : "screen.radminlan.request_failed";
            refresh();
        }));
    }

    private void showNetworkMembers(RadminBackend.Network network) {
        if (network == null) return;
        selectedNetwork = network;
        clearContext();
        tab = Tab.PEERS;
        scrollOffset = 0;
        rebuildWidgets();
    }

    private void kickPeer() {
        RadminBackend.Peer peer = contextPeer;
        clearContext();
        refreshDynamicWidgets();
        if (peer == null) return;
        RadminBackend.Network network = networkFor(peer);
        if (network == null || peer.nodeId() < 0) return;
        lastAction = "screen.radminlan.request_pending";
        RadminLanClient.BACKEND.kickPeer(network.guid(), peer.nodeId(), network.name()).whenComplete((ok, error) -> runOnClient(() -> {
            lastAction = error == null && Boolean.TRUE.equals(ok) ? "screen.radminlan.request_sent" : "screen.radminlan.request_failed";
            refresh();
        }));
    }

    private void blockPeer() {
        RadminBackend.Peer peer = contextPeer;
        clearContext();
        if (peer == null) return;
        RadminLanClient.CONFIG.blockPeer(peer.nodeId(), peer.name(), peer.publicIp(), peer.virtualIp());
        lastAction = "screen.radminlan.peer_blocked";
        refreshDynamicWidgets();
    }

    private void showPeerDetails() {
        RadminBackend.Peer peer = contextPeer;
        clearContext();
        if (peer != null && minecraft != null) minecraft.setScreenAndShow(new PeerDetailsScreen(this, peer));
    }

    private void showPeerDetails(RadminBackend.Peer peer) {
        clearContext();
        if (peer != null && minecraft != null) minecraft.setScreenAndShow(new PeerDetailsScreen(this, peer));
    }

    private void showNetworkProperties(RadminBackend.Network network) {
        clearContext();
        if (network != null && minecraft != null) minecraft.setScreenAndShow(new NetworkPropertiesScreen(this, network));
    }

    private void runOnClient(Runnable task) {
        if (minecraft != null) minecraft.execute(task);
    }

    private RadminBackend.Network networkFor(RadminBackend.Peer peer) {
        return snapshot.networks().stream().filter(network -> network.name().equals(peer.networkName())).findFirst().orElse(null);
    }

    private void clearContext() {
        contextPeer = null;
        contextNetwork = null;
    }

    @Override
    public void onClose() {
        if (minecraft != null) minecraft.setScreenAndShow(parent);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        extractTransparentBackground(graphics);
        Component status = snapshot.state() == RadminBackend.ServiceState.READY
                ? Component.translatable("screen.radminlan.service_ready").append(snapshot.transport().isBlank() ? "" : " · " + snapshot.transport())
                : snapshot.state() == RadminBackend.ServiceState.DISABLED
                        ? Component.translatable("screen.radminlan.service_disabled")
                        : Component.translatable("screen.radminlan.service_unavailable").append(" (")
                                .append(snapshot.message().startsWith("screen.radminlan.")
                                        ? Component.translatable(snapshot.message()) : Component.literal(snapshot.message()))
                                .append(")");
        graphics.text(font, truncate(status.getString(), width - 40), 20, 38,
                snapshot.state() == RadminBackend.ServiceState.READY ? 0xFF80FF80
                        : snapshot.state() == RadminBackend.ServiceState.DISABLED ? 0xFFAAAAAA : 0xFFFFCC66);
        if (!lastAction.isBlank()) graphics.text(font, Component.translatable(lastAction), 20, 52, 0xFFCCCCCC);
        String localName = snapshot.localName() == null ? "" : snapshot.localName().trim();
        String localIp = snapshot.virtualIp() == null ? "" : snapshot.virtualIp().trim();
        if (!localName.isBlank() || !localIp.isBlank()) {
            String localText = localName + (localName.isBlank() || localIp.isBlank() ? "" : "  ·  ") + localIp;
            graphics.text(font, truncate(localText, width - 40), 20, 24, 0xFFB9C2CA);
        }
        graphics.enableScissor(20, listTop(), width - 20, listBottom());
        switch (tab) {
            case NETWORKS -> extractNetworks(graphics);
            case PEERS -> extractPeers(graphics);
            case BLOCKED -> extractBlocked(graphics);
        }
        graphics.disableScissor();
        extractScrollbar(graphics);
        if (contextNetwork != null) extractContextPanel(graphics, contextNetwork.name());
        else if (contextPeer != null) extractContextPanel(graphics, contextPeer.name());
        graphics.blit(net.minecraft.client.renderer.RenderPipelines.GUI_TEXTURED, Screen.HEADER_SEPARATOR,
                20, height - FOOTER_HEIGHT, 0.0F, 0.0F, width - 40, 2, 32, 2);
        super.extractRenderState(graphics, mouseX, mouseY, delta);
    }

    private void extractNetworks(GuiGraphicsExtractor graphics) {
        List<RadminBackend.Network> networks = filteredNetworks();
        if (networks.isEmpty()) {
            graphics.text(font, Component.translatable("screen.radminlan.no_networks"), 30, listContentTop(), 0xFFAAAAAA);
            return;
        }
        for (int i = 0; i < networks.size(); i++) {
            RadminBackend.Network network = networks.get(i);
            int y = listContentTop() + i * ROW_HEIGHT - scrollOffset;
            if (!rowVisible(y, ROW_HEIGHT)) continue;
            boolean disabled = isNetworkDisabled(network);
            boolean selected = network == selectedNetwork;
            int base = selected ? 0x665D7C99 : 0x44101010;
            if (disabled) base = selected ? 0x66474747 : 0x33333333;
            graphics.fill(20, y, width - 20, y + ROW_HEIGHT - 3, base);
            int color = disabled ? 0xFF858585 : 0xFFFFFFFF;
            int nameX = 30;
            if (multiSelectMode) {
                drawCheckbox(graphics, 30, y + 7, selectedNetworkGuids.contains(network.guid()));
                nameX = 48;
            }
            graphics.text(font, truncate(network.name(), Math.max(80, width - (nameX + 220))), nameX, y + 4, color);
            String state = disabled
                    ? Component.translatable("screen.radminlan.network_disabled").getString()
                    : Component.translatable(network.publicNetwork() ? "screen.radminlan.public_network" : "screen.radminlan.private_network").getString();
            graphics.text(font, state + " · " + network.peers().size() + " " + Component.translatable("screen.radminlan.members").getString(), 30, y + 17,
                    disabled ? 0xFF777777 : 0xFFAAAAAA);
            graphics.text(font, truncate(network.guid(), 120), width - 184, y + 10, disabled ? 0xFF686868 : 0xFF777777);
        }
    }

    private void extractPeers(GuiGraphicsExtractor graphics) {
        List<RadminBackend.Peer> peers = filteredPeers();
        if (peers.isEmpty()) {
            graphics.text(font, Component.translatable("screen.radminlan.no_members"), 30, listContentTop(), 0xFFAAAAAA);
            return;
        }
        for (int i = 0; i < peers.size(); i++) {
            RadminBackend.Peer peer = peers.get(i);
            int y = listContentTop() + i * ROW_HEIGHT - scrollOffset;
            if (!rowVisible(y, ROW_HEIGHT)) continue;
            graphics.fill(20, y, width - 20, y + ROW_HEIGHT - 3,
                    peer == selectedPeer ? 0x665D7C99 : 0x44101010);
            int latencyX = width - 112;
            int connectionX = Math.max(220, latencyX - 120);
            int networkX = Math.max(150, Math.min(width / 2, connectionX - 150));
            int nameX = 30;
            if (multiSelectMode) {
                drawCheckbox(graphics, 30, y + 8, selectedPeerIds.contains(peerKey(peer)));
                nameX = 48;
            }
            graphics.text(font, truncate(peer.name(), Math.max(70, networkX - nameX - 8)), nameX, y + 5, 0xFFFFFFFF);
            graphics.text(font, truncate(networkNamesFor(peer), Math.max(60, connectionX - networkX - 12)), networkX, y + 5, 0xFF999999);
            String connection = peer.connectionType().isBlank() ? peer.state() : peer.connectionType();
            graphics.text(font, truncate(connection, Math.max(40, latencyX - connectionX - 12)), connectionX, y + 5, 0xFFAAAAAA);
            // The service can retain a stale numeric latency for a member after
            // its connection drops.  Never present that value as live: the
            // status flag is authoritative and offline members are rendered as
            // the explicit red -1 ms marker used by the properties page.
            graphics.text(font, formatLatency(peer.latencyMs(), peer.online()), latencyX, y + 5,
                    latencyColor(peer.latencyMs(), peer.online()));
        }
    }

    private void extractBlocked(GuiGraphicsExtractor graphics) {
        List<LanRoomSnapshot> rooms = filteredBlockedRooms();
        List<RadminBackend.Peer> peers = filteredBlockedPeers();
        int y = listContentTop() - scrollOffset;
        if (rooms.isEmpty() && peers.isEmpty()) {
            graphics.text(font, Component.translatable("screen.radminlan.no_blocked"), 30, y, 0xFFAAAAAA);
            return;
        }
        if (!rooms.isEmpty()) {
            graphics.text(font, Component.translatable("screen.radminlan.blocked_rooms"), 30, y + 3, 0xFFFFCC66);
            y += 22;
            for (LanRoomSnapshot room : rooms) {
                if (rowVisible(y, ROW_HEIGHT)) {
                    boolean selected = selectedBlockedRoomKeys.contains(blockedRoomKey(room));
                    graphics.fill(20, y, width - 20, y + ROW_HEIGHT - 3, selected ? 0x665D7C99 : 0x44101010);
                    int textX = 30;
                    if (multiSelectMode) {
                        drawCheckbox(graphics, 30, y + 8, selected);
                        textX = 48;
                    }
                    int buttonStart = width - 132;
                    int ipX = Math.max(textX + 80, buttonStart - 170);
                    graphics.text(font, truncate(room.displayMotd().isBlank() ? room.key().toString() : room.displayMotd(), Math.max(80, ipX - textX - 10)), textX, y + 5, 0xFFDDDDDD);
                    graphics.text(font, truncate(room.key().toString(), Math.max(80, buttonStart - ipX - 8)), ipX, y + 5, 0xFF999999);
                }
                y += ROW_HEIGHT;
            }
        }
        if (!peers.isEmpty()) {
            graphics.text(font, Component.translatable("screen.radminlan.blocked_users"), 30, y + 3, 0xFFFFCC66);
            y += 22;
            for (RadminBackend.Peer peer : peers) {
                if (rowVisible(y, ROW_HEIGHT)) {
                    boolean selected = selectedBlockedPeerIds.contains(peerKey(peer));
                    graphics.fill(20, y, width - 20, y + ROW_HEIGHT - 3, selected ? 0x665D7C99 : 0x44101010);
                    int textX = 30;
                    if (multiSelectMode) {
                        drawCheckbox(graphics, 30, y + 8, selected);
                        textX = 48;
                    }
                    graphics.text(font, truncate(peer.name(), Math.max(80, width - 176 - textX)), textX, y + 5, 0xFFDDDDDD);
                }
                y += ROW_HEIGHT;
            }
        }
    }

    private void extractContextPanel(GuiGraphicsExtractor graphics, String title) {
        int x = contextPanelX();
        int y = contextPanelY();
        graphics.fill(x, y, x + 196, y + contextPanelHeight(), 0xF0101010);
        graphics.text(font, truncate(title, 174), x + 10, y + 7, 0xFFFFFFFF);
    }

    private void drawCheckbox(GuiGraphicsExtractor graphics, int x, int y, boolean checked) {
        graphics.fill(x, y, x + 11, y + 11, checked ? 0xFF4D86B8 : 0xAA151A1F);
        graphics.outline(x, y, 11, 11, checked ? 0xFFB8D8F5 : 0xFF87939D);
        if (checked) {
            graphics.fill(x + 3, y + 5, x + 5, y + 8, 0xFFFFFFFF);
            graphics.fill(x + 5, y + 3, x + 8, y + 7, 0xFFFFFFFF);
        }
    }

    private void extractScrollbar(GuiGraphicsExtractor graphics) {
        int content = contentHeight();
        int viewport = Math.max(1, listBottom() - listTop());
        if (content <= viewport) return;
        int thumb = scrollbarThumbHeight();
        int track = viewport - thumb;
        int thumbY = listTop() + (maxScrollOffset() == 0 ? 0 : track * scrollOffset / maxScrollOffset());
        graphics.fill(width - 12, listTop(), width - 7, listBottom(), 0x88404040);
        graphics.fill(width - 12, thumbY, width - 7, thumbY + thumb, 0xFFB0B0B0);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (hasContext()) {
            if (insideContextPanel(event.x(), event.y())) {
                // Context menus are opened with the right mouse button, but
                // their actions use the normal left mouse button.
                if (event.button() == 1) {
                    for (int i = contextButtons.size() - 1; i >= 0; i--) {
                        AbstractButton button = contextButtons.get(i);
                        if (button.isMouseOver(event.x(), event.y())) {
                            button.mouseClicked(event, doubleClick);
                            return true;
                        }
                    }
                }
                return true;
            }
            clearContext();
            refreshDynamicWidgets();
            return true;
        }
        if (event.button() == 1 && isOverScrollbar(event.x(), event.y()) && contentHeight() > listBottom() - listTop()) {
            draggingScrollbar = true;
            int thumbHeight = scrollbarThumbHeight();
            dragGrabOffset = Math.max(0, (int) event.y() - scrollbarThumbY(thumbHeight));
            return true;
        }
        return super.mouseClicked(event, doubleClick);
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dragX, double dragY) {
        if (draggingScrollbar) {
            int thumbHeight = scrollbarThumbHeight();
            int travel = Math.max(1, listBottom() - listTop() - thumbHeight);
            int relative = Math.max(0, Math.min(travel, (int) event.y() - listTop() - dragGrabOffset));
            scrollOffset = maxScrollOffset() * relative / travel;
            refreshDynamicWidgets();
            return true;
        }
        return super.mouseDragged(event, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        draggingScrollbar = false;
        return super.mouseReleased(event);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        if (hasContext()) return true;
        if (mouseY < listTop() || mouseY > listBottom()) return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
        int delta = Math.max(1, (int) Math.round(Math.abs(verticalAmount) * ROW_HEIGHT));
        int next = Math.max(0, Math.min(maxScrollOffset(), scrollOffset - (verticalAmount > 0 ? delta : -delta)));
        if (next == scrollOffset) return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
        scrollOffset = next;
        refreshDynamicWidgets();
        return true;
    }

    private boolean hasContext() { return contextNetwork != null || contextPeer != null; }
    private boolean insideContextPanel(double x, double y) { return x >= contextPanelX() && x <= contextPanelX() + 196 && y >= contextPanelY() && y <= contextPanelY() + contextPanelHeight(); }
    private boolean isOverScrollbar(double x, double y) { return x >= width - 18 && x <= width - 4 && y >= listTop() && y <= listBottom(); }
    private boolean rowVisible(int y, int height) { return y + height >= listTop() && y <= listBottom(); }
    private int scrollbarThumbHeight() { int viewport = Math.max(1, listBottom() - listTop()); return Math.max(18, viewport * viewport / Math.max(viewport, contentHeight())); }
    private int scrollbarThumbY(int thumbHeight) { int travel = Math.max(0, listBottom() - listTop() - thumbHeight); return listTop() + (maxScrollOffset() == 0 ? 0 : travel * scrollOffset / maxScrollOffset()); }

    private List<RadminBackend.Network> filteredNetworks() {
        ensureFilterCache();
        return cachedFilteredNetworks;
    }

    private List<RadminBackend.Peer> filteredPeers() {
        ensureFilterCache();
        return cachedFilteredPeers;
    }

    private List<LanRoomSnapshot> filteredBlockedRooms() {
        ensureFilterCache();
        return cachedFilteredBlockedRooms;
    }

    private List<RadminBackend.Peer> filteredBlockedPeers() {
        ensureFilterCache();
        return cachedFilteredBlockedPeers;
    }

    private void invalidateFilterCache() {
        cachedFilterQuery = null;
        cachedFilterSnapshot = null;
        cachedFilterNetworkGuid = null;
        cachedFilterBlockedSource = List.of();
        cachedContentHeight = -1;
        cachedPeerNetworkNames.clear();
        blockedRoomsGeneration = Long.MIN_VALUE;
        filterGeneration++;
    }

    private List<LanRoomSnapshot> observedBlockedRooms() {
        if (blockedRoomsGeneration != filterGeneration) {
            observedBlockedRooms = RadminLanClient.ROOMS == null
                    ? List.of() : RadminLanClient.ROOMS.blockedRooms();
            observedBlockedRoomsHash = observedBlockedRooms.hashCode();
            blockedRoomsGeneration = filterGeneration;
        }
        return observedBlockedRooms;
    }

    private void ensureFilterCache() {
        String currentQuery = query();
        String networkGuid = selectedNetwork == null ? "" : selectedNetwork.guid();
        List<LanRoomSnapshot> blockedRooms = tab == Tab.BLOCKED ? observedBlockedRooms() : List.of();
        if (cachedFilterSnapshot == snapshot
                && currentQuery.equals(cachedFilterQuery)
                && networkGuid.equals(cachedFilterNetworkGuid)
                && blockedRooms == cachedFilterBlockedSource) {
            return;
        }

        cachedFilterSnapshot = snapshot;
        cachedFilterQuery = currentQuery;
        cachedFilterNetworkGuid = networkGuid;
        cachedFilterBlockedSource = blockedRooms;
        cachedPeerNetworkNames.clear();
        cachedFilteredNetworks = snapshot.networks().stream()
                .filter(network -> currentQuery.isBlank()
                        || contains(network.name(), currentQuery)
                        || contains(network.guid(), currentQuery))
                .toList();
        cachedFilteredPeers = snapshot.networks().stream()
                .filter(network -> selectedNetwork == null || network.guid().equals(selectedNetwork.guid()))
                .flatMap(network -> network.peers().stream())
                .filter(peer -> !RadminLanClient.CONFIG.isPeerBlocked(peer.nodeId(), peer.name(), peer.publicIp(), peer.virtualIp()))
                .filter(peer -> currentQuery.isBlank() || matchesPeer(peer, currentQuery))
                .toList();
        cachedFilteredBlockedRooms = blockedRooms.stream()
                .filter(room -> currentQuery.isBlank()
                        || contains(room.displayMotd(), currentQuery)
                        || contains(room.key().toString(), currentQuery))
                .toList();
        cachedFilteredBlockedPeers = snapshot.networks().stream()
                .flatMap(network -> network.peers().stream())
                .filter(peer -> RadminLanClient.CONFIG.isPeerBlocked(peer.nodeId(), peer.name(), peer.publicIp(), peer.virtualIp()))
                .filter(peer -> currentQuery.isBlank() || matchesPeer(peer, currentQuery))
                .toList();
        cachedContentHeight = -1;
    }

    private boolean matchesPeer(RadminBackend.Peer peer, String query) {
        return contains(peer.name(), query) || contains(networkNamesFor(peer), query) || contains(peer.networkName(), query) || contains(peer.state(), query)
                || contains(peer.connectionType(), query) || contains(peer.publicIp(), query) || contains(peer.virtualIp(), query)
                || contains(Long.toUnsignedString(peer.nodeId()), query) || contains(peer.version(), query);
    }

    private String networkNamesFor(RadminBackend.Peer peer) {
        if (peer == null) return "";
        long key = peerKey(peer);
        if (cachedPeerNetworkNames.containsKey(key)) return cachedPeerNetworkNames.get(key);
        String value = snapshot.networks().stream()
                .filter(network -> network.peers().stream().anyMatch(candidate -> samePeer(candidate, peer)))
                .map(RadminBackend.Network::name)
                .distinct()
                .reduce((left, right) -> left + ", " + right)
                .orElseGet(peer::networkName);
        cachedPeerNetworkNames.put(key, value);
        return value;
    }

    private static boolean samePeer(RadminBackend.Peer left, RadminBackend.Peer right) {
        if (left.nodeId() >= 0 && right.nodeId() >= 0) return left.nodeId() == right.nodeId();
        return !left.virtualIp().isBlank() && left.virtualIp().equalsIgnoreCase(right.virtualIp());
    }

    private String query() { return searchBox == null ? "" : searchBox.getValue().trim().toLowerCase(Locale.ROOT); }
    private static boolean contains(String value, String query) { return value != null && value.toLowerCase(Locale.ROOT).contains(query); }
    private String currentSignature() {
        if (tab == Tab.BLOCKED) observedBlockedRooms();
        return tab + "|" + snapshotNetworksHash + "|" + (tab == Tab.BLOCKED ? observedBlockedRoomsHash : 0) + "|" + scrollOffset;
    }
    private int contentHeight() {
        ensureFilterCache();
        if (cachedContentHeight >= 0) return cachedContentHeight;
        cachedContentHeight = switch (tab) {
            case NETWORKS -> cachedFilteredNetworks.size() * ROW_HEIGHT;
            case PEERS -> cachedFilteredPeers.size() * ROW_HEIGHT;
            case BLOCKED -> (cachedFilteredBlockedRooms.isEmpty() ? 0 : cachedFilteredBlockedRooms.size() * ROW_HEIGHT + 22)
                    + (cachedFilteredBlockedPeers.isEmpty() ? 0 : cachedFilteredBlockedPeers.size() * ROW_HEIGHT + 22);
        };
        return cachedContentHeight;
    }

    private int listTop() {
        int base = tab == Tab.NETWORKS ? 140 : 92;
        if (tab == Tab.NETWORKS && networkActionsExpanded) base += 24;
        if (multiSelectMode && hasAnySelection()) base += 24;
        return base;
    }
    private int listContentTop() { return listTop() + 7; }
    private boolean hasAnySelection() {
        return !selectedNetworkGuids.isEmpty() || !selectedPeerIds.isEmpty()
                || !selectedBlockedRoomKeys.isEmpty() || !selectedBlockedPeerIds.isEmpty();
    }
    private int listBottom() { return Math.max(listTop() + 1, height - FOOTER_HEIGHT - 8); }
    private int maxScrollOffset() { return Math.max(0, contentHeight() - (listBottom() - listTop())); }
    private boolean clampScrollOffset() { int next = Math.min(scrollOffset, maxScrollOffset()); boolean changed = next != scrollOffset; scrollOffset = next; return changed; }
    private int contextPanelX() { return Math.max(8, Math.min(width - 204, contextMenuX - 8)); }
    private int contextPanelY() { return Math.max(listTop() + 2, Math.min(listBottom() - contextPanelHeight() - 2, contextMenuY - 8)); }
    private int contextPanelHeight() { return contextNetwork == null ? 106 : Boolean.TRUE.equals(contextNetwork.admin()) ? 150 : 130; }
    private String truncate(String value, int maxWidth) {
        if (value == null) return "";
        return font.width(value) <= maxWidth ? value : font.plainSubstrByWidth(value, Math.max(1, maxWidth - font.width("…"))) + "…";
    }
    private static String formatLatency(long value, boolean online) {
        return !online || value < 0 ? "-1 ms" : value + " ms";
    }
    private static int latencyColor(long value, boolean online) {
        return !online || value < 0 ? 0xFFFF6666 : value < 100 ? 0xFF80FF80 : 0xFFFFCC66;
    }

    private boolean isNetworkDisabled(RadminBackend.Network network) {
        return network != null && (Boolean.FALSE.equals(network.enabled())
                || (RadminLanClient.CONFIG != null && RadminLanClient.CONFIG.isNetworkDisabled(network.guid())));
    }

    private void unblockRoom(LanRoomSnapshot room) {
        RadminLanClient.CONFIG.blocked.removeIf(rule -> rule.matches(room.key().ip(), room.key().port(), room.motd()));
        RadminLanClient.CONFIG.save();
        selectedBlockedRoomKeys.remove(blockedRoomKey(room));
        refreshDynamicWidgets();
    }

    private void batchUnblockBlocked() {
        List<LanRoomSnapshot> rooms = filteredBlockedRooms().stream()
                .filter(room -> selectedBlockedRoomKeys.contains(blockedRoomKey(room))).toList();
        for (LanRoomSnapshot room : rooms) {
            RadminLanClient.CONFIG.blocked.removeIf(rule -> rule.matches(room.key().ip(), room.key().port(), room.motd()));
        }
        for (RadminBackend.Peer peer : filteredBlockedPeers()) {
            if (selectedBlockedPeerIds.contains(peerKey(peer))) {
                RadminLanClient.CONFIG.unblockPeer(peer.nodeId(), peer.name(), peer.publicIp(), peer.virtualIp());
            }
        }
        RadminLanClient.CONFIG.save();
        selectedBlockedRoomKeys.clear();
        selectedBlockedPeerIds.clear();
        refreshDynamicWidgets();
    }

    private static String blockedRoomKey(LanRoomSnapshot room) {
        return room == null || room.key() == null ? "" : room.key().toString();
    }

    private long peerKey(RadminBackend.Peer peer) {
        if (peer.nodeId() >= 0) return peer.nodeId();
        return 0x8000_0000_0000_0000L ^ (peer.virtualIp() == null ? 0 : peer.virtualIp().hashCode());
    }

    private void invertSelection() {
        if (tab == Tab.NETWORKS) {
            Set<String> next = new HashSet<>();
            for (RadminBackend.Network network : filteredNetworks()) if (!selectedNetworkGuids.contains(network.guid())) next.add(network.guid());
            selectedNetworkGuids.clear();
            selectedNetworkGuids.addAll(next);
        } else if (tab == Tab.PEERS) {
            Set<Long> next = new HashSet<>();
            for (RadminBackend.Peer peer : filteredPeers()) if (!selectedPeerIds.contains(peerKey(peer))) next.add(peerKey(peer));
            selectedPeerIds.clear();
            selectedPeerIds.addAll(next);
        } else if (tab == Tab.BLOCKED) {
            Set<String> roomKeys = new HashSet<>();
            for (LanRoomSnapshot room : filteredBlockedRooms()) {
                String key = blockedRoomKey(room);
                if (!selectedBlockedRoomKeys.contains(key)) roomKeys.add(key);
            }
            selectedBlockedRoomKeys.clear();
            selectedBlockedRoomKeys.addAll(roomKeys);
            Set<Long> peerKeys = new HashSet<>();
            for (RadminBackend.Peer peer : filteredBlockedPeers()) {
                long key = peerKey(peer);
                if (!selectedBlockedPeerIds.contains(key)) peerKeys.add(key);
            }
            selectedBlockedPeerIds.clear();
            selectedBlockedPeerIds.addAll(peerKeys);
        }
        rebuildWidgets();
    }

    private void batchNetworkEnabled(boolean enabled) {
        List<RadminBackend.Network> targets = filteredNetworks().stream()
                .filter(network -> selectedNetworkGuids.contains(network.guid())).toList();
        if (targets.isEmpty()) return;
        lastAction = "screen.radminlan.request_pending";
        java.util.concurrent.CompletableFuture<?> chain = java.util.concurrent.CompletableFuture.completedFuture(null);
        for (RadminBackend.Network network : targets) chain = chain.thenCompose(ignored -> RadminLanClient.BACKEND.setNetworkEnabled(network.guid(), enabled));
        chain.whenComplete((ignored, error) -> runOnClient(() -> {
            if (error == null && RadminLanClient.CONFIG != null) {
                for (RadminBackend.Network network : targets) {
                    RadminLanClient.CONFIG.setNetworkDisabled(network.guid(), !enabled);
                }
            }
            lastAction = error == null ? "screen.radminlan.request_sent" : "screen.radminlan.request_failed";
            selectedNetworkGuids.clear();
            refresh();
        }));
    }

    private void batchLeaveNetworks() {
        List<RadminBackend.Network> targets = filteredNetworks().stream()
                .filter(network -> selectedNetworkGuids.contains(network.guid())).toList();
        if (targets.isEmpty()) return;
        lastAction = "screen.radminlan.request_pending";
        java.util.concurrent.CompletableFuture<?> chain = java.util.concurrent.CompletableFuture.completedFuture(null);
        for (RadminBackend.Network network : targets) chain = chain.thenCompose(ignored -> RadminLanClient.BACKEND.leaveNetworkByGuid(network.guid()));
        chain.whenComplete((ignored, error) -> runOnClient(() -> {
            lastAction = error == null ? "screen.radminlan.request_sent" : "screen.radminlan.request_failed";
            selectedNetworkGuids.clear();
            refresh();
        }));
    }

    private void batchBlockPeers() {
        for (RadminBackend.Peer peer : filteredPeers()) if (selectedPeerIds.contains(peerKey(peer)))
            RadminLanClient.CONFIG.blockPeer(peer.nodeId(), peer.name(), peer.publicIp(), peer.virtualIp());
        selectedPeerIds.clear();
        lastAction = "screen.radminlan.peer_blocked";
        refreshDynamicWidgets();
    }

    private void batchKickPeers() {
        List<RadminBackend.Peer> targets = filteredPeers().stream().filter(peer -> selectedPeerIds.contains(peerKey(peer))).toList();
        if (targets.isEmpty()) return;
        lastAction = "screen.radminlan.request_pending";
        java.util.concurrent.CompletableFuture<?> chain = java.util.concurrent.CompletableFuture.completedFuture(null);
        for (RadminBackend.Peer peer : targets) {
            RadminBackend.Network network = networkFor(peer);
            if (network != null && peer.nodeId() >= 0) chain = chain.thenCompose(ignored -> RadminLanClient.BACKEND.kickPeer(network.guid(), peer.nodeId(), network.name()));
        }
        chain.whenComplete((ignored, error) -> runOnClient(() -> {
            lastAction = error == null ? "screen.radminlan.request_sent" : "screen.radminlan.request_failed";
            selectedPeerIds.clear();
            refresh();
        }));
    }

    private final class BlockedRoomRowButton extends AbstractButton {
        private final LanRoomSnapshot room;
        private BlockedRoomRowButton(int x, int y, int width, int height, LanRoomSnapshot room) {
            super(x, y, width, height, Component.literal(room.displayMotd()));
            this.room = room;
        }
        @Override public void onPress(InputWithModifiers input) {
            String key = blockedRoomKey(room);
            if (input.hasShiftDown()) {
                List<LanRoomSnapshot> all = filteredBlockedRooms();
                int current = all.indexOf(room);
                int anchor = -1;
                for (int i = 0; i < all.size(); i++) {
                    if (selectedBlockedRoomKeys.contains(blockedRoomKey(all.get(i)))) { anchor = i; break; }
                }
                if (anchor >= 0) {
                    for (int i = Math.min(anchor, current); i <= Math.max(anchor, current); i++) {
                        selectedBlockedRoomKeys.add(blockedRoomKey(all.get(i)));
                    }
                } else if (!selectedBlockedRoomKeys.add(key)) {
                    selectedBlockedRoomKeys.remove(key);
                }
            } else if (!selectedBlockedRoomKeys.add(key)) {
                selectedBlockedRoomKeys.remove(key);
            }
            dynamicWidgetsDirty = true;
        }
        @Override protected void extractContents(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
            if (isHoveredOrFocused()) graphics.fill(getX(), getY(), getRight(), getBottom(), 0x225B8DB8);
        }
        @Override public void updateWidgetNarration(NarrationElementOutput output) { defaultButtonNarrationText(output); }
    }

    private final class BlockedPeerRowButton extends AbstractButton {
        private final RadminBackend.Peer peer;
        private BlockedPeerRowButton(int x, int y, int width, int height, RadminBackend.Peer peer) {
            super(x, y, width, height, Component.literal(peer.name()));
            this.peer = peer;
        }
        @Override public void onPress(InputWithModifiers input) {
            long key = peerKey(peer);
            if (input.hasShiftDown()) {
                List<RadminBackend.Peer> all = filteredBlockedPeers();
                int current = all.indexOf(peer);
                int anchor = -1;
                for (int i = 0; i < all.size(); i++) {
                    if (selectedBlockedPeerIds.contains(peerKey(all.get(i)))) { anchor = i; break; }
                }
                if (anchor >= 0) {
                    for (int i = Math.min(anchor, current); i <= Math.max(anchor, current); i++) {
                        selectedBlockedPeerIds.add(peerKey(all.get(i)));
                    }
                } else if (!selectedBlockedPeerIds.add(key)) {
                    selectedBlockedPeerIds.remove(key);
                }
            } else if (!selectedBlockedPeerIds.add(key)) {
                selectedBlockedPeerIds.remove(key);
            }
            dynamicWidgetsDirty = true;
        }
        @Override protected void extractContents(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
            if (isHoveredOrFocused()) graphics.fill(getX(), getY(), getRight(), getBottom(), 0x225B8DB8);
        }
        @Override public void updateWidgetNarration(NarrationElementOutput output) { defaultButtonNarrationText(output); }
    }

    private final class NetworkRowButton extends AbstractButton {
        private final RadminBackend.Network network;
        private NetworkRowButton(int x, int y, int width, int height, RadminBackend.Network network) {
            super(x, y, width, height, Component.literal(network.name()));
            this.network = network;
        }
        @Override public void onPress(InputWithModifiers input) {
            selectedNetwork = network;
            if (!multiSelectMode) return;
            if (input.hasShiftDown()) {
                List<RadminBackend.Network> all = filteredNetworks();
                int current = all.indexOf(network);
                int anchor = -1;
                for (int i = 0; i < all.size(); i++) if (selectedNetworkGuids.contains(all.get(i).guid())) { anchor = i; break; }
                if (anchor >= 0) for (int i = Math.min(anchor, current); i <= Math.max(anchor, current); i++) selectedNetworkGuids.add(all.get(i).guid());
                else if (!selectedNetworkGuids.add(network.guid())) selectedNetworkGuids.remove(network.guid());
            } else if (!selectedNetworkGuids.add(network.guid())) {
                selectedNetworkGuids.remove(network.guid());
            }
            dynamicWidgetsDirty = true;
        }
        @Override public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
            if (event.button() == 3) {
                contextNetwork = network;
                contextPeer = null;
                contextMenuX = (int) event.x();
                contextMenuY = (int) event.y();
                refreshDynamicWidgets();
                return true;
            }
            return event.button() == 1 && super.mouseClicked(event, doubleClick);
        }
        @Override protected void extractContents(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
            if (isHoveredOrFocused()) graphics.fill(getX(), getY(), getRight(), getBottom(), 0x225B8DB8);
        }
        @Override public void updateWidgetNarration(NarrationElementOutput output) { defaultButtonNarrationText(output); }
    }

    private final class PeerRowButton extends AbstractButton {
        private final RadminBackend.Peer peer;
        private PeerRowButton(int x, int y, int width, int height, RadminBackend.Peer peer) {
            super(x, y, width, height, Component.literal(peer.name()));
            this.peer = peer;
        }
        @Override public void onPress(InputWithModifiers input) {
            selectedPeer = peer;
            if (!multiSelectMode) return;
            long key = peerKey(peer);
            if (input.hasShiftDown()) {
                List<RadminBackend.Peer> all = filteredPeers();
                int current = all.indexOf(peer);
                int anchor = -1;
                for (int i = 0; i < all.size(); i++) if (selectedPeerIds.contains(peerKey(all.get(i)))) { anchor = i; break; }
                if (anchor >= 0) for (int i = Math.min(anchor, current); i <= Math.max(anchor, current); i++) selectedPeerIds.add(peerKey(all.get(i)));
                else if (!selectedPeerIds.add(key)) selectedPeerIds.remove(key);
            } else if (!selectedPeerIds.add(key)) {
                selectedPeerIds.remove(key);
            }
            dynamicWidgetsDirty = true;
        }
        @Override public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
            if (event.button() == 3) {
                contextPeer = peer;
                contextNetwork = null;
                contextMenuX = (int) event.x();
                contextMenuY = (int) event.y();
                refreshDynamicWidgets();
                return true;
            }
            return event.button() == 1 && super.mouseClicked(event, doubleClick);
        }
        @Override protected void extractContents(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
            if (isHoveredOrFocused()) graphics.fill(getX(), getY(), getRight(), getBottom(), 0x225B8DB8);
        }
        @Override public void updateWidgetNarration(NarrationElementOutput output) { defaultButtonNarrationText(output); }
    }

    private static final class RadminMenuTab extends GridLayoutTab {
        private final Tab page;
        private RadminMenuTab(Tab page, Component title) { super(title); this.page = page; }
    }

    private enum Tab { NETWORKS, PEERS, BLOCKED }
}
