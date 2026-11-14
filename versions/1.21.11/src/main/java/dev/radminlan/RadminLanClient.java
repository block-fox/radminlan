package dev.radminlan;

import dev.radminlan.config.RadminConfig;
import dev.radminlan.network.RadminBackend;
import dev.radminlan.network.RadminBackends;
import dev.radminlan.room.LanRoomRepository;
import dev.radminlan.ui.RadminScreen;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.gui.screens.Screen;

import java.util.concurrent.atomic.AtomicBoolean;

public final class RadminLanClient implements ClientModInitializer {
    public static final String MOD_ID = "radminlan";
    public static RadminConfig CONFIG;
    public static RadminBackend BACKEND;
    public static LanRoomRepository ROOMS;
    public static volatile Screen MULTIPLAYER_SCREEN;
    private static volatile RadminBackend.RadminSnapshot cachedSnapshot =
            RadminBackend.RadminSnapshot.unavailable("Loading...");
    private static final AtomicBoolean SNAPSHOT_REQUEST_PENDING = new AtomicBoolean();

    public static RadminBackend.RadminSnapshot cachedSnapshot() {
        return cachedSnapshot;
    }

    public static void publishSnapshot(RadminBackend.RadminSnapshot snapshot) {
        if (snapshot != null) cachedSnapshot = snapshot;
    }

    @Override
    public void onInitializeClient() {
        CONFIG = RadminConfig.load();
        BACKEND = RadminBackends.create(CONFIG);
        ROOMS = new LanRoomRepository(CONFIG);
        refreshSnapshot();
        int[] ticksSinceRefresh = {0};
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            // The management screen owns its refresh cadence while open.
            // Reset the background timer while paused so leaving the screen
            // does not immediately repeat its most recent request.
            if (!CONFIG.autoRefresh || client.screen instanceof RadminScreen) {
                ticksSinceRefresh[0] = 0;
                return;
            }
            if (++ticksSinceRefresh[0] >= CONFIG.getRefreshIntervalTicks()) {
                ticksSinceRefresh[0] = 0;
                refreshSnapshot();
            }
        });
        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> {
            ROOMS.close();
            BACKEND.close();
            CONFIG.save();
        });
    }

    private static void refreshSnapshot() {
        if (BACKEND == null || !SNAPSHOT_REQUEST_PENDING.compareAndSet(false, true)) return;
        try {
            BACKEND.snapshot().whenComplete((snapshot, error) -> {
                if (error == null && snapshot != null) publishSnapshot(snapshot);
                SNAPSHOT_REQUEST_PENDING.set(false);
            });
        } catch (RuntimeException ignored) {
            SNAPSHOT_REQUEST_PENDING.set(false);
        }
    }
}

