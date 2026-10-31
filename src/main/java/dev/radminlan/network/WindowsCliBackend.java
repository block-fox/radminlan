package dev.radminlan.network;

import dev.radminlan.config.RadminConfig;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Best-effort adapter for the Radmin VPN command line client. UI never calls it on the render thread. */
public final class WindowsCliBackend implements RadminBackend {
    private final RadminConfig config;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "radminlan-cli");
        thread.setDaemon(true);
        return thread;
    });
    private volatile boolean closed;

    public WindowsCliBackend(RadminConfig config) { this.config = config; }

    @Override public CompletableFuture<RadminSnapshot> snapshot() {
        return CompletableFuture.supplyAsync(() -> {
            if (closed) return RadminSnapshot.unavailable("Backend closed");
            if (config.radminExecutable == null || config.radminExecutable.isBlank()) {
                return RadminSnapshot.unavailable("Set the Radmin executable path in config");
            }
            Path executable = Path.of(config.radminExecutable);
            if (!Files.isRegularFile(executable)) return RadminSnapshot.unavailable("Radmin executable not found");
            try {
                Process process = new ProcessBuilder(executable.toString(), "network", "list")
                        .redirectErrorStream(true).start();
                List<String> lines = new ArrayList<>();
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) lines.add(line);
                }
                boolean ready = process.waitFor() == 0;
                return new RadminSnapshot(ready ? RadminBackend.ServiceState.READY : RadminBackend.ServiceState.ERROR,
                        "", "", parseNetworks(lines), ready ? "" : "Radmin CLI returned an error", ready, "Radmin CLI");
            } catch (Exception e) {
                return RadminSnapshot.unavailable(e.getMessage() == null ? "Unable to start Radmin CLI" : e.getMessage());
            }
        }, executor);
    }

    @Override public CompletableFuture<Boolean> createNetwork(String name, String password) { return command("network", "create", name, password); }
    @Override public CompletableFuture<Boolean> joinNetwork(String name, String password) { return command("network", "join", name, password); }
    @Override public CompletableFuture<Boolean> leaveNetwork(String name) { return command("network", "leave", name); }

    private CompletableFuture<Boolean> command(String... args) {
        return CompletableFuture.supplyAsync(() -> {
            if (closed || config.radminExecutable == null || config.radminExecutable.isBlank()) return false;
            try {
                List<String> command = new ArrayList<>();
                command.add(config.radminExecutable);
                command.addAll(List.of(args));
                return new ProcessBuilder(command).redirectErrorStream(true).start().waitFor() == 0;
            } catch (Exception ignored) { return false; }
        }, executor);
    }

    private static List<RadminBackend.Network> parseNetworks(List<String> lines) {
        List<RadminBackend.Network> networks = new ArrayList<>();
        for (String line : lines) {
            String trimmed = line.trim();
            if (!trimmed.isEmpty() && !trimmed.startsWith("#")) networks.add(new RadminBackend.Network(trimmed, "", "", List.of()));
        }
        return networks;
    }

    @Override public void close() { closed = true; executor.shutdownNow(); }
}

