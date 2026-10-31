package dev.radminlan.mixin;

import dev.radminlan.RadminLanClient;
import dev.radminlan.config.RadminConfig;
import dev.radminlan.ui.RadminScreen;
import dev.radminlan.ui.RadminEntryButton;
import dev.radminlan.ui.LanRoomContextScreen;
import dev.radminlan.network.RadminBackend;
import dev.radminlan.room.LanRoomSnapshot;
import dev.radminlan.room.LanRoomFaviconCache;
import dev.radminlan.room.RoomKey;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.server.LanServer;
import net.minecraft.client.server.LanServerDetection;
import net.minecraft.client.gui.screens.multiplayer.ServerSelectionList;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

@Mixin(JoinMultiplayerScreen.class)
public abstract class MultiplayerScreenMixin extends Screen {
    @org.spongepowered.asm.mixin.Shadow
    protected ServerSelectionList serverSelectionList;

    @org.spongepowered.asm.mixin.Shadow
    private LanServerDetection.LanServerList lanServerList;

    @org.spongepowered.asm.mixin.Shadow
    private Button editButton;

    @org.spongepowered.asm.mixin.Shadow
    private Button deleteButton;

    private String radminlan$lastLanSignature = "";
    private int radminlan$refreshCooldown;
    private Button radminlan$roomDetailsButton;
    private Button radminlan$roomBlockButton;

    protected MultiplayerScreenMixin(Component title) { super(title); }

    @Inject(method = "init", at = @At("TAIL"))
    private void radminlan$startDiscovery(CallbackInfo ci) {
        RadminLanClient.MULTIPLAYER_SCREEN = (Screen)(Object)this;
        if (RadminLanClient.ROOMS != null) {
            radminlan$refreshLanEntries();
        }
    }

    @Inject(method = "tick", at = @At("TAIL"))
    private void radminlan$mergeRooms(CallbackInfo ci) {
        // LAN discovery is updated asynchronously by vanilla.  Rebuilding the
        // merged list every client tick only copied the same entries repeatedly;
        // a short cadence is enough to keep discovery responsive while leaving
        // the render thread free for the list itself.
        if (radminlan$refreshCooldown > 0) {
            radminlan$refreshCooldown--;
            return;
        }
        radminlan$refreshCooldown = 4;
        radminlan$refreshLanEntries();
    }

    @Inject(method = "removed", at = @At("HEAD"))
    private void radminlan$releaseLanFavicons(CallbackInfo ci) {
        LanRoomFaviconCache.clear();
    }

    private void radminlan$refreshLanEntries() {
        if (serverSelectionList == null || lanServerList == null || RadminLanClient.ROOMS == null) return;

        List<LanServer> detected;
        synchronized (lanServerList) {
            detected = new ArrayList<>(((LanServerListAccessor) lanServerList).radminlan$getServers());
        }
        RadminLanClient.ROOMS.syncVanillaServers(detected);
        List<LanServer> vanilla = new ArrayList<>(detected);
        // A malformed multicast advertisement must never reach ServerAddress.parseString.
        // Keep the vanilla list usable even when another VPN client broadcasts bad data.
        vanilla.removeIf(server -> !radminlan$isValidAddress(server.getAddress())
                || radminlan$isBlocked(server.getAddress(), server.getMotd()));
        Set<String> addresses = new HashSet<>();
        for (LanServer server : vanilla) addresses.add(radminlan$normalizeAddress(server.getAddress()));

        for (LanRoomSnapshot room : RadminLanClient.ROOMS.visibleRooms()) {
            String address = radminlan$formatAddress(room.key().ip(), room.key().port());
            if (!radminlan$isValidAddress(address)) continue;
            if (addresses.add(radminlan$normalizeAddress(address))
                    && !RadminLanClient.CONFIG.isBlocked(room.key().ip(), room.key().port(), room.motd())) {
                String motd = room.motd() == null || room.motd().isBlank() ? "Radmin VPN" : room.motd();
                String safeMotd = motd.replace("[MOTD]", "").replace("[/MOTD]", "").replace("[AD]", "").replace("[/AD]", "");
                vanilla.add(new LanServer(safeMotd, address));
            }
        }
        StringBuilder signature = new StringBuilder();
        for (LanServer server : vanilla) {
            signature.append(server.getAddress()).append('\u0000').append(server.getMotd()).append('\u0001');
        }
        String nextSignature = signature.toString();
        if (nextSignature.equals(radminlan$lastLanSignature)) return;
        radminlan$lastLanSignature = nextSignature;
        serverSelectionList.updateNetworkServers(vanilla);
    }

