package dev.radminlan.ui;

import dev.radminlan.RadminLanClient;
import dev.radminlan.input.MouseButtons;
import dev.radminlan.network.RadminBackend;
import dev.radminlan.room.LanRoomSnapshot;
import dev.radminlan.ui.RadminListLayout.Cell;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.components.tabs.GridLayoutTab;
import net.minecraft.client.gui.components.tabs.TabNavigationBar;
import net.minecraft.client.gui.components.tabs.TabManager;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.InputWithModifiers;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.client.renderer.RenderPipelines;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Radmin management workspace built from vanilla Minecraft widgets. */
public final class RadminScreen extends Screen {
    /** Roomier rows keep the network name, type and member count readable. */
    private static final int ROW_HEIGHT = 36;
    private static final int FOOTER_HEIGHT = 44;
    private static final int IDENTITY_Y = 34;
    private static final int TOOLBAR_Y = 94;
    private static final Identifier SCROLLER = Identifier.withDefaultNamespace("widget/scroller");
    private static final Identifier SCROLLER_BACKGROUND = Identifier.withDefaultNamespace("widget/scroller_background");

    private final Screen parent;
    private Tab tab = Tab.NETWORKS;
    private volatile RadminBackend.RadminSnapshot snapshot = RadminBackend.RadminSnapshot.unavailable("Loading...");
    private String lastAction = "";
    private String listSignature = "";
    private int scrollOffset;
    private int refreshTicks;
    private int ticksUntilRefresh;
    private ActionMenu actionMenu = ActionMenu.NONE;
    private int menuScrollOffset;
    private final RadminListPreferences networkPreferences = new RadminListPreferences();
    private final RadminListPreferences peerPreferences = new RadminListPreferences();
    private final RadminSettingsPanel settingsPanel = new RadminSettingsPanel();
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
    private EditBox aliasBox;
    private Button aliasConfirmButton;
    private Button powerButton;
    private String aliasDraft = "";
    private String aliasBaseline = "";
    private boolean aliasSaveInFlight;
    private final List<GuiEventListener> dynamicWidgets = new ArrayList<>();
    private final List<AbstractWidget> listWidgets = new ArrayList<>();
    private final List<AbstractButton> contextButtons = new ArrayList<>();
    private List<MenuItem> currentMenuItems = List.of();

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
    private final Map<Long, List<String>> cachedPeerNetworkMemberships = new HashMap<>();
    private long filterGeneration;
    private long blockedRoomsGeneration = Long.MIN_VALUE;
    private List<LanRoomSnapshot> observedBlockedRooms = List.of();
    private int observedBlockedRoomsHash;
    private int snapshotNetworksHash;

    public RadminScreen(Screen parent) {
        super(Component.translatable("screen.radminlan.title"));
        this.parent = parent;
        networkPreferences.viewMode = readViewMode(RadminLanClient.CONFIG.networkViewMode);
        peerPreferences.viewMode = readViewMode(RadminLanClient.CONFIG.peerViewMode);
    }

    public static RadminScreen settings(Screen parent) {
        RadminScreen screen = new RadminScreen(parent);
        screen.tab = Tab.SETTINGS;
        return screen;
    }

    private static RadminListPreferences.ViewMode readViewMode(String value) {
        try { return RadminListPreferences.ViewMode.valueOf(value); }
        catch (IllegalArgumentException | NullPointerException ignored) { return RadminListPreferences.ViewMode.LIST; }
    }

    private RadminListPreferences preferences() { return tab == Tab.NETWORKS ? networkPreferences : peerPreferences; }

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
        listWidgets.clear();
        clampScrollOffset();

        RadminMenuTab networksTab = new RadminMenuTab(Tab.NETWORKS, Component.translatable("screen.radminlan.networks"));
        RadminMenuTab peersTab = new RadminMenuTab(Tab.PEERS, Component.translatable("screen.radminlan.peers"));
        RadminMenuTab blockedTab = new RadminMenuTab(Tab.BLOCKED, Component.translatable("screen.radminlan.blocked"));
        RadminMenuTab settingsTab = new RadminMenuTab(Tab.SETTINGS, Component.translatable("screen.radminlan.settings"));
        rebuildingTabs = true;
        TabManager manager = new TabManager(
                widget -> addRenderableWidget(widget),
                this::removeWidget,
                selected -> {
                    if (rebuildingTabs || !(selected instanceof RadminMenuTab menuTab) || tab == menuTab.page) return;
                    if (tab == Tab.SETTINGS) settingsPanel.save();
                    tab = menuTab.page;
                    selectedNetwork = null;
                    selectedPeer = null;
                    clearContext();
                    scrollOffset = 0;
                    rebuildWidgets();
                },
                ignored -> {}
        );
        TabNavigationBar tabBar = TabNavigationBar.builder(manager, width)
                .addTabs(networksTab, peersTab, blockedTab, settingsTab)
                .build();
        tabBar.arrangeElements();
        addRenderableWidget(tabBar);
        manager.setCurrentTab(switch (tab) {
            case NETWORKS -> networksTab;
            case PEERS -> peersTab;
            case BLOCKED -> blockedTab;
            case SETTINGS -> settingsTab;
        }, false);
        rebuildingTabs = false;

