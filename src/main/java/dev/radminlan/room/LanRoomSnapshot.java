package dev.radminlan.room;

import java.util.UUID;

public record LanRoomSnapshot(
        UUID id,
        RoomKey key,
        String motd,
        String favicon,
        int players,
        int maxPlayers,
        long latencyMs,
        long lastSeen,
        State state
) {
    public enum State { DISCOVERED, QUERYING, READY, TIMED_OUT, FAILED }
    public LanRoomSnapshot withState(State next) { return new LanRoomSnapshot(id, key, motd, favicon, players, maxPlayers, latencyMs, lastSeen, next); }
}