    private static boolean radminlan$isValidAddress(String address) {
        if (address == null || address.isBlank()) return false;
        String value = address.trim();
        int separator = value.lastIndexOf(':');
        if (separator <= 0 || separator == value.length() - 1) return false;
        String host = value.substring(0, separator);
        String portText = value.substring(separator + 1);
        if (host.indexOf('[') >= 0 || host.indexOf(']') >= 0) {
            if (!(host.startsWith("[") && host.endsWith("]"))) return false;
            host = host.substring(1, host.length() - 1);
        }
        try {
            int port = Integer.parseInt(portText);
            return port > 0 && port <= 65535 && !host.isBlank() && !host.contains("§");
        } catch (NumberFormatException ignored) {
            return false;
        }
    }

    private boolean radminlan$isBlocked(String address, String motd) {
        if (RadminLanClient.CONFIG == null) return false;
        int separator = address.lastIndexOf(':');
        if (separator <= 0 || separator == address.length() - 1) return false;
        String host = address.substring(0, separator).trim();
        if (host.startsWith("[") && host.endsWith("]")) host = host.substring(1, host.length() - 1);
        try {
            return RadminLanClient.CONFIG.isBlocked(host, Integer.parseInt(address.substring(separator + 1)), motd);
        } catch (NumberFormatException ignored) {
            return false;
        }
    }

    private static String radminlan$formatAddress(String host, int port) {
        String normalizedHost = host == null ? "" : host.trim();
        return (normalizedHost.indexOf(':') >= 0 && !normalizedHost.startsWith("[")
                ? "[" + normalizedHost + "]" : normalizedHost) + ":" + port;
    }

    private static String radminlan$normalizeAddress(String address) {
        String value = address == null ? "" : address.trim();
        int separator = value.lastIndexOf(':');
        if (separator <= 0 || separator == value.length() - 1) return value.toLowerCase(Locale.ROOT);
        String host = value.substring(0, separator).trim();
        if (host.startsWith("[") && host.endsWith("]")) host = host.substring(1, host.length() - 1);
        return host.toLowerCase(Locale.ROOT) + ":" + value.substring(separator + 1).trim();
    }

    @Inject(method = "init", at = @At("TAIL"))
    private void radminlan$addButton(CallbackInfo ci) {
        RadminConfig config = RadminLanClient.CONFIG;
        if (config == null) return;
        int x;
        int y;
        int titleWidth = font.width(title);
        switch (config.buttonAnchor) {
            case TITLE_AFTER -> { x = width / 2 + titleWidth / 2 + 8; y = 8; }
            case BOTTOM_LEFT -> { x = 12; y = height - 54; }
            case BOTTOM_RIGHT -> { x = width - 140; y = height - 54; }
            case MULTIPLAYER_TITLE, TITLE_BEFORE -> { x = width / 2 - titleWidth / 2 - 136; y = 8; }
            default -> { x = width / 2 - titleWidth / 2 - 136; y = 8; }
        }
        addRenderableWidget(new RadminEntryButton(x, y, 128, 20,
                Component.translatable("screen.radminlan.title"),
                () -> minecraft.setScreenAndShow(new RadminScreen(this))));
    }

    @Inject(method = "init", at = @At("TAIL"))
    private void radminlan$addRoomFooterActions(CallbackInfo ci) {
        if (editButton == null || deleteButton == null) return;
        radminlan$roomDetailsButton = Button.builder(Component.translatable("screen.radminlan.room_details"),
                        ignored -> radminlan$openRoomDetails())
                .bounds(editButton.getX(), editButton.getY(), editButton.getWidth(), editButton.getHeight()).build();
        radminlan$roomBlockButton = Button.builder(Component.translatable("screen.radminlan.block_room"),
                        ignored -> radminlan$blockSelectedRoom())
                .bounds(deleteButton.getX(), deleteButton.getY(), deleteButton.getWidth(), deleteButton.getHeight()).build();
        radminlan$roomDetailsButton.visible = false;
        radminlan$roomBlockButton.visible = false;
        addRenderableWidget(radminlan$roomDetailsButton);
        addRenderableWidget(radminlan$roomBlockButton);
        radminlan$updateRoomFooterActions();
    }

    @Inject(method = "onSelectedChange", at = @At("TAIL"))
    private void radminlan$updateRoomFooterActions(CallbackInfo ci) {
        radminlan$updateRoomFooterActions();
    }

    @Inject(method = "repositionElements", at = @At("TAIL"))
    private void radminlan$repositionRoomFooterActions(CallbackInfo ci) {
        // Header/footer layout recalculates button bounds during init and
        // window resize. Mirror the vanilla edit/delete slots immediately so
        // the custom room actions never flash over the top row.
        radminlan$positionRoomFooterActions();
    }

