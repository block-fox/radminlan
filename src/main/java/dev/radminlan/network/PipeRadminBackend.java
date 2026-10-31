package dev.radminlan.network;

import dev.radminlan.config.RadminConfig;

import java.time.Duration;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Java implementation of the official Radmin VPN control-pipe snapshot and command paths. */
public final class PipeRadminBackend implements RadminBackend {
    private static final int POWER_CONFIRM_ATTEMPTS = 6;
    private static final long POWER_CONFIRM_DELAY_MS = 350L;
    private static final int JOIN_CONFIRM_ATTEMPTS = 8;
    private static final long JOIN_CONFIRM_DELAY_MS = 350L;
    private final RadminConfig config;
    private final RadminPipeClient pipe;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "radminlan-pipe-backend");
        t.setDaemon(true);
        return t;
    });
    /** Coalesces overlapping UI refreshes while keeping the snapshot uncached after completion. */
    private final AtomicReference<CompletableFuture<RadminSnapshot>> snapshotInFlight = new AtomicReference<>();
    private volatile boolean closed;
    private volatile boolean pipeCanWrite = true;

    public PipeRadminBackend(RadminConfig config) {
        this.config = config;
        this.pipe = new RadminPipeClient(config.pipeName);
    }

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
                    result = RadminSnapshot.unavailable(message(error));
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
        try {
            RadminPipeClient.ReadResult result = pipe.readInitialFrames(
                    Duration.ofMillis(Math.max(1000, config.pipeTimeoutMs)));
            pipeCanWrite = result.canWrite();
            return RadminSnapshotParser.parse(result.bytes(), result.canWrite(), result.transport());
        } catch (Exception e) {
            return RadminSnapshot.unavailable(message(e));
        }
    }

    private static String message(Exception e) {
        String text = e.getMessage();
        if (text == null || text.isBlank()) return "Unable to read Radmin control pipe";
        if (text.contains("no response bytes arrived")) return "screen.radminlan.pipe_no_response";
        if (text.contains("Win32 2")) return "Radmin control pipe was not found (Win32 2)";
        return text;
    }

    @Override public boolean supportsWrite() { return pipeCanWrite; }

    @Override public CompletableFuture<Boolean> createNetwork(String name, String password) {
        return send(RadminCommandWire.create(name, password));
    }

    @Override public CompletableFuture<Boolean> joinNetwork(String name, String password) {
        if (name == null || name.isBlank()) return CompletableFuture.completedFuture(false);
        return send(RadminCommandWire.join(name, password))
                .thenCompose(ok -> ok ? confirmNetworkJoined(name, JOIN_CONFIRM_ATTEMPTS)
                        : CompletableFuture.completedFuture(false));
    }

    /**
     * The control pipe acknowledges a join asynchronously and the write call
     * itself only means that the request was accepted by the service. Poll the
     * normal snapshot until the network appears so the UI can report the real
     * result instead of closing immediately after a fire-and-forget request.
     */
    private CompletableFuture<Boolean> confirmNetworkJoined(String name, int attemptsLeft) {
        return snapshot().thenCompose(state -> {
            boolean joined = state != null && state.networks().stream()
                    .anyMatch(network -> name.equalsIgnoreCase(network.name()));
            if (joined) return CompletableFuture.completedFuture(true);
            if (attemptsLeft <= 1) return CompletableFuture.completedFuture(false);
            CompletableFuture<Boolean> retry = new CompletableFuture<>();
            CompletableFuture.delayedExecutor(JOIN_CONFIRM_DELAY_MS, TimeUnit.MILLISECONDS).execute(() ->
                    confirmNetworkJoined(name, attemptsLeft - 1)
                            .whenComplete((ok, error) -> retry.complete(error == null && Boolean.TRUE.equals(ok))));
            return retry;
        });
    }

    @Override public CompletableFuture<List<String>> publicNetworks(String query) {
        // 0x13 is a directory query. The official client writes it to a
        // duplex pipe and reads the directory response from that same handle;
        // reading a snapshot first can consume the response and leaves only
        // the currently joined networks visible. Search is intentionally done
        // by the screen after the complete directory has been loaded.
        return CompletableFuture.supplyAsync(() -> {
            if (closed) throw new CompletionException(new IllegalStateException("Backend closed"));
            try {
                Duration timeout = Duration.ofMillis(Math.max(4000, config.pipeTimeoutMs));
                // The directory response must be collected from the same
                // duplex request that sent CEnumPublicNetworks. Only after it
                // is drained do we read a normal snapshot to remove networks
                // that are already joined locally.
                byte[] response = pipe.sendCommandAndReadSnapshot(RadminCommandWire.enumPublicNetworks(""), timeout);
                RadminPipeClient.ReadResult joinedFrames = pipe.readInitialFrames(timeout);
                RadminBackend.RadminSnapshot joinedSnapshot = RadminSnapshotParser.parse(
                        joinedFrames.bytes(), joinedFrames.canWrite(), joinedFrames.transport());
                List<String> joinedNames = joinedSnapshot.networks().stream()
                        .map(RadminBackend.Network::name)
                        .toList();
                return RadminSnapshotParser.parsePublicNetworkNames(response, "", joinedNames);
            } catch (Exception e) {
                throw new CompletionException(e);
            }
        }, executor);
    }

    @Override public CompletableFuture<Boolean> setPower(boolean enabled) {
        CompletableFuture<Boolean> preparation = enabled
                ? CompletableFuture.supplyAsync(this::ensureRadminProcesses, executor)
                : CompletableFuture.completedFuture(true);
        return preparation.thenCompose(prepared -> {
            if (!prepared && enabled) return CompletableFuture.completedFuture(false);
            return send(RadminCommandWire.setPower(enabled));
        }).thenCompose(ok -> ok ? confirmPowerState(enabled, POWER_CONFIRM_ATTEMPTS)
                : CompletableFuture.completedFuture(false));
    }

    @Override public CompletableFuture<Boolean> setAlias(String alias) {
        if (alias == null || alias.isBlank()) return CompletableFuture.completedFuture(false);
        return send(RadminCommandWire.setAlias(alias.trim()));
    }

    private CompletableFuture<Boolean> confirmPowerState(boolean enabled, int attemptsLeft) {
        return snapshot().thenCompose(state -> {
            if (matchesPowerState(state, enabled)) return CompletableFuture.completedFuture(true);
            if (attemptsLeft <= 1) return CompletableFuture.completedFuture(false);
            CompletableFuture<Boolean> retry = new CompletableFuture<>();
            CompletableFuture.delayedExecutor(POWER_CONFIRM_DELAY_MS, TimeUnit.MILLISECONDS).execute(() ->
                    confirmPowerState(enabled, attemptsLeft - 1)
                            .whenComplete((ok, error) -> retry.complete(error == null && Boolean.TRUE.equals(ok))));
            return retry;
        });
    }

    private static boolean matchesPowerState(RadminSnapshot state, boolean enabled) {
        if (state == null) return false;
        if (state.power() != null) return state.power() == enabled;
        return enabled && state.state() == ServiceState.READY
                || !enabled && state.state() == ServiceState.DISABLED;
    }

    /** Starts the Windows service and GUI when the control pipe is still present but the VPN is powered off. */
    private boolean ensureRadminProcesses() {
        try {
            if (serviceExists("RvControlSvc")) {
                runWindowsCommand("sc.exe", "start", "RvControlSvc");
            }
            // `radminExecutable` is normally the optional CLI path used for
            // fallback queries. Do not launch it as a GUI here; only use it
            // when it is explicitly the Radmin VPN GUI binary.
            String executable = config.radminExecutable;
            if (executable == null || executable.isBlank()
                    || !Path.of(executable).getFileName().toString().equalsIgnoreCase("RvRvpnGui.exe")) {
                executable = discoverGuiExecutable();
            }
            if (executable != null && !executable.isBlank() && Files.isRegularFile(Path.of(executable))) {
                if (!processRunning("RvRvpnGui.exe")) {
                    new ProcessBuilder(executable).redirectErrorStream(true).start();
                }
            }
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    private static boolean serviceExists(String service) {
        try {
            Process process = new ProcessBuilder("sc.exe", "query", service).redirectErrorStream(true).start();
            return process.waitFor(3, TimeUnit.SECONDS) && process.exitValue() == 0;
        } catch (Exception ignored) { return false; }
    }

    private static void runWindowsCommand(String... command) {
        try {
            Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
            process.waitFor(5, TimeUnit.SECONDS);
        } catch (Exception ignored) { }
    }

    private static boolean processRunning(String imageName) {
        try {
            Process process = new ProcessBuilder("tasklist", "/FI", "IMAGENAME eq " + imageName,
                    "/FO", "CSV", "/NH").redirectErrorStream(true).start();
            String output = new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            process.waitFor(3, TimeUnit.SECONDS);
            return output.toLowerCase(java.util.Locale.ROOT).contains(imageName.toLowerCase(java.util.Locale.ROOT));
        } catch (Exception ignored) { return false; }
    }

    private static String discoverGuiExecutable() {
        String[] candidates = {
                "C:\\Program Files\\Radmin VPN\\RvRvpnGui.exe",
                "C:\\Program Files (x86)\\Radmin VPN\\RvRvpnGui.exe",
                "C:\\Program Files\\Radmin VPN\\RvRvpnGui.exe"
        };
        for (String candidate : candidates) if (Files.isRegularFile(Path.of(candidate))) return candidate;
        return "";
    }

    @Override public CompletableFuture<Boolean> leaveNetwork(String name) {
        if (name == null || name.isBlank()) return CompletableFuture.completedFuture(false);
        return snapshot().thenCompose(state -> state.networks().stream()
                .filter(network -> name.equalsIgnoreCase(network.name()))
                .findFirst()
                .map(network -> leaveNetworkByGuid(network.guid()))
                .orElseGet(() -> CompletableFuture.completedFuture(false)));
    }

    @Override public CompletableFuture<Boolean> leaveNetworkByGuid(String guid) {
        return send(RadminCommandWire.leave(guid));
    }

    @Override public CompletableFuture<Boolean> deleteNetwork(String guid) {
        return send(RadminCommandWire.delete(guid));
    }

    @Override public CompletableFuture<Boolean> setNetworkEnabled(String guid, boolean enabled) {
        return send(RadminCommandWire.setEnabled(guid, enabled));
    }

    @Override public CompletableFuture<Boolean> kickPeer(String guid, long nodeId, String networkName) {
        return send(RadminCommandWire.kick(guid, nodeId, networkName));
    }

    private CompletableFuture<Boolean> send(byte[] command) {
        return CompletableFuture.supplyAsync(() -> {
            if (closed) return false;
            try {
                pipe.sendCommand(command);
                return true;
            } catch (Exception ignored) {
                return false;
            }
        }, executor);
    }
    @Override public void close() { closed = true; pipe.close(); executor.shutdownNow(); }
}

