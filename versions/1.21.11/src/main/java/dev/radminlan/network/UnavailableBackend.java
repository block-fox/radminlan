package dev.radminlan.network;

import java.util.concurrent.CompletableFuture;

public final class UnavailableBackend implements RadminBackend {
    private final String message;
    public UnavailableBackend(String message) { this.message = message; }
    @Override public CompletableFuture<RadminSnapshot> snapshot() { return CompletableFuture.completedFuture(RadminSnapshot.unavailable(message)); }
    @Override public CompletableFuture<Boolean> createNetwork(String name, String password) { return CompletableFuture.completedFuture(false); }
    @Override public CompletableFuture<Boolean> joinNetwork(String name, String password) { return CompletableFuture.completedFuture(false); }
    @Override public CompletableFuture<Boolean> leaveNetwork(String name) { return CompletableFuture.completedFuture(false); }
    @Override public void close() {}
}

