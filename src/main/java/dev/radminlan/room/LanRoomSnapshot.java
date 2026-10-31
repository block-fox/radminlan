package dev.radminlan.room;

import net.minecraft.network.chat.Component;

import java.util.List;
import java.util.UUID;

public record LanRoomSnapshot(
        UUID id,
        RoomKey key,
        String motd,
        String statusMotd,
        String favicon,
        int players,
        int maxPlayers,
        long latencyMs,
        long lastSeen,
        State state,
        Component statusComponent,
        List<String> samplePlayers
) {
    public LanRoomSnapshot(UUID id, RoomKey key, String motd, String favicon, int players, int maxPlayers,
                           long latencyMs, long lastSeen, State state) {
        this(id, key, motd, "", favicon, players, maxPlayers, latencyMs, lastSeen, state, null, List.of());
    }

    public LanRoomSnapshot(UUID id, RoomKey key, String motd, String statusMotd, String favicon, int players,
                           int maxPlayers, long latencyMs, long lastSeen, State state) {
        this(id, key, motd, statusMotd, favicon, players, maxPlayers, latencyMs, lastSeen, state, null, List.of());
    }

    public LanRoomSnapshot {
        samplePlayers = samplePlayers == null ? List.of() : List.copyOf(samplePlayers);
    }

    public enum State { DISCOVERED, QUERYING, READY, TIMED_OUT, FAILED }
    public String displayMotd() { return statusMotd == null || statusMotd.isBlank() ? motd : statusMotd; }
    public LanRoomSnapshot withState(State next) {
        return new LanRoomSnapshot(id, key, motd, statusMotd, favicon, players, maxPlayers, latencyMs, lastSeen, next,
                statusComponent, samplePlayers);
    }
}

