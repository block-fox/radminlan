package dev.radminlan.ui;

import dev.radminlan.RadminLanClient;
import dev.radminlan.network.RadminBackend;
import dev.radminlan.room.LanRoomSnapshot;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

public final class RadminScreen extends Screen {
    private final Screen parent;
    private Tab tab = Tab.NETWORKS;
    private volatile RadminBackend.RadminSnapshot snapshot = RadminBackend.RadminSnapshot.unavailable("Loading...");
    private final AtomicReference<List<LanRoomSnapshot>> rooms = new AtomicReference<>(List.of());
    private final AtomicReference<List<LanRoomSnapshot>> blockedRooms = new AtomicReference<>(List.of());

    public RadminScreen(Screen parent) {
        super(Text.translatable("screen.radminlan.title"));
        this.parent = parent;
    }

    @Override protected void init() {
        int center = width / 2;
        addDrawableChild(ButtonWidget.builder(Text.translatable("screen.radminlan.networks"), b -> select(Tab.NETWORKS)).dimensions(center - 220, 28, 105, 20).build());
        addDrawableChild(ButtonWidget.builder(Text.translatable("screen.radminlan.peers"), b -> select(Tab.PEERS)).dimensions(center - 110, 28, 105, 20).build());
        addDrawableChild(ButtonWidget.builder(Text.translatable("screen.radminlan.rooms"), b -> select(Tab.ROOMS)).dimensions(center, 28, 105, 20).build());
        addDrawableChild(ButtonWidget.builder(Text.translatable("screen.radminlan.blocked"), b -> select(Tab.BLOCKED)).dimensions(center + 110, 28, 105, 20).build());
        addDrawableChild(ButtonWidget.builder(Text.translatable("screen.radminlan.refresh"), b -> refresh()).dimensions(width - 110, height - 28, 100, 20).build());
        addDrawableChild(ButtonWidget.builder(Text.translatable("screen.radminlan.close"), b -> close()).dimensions(10, height - 28, 100, 20).build());
        if (tab == Tab.ROOMS || tab == Tab.BLOCKED) addRoomButtons(tab == Tab.BLOCKED ? blockedRooms.get() : rooms.get(), tab == Tab.BLOCKED);
        refresh();
    }

    private void addRoomButtons(List<LanRoomSnapshot> list, boolean blocked) {
        int y = 95;
        for (LanRoomSnapshot room : list) {
            final int rowY = y;
            addDrawableChild(ButtonWidget.builder(Text.translatable(blocked ? "screen.radminlan.unblock" : "screen.radminlan.block"), b -> {
                if (blocked) unblock(room); else block(room);
            }).dimensions(width - 120, rowY + 9, 100, 16).build());
            y += 36;
        }
    }

    private void select(Tab next) { tab = next; clearAndInit(); }

    private void refresh() {
        RadminLanClient.BACKEND.snapshot().thenAccept(result -> snapshot = result);
        RadminLanClient.ROOMS.discoverAsync();
        rooms.set(RadminLanClient.ROOMS.visibleRooms());
        blockedRooms.set(RadminLanClient.ROOMS.blockedRooms());
    }

    @Override public void close() { client.setScreen(parent); }

    @Override public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        renderBackground(context, mouseX, mouseY, delta);
        context.drawCenteredTextWithShadow(textRenderer, title, width / 2, 8, 0xFFFFFFFF);
        context.drawTextWithShadow(textRenderer, statusText(), 20, 60, snapshot.state() == RadminBackend.ServiceState.READY ? 0xFF80FF80 : 0xFFFFCC66);
        switch (tab) {
            case NETWORKS -> renderNetworks(context);
            case PEERS -> renderPeers(context);
            case ROOMS -> renderRooms(context, rooms.get(), false);
            case BLOCKED -> renderRooms(context, blockedRooms.get(), true);
        }
        super.render(context, mouseX, mouseY, delta);
    }

    private Text statusText() {
        if (snapshot.state() == RadminBackend.ServiceState.READY) return Text.translatable("screen.radminlan.service_ready");
        return Text.translatable("screen.radminlan.unavailable").append(" (" + snapshot.message() + ")");
    }

    private void renderNetworks(DrawContext context) {
        int y = 86;
        if (snapshot.networks().isEmpty()) {
            context.drawTextWithShadow(textRenderer, Text.translatable("screen.radminlan.no_rooms"), 20, y, 0xFFAAAAAA);
            return;
        }
        for (RadminBackend.Network network : snapshot.networks()) {
            context.drawTextWithShadow(textRenderer, network.name(), 28, y, 0xFFFFFFFF);
            context.drawTextWithShadow(textRenderer, network.owner().isBlank() ? "" : network.owner(), 220, y, 0xFFAAAAAA);
            y += 20;
        }
    }

    private void renderPeers(DrawContext context) {
        int y = 86;
        for (RadminBackend.Network network : snapshot.networks()) {
            for (RadminBackend.Peer peer : network.peers()) {
                context.drawTextWithShadow(textRenderer, peer.name(), 28, y, 0xFFFFFFFF);
                context.drawTextWithShadow(textRenderer, peer.virtualIp(), 180, y, 0xFFAAAAAA);
                context.drawTextWithShadow(textRenderer, peer.latencyMs() + " ms", 300, y, 0xFF80FF80);
                y += 18;
            }
        }
    }

    private void renderRooms(DrawContext context, List<LanRoomSnapshot> list, boolean blocked) {
        int y = 86;
        if (list.isEmpty()) {
            context.drawTextWithShadow(textRenderer, Text.translatable(blocked ? "screen.radminlan.no_blocked" : "screen.radminlan.no_rooms"), 20, y, 0xFFAAAAAA);
            return;
        }
        for (LanRoomSnapshot room : list) {
            context.fill(20, y - 4, width - 20, y + 26, 0x66101010);
            context.drawTextWithShadow(textRenderer, room.motd().isBlank() ? room.key().toString() : room.motd(), 30, y, 0xFFFFFFFF);
            context.drawTextWithShadow(textRenderer, room.key().toString(), 30, y + 12, 0xFFAAAAAA);
            context.drawTextWithShadow(textRenderer, room.players() + "/" + room.maxPlayers(), width - 160, y, 0xFFDDDDDD);
            context.drawTextWithShadow(textRenderer, room.latencyMs() + " ms", width - 95, y, room.latencyMs() < 100 ? 0xFF80FF80 : 0xFFFFCC66);
            y += 36;
        }
    }

    private void block(LanRoomSnapshot room) {
        RadminLanClient.CONFIG.block(room.key().ip(), room.key().port(), room.motd());
        clearAndInit();
    }
    private void unblock(LanRoomSnapshot room) {
        RadminLanClient.CONFIG.blocked.removeIf(rule -> rule.matches(room.key().ip(), room.key().port(), room.motd()));
        RadminLanClient.CONFIG.save();
        clearAndInit();
    }

    private enum Tab { NETWORKS, PEERS, ROOMS, BLOCKED }
}

