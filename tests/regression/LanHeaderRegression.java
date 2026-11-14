import dev.radminlan.RadminLanClient;
import dev.radminlan.mixin.LanServerEntryMixin;
import dev.radminlan.network.RadminBackend;
import dev.radminlan.room.RoomKey;

import java.lang.reflect.Method;
import java.util.List;

/** Exercises the actual room-to-peer matcher used by the LAN title redirect. */
public final class LanHeaderRegression {
    public static void check() throws Exception {
        Method matching = LanServerEntryMixin.class.getDeclaredMethod("radminlan$matchingPeer", RoomKey.class);
        matching.setAccessible(true);
        LanServerEntryMixin entry = new LanServerEntryMixin() {};
        RadminBackend.RadminSnapshot original = RadminLanClient.cachedSnapshot();
        RadminBackend.Peer host = peer("Host", "26.10.20.30", "FD00::1234");
        RadminBackend.Peer other = peer("Other", "26.10.20.31", "FD00::1235");
        try {
            RadminLanClient.publishSnapshot(ready(host, other));
            require(matching.invoke(entry, new RoomKey("26.10.20.30", 25565)) == host,
                    "known Radmin IPv4 identifies a Radmin LAN room");
            require(matching.invoke(entry, new RoomKey("26.10.20.30", 25566)) == host,
                    "another port on the same host keeps the peer association");
            require(matching.invoke(entry, new RoomKey("26.10.20.31", 25565)) == other,
                    "changing the room host invalidates the entry cache");
            require(matching.invoke(entry, new RoomKey("fd00::1234", 25565)) == host,
                    "known virtual IPv6 identifies a Radmin LAN room");
            require(matching.invoke(entry, new RoomKey("192.168.1.20", 25565)) == null,
                    "ordinary LAN addresses keep the vanilla room title");
            require(matching.invoke(entry, new RoomKey("26.99.99.99", 25565)) == null,
                    "an unmatched 26-range address does not imply Radmin membership");
            require(matching.invoke(entry, new RoomKey("", 25565)) == null,
                    "empty room addresses never match missing peer fields");
            require(matching.invoke(entry, new Object[] {null}) == null,
                    "missing room keys keep the vanilla title");

            RadminBackend.Peer replacement = peer("Updated host", "26.10.20.30", "FD00::1234");
            RadminLanClient.publishSnapshot(ready(replacement));
            require(matching.invoke(entry, new RoomKey("26.10.20.30", 25565)) == replacement,
                    "a refreshed snapshot invalidates a cached peer at the same IP");
            RadminLanClient.publishSnapshot(RadminBackend.RadminSnapshot.unavailable("Service stopped"));
            require(matching.invoke(entry, new RoomKey("26.10.20.30", 25565)) == null,
                    "an unavailable snapshot clears a previous Radmin association");
        } finally {
            RadminLanClient.publishSnapshot(original);
        }
        System.out.println("LAN_HEADER_REGRESSION_OK cases=10");
    }

    private static RadminBackend.RadminSnapshot ready(RadminBackend.Peer... peers) {
        return new RadminBackend.RadminSnapshot(RadminBackend.ServiceState.READY, "Local", "26.10.20.1",
                List.of(new RadminBackend.Network("Test", "guid", "", List.of(peers))), "");
    }

    private static RadminBackend.Peer peer(String name, String ipv4, String ipv6) {
        return new RadminBackend.Peer(name, ipv4, ipv6, "online", -1, true,
                -1, "", "Direct", "Test", null, "");
    }

    private static void require(boolean condition, String description) {
        if (!condition) throw new AssertionError(description);
    }
}