    private void radminlan$updateRoomFooterActions() {
        if (radminlan$roomDetailsButton == null || serverSelectionList == null) return;
        radminlan$positionRoomFooterActions();
        boolean selectedLan = serverSelectionList.getSelected() instanceof ServerSelectionList.NetworkServerEntry;
        radminlan$roomDetailsButton.visible = selectedLan;
        radminlan$roomBlockButton.visible = selectedLan;
        radminlan$roomDetailsButton.active = selectedLan;
        radminlan$roomBlockButton.active = selectedLan;
        if (editButton != null) editButton.visible = !selectedLan;
        if (deleteButton != null) deleteButton.visible = !selectedLan;
    }

    private void radminlan$positionRoomFooterActions() {
        if (radminlan$roomDetailsButton == null || radminlan$roomBlockButton == null) return;
        // The vanilla footer is a two-row layout: join/direct/add on the
        // first row and edit/delete/refresh/back on the second. The buttons
        // are added after vanilla's layout pass, so mirror the live edit and
        // delete geometry once it is available instead of guessing a row.
        if (editButton == null || deleteButton == null
                || editButton.getWidth() <= 0 || editButton.getHeight() <= 0
                || deleteButton.getWidth() <= 0 || deleteButton.getHeight() <= 0) return;
        radminlan$roomDetailsButton.setX(editButton.getX());
        radminlan$roomDetailsButton.setY(editButton.getY());
        radminlan$roomDetailsButton.setWidth(editButton.getWidth());
        radminlan$roomDetailsButton.setHeight(editButton.getHeight());
        radminlan$roomBlockButton.setX(deleteButton.getX());
        radminlan$roomBlockButton.setY(deleteButton.getY());
        radminlan$roomBlockButton.setWidth(deleteButton.getWidth());
        radminlan$roomBlockButton.setHeight(deleteButton.getHeight());
    }

    private void radminlan$openRoomDetails() {
        LanRoomSnapshot room = radminlan$selectedRoom();
        if (room == null) return;
        RadminBackend.Peer peer = radminlan$matchingPeer(room);
        List<RadminBackend.Network> networks = peer == null ? List.of() : RadminLanClient.cachedSnapshot().networks().stream()
                .filter(network -> network.peers().stream().anyMatch(candidate ->
                        (peer.nodeId() >= 0 && candidate.nodeId() == peer.nodeId())
                                || (!peer.virtualIp().isBlank() && peer.virtualIp().equalsIgnoreCase(candidate.virtualIp()))))
                .toList();
        minecraft.setScreenAndShow(new LanRoomContextScreen((Screen) (Object) this, room, peer, networks));
    }

    private void radminlan$blockSelectedRoom() {
        LanServer server = radminlan$selectedLanServer();
        RoomKey key = server == null ? null : radminlan$roomKey(server.getAddress());
        if (server == null || key == null || RadminLanClient.CONFIG == null) return;
        RadminLanClient.CONFIG.block(key.ip(), key.port(), server.getMotd());
        if (RadminLanClient.ROOMS != null) RadminLanClient.ROOMS.remove(key);
        radminlan$refreshLanEntries();
        if (serverSelectionList != null) serverSelectionList.setSelected(null);
    }

    private LanRoomSnapshot radminlan$selectedRoom() {
        LanServer server = radminlan$selectedLanServer();
        RoomKey key = server == null ? null : radminlan$roomKey(server.getAddress());
        if (key == null) return null;
        LanRoomSnapshot room = RadminLanClient.ROOMS == null ? null : RadminLanClient.ROOMS.get(key);
        return room != null ? room : new LanRoomSnapshot(UUID.randomUUID(), key, server.getMotd(), "", "",
                -1, -1, -1, System.currentTimeMillis(), LanRoomSnapshot.State.DISCOVERED);
    }

    private LanServer radminlan$selectedLanServer() {
        if (serverSelectionList == null) return null;
        var selected = serverSelectionList.getSelected();
        if (!(selected instanceof ServerSelectionList.NetworkServerEntry entry)
                || !(entry instanceof LanServerEntryDataAccessor accessor)) return null;
        return accessor.radminlan$getServerData();
    }

    private RadminBackend.Peer radminlan$matchingPeer(LanRoomSnapshot room) {
        if (room == null) return null;
        return RadminLanClient.cachedSnapshot().networks().stream().flatMap(network -> network.peers().stream())
                .filter(peer -> room.key().ip().equalsIgnoreCase(peer.virtualIp())).findFirst().orElse(null);
    }

    private static RoomKey radminlan$roomKey(String address) {
        if (address == null || address.isBlank()) return null;
        int separator = address.lastIndexOf(':');
        if (separator <= 0 || separator == address.length() - 1) return null;
        String host = address.substring(0, separator).trim();
        if (host.startsWith("[") && host.endsWith("]")) host = host.substring(1, host.length() - 1);
        try { return new RoomKey(host, Integer.parseInt(address.substring(separator + 1))); }
        catch (NumberFormatException ignored) { return null; }
    }
}
