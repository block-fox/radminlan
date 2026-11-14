package dev.radminlan.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public final class RadminConfig {
    public static final int MIN_REFRESH_INTERVAL_SECONDS = 1;
    public static final int MAX_REFRESH_INTERVAL_SECONDS = 60;
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path FILE = FabricLoader.getInstance().getConfigDir().resolve("radminlan.json");

    public int buttonOffsetX = 0;
    public int buttonOffsetY = 0;
    public ButtonAnchor buttonAnchor = ButtonAnchor.TITLE_BEFORE;
    public String radminExecutable = "";
    public String pipeName = "\\\\.\\pipe\\RadminVpnGuiChannel";
    public int pipeTimeoutMs = 5000;
    public boolean enableCli = true;
    public boolean autoRefresh = true;
    public int refreshIntervalSeconds = 2;
    public String networkViewMode = "LIST";
    public String peerViewMode = "LIST";
    public boolean queryLanStatus = true;
    public int queryTimeoutMs = 1000;
    public BlockMatchType defaultBlockType = BlockMatchType.IP_PORT;
    public List<BlockRule> blocked = new ArrayList<>();
    public List<PeerBlockRule> blockedPeers = new ArrayList<>();
    /** Local fallback for public-network entries that omit the enabled flag. */
    public List<String> disabledNetworks = new ArrayList<>();

    public static RadminConfig load() {
        if (!Files.exists(FILE)) return new RadminConfig();
        try (Reader reader = Files.newBufferedReader(FILE)) {
            RadminConfig loaded = GSON.fromJson(reader, RadminConfig.class);
            if (loaded == null) return new RadminConfig();
            if (loaded.blocked == null) loaded.blocked = new ArrayList<>();
            if (loaded.blockedPeers == null) loaded.blockedPeers = new ArrayList<>();
            if (loaded.disabledNetworks == null) loaded.disabledNetworks = new ArrayList<>();
            if (loaded.defaultBlockType == null) loaded.defaultBlockType = BlockMatchType.IP_PORT;
            if (loaded.buttonAnchor == null) loaded.buttonAnchor = ButtonAnchor.TITLE_BEFORE;
            // X/Y offsets were replaced by the four fixed anchors. Ignore stale
            // values from older configs so a previous large offset cannot hide the button.
            loaded.buttonOffsetX = 0;
            loaded.buttonOffsetY = 0;
            loaded.save();
            return loaded;
        } catch (Exception ignored) {
            return new RadminConfig();
        }
    }

    public void save() {
        refreshIntervalSeconds = getRefreshIntervalTicks() / 20;
        try {
            Files.createDirectories(FILE.getParent());
            try (Writer writer = Files.newBufferedWriter(FILE)) {
                GSON.toJson(this, writer);
            }
        } catch (IOException ignored) {
        }
    }

    /** Bounds hand-edited or older configuration values before scheduling work. */
    public int getRefreshIntervalTicks() {
        int seconds = Math.max(MIN_REFRESH_INTERVAL_SECONDS,
                Math.min(MAX_REFRESH_INTERVAL_SECONDS, refreshIntervalSeconds));
        return seconds * 20;
    }

    public boolean isBlocked(String ip, int port, String motd) {
        return blocked.stream().anyMatch(rule -> rule.enabled && rule.matches(ip, port, motd));
    }

    public void block(String ip, int port, String motd) {
        BlockMatchType type = defaultBlockType;
        String value = type.normalize(ip, port, motd);
        blocked.removeIf(rule -> rule.type == type && rule.value.equals(value));
        blocked.add(new BlockRule(type, value));
        save();
    }

    public void unblock(BlockRule rule) {
        blocked.remove(rule);
        save();
    }

    public boolean isPeerBlocked(long nodeId, String alias, String publicIp, String virtualIp) {
        return blockedPeers.stream().anyMatch(rule -> rule.enabled && rule.matches(nodeId, alias, publicIp, virtualIp));
    }

    public void blockPeer(long nodeId, String alias, String publicIp, String virtualIp) {
        blockedPeers.removeIf(rule -> rule.nodeId == nodeId && nodeId >= 0);
        blockedPeers.add(new PeerBlockRule(nodeId, alias, publicIp, virtualIp));
        save();
    }

    public void unblockPeer(long nodeId, String alias, String publicIp, String virtualIp) {
        blockedPeers.removeIf(rule -> rule.matches(nodeId, alias, publicIp, virtualIp));
        save();
    }

    public boolean isNetworkDisabled(String guid) {
        return guid != null && disabledNetworks.stream().anyMatch(guid::equalsIgnoreCase);
    }

    public void setNetworkDisabled(String guid, boolean disabled) {
        if (guid == null || guid.isBlank()) return;
        disabledNetworks.removeIf(guid::equalsIgnoreCase);
        if (disabled) disabledNetworks.add(guid);
        save();
    }

    /** Entry button anchors exposed by the configuration screen. */
    public enum ButtonAnchor {
        TITLE_BEFORE,
        TITLE_AFTER,
        BOTTOM_LEFT,
        BOTTOM_RIGHT,
        /** Legacy value kept so old JSON files still deserialize safely. */
        MULTIPLAYER_TITLE
    }
    public enum BlockMatchType {
        IP {
            @Override public String normalize(String ip, int port, String motd) { return ip == null ? "" : ip.trim(); }
            @Override public boolean matches(String ip, int port, String motd, String value) { return normalize(ip, port, motd).equalsIgnoreCase(value); }
        },
        IP_PORT {
            @Override public String normalize(String ip, int port, String motd) { return (ip == null ? "" : ip.trim()) + ":" + port; }
            @Override public boolean matches(String ip, int port, String motd, String value) { return normalize(ip, port, motd).equalsIgnoreCase(value); }
        },
        MOTD {
            @Override public String normalize(String ip, int port, String motd) { return normalizeMotd(motd); }
            @Override public boolean matches(String ip, int port, String motd, String value) { return normalize(ip, port, motd).equals(value); }
        };

        public abstract String normalize(String ip, int port, String motd);
        public boolean matches(String ip, int port, String motd) { return matches(ip, port, motd, normalize(ip, port, motd)); }
        public abstract boolean matches(String ip, int port, String motd, String value);
        private static String normalizeMotd(String text) { return text == null ? "" : text.replaceAll("\\s+", " ").trim(); }
    }

    public static final class BlockRule {
        public BlockMatchType type;
        public String value;
        public boolean enabled = true;
        public long createdAt = System.currentTimeMillis();
        public BlockRule(BlockMatchType type, String value) { this.type = type; this.value = value; }
        public boolean matches(String ip, int port, String motd) { return type.matches(ip, port, motd, value); }
    }

    public static final class PeerBlockRule {
        public long nodeId;
        public String alias;
        public String publicIp;
        public String virtualIp;
        public boolean enabled = true;
        public long createdAt = System.currentTimeMillis();

        public PeerBlockRule(long nodeId, String alias, String publicIp, String virtualIp) {
            this.nodeId = nodeId;
            this.alias = alias == null ? "" : alias;
            this.publicIp = publicIp == null ? "" : publicIp;
            this.virtualIp = virtualIp == null ? "" : virtualIp;
        }

        public boolean matches(long nodeId, String alias, String publicIp, String virtualIp) {
            if (this.nodeId >= 0 && nodeId >= 0) return this.nodeId == nodeId;
            return equalsIgnoreCase(this.alias, alias) || equalsIgnoreCase(this.publicIp, publicIp)
                    || equalsIgnoreCase(this.virtualIp, virtualIp);
        }

        private static boolean equalsIgnoreCase(String left, String right) {
            return left != null && !left.isBlank() && right != null && left.equalsIgnoreCase(right);
        }
    }
}
