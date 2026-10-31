package dev.radminlan.room;

public record RoomKey(String ip, int port) {
    public RoomKey {
        ip = ip == null ? "" : ip.trim();
    }
    @Override public String toString() { return ip + ":" + port; }
}