        addIdentityControls();
        if (tab != Tab.SETTINGS) addToolbar(previousSearch);
        addFooterControls();
        refreshDynamicWidgets();
    }

    private void addIdentityControls() {
        int powerWidth = Math.max(80, Math.max(font.width(Component.translatable("screen.radminlan.power_unknown")),
                Math.max(font.width(Component.translatable("screen.radminlan.power_on")),
                        font.width(Component.translatable("screen.radminlan.power_off")))) + 16);
        int confirmWidth = Math.max(44, font.width(Component.translatable("screen.radminlan.confirm_alias")) + 12);
        int powerX = width - 20 - powerWidth;
        int confirmX = powerX - 6 - confirmWidth;
        aliasBox = new EditBox(font, 20, IDENTITY_Y, confirmX - 26, 20,
                Component.translatable("screen.radminlan.alias"));
        aliasBox.setMaxLength(64);
        aliasBox.setHint(Component.literal(truncate(Component.translatable("screen.radminlan.alias").getString(), confirmX - 34)));
        aliasBox.setValue(aliasDraft);
        aliasBox.setResponder(value -> {
            aliasDraft = value;
            updateAliasControls();
        });
        addRenderableWidget(aliasBox);
        aliasConfirmButton = addRenderableWidget(Button.builder(Component.translatable("screen.radminlan.confirm_alias"),
                button -> submitAlias()).bounds(confirmX, IDENTITY_Y, confirmWidth, 20).build());
        powerButton = addRenderableWidget(Button.builder(Component.empty(), button -> togglePower())
                .bounds(powerX, IDENTITY_Y, powerWidth, 20).build());
        syncAliasFromSnapshot();
        updatePowerButton();
    }

    private void syncAliasFromSnapshot() {
        String current = snapshot.localName() == null ? "" : snapshot.localName().trim();
        if (!current.isBlank()) {
            boolean edited = !aliasDraft.trim().equals(aliasBaseline);
            aliasBaseline = current;
            if (!edited && !aliasSaveInFlight) {
                aliasDraft = current;
                if (aliasBox != null && !aliasBox.getValue().equals(current)) aliasBox.setValue(current);
            }
        }
        updateAliasControls();
    }

    private void updateAliasControls() {
        if (aliasConfirmButton == null) return;
        aliasConfirmButton.visible = !aliasDraft.trim().equals(aliasBaseline);
        aliasConfirmButton.active = !aliasDraft.isBlank() && !aliasSaveInFlight && snapshot.canWrite();
        if (aliasBox != null) aliasBox.setEditable(!aliasSaveInFlight);
    }

    private void submitAlias() {
        String alias = aliasDraft.trim();
        if (alias.isBlank() || alias.equals(aliasBaseline) || aliasSaveInFlight || !snapshot.canWrite()) return;
        aliasSaveInFlight = true;
        lastAction = "screen.radminlan.request_pending";
        updateAliasControls();
        RadminLanClient.BACKEND.setAlias(alias).whenComplete((ok, error) -> runOnClient(() -> {
            aliasSaveInFlight = false;
            if (error == null && Boolean.TRUE.equals(ok)) {
                aliasBaseline = alias;
                aliasDraft = alias;
                if (aliasBox != null) aliasBox.setValue(alias);
                lastAction = "screen.radminlan.request_sent";
            } else {
                lastAction = "screen.radminlan.request_failed";
            }
            updateAliasControls();
            refresh();
        }));
    }

    private void togglePower() {
        if (minecraft == null) return;
        if (!Boolean.TRUE.equals(snapshot.power())) {
            setPower(true);
            return;
        }
        clearContext();
        minecraft.setScreenAndShow(new ConfirmScreen(confirmed -> {
            minecraft.setScreenAndShow(this);
            if (confirmed) setPower(false);
        }, Component.translatable("screen.radminlan.power_off_title"),
                Component.translatable("screen.radminlan.power_off_warning")));
    }

    private void addToolbar(String previousSearch) {
        List<ActionMenu> menus = new ArrayList<>();
        menus.add(ActionMenu.SELECTION);
        if (tab != Tab.BLOCKED) {
            menus.add(ActionMenu.VIEW);
            menus.add(ActionMenu.SORT);
            menus.add(ActionMenu.FILTER);
        }
        if (tab == Tab.NETWORKS) menus.add(ActionMenu.NETWORK);
        int controlsWidth = 0;
        for (ActionMenu menu : menus) controlsWidth += toolbarWidth(menu) + 4;
        int searchWidth = Math.max(32, width - 40 - controlsWidth);
        searchBox = new EditBox(font, 20, TOOLBAR_Y, searchWidth, 20, Component.translatable("screen.radminlan.search"));
        searchBox.setMaxLength(128);
        searchBox.setHint(Component.literal(truncate(Component.translatable("screen.radminlan.search_hint").getString(), searchWidth - 8)));
        searchBox.setValue(previousSearch);
        searchBox.setResponder(value -> {
            scrollOffset = 0;
            clearContext();
            dynamicWidgetsDirty = true;
        });
        addRenderableWidget(searchBox);
        int x = 20 + searchWidth + 4;
        for (ActionMenu menu : menus) {
            Component label = Component.translatable(toolbarKey(menu));
            if ((menu == ActionMenu.SELECTION && multiSelectMode) || (menu == ActionMenu.FILTER && hasFilters())) label = label.copy().append("*");
            Button button = addRenderableWidget(Button.builder(label, widget -> toggleActionMenu(menu, widget))
                    .bounds(x, TOOLBAR_Y, toolbarWidth(menu), 20).build());
            Component tooltip = menu == ActionMenu.VIEW ? Component.translatable(preferences().viewMode.labelKey())
                    : menu == ActionMenu.FILTER && tab == Tab.PEERS && !peerPreferences.peerNetworkFilter.isBlank()
                            ? Component.translatable("screen.radminlan.filter.network").append(": ").append(peerPreferences.peerNetworkFilter)
                            : Component.translatable(toolbarKey(menu));
            button.setTooltip(Tooltip.create(tooltip));
            x += toolbarWidth(menu) + 4;
        }
    }

    private String toolbarKey(ActionMenu menu) {
        return "screen.radminlan." + switch (menu) {
            case SELECTION -> "select_menu";
            case NETWORK -> "network_menu";
            case VIEW -> "view";
            case SORT -> "sort";
            default -> "filter";
        };
    }

    private int toolbarWidth(ActionMenu menu) {
        // Reserve the active marker up front so changing a filter never shifts controls.
        return Math.max(32, font.width(Component.translatable(toolbarKey(menu))) + 12);
    }

    private boolean hasFilters() {
        RadminListPreferences p = preferences();
        return tab == Tab.NETWORKS ? p.excludeDisabled || p.excludeEnabled || p.excludePublic || p.excludePrivate
                : p.excludeOffline || p.excludeOnline || !p.peerNetworkFilter.isBlank() || !p.peerConnectionFilter.isBlank();
    }

    private void toggleMultiSelect() {
        multiSelectMode = !multiSelectMode;
        clearContext();
        if (!multiSelectMode) {
            selectedNetworkGuids.clear();
            selectedPeerIds.clear();
            selectedBlockedRoomKeys.clear();
            selectedBlockedPeerIds.clear();
        }
        rebuildWidgets();
    }

    private void addFooterControls() {
        if (tab == Tab.SETTINGS) {
            addRenderableWidget(Button.builder(Component.translatable("screen.radminlan.close"), button -> onClose())
                    .bounds(width / 2 - 100, height - 30, 200, 20).build());
            return;
        }
        int autoWidth = Math.max(96, Math.max(font.width(Component.translatable("screen.radminlan.auto_refresh_on")),
                font.width(Component.translatable("screen.radminlan.auto_refresh_off"))) + 16);
        int buttonWidth = Math.min(100, (width - 40 - autoWidth - 12) / 2);
        int x = (width - buttonWidth * 2 - autoWidth - 12) / 2;
        int y = height - 30;
        addRenderableWidget(Button.builder(Component.translatable("screen.radminlan.close"), button -> onClose())
                .bounds(x, y, buttonWidth, 20).build());
        x += buttonWidth + 6;
        addRenderableWidget(Button.builder(Component.translatable("screen.radminlan.refresh"), button -> refresh())
                .bounds(x, y, buttonWidth, 20).build());
        x += buttonWidth + 6;
        addRenderableWidget(Button.builder(autoRefreshLabel(), button -> {
            RadminLanClient.CONFIG.autoRefresh = !RadminLanClient.CONFIG.autoRefresh;
            RadminLanClient.CONFIG.save();
            ticksUntilRefresh = RadminLanClient.CONFIG.getRefreshIntervalTicks();
            button.setMessage(autoRefreshLabel());
            if (RadminLanClient.CONFIG.autoRefresh) refresh();
        }).bounds(x, y, autoWidth, 20).build());
    }

    private Component autoRefreshLabel() {
        return Component.translatable(RadminLanClient.CONFIG.autoRefresh
                ? "screen.radminlan.auto_refresh_on" : "screen.radminlan.auto_refresh_off");
    }

    private void toggleActionMenu(ActionMenu menu, AbstractButton button) {
        boolean closing = actionMenu == menu;
        clearContext();
        if (!closing) {
            actionMenu = menu;
            menuScrollOffset = 0;
            contextMenuX = button.getX() + 8;
            contextMenuY = button.getBottom() + 2;
        }
        refreshDynamicWidgets();
    }

    private void openPublicNetworks() {
        clearContext();
        if (minecraft != null) minecraft.setScreenAndShow(new PublicNetworksScreen(this));
    }

    private void addNetworkActionButtons() {
        int x = contextPanelX();
        int y = contextPanelY() + 23 - menuScrollOffset;
        addContextButton(Component.translatable("screen.radminlan.public_networks"), x, y, this::openPublicNetworks);
        addContextButton(Component.translatable("screen.radminlan.join_network"), x, y + 20, () -> {
            clearContext();
            minecraft.setScreenAndShow(new NetworkOperationScreen(this, NetworkOperationScreen.Mode.JOIN));
        });
        addContextButton(Component.translatable("screen.radminlan.create_network"), x, y + 40, () -> {
            clearContext();
            minecraft.setScreenAndShow(new NetworkOperationScreen(this, NetworkOperationScreen.Mode.CREATE));
        });
    }

    private void addBatchControls() {
        int x = contextPanelX();
        int y = contextPanelY() + 23 - menuScrollOffset;
        addContextButton(Component.translatable(multiSelectMode ? "screen.radminlan.single_select" : "screen.radminlan.multi_select"), x, y, this::toggleMultiSelect);
        if (!multiSelectMode) return;
        y += 20;
        addContextButton(Component.translatable("screen.radminlan.invert_selection"), x, y, () -> {
            clearContext();
            invertSelection();
        });
        y += 20;
        if (tab == Tab.NETWORKS) {
            addBatchButton("screen.radminlan.enable_network", x, y, () -> batchNetworkEnabled(true));
            addBatchButton("screen.radminlan.disable_network", x, y + 20, () -> batchNetworkEnabled(false));
            addBatchButton("screen.radminlan.leave", x, y + 40, this::batchLeaveNetworks);
        } else if (tab == Tab.PEERS) {
            addBatchButton("screen.radminlan.block_user", x, y, this::batchBlockPeers);
            addBatchButton("screen.radminlan.kick", x, y + 20, this::batchKickPeers);
        } else {
            addBatchButton("screen.radminlan.unblock", x, y, this::batchUnblockBlocked);
        }
    }

    private record MenuItem(Component label, Runnable action) {}

    private Component checked(boolean value, String key) {
        return Component.literal(value ? "[x] " : "[ ] ").append(Component.translatable(key));
    }

    private void changeListOption(Runnable action, boolean keepOpen) {
        action.run();
        scrollOffset = 0;
        invalidateFilterCache();
        if (!keepOpen) clearContext();
        rebuildWidgets();
    }

    private List<MenuItem> optionItems() {
        List<MenuItem> items = new ArrayList<>();
        RadminListPreferences p = preferences();
        switch (actionMenu) {
            case VIEW -> {
                for (RadminListPreferences.ViewMode mode : RadminListPreferences.ViewMode.values()) {
                    items.add(new MenuItem(checked(p.viewMode == mode, mode.labelKey()), () -> changeListOption(() -> {
                        p.viewMode = mode;
                        if (tab == Tab.NETWORKS) RadminLanClient.CONFIG.networkViewMode = mode.name();
                        else RadminLanClient.CONFIG.peerViewMode = mode.name();
                        RadminLanClient.CONFIG.save();
                    }, false)));
                }
            }
            case SORT -> {
                if (tab == Tab.NETWORKS) for (RadminListPreferences.NetworkSort sort : RadminListPreferences.NetworkSort.values()) {
                    items.add(new MenuItem(checked(p.networkSort == sort, sort.labelKey()), () -> changeListOption(() -> p.networkSort = sort, false)));
                }
                else for (RadminListPreferences.PeerSort sort : RadminListPreferences.PeerSort.values()) {
                    items.add(new MenuItem(checked(p.peerSort == sort, sort.labelKey()), () -> changeListOption(() -> p.peerSort = sort, false)));
                }
                for (RadminListPreferences.SortDirection direction : RadminListPreferences.SortDirection.values()) {
                    boolean selected = (tab == Tab.NETWORKS ? p.networkDirection : p.peerDirection) == direction;
                    items.add(new MenuItem(checked(selected, direction.labelKey()), () -> changeListOption(() -> {
                        if (tab == Tab.NETWORKS) p.networkDirection = direction; else p.peerDirection = direction;
                    }, false)));
                }
            }
            case FILTER -> {
                if (tab == Tab.NETWORKS) {
                    items.add(new MenuItem(checked(p.excludeDisabled, "screen.radminlan.filter.hide_disabled"), () -> changeListOption(() -> p.excludeDisabled = !p.excludeDisabled, true)));
                    items.add(new MenuItem(checked(p.excludeEnabled, "screen.radminlan.filter.hide_enabled"), () -> changeListOption(() -> p.excludeEnabled = !p.excludeEnabled, true)));
                    items.add(new MenuItem(checked(p.excludePublic, "screen.radminlan.filter.hide_public"), () -> changeListOption(() -> p.excludePublic = !p.excludePublic, true)));
                    items.add(new MenuItem(checked(p.excludePrivate, "screen.radminlan.filter.hide_private"), () -> changeListOption(() -> p.excludePrivate = !p.excludePrivate, true)));
                } else {
                    items.add(new MenuItem(checked(p.excludeOffline, "screen.radminlan.filter.hide_offline"), () -> changeListOption(() -> p.excludeOffline = !p.excludeOffline, true)));
                    items.add(new MenuItem(checked(p.excludeOnline, "screen.radminlan.filter.hide_online"), () -> changeListOption(() -> p.excludeOnline = !p.excludeOnline, true)));
                    items.add(new MenuItem(Component.translatable("screen.radminlan.filter.network").append(" >"), () -> openFilterMenu(ActionMenu.FILTER_NETWORK)));
                    items.add(new MenuItem(Component.translatable("screen.radminlan.filter.connection").append(" >"), () -> openFilterMenu(ActionMenu.FILTER_CONNECTION)));
                }
                items.add(new MenuItem(Component.translatable("screen.radminlan.filter.reset"), () -> changeListOption(() -> {
                    p.excludeDisabled = p.excludeEnabled = p.excludePublic = p.excludePrivate = p.excludeOffline = p.excludeOnline = false;
                    p.peerNetworkFilter = p.peerConnectionFilter = "";
                }, true)));
            }
            case FILTER_NETWORK, FILTER_CONNECTION -> {
                boolean network = actionMenu == ActionMenu.FILTER_NETWORK;
                String selected = network ? p.peerNetworkFilter : p.peerConnectionFilter;
                items.add(new MenuItem(checked(selected.isBlank(), "screen.radminlan.filter.all"), () -> changeListOption(() -> {
                    if (network) p.peerNetworkFilter = ""; else p.peerConnectionFilter = "";
                }, false)));
                List<String> values = network ? snapshot.networks().stream().map(RadminBackend.Network::name).distinct().sorted(String.CASE_INSENSITIVE_ORDER).toList()
                        : snapshot.networks().stream().flatMap(n -> n.peers().stream()).map(RadminBackend.Peer::connectionType).filter(v -> !v.isBlank()).distinct().sorted().toList();
                for (String value : values) {
                    items.add(new MenuItem(Component.literal((value.equalsIgnoreCase(selected) ? "[x] " : "[ ] ") + value), () -> changeListOption(() -> {
                        if (network) p.peerNetworkFilter = value; else p.peerConnectionFilter = value;
                    }, false)));
                }
                items.add(new MenuItem(Component.translatable("gui.back"), () -> openFilterMenu(ActionMenu.FILTER)));
            }
            default -> {}
        }
        return items;
    }

    private void openFilterMenu(ActionMenu menu) {
        actionMenu = menu;
        menuScrollOffset = 0;
        refreshDynamicWidgets();
    }

    private void addOptionButtons() {
        int y = contextPanelY() + 23 - menuScrollOffset;
        for (MenuItem item : currentMenuItems) {
            addContextButton(item.label(), contextPanelX(), y, item.action());
            y += 20;
        }
    }

    private void addBatchButton(String key, int x, int y, Runnable action) {
        Button button = addContextButton(Component.translatable(key), x, y, () -> {
            clearContext();
            refreshDynamicWidgets();
            action.run();
        });
        button.active = hasAnySelection();
    }
    private <T extends AbstractWidget> T addListWidget(T widget) {
        dynamicWidgets.add(widget);
        listWidgets.add(widget);
        // Render the complete widget inside the list scissor, including the
        // vanilla focus outline that is drawn outside renderContents().
        return addWidget(widget);
    }

    private void refreshDynamicWidgets() {
        invalidateFilterCache();
        currentMenuItems = switch (actionMenu) {
            case VIEW, SORT, FILTER, FILTER_NETWORK, FILTER_CONNECTION -> optionItems();
            default -> List.of();
        };
        menuScrollOffset = Math.max(0, Math.min(menuScrollOffset, Math.max(0, contextNaturalHeight() - contextPanelHeight())));
        for (GuiEventListener widget : List.copyOf(dynamicWidgets)) removeWidget(widget);
        dynamicWidgets.clear();
        listWidgets.clear();
        contextButtons.clear();
        clampScrollOffset();
        switch (tab) {
            case NETWORKS -> addNetworkRows(filteredNetworks());
            case PEERS -> addPeerRows(filteredPeers());
            case BLOCKED -> addBlockedRows();
            case SETTINGS -> {
                for (AbstractWidget widget : settingsPanel.build(font, width, listTop() + 8, scrollOffset)) {
                    widget.visible = widget.getBottom() > listTop() && widget.getY() < listBottom();
                    addListWidget(widget);
                }
            }
        }
        if (actionMenu == ActionMenu.NETWORK) addNetworkActionButtons();
        else if (actionMenu == ActionMenu.SELECTION) addBatchControls();
        else if (actionMenu != ActionMenu.NONE) addOptionButtons();
        else if (contextNetwork != null) addNetworkContextButtons();
        else if (contextPeer != null) addPeerContextButtons();
    }

    private void addNetworkRows(List<RadminBackend.Network> networks) {
        for (int index = 0; index < networks.size(); index++) {
            Cell cell = cellAt(index);
            if (!rowVisible(cell.y(), cell.height())) continue;
            addListWidget(new NetworkRowButton(cell.x(), cell.y(), cell.width(), cell.height() - 2, networks.get(index)));
        }
    }

    private void addPeerRows(List<RadminBackend.Peer> peers) {
        for (int index = 0; index < peers.size(); index++) {
            Cell cell = cellAt(index);
            if (!rowVisible(cell.y(), cell.height())) continue;
            addListWidget(new PeerRowButton(cell.x(), cell.y(), cell.width(), cell.height() - 2, peers.get(index)));
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
            addListWidget(new BlockedPeerRowButton(20, rowY, width - 176, ROW_HEIGHT - 2, peer));
            addListWidget(Button.builder(Component.translatable("screen.radminlan.unblock"), button -> {
                RadminLanClient.CONFIG.unblockPeer(peer.nodeId(), peer.name(), peer.publicIp(), peer.virtualIp());
                selectedBlockedPeerIds.remove(peerKey(peer));
                refreshDynamicWidgets();
            }).bounds(width - 124, rowY + 2, 104, 20).build());
        }
        for (int i = 0; i < rooms.size(); i++) {
            int rowY = listContentTop() + 22 + i * ROW_HEIGHT - scrollOffset;
            if (!rowVisible(rowY, ROW_HEIGHT)) continue;
            LanRoomSnapshot room = rooms.get(i);
            addListWidget(new BlockedRoomRowButton(20, rowY, width - 176, ROW_HEIGHT - 2, room));
            addListWidget(Button.builder(Component.translatable("screen.radminlan.unblock"), button -> unblockRoom(room))
                    .bounds(width - 124, rowY + 2, 104, 20).build());
        }
    }

    private void addNetworkContextButtons() {
        int x = contextPanelX();
        int y = contextPanelY() + 23 - menuScrollOffset;
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
        int y = contextPanelY() + 23 - menuScrollOffset;
        addContextButton(Component.translatable("screen.radminlan.properties"), x, y, this::showPeerDetails);
        addContextButton(Component.translatable("screen.radminlan.block_user"), x, y + 20, this::blockPeer);
        addContextButton(Component.translatable("screen.radminlan.kick"), x, y + 40, this::kickPeer);
        addContextButton(Component.translatable("screen.radminlan.cancel"), x, y + 60, () -> { clearContext(); refreshDynamicWidgets(); });
    }

    private Button addContextButton(Component label, int x, int y, Runnable action) {
        Button button = Button.builder(Component.literal(truncate(label.getString(), 172)), widget -> action.run()).bounds(x + 6, y, 184, 20).build();
        if (font.width(label) > 172) button.setTooltip(Tooltip.create(label));
        button.visible = y + 20 > contextPanelY() + 23 && y < contextPanelY() + contextPanelHeight() - 4;
        dynamicWidgets.add(button);
        addWidget(button);
        contextButtons.add(button);
        return button;
    }

    private void refresh() {
        if (refreshInFlight) return;
        ticksUntilRefresh = RadminLanClient.CONFIG.getRefreshIntervalTicks();
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
                syncAliasFromSnapshot();
                if (tab != Tab.SETTINGS) refreshDynamicWidgets();
            });
        });
        if (tab != Tab.SETTINGS) refreshDynamicWidgets();
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
        refreshTicks++;
        if (RadminLanClient.CONFIG.autoRefresh) {
            if (--ticksUntilRefresh <= 0) refresh();
        } else {
            ticksUntilRefresh = RadminLanClient.CONFIG.getRefreshIntervalTicks();
        }
        if (tab == Tab.SETTINGS) return;
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
        selectedNetwork = null;
        peerPreferences.peerNetworkFilter = network.name();
        peerPreferences.excludeOffline = false;
        peerPreferences.excludeOnline = false;
        peerPreferences.peerConnectionFilter = "";
        if (searchBox != null) searchBox.setValue("");
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
        refreshDynamicWidgets();
        if (peer != null && minecraft != null) minecraft.setScreenAndShow(new PeerDetailsScreen(this, peer));
    }

    private void showPeerDetails(RadminBackend.Peer peer) {
        clearContext();
        refreshDynamicWidgets();
        if (peer != null && minecraft != null) minecraft.setScreenAndShow(new PeerDetailsScreen(this, peer));
    }

    private void showNetworkProperties(RadminBackend.Network network) {
        clearContext();
        refreshDynamicWidgets();
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
        actionMenu = ActionMenu.NONE;
        menuScrollOffset = 0;
    }

    @Override
    public void onClose() {
        settingsPanel.save();
        if (minecraft != null) minecraft.setScreenAndShow(parent);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float delta) {
        renderTransparentBackground(graphics);
        Component status = snapshot.state() == RadminBackend.ServiceState.READY
                ? Component.translatable("screen.radminlan.service_ready")
                : snapshot.state() == RadminBackend.ServiceState.DISABLED
                        ? Component.translatable("screen.radminlan.service_disabled")
                        : Component.translatable("screen.radminlan.service_unavailable").append(" (")
                                .append(snapshot.message().startsWith("screen.radminlan.")
                                        ? Component.translatable(snapshot.message()) : Component.literal(snapshot.message()))
                                .append(")");
        String localIp = snapshot.virtualIp() == null ? "" : snapshot.virtualIp().trim();
        int ipWidth = Math.min(font.width(localIp), Math.max(0, (width - 40) / 2));
        graphics.drawString(font, truncate(status.getString(), width - 40 - (localIp.isBlank() ? 0 : ipWidth + 12)), 20, 62,
                snapshot.state() == RadminBackend.ServiceState.READY ? 0xFF80FF80
                        : snapshot.state() == RadminBackend.ServiceState.DISABLED ? 0xFFAAAAAA : 0xFFFFCC66);
        if (!localIp.isBlank()) graphics.drawString(font, truncate(localIp, ipWidth), width - 20 - ipWidth, 62, 0xFFB9C2CA);
        if (!lastAction.isBlank()) graphics.drawString(font, truncate(Component.translatable(lastAction).getString(), width - 40), 20, 76, 0xFFCCCCCC);
        graphics.enableScissor(20, listRowsTop(), width - 20, listBottom());
        try {
            switch (tab) {
                case NETWORKS -> extractNetworks(graphics);
                case PEERS -> extractPeers(graphics);
                case BLOCKED -> extractBlocked(graphics);
                case SETTINGS -> settingsPanel.extractContent(graphics, mouseX, mouseY, delta);
            }
            boolean overList = !hasContext() && isInsideList(mouseX, mouseY);
            for (AbstractWidget widget : listWidgets) {
                widget.render(graphics, overList ? mouseX : -1, overList ? mouseY : -1, delta);
            }
        } finally {
            graphics.disableScissor();
        }
        extractColumnHeaders(graphics);
        extractScrollbar(graphics);
        graphics.blit(net.minecraft.client.renderer.RenderPipelines.GUI_TEXTURED, Screen.HEADER_SEPARATOR,
                0, listTop() - 2, 0.0F, 0.0F, width, 2, 32, 2);
        graphics.blit(net.minecraft.client.renderer.RenderPipelines.GUI_TEXTURED, Screen.FOOTER_SEPARATOR,
                0, listBottom(), 0.0F, 0.0F, width, 2, 32, 2);
        super.render(graphics, mouseX, mouseY, delta);
        if (tab == Tab.PEERS && !hasContext()) extractSignalTooltip(graphics, mouseX, mouseY);
        if (hasContext()) {
            graphics.nextStratum();
            String menuTitle = actionMenu != ActionMenu.NONE ? Component.translatable(switch (actionMenu) {
                case NETWORK -> "screen.radminlan.network_actions";
                case SELECTION -> "screen.radminlan.select_actions";
                case VIEW -> "screen.radminlan.view";
                case SORT -> "screen.radminlan.sort";
                case FILTER_NETWORK -> "screen.radminlan.filter.network_title";
                case FILTER_CONNECTION -> "screen.radminlan.filter.connection_title";
                default -> "screen.radminlan.filter";
            }).getString() : contextNetwork != null ? contextNetwork.name() : contextPeer.name();
            extractContextPanel(graphics, menuTitle);
            graphics.enableScissor(contextPanelX() + 4, contextPanelY() + 23, contextPanelX() + 192, contextPanelY() + contextPanelHeight() - 4);
            try {
                for (AbstractButton button : contextButtons) button.render(graphics, mouseX, mouseY, delta);
            } finally { graphics.disableScissor(); }
            int overflow = contextNaturalHeight() - contextPanelHeight();
            if (overflow > 0) {
                int bodyHeight = contextPanelHeight() - 27;
                int thumb = Math.max(10, bodyHeight * bodyHeight / (bodyHeight + overflow));
                int thumbY = contextPanelY() + 23 + (bodyHeight - thumb) * menuScrollOffset / overflow;
                graphics.fill(contextPanelX() + 192, contextPanelY() + 23, contextPanelX() + 194, contextPanelY() + contextPanelHeight() - 4, 0xFF404040);
                graphics.fill(contextPanelX() + 192, thumbY, contextPanelX() + 194, thumbY + thumb, 0xFFA0A0A0);
            }
        }
    }

    private Cell cellAt(int index) {
        return RadminListLayout.cellAt(index, width, listContentTop(), scrollOffset, preferences().viewMode);
    }

    private int textStart(Cell cell) { return cell.x() + 8 + (multiSelectMode ? 18 : 0); }

    private boolean iconView() {
        return switch (preferences().viewMode) {
            case EXTRA_LARGE_ICONS, LARGE_ICONS, MEDIUM_ICONS -> true;
            default -> false;
        };
    }

    private int entryIconSize() {
        return switch (preferences().viewMode) {
            case EXTRA_LARGE_ICONS -> 48;
            case LARGE_ICONS -> 32;
            case MEDIUM_ICONS -> 20;
            case TILES -> 24;
            default -> 12;
        };
    }

    private String networkType(RadminBackend.Network network) {
        return Component.translatable(isNetworkDisabled(network) ? "screen.radminlan.network_disabled"
                : network.publicNetwork() ? "screen.radminlan.public_network" : "screen.radminlan.private_network").getString();
    }

    private String memberCount(RadminBackend.Network network) {
        return network.peers().size() + " " + Component.translatable("screen.radminlan.members").getString();
    }

    private void extractNetworks(GuiGraphics graphics) {
        List<RadminBackend.Network> networks = filteredNetworks();
        if (networks.isEmpty()) {
            graphics.drawString(font, Component.translatable("screen.radminlan.no_networks"), 30, listContentTop(), 0xFFAAAAAA);
            return;
        }
        for (int i = 0; i < networks.size(); i++) {
            RadminBackend.Network network = networks.get(i);
            Cell c = cellAt(i);
            if (!rowVisible(c.y(), c.height())) continue;
            boolean disabled = isNetworkDisabled(network);
            boolean selected = network == selectedNetwork || multiSelectMode && selectedNetworkGuids.contains(network.guid());
            graphics.fill(c.x(), c.y(), c.x() + c.width(), c.y() + c.height() - 3,
                    selected ? 0x665D7C99 : disabled ? 0x33333333 : 0x44101010);
            if (multiSelectMode) drawCheckbox(graphics, c.x() + 7, c.y() + 7, selectedNetworkGuids.contains(network.guid()));
            int color = disabled ? 0xFF858585 : 0xFFFFFFFF;
            int tx = textStart(c);
            int right = c.x() + c.width() - 8;
            if (iconView()) {
                int size = entryIconSize();
                drawNetworkIcon(graphics, c.x() + (c.width() - size) / 2, c.y() + 6, size, disabled);
                drawCenteredLines(graphics, network.name(), c.x() + 6, c.y() + size + 11, c.width() - 12, color);
                graphics.drawCenteredString(font, truncate(memberCount(network), c.width() - 12), c.x() + c.width() / 2, c.y() + c.height() - 15, 0xFFAAAAAA);
            } else if (preferences().viewMode == RadminListPreferences.ViewMode.DETAILS) {
                int countX = right - 32;
                int typeX = tx + Math.max(64, (countX - tx) * 2 / 3);
                graphics.drawString(font, truncate(network.name(), typeX - tx - 8), tx, c.y() + 5, color);
                graphics.drawString(font, truncate(network.guid(), typeX - tx - 8), tx, c.y() + 18, 0xFF777777);
                graphics.drawString(font, truncate(networkType(network), countX - typeX - 6), typeX, c.y() + 10, 0xFFAAAAAA);
                graphics.drawString(font, truncate(Integer.toString(network.peers().size()), 30), countX, c.y() + 10, 0xFFAAAAAA);
            } else {
                int size = entryIconSize();
                drawNetworkIcon(graphics, tx, c.y() + (c.height() - size) / 2 - 1, size, disabled);
                tx += size + 7;
                graphics.drawString(font, truncate(network.name(), right - tx), tx, c.y() + (c.height() == 24 ? 7 : 4), color);
                if (c.height() > 24) graphics.drawString(font, truncate(networkType(network) + " · " + memberCount(network), right - tx), tx, c.y() + 17, 0xFFAAAAAA);
                if (preferences().viewMode == RadminListPreferences.ViewMode.CONTENT) graphics.drawString(font, truncate(network.guid(), right - tx), tx, c.y() + 29, 0xFF777777);
            }
        }
    }

    private record PeerColumns(int name, int network, int connection, int signal) {}

    private PeerColumns peerColumns(Cell cell) {
        int tx = textStart(cell);
        int signalX = cell.x() + cell.width() - 23;
        int available = signalX - tx - 10;
        int networkX = tx + available * (cell.width() >= 420 ? 45 : 62) / 100;
        int connectionX = cell.width() >= 420 ? tx + available * 78 / 100 : signalX;
        return new PeerColumns(tx, networkX, connectionX, signalX);
    }

    private void extractPeers(GuiGraphics graphics) {
        List<RadminBackend.Peer> peers = filteredPeers();
        if (peers.isEmpty()) {
            graphics.drawString(font, Component.translatable("screen.radminlan.no_members"), 30, listContentTop(), 0xFFAAAAAA);
            return;
        }
        for (int i = 0; i < peers.size(); i++) {
            RadminBackend.Peer peer = peers.get(i);
            Cell c = cellAt(i);
            if (!rowVisible(c.y(), c.height())) continue;
            graphics.fill(c.x(), c.y(), c.x() + c.width(), c.y() + c.height() - 3,
                    peer == selectedPeer || multiSelectMode && selectedPeerIds.contains(peerKey(peer)) ? 0x665D7C99 : 0x44101010);
            if (multiSelectMode) drawCheckbox(graphics, c.x() + 7, c.y() + 7, selectedPeerIds.contains(peerKey(peer)));
            int tx = textStart(c);
            int signalX = c.x() + c.width() - 23;
            int color = peer.online() ? 0xFFFFFFFF : 0xFFAAAAAA;
            if (iconView()) {
                int size = entryIconSize();
                drawPeerIcon(graphics, c.x() + (c.width() - size) / 2, c.y() + 6, size, peer.online());
                drawCenteredLines(graphics, peer.name(), c.x() + 6, c.y() + size + 11, c.width() - 12, color);
                graphics.drawCenteredString(font, truncate(peer.virtualIp(), c.width() - 12), c.x() + c.width() / 2, c.y() + c.height() - 15, 0xFF9BAAB5);
                drawSignal(graphics, signalX, c.y() + 6, peer);
            } else if (preferences().viewMode == RadminListPreferences.ViewMode.DETAILS) {
                PeerColumns cols = peerColumns(c);
                graphics.drawString(font, truncate(peer.name(), cols.network() - tx - 8), tx, c.y() + 5, color);
                graphics.drawString(font, truncate(peer.virtualIp(), cols.network() - tx - 8), tx, c.y() + 18, 0xFF9BAAB5);
                graphics.drawString(font, truncate(networkNamesFor(peer), cols.connection() - cols.network() - 8), cols.network(), c.y() + 5, 0xFF999999);
                if (cols.connection() < cols.signal()) graphics.drawString(font, truncate(peer.connectionType(), cols.signal() - cols.connection() - 8), cols.connection(), c.y() + 5, 0xFFAAAAAA);
                drawSignal(graphics, signalX, c.y() + 8, peer);
            } else {
                int size = entryIconSize();
                drawPeerIcon(graphics, tx, c.y() + (c.height() - size) / 2 - 1, size, peer.online());
                tx += size + 7;
                int metadataX = tx + (signalX - tx) * 3 / 5;
                boolean metadata = preferences().viewMode == RadminListPreferences.ViewMode.CONTENT && c.width() >= 420;
                int nameRight = metadata ? metadataX - 8 : signalX - 7;
                graphics.drawString(font, truncate(peer.name(), nameRight - tx), tx, c.y() + (c.height() == 24 ? 7 : 4), color);
                if (c.height() > 24) graphics.drawString(font, truncate(peer.virtualIp(), nameRight - tx), tx, c.y() + 17, 0xFF9BAAB5);
                if (metadata) {
                    graphics.drawString(font, truncate(networkNamesFor(peer), signalX - metadataX - 7), metadataX, c.y() + 4, 0xFF999999);
                    graphics.drawString(font, truncate(peer.connectionType(), signalX - metadataX - 7), metadataX, c.y() + 17, 0xFFAAAAAA);
                } else if (preferences().viewMode == RadminListPreferences.ViewMode.CONTENT) {
                    graphics.drawString(font, truncate(networkNamesFor(peer) + " · " + peer.connectionType(), signalX - tx - 7), tx, c.y() + 29, 0xFF999999);
                } else if (preferences().viewMode == RadminListPreferences.ViewMode.TILES) {
                    graphics.drawString(font, truncate(peer.connectionType(), signalX - tx - 7), tx, c.y() + 30, 0xFF999999);
                }
                drawSignal(graphics, signalX, c.y() + 7, peer);
            }
        }
    }

    private void drawCenteredLines(GuiGraphics graphics, String value, int x, int y, int available, int color) {
        String first = font.plainSubstrByWidth(value, Math.max(1, available));
        graphics.drawCenteredString(font, first, x + available / 2, y, color);
        if (first.length() < value.length()) graphics.drawCenteredString(font, truncate(value.substring(first.length()), available), x + available / 2, y + 11, color);
    }

    private void drawNetworkIcon(GuiGraphics graphics, int x, int y, int size, boolean disabled) {
        int color = disabled ? 0xFF737C83 : 0xFF80B8DC;
        int unit = Math.max(1, size / 12);
        int displayHeight = size * 2 / 3;
        graphics.fill(x, y, x + size, y + displayHeight, color);
        graphics.fill(x + unit, y + unit, x + size - unit, y + displayHeight - unit, 0xFF20384B);
        graphics.fill(x + size / 2 - unit, y + displayHeight, x + size / 2 + unit, y + size - unit, color);
        graphics.fill(x + size / 4, y + size - unit, x + size * 3 / 4, y + size, color);
    }

    private void drawPeerIcon(GuiGraphics graphics, int x, int y, int size, boolean online) {
        int color = online ? 0xFF83BBD6 : 0xFF707B83;
        graphics.fill(x + size / 3, y, x + size * 2 / 3, y + size / 3, color);
        graphics.fill(x + size / 4, y + size * 5 / 12, x + size * 3 / 4, y + size * 5 / 6, color);
        graphics.fill(x + size / 6, y + size * 2 / 3, x + size * 5 / 6, y + size, color);
    }

    private void drawSignal(GuiGraphics graphics, int x, int y, RadminBackend.Peer peer) {
        int bars = !peer.online() || peer.latencyMs() < 0 ? 0 : peer.latencyMs() < 100 ? 4 : peer.latencyMs() < 200 ? 3 : peer.latencyMs() < 400 ? 2 : 1;
        for (int i = 0; i < 4; i++) {
            int barHeight = 2 + i * 2;
            graphics.fill(x + i * 3, y + 9 - barHeight, x + i * 3 + 2, y + 9, i < bars ? 0xFF72D18A : 0xFF58616A);
        }
        if (!peer.online()) {
            boolean offline = "offline".equalsIgnoreCase(peer.state());
            graphics.drawString(font, offline ? "x" : "?", x - 5, y, offline ? 0xFFAAAAAA : 0xFFFFCC66);
        } else if (bars == 0) graphics.drawString(font, "?", x - 5, y, 0xFFFFCC66);
    }

    private Component peerStatus(RadminBackend.Peer peer) {
        return Component.translatable(peer.online() ? "screen.radminlan.peer.online"
                : "offline".equalsIgnoreCase(peer.state()) ? "screen.radminlan.peer.offline" : "screen.radminlan.peer.unknown");
    }

    private void extractSignalTooltip(GuiGraphics graphics, int mouseX, int mouseY) {
        if (!isInsideList(mouseX, mouseY)) return;
        List<RadminBackend.Peer> peers = filteredPeers();
        for (int i = 0; i < peers.size(); i++) {
            Cell cell = cellAt(i);
            int signalX = cell.x() + cell.width() - 23;
            int signalY = cell.y() + (iconView() ? 6
                    : preferences().viewMode == RadminListPreferences.ViewMode.DETAILS ? 8 : 7);
            if (mouseX < signalX - 6 || mouseX >= signalX + 12 || mouseY < signalY - 1 || mouseY >= signalY + 11) continue;
            RadminBackend.Peer peer = peers.get(i);
            int maxWidth = Math.min(220, width - 48);
            List<Component> lines = new ArrayList<>();
            lines.add(Component.literal(truncate(peer.name(), maxWidth)));
            lines.add(Component.translatable("screen.radminlan.property.state").append(": ").append(peerStatus(peer)));
            if (peer.online()) {
                Component latency = Component.translatable(peer.latencyMs() >= 0
                        ? "screen.radminlan.peer.latency" : "screen.radminlan.peer.latency_unknown", peer.latencyMs());
                lines.add(peer.latencyMs() >= 0 ? Component.translatable("screen.radminlan.property.latency").append(": ").append(latency) : latency);
                if (!peer.connectionType().isBlank()) lines.add(Component.translatable("screen.radminlan.column.connection")
                        .append(": ").append(truncate(peer.connectionType(), maxWidth - 60)));
            }
            graphics.setComponentTooltipForNextFrame(font, lines, mouseX, mouseY);
            return;
        }
    }

    private void extractColumnHeaders(GuiGraphics graphics) {
        if (tab == Tab.BLOCKED || tab == Tab.SETTINGS || preferences().viewMode != RadminListPreferences.ViewMode.DETAILS) return;
        Cell cell = cellAt(0);
        int tx = textStart(cell);
        int y = listTop() + 5;
        graphics.fill(20, listTop(), width - 20, listContentTop() - 2, 0xF0202830);
        if (tab == Tab.NETWORKS) {
            int countX = cell.x() + cell.width() - 40;
            int typeX = tx + Math.max(64, (countX - tx) * 2 / 3);
            graphics.drawString(font, truncate(Component.translatable("screen.radminlan.column.name").getString(), typeX - tx - 8), tx, y, 0xFFB9C2CA);
            graphics.drawString(font, truncate(Component.translatable("screen.radminlan.column.network_type").getString(), countX - typeX - 6), typeX, y, 0xFFB9C2CA);
            graphics.drawString(font, truncate(Component.translatable("screen.radminlan.column.member_count").getString(), 34), countX, y, 0xFFB9C2CA);
        } else {
            PeerColumns cols = peerColumns(cell);
            graphics.drawString(font, truncate(Component.translatable("screen.radminlan.column.name").getString(), cols.network() - tx - 8), tx, y, 0xFFB9C2CA);
            graphics.drawString(font, truncate(Component.translatable("screen.radminlan.column.network").getString(), cols.connection() - cols.network() - 8), cols.network(), y, 0xFFB9C2CA);
            if (cols.connection() < cols.signal()) graphics.drawString(font, truncate(Component.translatable("screen.radminlan.column.connection").getString(), cols.signal() - cols.connection() - 8), cols.connection(), y, 0xFFB9C2CA);
        }
    }
    private void extractBlocked(GuiGraphics graphics) {
        List<LanRoomSnapshot> rooms = filteredBlockedRooms();
        List<RadminBackend.Peer> peers = filteredBlockedPeers();
        int y = listContentTop() - scrollOffset;
        if (rooms.isEmpty() && peers.isEmpty()) {
            graphics.drawString(font, Component.translatable("screen.radminlan.no_blocked"), 30, y, 0xFFAAAAAA);
            return;
        }
        if (!rooms.isEmpty()) {
            graphics.drawString(font, Component.translatable("screen.radminlan.blocked_rooms"), 30, y + 3, 0xFFFFCC66);
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
                    graphics.drawString(font, truncate(room.displayMotd().isBlank() ? room.key().toString() : room.displayMotd(), Math.max(80, ipX - textX - 10)), textX, y + 5, 0xFFDDDDDD);
                    graphics.drawString(font, truncate(room.key().toString(), Math.max(80, buttonStart - ipX - 8)), ipX, y + 5, 0xFF999999);
                }
                y += ROW_HEIGHT;
            }
        }
        if (!peers.isEmpty()) {
            graphics.drawString(font, Component.translatable("screen.radminlan.blocked_users"), 30, y + 3, 0xFFFFCC66);
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
                    graphics.drawString(font, truncate(peer.name(), Math.max(80, width - 176 - textX)), textX, y + 5, 0xFFDDDDDD);
                }
                y += ROW_HEIGHT;
            }
        }
    }

    private void extractContextPanel(GuiGraphics graphics, String title) {
        int x = contextPanelX();
        int y = contextPanelY();
        graphics.fill(x, y, x + 196, y + contextPanelHeight(), 0xF0101010);
        graphics.drawString(font, truncate(title, 174), x + 10, y + 7, 0xFFFFFFFF);
    }

    private void drawCheckbox(GuiGraphics graphics, int x, int y, boolean checked) {
        graphics.fill(x, y, x + 11, y + 11, checked ? 0xFF4D86B8 : 0xAA151A1F);
        graphics.renderOutline(x, y, 11, 11, checked ? 0xFFB8D8F5 : 0xFF87939D);
        if (checked) {
            graphics.fill(x + 3, y + 5, x + 5, y + 8, 0xFFFFFFFF);
            graphics.fill(x + 5, y + 3, x + 8, y + 7, 0xFFFFFFFF);
        }
    }

    private void extractScrollbar(GuiGraphics graphics) {
        int content = contentHeight();
        int viewport = Math.max(1, listBottom() - listTop());
        if (content <= viewport) return;
        int thumb = scrollbarThumbHeight();
        int track = viewport - thumb;
        int thumbY = listTop() + (maxScrollOffset() == 0 ? 0 : track * scrollOffset / maxScrollOffset());
        graphics.blitSprite(RenderPipelines.GUI_TEXTURED, SCROLLER_BACKGROUND, width - 13, listTop(), 6, viewport);
        graphics.blitSprite(RenderPipelines.GUI_TEXTURED, SCROLLER, width - 13, thumbY, 6, thumb);
    }

    @Override
    public Optional<GuiEventListener> getChildAt(double mouseX, double mouseY) {
        if (hasContext()) {
            if (!insideContextBody(mouseX, mouseY)) return Optional.empty();
            for (AbstractButton button : contextButtons) if (button.isMouseOver(mouseX, mouseY)) return Optional.of(button);
            return Optional.empty();
        }
        for (GuiEventListener widget : children()) {
            if ((!listWidgets.contains(widget) || isInsideList(mouseX, mouseY))
                    && widget.isMouseOver(mouseX, mouseY)) return Optional.of(widget);
        }
        return Optional.empty();
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (hasContext()) {
            if (insideContextPanel(event.x(), event.y())) {
                // Context menus are opened with the right mouse button, but
                // their actions use the normal left mouse button.
                if (event.button() == MouseButtons.left() && insideContextBody(event.x(), event.y())) {
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
        if (event.button() == MouseButtons.left() && isOverScrollbar(event.x(), event.y()) && contentHeight() > listBottom() - listTop()) {
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
        if (hasContext()) {
            if (insideContextPanel(mouseX, mouseY) && verticalAmount != 0) {
                int overflow = Math.max(0, contextNaturalHeight() - contextPanelHeight());
                menuScrollOffset = Math.max(0, Math.min(overflow, menuScrollOffset - (verticalAmount > 0 ? 20 : -20)));
                refreshDynamicWidgets();
            }
            return true;
        }
        if (mouseY < listTop() || mouseY >= listBottom()) return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
        int delta = Math.max(1, (int) Math.round(Math.abs(verticalAmount) * (tab == Tab.SETTINGS ? 28
                : tab == Tab.BLOCKED ? ROW_HEIGHT : preferences().viewMode.rowHeight())));
        int next = Math.max(0, Math.min(maxScrollOffset(), scrollOffset - (verticalAmount > 0 ? delta : -delta)));
        if (next == scrollOffset) return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
        scrollOffset = next;
        refreshDynamicWidgets();
        return true;
    }

    private boolean hasContext() { return actionMenu != ActionMenu.NONE || contextNetwork != null || contextPeer != null; }
    private boolean insideContextPanel(double x, double y) { return x >= contextPanelX() && x <= contextPanelX() + 196 && y >= contextPanelY() && y <= contextPanelY() + contextPanelHeight(); }
    private boolean insideContextBody(double x, double y) { return insideContextPanel(x, y) && y >= contextPanelY() + 23 && y < contextPanelY() + contextPanelHeight() - 4; }
    private int listRowsTop() { return tab != Tab.BLOCKED && tab != Tab.SETTINGS && preferences().viewMode == RadminListPreferences.ViewMode.DETAILS ? listContentTop() : listTop(); }
    private boolean isInsideList(double x, double y) { return x >= 20 && x < width - 20 && y >= listRowsTop() && y < listBottom(); }
    private boolean isOverScrollbar(double x, double y) { return x >= width - 18 && x <= width - 4 && y >= listTop() && y < listBottom(); }
    private boolean rowVisible(int y, int height) { return y + height > listTop() && y < listBottom(); }
    private int scrollbarThumbHeight() { int viewport = Math.max(1, listBottom() - listTop()); return Math.min(viewport, Math.max(18, viewport * viewport / Math.max(viewport, contentHeight()))); }
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
        cachedPeerNetworkMemberships.clear();
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
        cachedPeerNetworkMemberships.clear();
        cachedFilteredNetworks = snapshot.networks().stream()
                .filter(network -> networkPreferences.matchesNetwork(network, isNetworkDisabled(network)))
                .filter(network -> currentQuery.isBlank()
                        || contains(network.name(), currentQuery)
                        || contains(network.guid(), currentQuery))
                .sorted(networkPreferences.networkComparator())
                .toList();
        Map<Long, RadminBackend.Peer> uniquePeers = new LinkedHashMap<>();
        Map<Long, Set<String>> memberships = new LinkedHashMap<>();
        for (RadminBackend.Network network : snapshot.networks()) for (RadminBackend.Peer peer : network.peers()) {
            long key = peerKey(peer);
            uniquePeers.putIfAbsent(key, peer);
            memberships.computeIfAbsent(key, ignored -> new LinkedHashSet<>()).add(network.name());
        }
        memberships.forEach((key, names) -> {
            cachedPeerNetworkMemberships.put(key, List.copyOf(names));
            cachedPeerNetworkNames.put(key, String.join(", ", names));
        });
        cachedFilteredPeers = uniquePeers.values().stream()
                .filter(peer -> !RadminLanClient.CONFIG.isPeerBlocked(peer.nodeId(), peer.name(), peer.publicIp(), peer.virtualIp()))
                .filter(peer -> peerPreferences.matchesPeer(peer, networkMembershipsFor(peer)))
                .filter(peer -> currentQuery.isBlank() || matchesPeer(peer, currentQuery))
                .sorted(peerPreferences.peerComparator(this::networkNamesFor))
                .toList();
        cachedFilteredBlockedRooms = blockedRooms.stream()
                .filter(room -> currentQuery.isBlank()
                        || contains(room.displayMotd(), currentQuery)
                        || contains(room.key().toString(), currentQuery))
                .toList();
        cachedFilteredBlockedPeers = uniquePeers.values().stream()
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

    private List<String> networkMembershipsFor(RadminBackend.Peer peer) {
        return cachedPeerNetworkMemberships.getOrDefault(peerKey(peer), List.of(peer.networkName()));
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
        if (tab == Tab.SETTINGS) return settingsPanel.contentHeight() + 8;
        ensureFilterCache();
        if (cachedContentHeight >= 0) return cachedContentHeight;
        cachedContentHeight = switch (tab) {
            case NETWORKS -> RadminListLayout.rowsHeight(cachedFilteredNetworks.size(), width, preferences().viewMode);
            case PEERS -> RadminListLayout.rowsHeight(cachedFilteredPeers.size(), width, preferences().viewMode);
            case BLOCKED -> (cachedFilteredBlockedRooms.isEmpty() ? 0 : cachedFilteredBlockedRooms.size() * ROW_HEIGHT + 22)
                    + (cachedFilteredBlockedPeers.isEmpty() ? 0 : cachedFilteredBlockedPeers.size() * ROW_HEIGHT + 22);
            case SETTINGS -> settingsPanel.contentHeight() + 8;
        };
        if (cachedContentHeight > 0) cachedContentHeight += listContentTop() - listTop();
        return cachedContentHeight;
    }

    private int listTop() {
        return tab == Tab.SETTINGS ? TOOLBAR_Y : TOOLBAR_Y + 28;
    }
    private int listContentTop() { return listTop() + (tab == Tab.SETTINGS ? 8
            : tab != Tab.BLOCKED && preferences().viewMode == RadminListPreferences.ViewMode.DETAILS ? 23 : 7); }
    private boolean hasAnySelection() {
        return switch (tab) {
            case NETWORKS -> filteredNetworks().stream().anyMatch(network -> selectedNetworkGuids.contains(network.guid()));
            case PEERS -> filteredPeers().stream().anyMatch(peer -> selectedPeerIds.contains(peerKey(peer)));
            case BLOCKED -> filteredBlockedRooms().stream().anyMatch(room -> selectedBlockedRoomKeys.contains(blockedRoomKey(room)))
                    || filteredBlockedPeers().stream().anyMatch(peer -> selectedBlockedPeerIds.contains(peerKey(peer)));
            case SETTINGS -> false;
        };
    }
    private int listBottom() { return Math.max(listTop() + 1, height - FOOTER_HEIGHT); }
    private int maxScrollOffset() { return Math.max(0, contentHeight() - (listBottom() - listTop())); }
    private boolean clampScrollOffset() { int next = Math.min(scrollOffset, maxScrollOffset()); boolean changed = next != scrollOffset; scrollOffset = next; return changed; }
    private int contextPanelX() { return Math.max(8, Math.min(width - 204, contextMenuX - 8)); }
    private int contextPanelY() { return Math.max(8, Math.min(height - contextPanelHeight() - 8, contextMenuY)); }
    private int contextPanelHeight() { return Math.min(contextNaturalHeight(), Math.max(48, height - 16)); }
    private int contextNaturalHeight() {
        int count;
        if (actionMenu == ActionMenu.NETWORK) count = 3;
        else if (actionMenu == ActionMenu.SELECTION) count = !multiSelectMode ? 1 : switch (tab) {
            case NETWORKS -> 5;
            case PEERS -> 4;
            case BLOCKED -> 3;
            case SETTINGS -> 0;
        };
        else if (actionMenu != ActionMenu.NONE) count = currentMenuItems.size();
        else count = contextNetwork == null ? 4 : Boolean.TRUE.equals(contextNetwork.admin()) ? 6 : 5;
        return 27 + count * 20;
    }
    private String truncate(String value, int maxWidth) {
        if (value == null) return "";
        return font.width(value) <= maxWidth ? value : font.plainSubstrByWidth(value, Math.max(1, maxWidth - font.width("…"))) + "…";
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
        @Override protected void renderContents(GuiGraphics graphics, int mouseX, int mouseY, float delta) {
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
        @Override protected void renderContents(GuiGraphics graphics, int mouseX, int mouseY, float delta) {
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
            if (event.button() == MouseButtons.right()) {
                contextNetwork = network;
                contextPeer = null;
                contextMenuX = (int) event.x();
                contextMenuY = (int) event.y();
                refreshDynamicWidgets();
                return true;
            }
            return event.button() == MouseButtons.left() && super.mouseClicked(event, doubleClick);
        }
        @Override protected void renderContents(GuiGraphics graphics, int mouseX, int mouseY, float delta) {
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
            if (event.button() == MouseButtons.right()) {
                contextPeer = peer;
                contextNetwork = null;
                contextMenuX = (int) event.x();
                contextMenuY = (int) event.y();
                refreshDynamicWidgets();
                return true;
            }
            return event.button() == MouseButtons.left() && super.mouseClicked(event, doubleClick);
        }
        @Override protected void renderContents(GuiGraphics graphics, int mouseX, int mouseY, float delta) {
            if (isHoveredOrFocused()) graphics.fill(getX(), getY(), getRight(), getBottom(), 0x225B8DB8);
        }
        @Override public void updateWidgetNarration(NarrationElementOutput output) { defaultButtonNarrationText(output); }
    }

    private static final class RadminMenuTab extends GridLayoutTab {
        private final Tab page;
        private RadminMenuTab(Tab page, Component title) { super(title); this.page = page; }
    }

    private enum ActionMenu { NONE, NETWORK, SELECTION, VIEW, SORT, FILTER, FILTER_NETWORK, FILTER_CONNECTION }
    private enum Tab { NETWORKS, PEERS, BLOCKED, SETTINGS }
}
