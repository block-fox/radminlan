package dev.radminlan.network;

import java.util.List;
import java.util.concurrent.CompletableFuture;

public interface RadminBackend extends AutoCloseable {
    CompletableFuture<RadminSnapshot> snapshot();
    CompletableFuture<Boolean> createNetwork(String name, String password);
    CompletableFuture<Boolean> joinNetwork(String name, String password);
    CompletableFuture<Boolean> leaveNetwork(String name);
    @Override void close();

    record RadminSnapshot(ServiceState state, String localName, String virtualIp,
                          List<Network> networks, String message) {
        public static RadminSnapshot unavailable(String message) {
            return new RadminSnapshot(ServiceState.UNAVAILABLE, "", "", List.of(), message);
        }
    }
    record Network(String name, String owner, List<Peer> peers) {}
    record Peer(String name, String virtualIp, String state, long latencyMs) {}
    enum ServiceState { READY, UNAVAILABLE, DISABLED, ERROR }
}

