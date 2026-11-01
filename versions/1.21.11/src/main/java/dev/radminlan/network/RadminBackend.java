package dev.radminlan.network;

import java.util.List;
import java.util.concurrent.CompletableFuture;

public interface RadminBackend extends AutoCloseable {
    CompletableFuture<RadminSnapshot> snapshot();
    CompletableFuture<Boolean> createNetwork(String name, String password);
    CompletableFuture<Boolean> joinNetwork(String name, String password);
    CompletableFuture<Boolean> leaveNetwork(String name);
    default CompletableFuture<List<String>> publicNetworks(String query) {
        return CompletableFuture.failedFuture(new UnsupportedOperationException("Public network enumeration is unavailable"));
    }
    default CompletableFuture<Boolean> setPower(boolean enabled) { return CompletableFuture.completedFuture(false); }
    /** Leaves a network by its 16-byte control-pipe GUID. */
    default CompletableFuture<Boolean> leaveNetworkByGuid(String guid) { return CompletableFuture.completedFuture(false); }
    default boolean supportsWrite() { return false; }
    default CompletableFuture<Boolean> setNetworkEnabled(String guid, boolean enabled) { return CompletableFuture.completedFuture(false); }
    default CompletableFuture<Boolean> deleteNetwork(String guid) { return CompletableFuture.completedFuture(false); }
    default CompletableFuture<Boolean> kickPeer(String guid, long nodeId, String networkName) { return CompletableFuture.completedFuture(false); }
    default CompletableFuture<Boolean> setAlias(String alias) { return CompletableFuture.completedFuture(false); }
    @Override void close();

    record RadminSnapshot(ServiceState state, String localName, String virtualIp,
                          List<Network> networks, String message, boolean canWrite,
                          String transport, Boolean power) {
        public RadminSnapshot(ServiceState state, String localName, String virtualIp,
                              List<Network> networks, String message, boolean canWrite,
                              String transport) {
            this(state, localName, virtualIp, networks, message, canWrite, transport, null);
        }

        public RadminSnapshot(ServiceState state, String localName, String virtualIp,
                              List<Network> networks, String message) {
            this(state, localName, virtualIp, networks, message, false, "", null);
        }

        public static RadminSnapshot unavailable(String message) {
            return new RadminSnapshot(ServiceState.UNAVAILABLE, "", "", List.of(), message, false, "", null);
        }
    }
    record Network(String name, String guid, String owner, List<Peer> peers,
                   Integer networkType, Boolean enabled, Boolean admin,
                   Boolean allowPasswordless, Boolean blockNewMembers, boolean publicNetwork) {
        public Network(String name, String guid, String owner, List<Peer> peers) {
            this(name, guid, owner, peers, null, null, null, null, null, isPublicNetworkName(name));
        }

        public Network {
            peers = peers == null ? List.of() : List.copyOf(peers);
            publicNetwork = isPublicNetworkName(name);
        }

        private static boolean isPublicNetworkName(String name) {
            if (name == null) return false;
            String trimmed = name.trim();
            int open = trimmed.lastIndexOf('[');
            if (open <= 0 || !trimmed.endsWith("]") || open + 2 >= trimmed.length()) return false;
            String marker = trimmed.substring(open + 1, trimmed.length() - 1);
            return marker.codePoints().anyMatch(Character::isDigit)
                    && marker.codePoints().anyMatch(Character::isLetter);
        }
    }

    /** A member as reported by the service snapshot. Older callers can use the five-argument constructor. */
    record Peer(String name, String virtualIp, String virtualIpv6, String state, long latencyMs, boolean online,
                long nodeId, String publicIp, String connectionType, String networkName, Long role, String version) {
        public Peer(String name, String virtualIp, String state, long latencyMs, boolean online) {
            this(name, virtualIp, "", state, latencyMs, online, -1, "", "", "", null, "");
        }
        public Peer(String name, String virtualIp, String state, long latencyMs, boolean online,
                    long nodeId, String publicIp, String connectionType, String networkName, String ignoredRid) {
            this(name, virtualIp, "", state, latencyMs, online, nodeId, publicIp, connectionType, networkName, null, "");
        }
        public Peer(String name, String virtualIp, String virtualIpv6, String state, long latencyMs, boolean online,
                    long nodeId, String publicIp, String connectionType, String networkName, String ignoredRid) {
            this(name, virtualIp, virtualIpv6, state, latencyMs, online, nodeId, publicIp, connectionType, networkName, null, "");
        }
    }
    enum ServiceState { READY, UNAVAILABLE, DISABLED, ERROR }
}

