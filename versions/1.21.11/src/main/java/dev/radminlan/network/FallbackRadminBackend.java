package dev.radminlan.network;

import java.util.concurrent.CompletableFuture;
import java.util.List;

/** Uses the pipe first and falls back to the configured CLI when the pipe is unavailable. */
final class FallbackRadminBackend implements RadminBackend {
    private final RadminBackend primary;
    private final RadminBackend fallback;

    FallbackRadminBackend(RadminBackend primary, RadminBackend fallback) {
        this.primary = primary;
        this.fallback = fallback;
    }

    @Override
    public CompletableFuture<RadminSnapshot> snapshot() {
        return primary.snapshot().thenCompose(result -> {
            if (result.state() == ServiceState.READY || result.state() == ServiceState.DISABLED) {
                return CompletableFuture.completedFuture(result);
            }
            return fallback.snapshot().thenApply(cli -> cli.state() == ServiceState.READY ? cli : result);
        });
    }

    @Override public CompletableFuture<Boolean> leaveNetwork(String name) {
        return primary.leaveNetwork(name).thenCompose(ok -> ok
                ? CompletableFuture.completedFuture(true)
                : fallback.leaveNetwork(name));
    }

    @Override public CompletableFuture<Boolean> leaveNetworkByGuid(String guid) {
        return primary.leaveNetworkByGuid(guid).thenCompose(ok -> ok
                ? CompletableFuture.completedFuture(true)
                : fallback.leaveNetwork(guid));
    }

    @Override public CompletableFuture<Boolean> createNetwork(String name, String password) {
        return primary.createNetwork(name, password).thenCompose(ok -> ok
                ? CompletableFuture.completedFuture(true)
                : fallback.createNetwork(name, password));
    }

    @Override public CompletableFuture<List<String>> publicNetworks(String query) {
        return primary.publicNetworks(query)
                .exceptionallyCompose(error -> fallback.publicNetworks(query));
    }

    @Override public CompletableFuture<Boolean> setPower(boolean enabled) {
        return primary.setPower(enabled).thenCompose(ok -> ok
                ? CompletableFuture.completedFuture(true)
                : fallback.setPower(enabled));
    }

    @Override public CompletableFuture<Boolean> setAlias(String alias) {
        return primary.setAlias(alias).thenCompose(ok -> ok
                ? CompletableFuture.completedFuture(true) : fallback.setAlias(alias));
    }

    @Override public CompletableFuture<Boolean> joinNetwork(String name, String password) {
        return primary.joinNetwork(name, password).thenCompose(ok -> ok
                ? CompletableFuture.completedFuture(true)
                : fallback.joinNetwork(name, password));
    }

    @Override public CompletableFuture<Boolean> setNetworkEnabled(String guid, boolean enabled) {
        return primary.setNetworkEnabled(guid, enabled).thenCompose(ok -> ok
                ? CompletableFuture.completedFuture(true)
                : fallback.setNetworkEnabled(guid, enabled));
    }

    @Override public CompletableFuture<Boolean> deleteNetwork(String guid) {
        return primary.deleteNetwork(guid).thenCompose(ok -> ok
                ? CompletableFuture.completedFuture(true)
                : fallback.deleteNetwork(guid));
    }

    @Override public CompletableFuture<Boolean> kickPeer(String guid, long nodeId, String networkName) {
        return primary.kickPeer(guid, nodeId, networkName).thenCompose(ok -> ok
                ? CompletableFuture.completedFuture(true)
                : fallback.kickPeer(guid, nodeId, networkName));
    }

    @Override public boolean supportsWrite() { return fallback.supportsWrite() || primary.supportsWrite(); }
    @Override public void close() { primary.close(); fallback.close(); }
}
