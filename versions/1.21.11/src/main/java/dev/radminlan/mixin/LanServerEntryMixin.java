package dev.radminlan.mixin;

import dev.radminlan.RadminLanClient;
import dev.radminlan.input.MouseButtons;
import dev.radminlan.network.RadminBackend;
import dev.radminlan.room.LanRoomFaviconCache;
import dev.radminlan.room.LanRoomLayout;
import dev.radminlan.room.LanRoomSnapshot;
import dev.radminlan.room.RoomKey;
import dev.radminlan.ui.LanRoomContextScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.multiplayer.ServerSelectionList;
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.server.LanServer;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

@Mixin(ServerSelectionList.NetworkServerEntry.class)
public abstract class LanServerEntryMixin {
    private static final int RADMINLAN$PING_WIDTH = 10;
    private static final int RADMINLAN$PING_HEIGHT = 8;

    @Shadow @Final protected LanServer serverData;
    @Shadow @Final protected Minecraft minecraft;
    @Shadow @Final private JoinMultiplayerScreen screen;

    private String radminlan$cachedFaviconAddress;
    private String radminlan$cachedFaviconData;
    private Identifier radminlan$cachedFaviconTexture;
    private long radminlan$cachedFaviconGeneration = -1L;
    private String radminlan$cachedRoomAddress;
    private RoomKey radminlan$cachedRoomKey;
    private RadminBackend.RadminSnapshot radminlan$cachedPeerSnapshot;
    private String radminlan$cachedPeerIp;
    private RadminBackend.Peer radminlan$cachedPeer;

