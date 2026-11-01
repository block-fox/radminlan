package dev.radminlan.room;

import dev.radminlan.config.RadminConfig;
import net.minecraft.SharedConstants;
import net.minecraft.client.server.LanServer;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import com.mojang.serialization.JsonOps;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.net.Socket;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import com.google.gson.JsonObject;
import com.google.gson.JsonElement;
import com.google.gson.JsonArray;
import com.google.gson.JsonParser;
import java.nio.charset.StandardCharsets;

/** Thread-safe cache shared by the vanilla LAN screen integration and Radmin screen. */
public final class LanRoomRepository implements AutoCloseable {
    private static final int MAX_STATUS_BYTES = 2 * 1024 * 1024;
    private static final Comparator<LanRoomSnapshot> MOST_RECENT_FIRST =
            Comparator.comparingLong(LanRoomSnapshot::lastSeen).reversed();
    private final RadminConfig config;
    private final Map<RoomKey, LanRoomSnapshot> rooms = new LinkedHashMap<>();
    private final ExecutorService queryExecutor = Executors.newFixedThreadPool(4, r -> {
        Thread thread = new Thread(r, "radminlan-status");
        thread.setDaemon(true);
        return thread;
    });
    private final Set<RoomKey> statusQueries = new HashSet<>();
    private final Map<RoomKey, Long> lastStatusQuery = new HashMap<>();

    public LanRoomRepository(RadminConfig config) { this.config = config; }

    /** Discovery is owned by Minecraft's LanServerDetector, which already binds UDP 4445. */
    @Deprecated
    public void discoverAsync() {
        // Kept as a source-compatible no-op for existing screen code. New rows are
        // synchronized through syncVanillaServers instead of binding a second socket.
    }

    /** Mirrors vanilla's LAN detector results and schedules bounded status refreshes. */
    public void syncVanillaServers(Collection<LanServer> detectedServers) {
        if (detectedServers == null) return;
        long now = System.currentTimeMillis();
        List<RoomQuery> queries = new ArrayList<>();
        Set<RoomKey> present = new HashSet<>();
        synchronized (this) {
            for (LanServer server : detectedServers) {
                RoomKey key = parseKey(server.getAddress());
                if (key == null) continue;
                present.add(key);

                String motd = server.getMotd() == null ? "" : server.getMotd();
                LanRoomSnapshot previous = rooms.get(key);
                // Vanilla republishes the same LAN advertisement frequently.
                // Keep the immutable snapshot when its advertised data did not
                // change, avoiding allocations and map writes on every sync.
                LanRoomSnapshot current;
                if (previous == null) {
                    current = new LanRoomSnapshot(UUID.randomUUID(), key, motd, "", "", -1, -1, -1,
                            now, LanRoomSnapshot.State.DISCOVERED);
                    rooms.put(key, current);
                } else if (!motd.equals(previous.motd())) {
                    current = new LanRoomSnapshot(previous.id(), key, motd, previous.statusMotd(), previous.favicon(),
                            previous.players(), previous.maxPlayers(), previous.latencyMs(), now, previous.state(),
                            previous.statusComponent(), previous.samplePlayers());
                    rooms.put(key, current);
                } else {
                    current = previous;
                }

                long lastQuery = lastStatusQuery.getOrDefault(key, 0L);
                if (config.queryLanStatus && now - lastQuery >= 30_000L && statusQueries.add(key)) {
                    lastStatusQuery.put(key, now);
                    rooms.put(key, current.withState(LanRoomSnapshot.State.QUERYING));
                    queries.add(new RoomQuery(key, motd, current.id()));
                }
            }
            rooms.keySet().removeIf(key -> !present.contains(key));
            lastStatusQuery.keySet().removeIf(key -> !present.contains(key));
        }
        for (RoomQuery query : queries) {
            queryExecutor.submit(() -> {
                try {
                    queryStatus(query.key(), query.motd(), query.roomId());
                } finally {
                    synchronized (LanRoomRepository.this) {
                        statusQueries.remove(query.key());
                    }
                }
            });
        }
    }

    private void queryStatus(RoomKey key, String fallbackMotd, UUID roomId) {
        long started = System.nanoTime();
        try (Socket socket = new Socket()) {
            socket.connect(new java.net.InetSocketAddress(key.ip(), key.port()), Math.max(100, config.queryTimeoutMs));
            socket.setSoTimeout(Math.max(100, config.queryTimeoutMs));
            DataOutputStream out = new DataOutputStream(socket.getOutputStream());
            DataInputStream in = new DataInputStream(socket.getInputStream());
            ByteArrayOutputStream handshake = new ByteArrayOutputStream();
            DataOutputStream h = new DataOutputStream(handshake);
            writeVarInt(h, 0x00);
            writeVarInt(h, SharedConstants.getCurrentVersion().protocolVersion());
            writeString(h, key.ip());
            h.writeShort(key.port());
            writeVarInt(h, 1);
            writePacket(out, handshake.toByteArray());
            ByteArrayOutputStream request = new ByteArrayOutputStream();
            DataOutputStream r = new DataOutputStream(request);
            writeVarInt(r, 0x00);
            writePacket(out, request.toByteArray());
            int packetLength = readVarInt(in);
            if (packetLength <= 0 || packetLength > MAX_STATUS_BYTES) throw new IOException("Invalid status packet length");
            int packetId = readVarInt(in);
            if (packetId != 0) throw new IOException("Unexpected status packet id");
            String json = readString(in);
            JsonObject root = JsonParser.parseString(json).getAsJsonObject();
            int online = root.has("players") && root.getAsJsonObject("players").has("online") ? root.getAsJsonObject("players").get("online").getAsInt() : -1;
            int max = root.has("players") && root.getAsJsonObject("players").has("max") ? root.getAsJsonObject("players").get("max").getAsInt() : -1;
            Component statusComponent = component(root.get("description"));
            String statusMotd = statusComponent == null ? "" : statusComponent.getString();
            List<String> samplePlayers = parseSamplePlayers(root);
            String favicon = root.has("favicon") && root.get("favicon").isJsonPrimitive()
                    ? root.get("favicon").getAsString() : "";
            long latency = (System.nanoTime() - started) / 1_000_000L;
            synchronized (this) {
                LanRoomSnapshot old = rooms.get(key);
                if (old != null && old.id().equals(roomId)) rooms.put(key, new LanRoomSnapshot(old.id(), key, old.motd(),
                        statusMotd.isBlank() ? old.statusMotd() : statusMotd,
                        favicon.isBlank() ? old.favicon() : favicon, online, max, latency,
                        System.currentTimeMillis(), LanRoomSnapshot.State.READY,
                        statusComponent == null ? old.statusComponent() : statusComponent,
                        samplePlayers));
            }
        } catch (Exception ignored) {
            synchronized (this) {
                LanRoomSnapshot old = rooms.get(key);
                if (old != null && old.id().equals(roomId)) rooms.put(key, new LanRoomSnapshot(old.id(), key, fallbackMotd,
                        old.statusMotd(), old.favicon(), old.players(), old.maxPlayers(), old.latencyMs(),
                        System.currentTimeMillis(), LanRoomSnapshot.State.TIMED_OUT, old.statusComponent(), old.samplePlayers()));
            }
        }
    }

