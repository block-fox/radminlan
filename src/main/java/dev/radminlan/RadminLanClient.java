package dev.radminlan;

import dev.radminlan.config.RadminConfig;
import dev.radminlan.network.RadminBackend;
import dev.radminlan.network.RadminBackends;
import dev.radminlan.room.LanRoomRepository;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;

public final class RadminLanClient implements ClientModInitializer {
    public static final String MOD_ID = "radminlan";
    public static RadminConfig CONFIG;
    public static RadminBackend BACKEND;
    public static LanRoomRepository ROOMS;

    @Override
    public void onInitializeClient() {
        CONFIG = RadminConfig.load();
        BACKEND = RadminBackends.create(CONFIG);
        ROOMS = new LanRoomRepository(CONFIG);
        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> {
            ROOMS.close();
            BACKEND.close();
            CONFIG.save();
        });
    }
}