    @Redirect(method = "renderContent", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/GuiGraphics;drawString(Lnet/minecraft/client/gui/Font;Lnet/minecraft/network/chat/Component;III)V", ordinal = 0))
    private void radminlan$replaceLanHeader(GuiGraphics graphics, Font font, Component header,
                                             int x, int y, int color) {
        RoomKey key = radminlan$cachedRoomKey(serverData.getAddress());
        Component title = radminlan$matchingPeer(key) == null ? header
                : Component.translatable("screen.radminlan.radmin_lan_world");
        graphics.drawString(font, title, x, y, color);
    }

    @Redirect(method = "renderContent", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/GuiGraphics;drawString(Lnet/minecraft/client/gui/Font;Ljava/lang/String;III)V"))
    private void radminlan$replaceVanillaDetails(GuiGraphics graphics, Font font, String text,
                                                  int x, int y, int color) {
        // LAN MOTD and address are drawn in the expanded row below, after status data is available.
    }

    @Inject(method = "renderContent", at = @At("TAIL"))
    private void radminlan$drawRoomDetails(GuiGraphics graphics, int mouseX, int mouseY,
                                            boolean hovered, float delta, CallbackInfo ci) {
        if (RadminLanClient.CONFIG == null) return;

        ServerSelectionList.Entry entry = (ServerSelectionList.Entry)(Object)this;
        LanRoomSnapshot room = radminlan$roomSnapshot();
        int textX = entry.getContentX() + 35;
        int textRight = entry.getContentRight() - 6;
        int textWidth = Math.max(0, textRight - textX);
        int contentY = entry.getContentY();

        // Keep this row compact like the vanilla LAN entry. The operation
        // page owns peer and network details, so the list only shows the
        // cover, LAN title, LAN MOTD and address.
        radminlan$drawLanMotd(graphics, serverData.getMotd(), textX, contentY + 12, textWidth);
        String addressText = minecraft.options.hideServerAddress
                ? Component.translatable("selectServer.hiddenAddress").getString() : serverData.getAddress();
        radminlan$drawDetail(graphics, addressText, textX, contentY + 23, textWidth, 0xFFAAAAAA);

        Identifier favicon = room == null ? null
                : radminlan$cachedFavicon(serverData.getAddress(), room.favicon());
        if (favicon != null) {
            // Match the vanilla entry content origin exactly.  The extra pixel
            // made the cover visibly drift right at higher GUI scales.
            graphics.blit(RenderPipelines.GUI_TEXTURED, favicon, entry.getContentX(), contentY,
                    0.0F, 0.0F, 32, 32, 32, 32);
        }

        if (room == null) return;

        int pingX = entry.getContentRight() - 18;
        int countRight = pingX - 5;
        String countText = room.players() >= 0 && room.maxPlayers() >= 0
                ? room.players() + "/" + room.maxPlayers() : "";
        int countWidth = minecraft.font.width(countText);
        int countX = countRight - countWidth;
        if (!countText.isEmpty()) {
            graphics.drawString(minecraft.font, countText, countX, contentY + 1, 0xFFE0E0E0);
        }

        Identifier pingSprite = radminlan$pingSprite(room.latencyMs());
        if (pingSprite != null) {
            graphics.blitSprite(RenderPipelines.GUI_TEXTURED, pingSprite, pingX, contentY + 1,
                    RADMINLAN$PING_WIDTH, RADMINLAN$PING_HEIGHT);
        }

        boolean pingHovered = room.latencyMs() >= 0 && radminlan$contains(mouseX, mouseY, pingX, contentY + 1,
                RADMINLAN$PING_WIDTH, RADMINLAN$PING_HEIGHT);
        boolean playersHovered = !countText.isEmpty() && radminlan$contains(mouseX, mouseY,
                countX, contentY, countWidth, 10);
        if (pingHovered) {
            graphics.setTooltipForNextFrame(minecraft.font,
                    Component.translatable("multiplayer.status.ping", room.latencyMs()), mouseX, mouseY);
        } else if (playersHovered) {
            graphics.setComponentTooltipForNextFrame(minecraft.font, radminlan$playerTooltip(room), mouseX, mouseY);
        }
    }

    private Identifier radminlan$cachedFavicon(String address, String encoded) {
        long generation = LanRoomFaviconCache.generation();
        if (Objects.equals(radminlan$cachedFaviconAddress, address)
                && Objects.equals(radminlan$cachedFaviconData, encoded)
                && radminlan$cachedFaviconGeneration == generation) {
            return radminlan$cachedFaviconTexture;
        }
        radminlan$cachedFaviconAddress = address;
        radminlan$cachedFaviconData = encoded;
        radminlan$cachedFaviconGeneration = generation;
        radminlan$cachedFaviconTexture = LanRoomFaviconCache.texture(minecraft, address, encoded);
        return radminlan$cachedFaviconTexture;
    }

    @Inject(method = "mouseClicked", at = @At("HEAD"), cancellable = true)
    private void radminlan$blockOnControl(MouseButtonEvent event, boolean doubleClick,
                                           CallbackInfoReturnable<Boolean> cir) {
        if (RadminLanClient.CONFIG == null) return;

        ServerSelectionList.Entry entry = (ServerSelectionList.Entry)(Object)this;
        boolean onRow = event.x() >= entry.getContentX() && event.x() <= entry.getContentRight()
                && event.y() >= entry.getContentY() && event.y() <= entry.getContentY() + LanRoomLayout.ROW_HEIGHT;
        // Minecraft 1.21.11 uses button 0 for left click and button 1 for right click.
        // Keep ordinary left-clicks on the row for vanilla server joining;
        // only the three-dot control and an explicit right-click open our menu.
        if (event.button() == MouseButtons.left()) return;
        if (event.button() == MouseButtons.right() && !onRow) return;
        if (event.button() != MouseButtons.left() && event.button() != MouseButtons.right()) return;

        RoomKey key = radminlan$roomKey(serverData.getAddress());
        LanRoomSnapshot room = key == null || RadminLanClient.ROOMS == null ? null : RadminLanClient.ROOMS.get(key);
        if (room == null && key != null) {
            room = new LanRoomSnapshot(java.util.UUID.randomUUID(), key, serverData.getMotd(), "", "",
                    -1, -1, -1, System.currentTimeMillis(), LanRoomSnapshot.State.DISCOVERED);
        }
        RadminBackend.Peer peer = radminlan$matchingPeer(room);
        List<RadminBackend.Network> networks = peer == null ? List.of() :
                RadminLanClient.cachedSnapshot().networks().stream()
                        .filter(network -> network.peers().stream().anyMatch(candidate ->
                                (peer.nodeId() >= 0 && candidate.nodeId() == peer.nodeId())
                                        || (!peer.virtualIp().isBlank() && peer.virtualIp().equalsIgnoreCase(candidate.virtualIp()))))
                        .toList();
        minecraft.setScreen(new LanRoomContextScreen(screen, room, peer, networks));
        cir.setReturnValue(true);
    }

    private LanRoomSnapshot radminlan$roomSnapshot() {
        if (RadminLanClient.ROOMS == null) return null;
        RoomKey key = radminlan$cachedRoomKey(serverData.getAddress());
        return key == null ? null : RadminLanClient.ROOMS.get(key);
    }

    private RoomKey radminlan$cachedRoomKey(String address) {
        if (Objects.equals(radminlan$cachedRoomAddress, address)) return radminlan$cachedRoomKey;
        radminlan$cachedRoomAddress = address;
        radminlan$cachedRoomKey = radminlan$roomKey(address);
        return radminlan$cachedRoomKey;
    }

    private RadminBackend.Peer radminlan$matchingPeer(LanRoomSnapshot room) {
        return room == null ? null : radminlan$matchingPeer(room.key());
    }

    private RadminBackend.Peer radminlan$matchingPeer(RoomKey key) {
        RadminBackend.RadminSnapshot snapshot = RadminLanClient.cachedSnapshot();
        String ip = key == null ? "" : key.ip();
        if (snapshot == radminlan$cachedPeerSnapshot && Objects.equals(ip, radminlan$cachedPeerIp)) {
            return radminlan$cachedPeer;
        }
        radminlan$cachedPeerSnapshot = snapshot;
        radminlan$cachedPeerIp = ip;
        radminlan$cachedPeer = radminlan$findMatchingPeer(snapshot, key);
        return radminlan$cachedPeer;
    }

    private static RadminBackend.Peer radminlan$findMatchingPeer(RadminBackend.RadminSnapshot snapshot, RoomKey key) {
        if (key == null || key.ip().isBlank() || snapshot == null
                || snapshot.state() != RadminBackend.ServiceState.READY) return null;
        return snapshot.networks().stream()
                .flatMap(network -> network.peers().stream())
                .filter(peer -> key.ip().equalsIgnoreCase(peer.virtualIp())
                        || key.ip().equalsIgnoreCase(peer.virtualIpv6()))
                .findFirst()
                .orElse(null);
    }

    private String radminlan$networkNames(RadminBackend.Peer peer) {
        if (peer == null) return "";
        String names = RadminLanClient.cachedSnapshot().networks().stream()
                .filter(network -> network.peers().stream().anyMatch(candidate ->
                        (peer.nodeId() >= 0 && candidate.nodeId() == peer.nodeId())
                                || (!peer.virtualIp().isBlank() && peer.virtualIp().equalsIgnoreCase(candidate.virtualIp()))))
                .map(RadminBackend.Network::name)
                .filter(name -> name != null && !name.isBlank())
                .distinct()
                .reduce((a, b) -> a + ", " + b)
                .orElse("");
        return names.isBlank() ? (peer.networkName() == null ? "" : peer.networkName()) : names;
    }

    private void radminlan$drawLanMotd(GuiGraphics graphics, String text, int x, int y, int width) {
        if (text == null || text.isBlank()) return;
        // The LAN MOTD is the primary room identifier.  Do not truncate it to
        // make room for status columns: those columns are on the first line,
        // while the MOTD owns the complete second line.
        graphics.drawString(minecraft.font, text, x, y, 0xFFAAAAAA);
    }

    private void radminlan$drawDetail(GuiGraphics graphics, String text, int x, int y,
                                       int width, int color) {
        if (text == null || text.isBlank() || width <= 0) return;
        graphics.drawString(minecraft.font, minecraft.font.plainSubstrByWidth(text, width), x, y, color);
    }

    private List<Component> radminlan$playerTooltip(LanRoomSnapshot room) {
        List<Component> lines = new ArrayList<>();
        lines.add(Component.translatable("screen.radminlan.player_count", room.players(), room.maxPlayers()));
        for (String name : room.samplePlayers()) lines.add(Component.literal(name));
        if (!room.samplePlayers().isEmpty() && room.players() > room.samplePlayers().size()) {
            lines.add(Component.translatable("screen.radminlan.more_players", room.players() - room.samplePlayers().size()));
        }
        return lines;
    }

    private static Identifier radminlan$pingSprite(long latency) {
        if (latency < 0) return null;
        int level = latency < 150 ? 5 : latency < 300 ? 4 : latency < 600 ? 3 : latency < 1000 ? 2 : 1;
        return Identifier.withDefaultNamespace("server_list/ping_" + level);
    }

    private static boolean radminlan$contains(int mouseX, int mouseY, int x, int y, int width, int height) {
        return mouseX >= x && mouseX < x + width && mouseY >= y && mouseY < y + height;
    }

    private static int radminlan$titleWidth(ServerSelectionList.Entry entry) {
        int pingX = entry.getContentRight() - 18;
        int countRight = pingX - 5;
        return Math.max(0, countRight - (entry.getContentX() + 35) - 5);
    }

    private static RoomKey radminlan$roomKey(String address) {
        if (address == null || address.isBlank()) return null;
        int separator = address.lastIndexOf(':');
        if (separator <= 0 || separator == address.length() - 1) return null;
        String host = address.substring(0, separator).trim();
        if (host.startsWith("[") && host.endsWith("]")) host = host.substring(1, host.length() - 1);
        try {
            return new RoomKey(host, Integer.parseInt(address.substring(separator + 1)));
        } catch (NumberFormatException ignored) {
            return null;
        }
    }
}
