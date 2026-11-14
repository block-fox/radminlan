import dev.radminlan.config.RadminConfig;
import dev.radminlan.network.PipeRadminBackend;
import dev.radminlan.network.RadminBackend;
import net.fabricmc.loader.api.FabricLoader;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/** Read-only live service check. Emits aggregate counts only. */
class NativeTelemetrySmoke {
    public static void main(String[] args) throws Exception {
        Path workDir = Path.of(System.getProperty("uiRegression.workDir", ".tmp/ui-regression")).toAbsolutePath();
        Path isolatedGameDir = Files.createTempDirectory(workDir, "telemetry-smoke-");
        FabricLoader loader = FabricLoader.getInstance();
        Method setGameDir = loader.getClass().getDeclaredMethod("setGameDir", Path.class);
        setGameDir.setAccessible(true);
        setGameDir.invoke(loader, isolatedGameDir);
        RadminConfig config = new RadminConfig();
        config.pipeTimeoutMs = 1500;
        try (PipeRadminBackend backend = new PipeRadminBackend(config)) {
            for (int sample = 0; sample < 3; sample++) {
                if (sample > 0) Thread.sleep(2000);
                RadminBackend.RadminSnapshot snapshot = backend.snapshot().get(4, TimeUnit.SECONDS);
                Map<Long, RadminBackend.Peer> peers = new LinkedHashMap<>();
                snapshot.networks().forEach(network -> network.peers().forEach(peer -> peers.put(peer.nodeId(), peer)));
                long online = peers.values().stream().filter(RadminBackend.Peer::online).count();
                long offline = peers.values().stream().filter(peer -> "offline".equals(peer.state())).count();
                long unknown = peers.values().stream().filter(peer -> "unknown".equals(peer.state())).count();
                long known = peers.values().stream().filter(peer -> peer.online() && peer.latencyMs() >= 0).count();
                var summary = peers.values().stream().filter(peer -> peer.online() && peer.latencyMs() >= 0)
                        .mapToLong(RadminBackend.Peer::latencyMs).summaryStatistics();
                System.out.println("NATIVE_TELEMETRY_SAMPLE sample=" + sample + " state=" + snapshot.state()
                        + " networks=" + snapshot.networks().size() + " peers=" + peers.size() + " online=" + online
                        + " offline=" + offline + " unknown=" + unknown + " knownLatency=" + known
                        + " min=" + (summary.getCount() == 0 ? -1 : summary.getMin())
                        + " max=" + (summary.getCount() == 0 ? -1 : summary.getMax()));
            }
        }
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (updateThreads() != 0 && System.nanoTime() < deadline) Thread.sleep(20);
        long alive = updateThreads();
        System.out.println("NATIVE_TELEMETRY_CLOSED updateThreads=" + alive);
        if (alive != 0) throw new AssertionError("Read-only update subscription did not stop");
    }

    private static long updateThreads() {
        return Thread.getAllStackTraces().keySet().stream()
                .filter(thread -> thread.isAlive() && thread.getName().equals("radminlan-pipe-updates")).count();
    }
}
