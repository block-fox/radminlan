package dev.radminlan.network;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/** Latest service-reported ping samples received over a read-only pipe subscription. */
public final class RadminPeerTelemetry {
    private static final long MAX_SAMPLE_AGE = TimeUnit.SECONDS.toNanos(60);
    private final Map<Long, Sample> samples = new HashMap<>();

    public synchronized void accept(byte[] framedBytes) {
        Map<Long, Long> attributes = new HashMap<>();
        Map<Long, Long> latencies = new HashMap<>();
        for (byte[] frame : RadminWire.splitFrames(framedBytes)) {
            RadminSnapshotParser.collectTelemetry(RadminWire.parseFrame(frame), attributes, latencies);
        }
        long now = System.nanoTime();
        samples.entrySet().removeIf(entry -> now - entry.getValue().receivedAt > MAX_SAMPLE_AGE);
        attributes.forEach((nodeId, flags) -> {
            if (flags < 0 || (flags & 1) == 0) samples.remove(nodeId);
        });
        latencies.forEach((nodeId, latency) -> {
            Long flags = attributes.get(nodeId);
            if (latency < 0 || flags != null && (flags < 0 || (flags & 1) == 0)) samples.remove(nodeId);
            else samples.put(nodeId, new Sample(latency, now));
        });
    }

    public synchronized RadminBackend.RadminSnapshot enrich(RadminBackend.RadminSnapshot snapshot) {
        long now = System.nanoTime();
        List<RadminBackend.Network> networks = snapshot.networks().stream().map(network -> {
            List<RadminBackend.Peer> peers = network.peers().stream().map(peer -> {
                if (!peer.online()) {
                    samples.remove(peer.nodeId());
                    return withLatency(peer, -1);
                }
                if (peer.latencyMs() >= 0) samples.put(peer.nodeId(), new Sample(peer.latencyMs(), now));
                Sample sample = samples.get(peer.nodeId());
                long latency = sample == null || now - sample.receivedAt > MAX_SAMPLE_AGE ? -1 : sample.latency;
                return withLatency(peer, latency);
            }).toList();
            return new RadminBackend.Network(network.name(), network.guid(), network.owner(), peers,
                    network.networkType(), network.enabled(), network.admin(), network.allowPasswordless(),
                    network.blockNewMembers(), network.publicNetwork());
        }).toList();
        return new RadminBackend.RadminSnapshot(snapshot.state(), snapshot.localName(), snapshot.virtualIp(),
                networks, snapshot.message(), snapshot.canWrite(), snapshot.transport(), snapshot.power());
    }

    public synchronized void clear() { samples.clear(); }

    private static RadminBackend.Peer withLatency(RadminBackend.Peer peer, long latency) {
        return new RadminBackend.Peer(peer.name(), peer.virtualIp(), peer.virtualIpv6(), peer.state(), latency,
                peer.online(), peer.nodeId(), peer.publicIp(), peer.connectionType(), peer.networkName(),
                peer.role(), peer.version());
    }

    private record Sample(long latency, long receivedAt) {}
}