    private static List<String> parseSamplePlayers(JsonObject root) {
        if (!root.has("players") || !root.get("players").isJsonObject()) return List.of();
        JsonObject players = root.getAsJsonObject("players");
        if (!players.has("sample") || !players.get("sample").isJsonArray()) return List.of();
        JsonArray sample = players.getAsJsonArray("sample");
        List<String> names = new ArrayList<>(Math.min(sample.size(), 12));
        for (JsonElement entry : sample) {
            if (!entry.isJsonObject()) continue;
            JsonElement name = entry.getAsJsonObject().get("name");
            if (name == null || !name.isJsonPrimitive()) continue;
            String value = name.getAsString().strip();
            if (!value.isEmpty() && value.length() <= 64) names.add(value);
            if (names.size() == 12) break;
        }
        return List.copyOf(names);
    }

    private static void writePacket(DataOutputStream out, byte[] payload) throws IOException {
        writeVarInt(out, payload.length);
        out.write(payload);
        out.flush();
    }
    private static void writeString(DataOutputStream out, String value) throws IOException { byte[] bytes = value.getBytes(StandardCharsets.UTF_8); writeVarInt(out, bytes.length); out.write(bytes); }
    private static String readString(DataInputStream in) throws IOException {
        int length = readVarInt(in);
        if (length < 0 || length > MAX_STATUS_BYTES) throw new IOException("Invalid status string length");
        byte[] bytes = in.readNBytes(length);
        if (bytes.length != length) throw new IOException("Truncated status string");
        return new String(bytes, StandardCharsets.UTF_8);
    }
    private static void writeVarInt(DataOutputStream out, int value) throws IOException { do { int part = value & 0x7F; value >>>= 7; if (value != 0) part |= 0x80; out.writeByte(part); } while (value != 0); }
    private static int readVarInt(DataInputStream in) throws IOException { int value = 0, position = 0, part; do { part = in.readUnsignedByte(); value |= (part & 0x7F) << position; position += 7; if (position > 35) throw new IOException("Invalid VarInt"); } while ((part & 0x80) != 0); return value; }

    private static RoomKey parseKey(String address) {
        if (address == null || address.isBlank()) return null;
        String value = address.trim();
        int separator = value.lastIndexOf(':');
        if (separator <= 0 || separator == value.length() - 1) return null;
        String host = value.substring(0, separator).trim();
        if (host.startsWith("[") && host.endsWith("]")) host = host.substring(1, host.length() - 1);
        try {
            int port = Integer.parseInt(value.substring(separator + 1));
            return port > 0 && port <= 65535 && !host.isBlank() ? new RoomKey(host, port) : null;
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static Component component(JsonElement json) {
        if (json == null || json.isJsonNull()) return null;
        return ComponentSerialization.CODEC.parse(JsonOps.INSTANCE, json)
                .result()
                .orElse(null);
    }

    private record RoomQuery(RoomKey key, String motd, UUID roomId) {}

    public synchronized void upsert(LanRoomSnapshot room) {
        rooms.put(room.key(), room);
    }

    public synchronized void remove(RoomKey key) { rooms.remove(key); }
    public synchronized void clear() { rooms.clear(); }
    public synchronized LanRoomSnapshot get(RoomKey key) { return rooms.get(key); }
    public synchronized List<LanRoomSnapshot> visibleRooms() {
        return filteredRooms(false);
    }
    public synchronized List<LanRoomSnapshot> blockedRooms() {
        return filteredRooms(true);
    }
    public synchronized Collection<LanRoomSnapshot> allRooms() { return new ArrayList<>(rooms.values()); }

    private List<LanRoomSnapshot> filteredRooms(boolean blocked) {
        List<LanRoomSnapshot> result = new ArrayList<>(rooms.size());
        for (LanRoomSnapshot room : rooms.values()) {
            boolean isBlocked = config.isBlocked(room.key().ip(), room.key().port(), room.motd());
            if (isBlocked == blocked) result.add(room);
        }
        result.sort(MOST_RECENT_FIRST);
        return List.copyOf(result);
    }
    public ExecutorService queryExecutor() { return queryExecutor; }
    @Override public void close() { queryExecutor.shutdownNow(); }
}

