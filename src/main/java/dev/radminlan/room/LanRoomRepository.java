package dev.radminlan.room;

import dev.radminlan.config.RadminConfig;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.net.DatagramPacket;
import java.net.InetAddress;
import java.net.MulticastSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Thread-safe cache shared by the vanilla LAN screen integration and Radmin screen. */
public final class LanRoomRepository implements AutoCloseable {
    private final RadminConfig config;
    private final Map<RoomKey, LanRoomSnapshot> rooms = new LinkedHashMap<>();
    private final ExecutorService queryExecutor = Executors.newFixedThreadPool(4, r -> {
        Thread thread = new Thread(r, "radminlan-status");
        thread.setDaemon(true);
        return thread;
    });

    public LanRoomRepository(RadminConfig config) { this.config = config; }

    /** Performs one bounded vanilla LAN multicast discovery pass on a worker thread. */
    public void discoverAsync() {
        queryExecutor.submit(() -> {
            try (MulticastSocket socket = new MulticastSocket(4445)) {
                socket.setSoTimeout(Math.max(100, config.queryTimeoutMs));
                InetAddress group = InetAddress.getByName("224.0.2.60");
                socket.joinGroup(group);
                long deadline = System.currentTimeMillis() + Math.max(500, config.queryTimeoutMs * 2L);
                byte[] bytes = new byte[2048];
                while (System.currentTimeMillis() < deadline) {
                    try {
                        DatagramPacket packet = new DatagramPacket(bytes, bytes.length);
                        socket.receive(packet);
                        String message = new String(packet.getData(), packet.getOffset(), packet.getLength(), StandardCharsets.UTF_8);
                        Matcher matcher = Pattern.compile("\\[AD\\](\\d+)\\[/AD\\]").matcher(message);
                        if (!matcher.find()) continue;
                        int port = Integer.parseInt(matcher.group(1));
                        String motd = message.replaceAll("\\[MOTD\\]|\\[/MOTD\\]|\\[AD\\].*?\\[/AD\\]", "").trim();
                        String ip = packet.getAddress().getHostAddress();
                        RoomKey key = new RoomKey(ip, port);
                        upsert(new LanRoomSnapshot(UUID.randomUUID(), key, motd, "", -1, -1, -1,
                                System.currentTimeMillis(), LanRoomSnapshot.State.QUERYING));
                        if (config.queryLanStatus) queryExecutor.submit(() -> queryStatus(key, motd));
                    } catch (SocketTimeoutException ignored) {
                        break;
                    }
                }
                socket.leaveGroup(group);
            } catch (Exception ignored) {
                // A missing multicast interface should not break the management UI.
            }
        });
    }

    private void queryStatus(RoomKey key, String fallbackMotd) {
        long started = System.nanoTime();
        try (Socket socket = new Socket()) {
            socket.connect(new java.net.InetSocketAddress(key.ip(), key.port()), Math.max(100, config.queryTimeoutMs));
            socket.setSoTimeout(Math.max(100, config.queryTimeoutMs));
            DataOutputStream out = new DataOutputStream(socket.getOutputStream());
            DataInputStream in = new DataInputStream(socket.getInputStream());
            ByteArrayOutputStream handshake = new ByteArrayOutputStream();
            DataOutputStream h = new DataOutputStream(handshake);
            writeVarInt(h, 0x00);
            writeVarInt(h, 0);
            writeString(h, key.ip());
            h.writeShort(key.port());
            writeVarInt(h, 1);
            writePacket(out, handshake.toByteArray());
            ByteArrayOutputStream request = new ByteArrayOutputStream();
            DataOutputStream r = new DataOutputStream(request);
            writeVarInt(r, 0x00);
            writePacket(out, request.toByteArray());
            readVarInt(in);
            readVarInt(in);
            String json = readString(in);
            JsonObject root = JsonParser.parseString(json).getAsJsonObject();
            JsonObject version = root.has("players") ? root : root;
            int online = root.has("players") && root.getAsJsonObject("players").has("online") ? root.getAsJsonObject("players").get("online").getAsInt() : -1;
            int max = root.has("players") && root.getAsJsonObject("players").has("max") ? root.getAsJsonObject("players").get("max").getAsInt() : -1;
            String motd = root.has("description") ? root.get("description").toString().replaceAll("\\\"text\\\":\\\"", "").replaceAll("[{}\\\"]", "").trim() : fallbackMotd;
            long latency = (System.nanoTime() - started) / 1_000_000L;
            synchronized (this) {
                LanRoomSnapshot old = rooms.get(key);
                if (old != null) rooms.put(key, new LanRoomSnapshot(old.id(), key, motd, old.favicon(), online, max, latency, System.currentTimeMillis(), LanRoomSnapshot.State.READY));
            }
        } catch (Exception ignored) {
            synchronized (this) {
                LanRoomSnapshot old = rooms.get(key);
                if (old != null) rooms.put(key, old.withState(LanRoomSnapshot.State.TIMED_OUT));
            }
        }
    }

    private static void writePacket(DataOutputStream out, byte[] payload) throws IOException {
        writeVarInt(out, payload.length);
        out.write(payload);
        out.flush();
    }
    private static void writeString(DataOutputStream out, String value) throws IOException { byte[] bytes = value.getBytes(StandardCharsets.UTF_8); writeVarInt(out, bytes.length); out.write(bytes); }
    private static String readString(DataInputStream in) throws IOException { int length = readVarInt(in); byte[] bytes = in.readNBytes(length); return new String(bytes, StandardCharsets.UTF_8); }
    private static void writeVarInt(DataOutputStream out, int value) throws IOException { do { int part = value & 0x7F; value >>>= 7; if (value != 0) part |= 0x80; out.writeByte(part); } while (value != 0); }
    private static int readVarInt(DataInputStream in) throws IOException { int value = 0, position = 0, part; do { part = in.readUnsignedByte(); value |= (part & 0x7F) << position; position += 7; if (position > 35) throw new IOException("Invalid VarInt"); } while ((part & 0x80) != 0); return value; }

    public synchronized void upsert(LanRoomSnapshot room) {
        rooms.put(room.key(), room);
    }

    public synchronized void remove(RoomKey key) { rooms.remove(key); }
    public synchronized void clear() { rooms.clear(); }
    public synchronized LanRoomSnapshot get(RoomKey key) { return rooms.get(key); }
    public synchronized List<LanRoomSnapshot> visibleRooms() {
        return rooms.values().stream()
                .filter(room -> !config.isBlocked(room.key().ip(), room.key().port(), room.motd()))
                .sorted(Comparator.comparing(LanRoomSnapshot::lastSeen).reversed())
                .toList();
    }
    public synchronized List<LanRoomSnapshot> blockedRooms() {
        return rooms.values().stream()
                .filter(room -> config.isBlocked(room.key().ip(), room.key().port(), room.motd()))
                .sorted(Comparator.comparing(LanRoomSnapshot::lastSeen).reversed())
                .toList();
    }
    public synchronized Collection<LanRoomSnapshot> allRooms() { return new ArrayList<>(rooms.values()); }
    public ExecutorService queryExecutor() { return queryExecutor; }
    @Override public void close() { queryExecutor.shutdownNow(); }
}

