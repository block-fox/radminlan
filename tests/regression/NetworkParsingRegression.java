import dev.radminlan.network.RadminBackend;
import dev.radminlan.network.RadminPeerTelemetry;
import dev.radminlan.network.RadminSnapshotParser;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.lang.reflect.Method;

class NetworkParsingRegression {
    static void check() {
        byte[] initial = initialSnapshot();
        RadminBackend.RadminSnapshot baseline = parse(initial);
        require(peer(baseline, 100).online(), "online attribute bit is independent of connection direction 0x21=0");
        require(!peer(baseline, 101).online(), "offline attribute overrides connection direction 0x21=1");
        require("offline".equals(peer(baseline, 101).state()), "known offline status remains distinct from unknown");
        require("unknown".equals(peer(baseline, 102).state()), "missing node attributes are unknown");
        require(peer(baseline, 100).latencyMs() == -1, "profile field 0x3E is never presented as a ping");

        var updated = parse(join(initial, ping(100, 37), ping(100, 53), ping(101, 12), ping(103, 0)));
        require(peer(updated, 100).latencyMs() == 53, "latest type 28 / 0x4D ping is used");
        require(peer(updated, 101).latencyMs() == -1, "offline node does not display a ping");
        require(peer(updated, 103).latencyMs() == 0, "an explicit zero millisecond service sample is valid");
        require(Boolean.TRUE.equals(updated.networks().getFirst().admin()), "peer ping IDs never replace the local node ID");

        var offline = parse(join(initial, ping(100, 37), attributes(100, 0)));
        require(!peer(offline, 100).online() && peer(offline, 100).latencyMs() == -1,
                "incremental node attributes invalidate a previous online sample");

        var telemetry = new RadminPeerTelemetry();
        telemetry.accept(ping(100, 44));
        require(peer(telemetry.enrich(baseline), 100).latencyMs() == 44,
                "subscription ping survives a fresh snapshot without latency fields");
        telemetry.accept(ping(100, 0));
        require(peer(telemetry.enrich(baseline), 100).latencyMs() == 0, "subscription preserves a reported zero");
        telemetry.accept(ping(100, 0xFFFF_FFFFL));
        require(peer(telemetry.enrich(baseline), 100).latencyMs() == -1, "unknown ping sentinel clears a previous sample");
        telemetry.accept(ping(100, 28));
        telemetry.accept(attributes(100, 0));
        require(peer(telemetry.enrich(baseline), 100).latencyMs() == -1, "offline notification invalidates cached telemetry");
        telemetry.accept(ping(100, 28));
        telemetry.clear();
        require(peer(telemetry.enrich(baseline), 100).latencyMs() == -1, "disconnect clears measurements");
        require(peer(baseline, 100).latencyMs() == -1, "telemetry never mutates an existing snapshot");
        checkIpv4Validation();
        System.out.println("NETWORK_PARSING_REGRESSION_OK attributes=0xA0 latency=type28/0x4D");
    }

    private static void checkIpv4Validation() {
        try {
            Method validate = RadminSnapshotParser.class.getDeclaredMethod("validIpv4", String.class);
            validate.setAccessible(true);
            for (String invalid : new String[]{null, "", "a.b.c.d", "1.999.x.y", "1.2.3.256", "1.2.3.-1",
                    "1.2.3.", ".1.2.3", "1.2.3.4.", "1.2.3.+4", "1.2.3.0000", "0.0.0.0", "255.255.255.255", "224.1.2.3"}) {
                require("".equals(validate.invoke(null, invalid)), "invalid IPv4 alias is rejected safely: " + invalid);
            }
            for (String valid : new String[]{"26.0.0.1", "1.2.3.255", "223.255.255.254"}) {
                require(valid.equals(validate.invoke(null, valid)), "valid IPv4 alias is retained: " + valid);
            }
        } catch (ReflectiveOperationException error) {
            throw new AssertionError("IPv4 validation regression failed", error);
        }
    }

    private static RadminBackend.RadminSnapshot parse(byte[] bytes) {
        return RadminSnapshotParser.parse(bytes, true, "test");
    }

    private static RadminBackend.Peer peer(RadminBackend.RadminSnapshot snapshot, long nodeId) {
        return snapshot.networks().getFirst().peers().stream().filter(peer -> peer.nodeId() == nodeId).findFirst().orElseThrow();
    }

    private static byte[] initialSnapshot() {
        byte[] network = container(0x23, string(0x06, "Regression"), field(0x09, 0x0D, new byte[16]),
                member(42, 2), member(100, 1), member(101, 1), member(102, 1), member(103, 1));
        return frame(scalar(0x01, 34), container(0x20, node(42), string(0x04, "Local"), scalar(0x11, 1),
                container(0x21, network,
                        profile(100, 1L, 0), profile(101, 0L, 1), profile(102, null, 1), profile(103, 9L, 0))));
    }

    private static byte[] profile(long id, Long flags, int direction) {
        return container(0x22, node(id), string(0x04, "Peer" + id), scalar(0x05, 0x1A000000L + id),
                flags == null ? new byte[0] : scalar(0xA0, flags), scalar(0x21, direction), scalar(0x26, 1), scalar(0x3E, 777));
    }

    private static byte[] member(long id, int role) { return container(0x24, node(id), scalar(0x0A, role)); }
    private static byte[] ping(long id, long latency) { return frame(scalar(0x01, 28), node(id), scalar(0x4D, latency)); }
    private static byte[] attributes(long id, long flags) {
        return frame(scalar(0x01, 7), container(0x1F, container(0x66, node(id), scalar(0xA0, flags))));
    }
    private static byte[] node(long id) { return field(0xE1, 0x02, ByteBuffer.allocate(8).putLong(id).array()); }
    private static byte[] scalar(int id, long value) { return field(id, 0x01, ByteBuffer.allocate(4).putInt((int) value).array()); }
    private static byte[] string(int id, String value) {
        return field(id, 0x03, join(value.getBytes(StandardCharsets.UTF_16BE), new byte[]{0, 1}));
    }
    private static byte[] container(int id, byte[]... children) {
        byte[] payload = join(children);
        return ByteBuffer.allocate(8 + payload.length).putInt(payload.length).put((byte) 0).put((byte) 0)
                .put((byte) 0x13).put((byte) id).put(payload).array();
    }
    private static byte[] field(int id, int type, byte[] payload) {
        return ByteBuffer.allocate(8 + payload.length).putInt(payload.length).put((byte) type).put((byte) 0)
                .put((byte) (id == 0xE1 ? 1 : 3)).put((byte) id).put(payload).array();
    }
    private static byte[] frame(byte[]... fields) {
        byte[] body = join(fields);
        return ByteBuffer.allocate(4 + body.length).order(ByteOrder.LITTLE_ENDIAN).putInt(body.length).put(body).array();
    }
    private static byte[] join(byte[]... arrays) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] array : arrays) out.writeBytes(array);
        return out.toByteArray();
    }
    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
