package dev.radminlan.network;

import dev.radminlan.config.RadminConfig;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Best-effort adapter for the Radmin VPN command line client. UI never calls it on the render thread. */
public final class WindowsCliBackend implements RadminBackend {
    private final RadminConfig config;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "radminlan-cli");
        thread.setDaemon(true);
        return thread;
    });
    /** Coalesces overlapping refreshes so the CLI is not spawned once per UI caller. */
    private final AtomicReference<CompletableFuture<RadminSnapshot>> snapshotInFlight = new AtomicReference<>();
    private volatile boolean closed;

    public WindowsCliBackend(RadminConfig config) { this.config = config; }

    @Override public CompletableFuture<RadminSnapshot> snapshot() {
        if (closed) return CompletableFuture.completedFuture(RadminSnapshot.unavailable("Backend closed"));
        CompletableFuture<RadminSnapshot> current = snapshotInFlight.get();
        if (current != null && !current.isDone()) return current;

        CompletableFuture<RadminSnapshot> next = new CompletableFuture<>();
        if (!snapshotInFlight.compareAndSet(current, next)) {
            CompletableFuture<RadminSnapshot> raced = snapshotInFlight.get();
            return raced == null ? CompletableFuture.completedFuture(RadminSnapshot.unavailable("Snapshot request superseded")) : raced;
        }
        try {
            executor.execute(() -> {
                RadminSnapshot result;
                try {
                    result = readSnapshot();
                } catch (RuntimeException error) {
                    result = RadminSnapshot.unavailable(error.getMessage() == null ? "Unable to read Radmin CLI" : error.getMessage());
                }
                next.complete(result);
            });
        } catch (RuntimeException rejected) {
            next.complete(RadminSnapshot.unavailable("Backend executor unavailable"));
        }
        next.whenComplete((ignored, error) -> snapshotInFlight.compareAndSet(next, null));
        return next;
    }

    private RadminSnapshot readSnapshot() {
            if (closed) return RadminSnapshot.unavailable("Backend closed");
            if (config.radminExecutable == null || config.radminExecutable.isBlank()) {
                return RadminSnapshot.unavailable("Set the Radmin executable path in config");
            }
            Path executable = Path.of(config.radminExecutable);
            if (!Files.isRegularFile(executable)) return RadminSnapshot.unavailable("Radmin executable not found");
            try {
                Process process = new ProcessBuilder(executable.toString(), "network", "list")
                        .redirectErrorStream(true).start();
                List<String> lines = Collections.synchronizedList(new ArrayList<>());
                // Drain stdout on a daemon thread while the caller waits with a hard deadline. A
                // direct readLine loop can otherwise block forever before waitFor(timeout) runs.
                Thread outputReader = new Thread(() -> {
                    try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                        String line;
                        while ((line = reader.readLine()) != null) lines.add(line);
                    } catch (Exception ignored) {
                        // The process may be forcibly terminated while its output is being read.
                    }
                }, "radminlan-cli-output");
                outputReader.setDaemon(true);
                outputReader.start();
                boolean finished = process.waitFor(Math.max(1000, config.queryTimeoutMs), TimeUnit.MILLISECONDS);
                if (!finished) {
                    process.destroyForcibly();
                    outputReader.join(250);
                    return RadminSnapshot.unavailable("Radmin CLI timed out");
                }
                outputReader.join(Math.max(250, Math.min(1000, config.queryTimeoutMs)));
                List<String> completedLines;
                synchronized (lines) {
                    completedLines = List.copyOf(lines);
                }
                boolean ready = process.exitValue() == 0;
                return new RadminSnapshot(ready ? RadminBackend.ServiceState.READY : RadminBackend.ServiceState.ERROR,
                        "", "", parseNetworks(completedLines), ready ? "" : "Radmin CLI returned an error", ready, "Radmin CLI");
            } catch (Exception e) {
                return RadminSnapshot.unavailable(e.getMessage() == null ? "Unable to start Radmin CLI" : e.getMessage());
            }
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
                Process process = new ProcessBuilder(command)
                        .redirectErrorStream(true)
                        .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                        .start();
                boolean finished = process.waitFor(Math.max(1000, config.queryTimeoutMs), TimeUnit.MILLISECONDS);
                if (!finished) {
                    process.destroyForcibly();
                    return false;
                }
                return process.exitValue() == 0;
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

