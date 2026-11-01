package dev.radminlan.network;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Maps decoded pipe fields to the small data model consumed by the Minecraft UI. */
public final class RadminSnapshotParser {
    private static final int SELF_ALIAS = 0x04;
    private static final int NETWORK_NAME = 0x06;
    private static final int NETWORK_GUID = 0x09;
    private static final int NETWORK_CONTAINER = 0x23;
    private static final int MEMBER_CONTAINER = 0x24;
    private static final int PROFILE_CONTAINER = 0x22;
    private static final int TOKEN = 0x47;
    private static final int PUBLIC_IP = 0xC9;
    private static final int NODE_ID = 0xE1;
    private static final int VIRTUAL_IP = 0x05;
    private static final int NETWORK_TYPE = 0x0A;
    private static final int NETWORK_FLAGS = 0xA3;

    private RadminSnapshotParser() {}

    public static RadminBackend.RadminSnapshot parse(byte[] raw, boolean canWrite, String transport) {
        List<NetworkSeed> seeds = new ArrayList<>();
        Map<Long, Profile> profiles = new HashMap<>();
        Local local = new Local();
        for (byte[] frame : RadminWire.splitFrames(raw)) walk(RadminWire.parseFrame(frame), 0, seeds, profiles, local);
        if (seeds.isEmpty()) {
            List<RadminWire.Field> fallback = RadminWire.parseFrame(raw);
            walk(fallback, 0, seeds, profiles, local);
        }
        List<RadminBackend.Network> networks = new ArrayList<>();
        for (NetworkSeed seed : seeds) {
            List<RadminBackend.Peer> peers = new ArrayList<>();
            for (long nodeId : seed.members) {
                Profile p = profiles.getOrDefault(nodeId, new Profile(nodeId, "#" + nodeId, "", "", "", -1, -1, -1, ""));
                peers.add(new RadminBackend.Peer(p.name(), p.virtualIp(), p.virtualIpv6(), p.state(), p.latency(), p.online(),
                        p.nodeId(), p.publicIp(), p.connectionType(), seed.name, seed.memberRoles.get(nodeId), p.version()));
            }
            Long localRole = local.nodeId < 0 ? null : seed.memberRoles.get(local.nodeId);
            networks.add(new RadminBackend.Network(seed.name, seed.guid, "", peers,
                    seed.networkType, seed.enabled, localRole == null ? null : localRole == 2,
                    seed.allowPasswordless, seed.blockNewMembers, isPublicNetworkName(seed.name)));
        }
        boolean hasSnapshot = local.nodeId >= 0 || !local.name.isBlank() || !local.virtualIp.isBlank() || !networks.isEmpty();
        if (!hasSnapshot) {
            return new RadminBackend.RadminSnapshot(RadminBackend.ServiceState.ERROR,
                    "", "", List.of(), "Radmin returned no usable snapshot", canWrite, transport, null);
        }
        RadminBackend.ServiceState state = Boolean.FALSE.equals(local.power)
                ? RadminBackend.ServiceState.DISABLED : RadminBackend.ServiceState.READY;
        return new RadminBackend.RadminSnapshot(state,
                local.name, local.virtualIp, networks, "", canWrite, transport, local.power);
    }

    private static void walk(List<RadminWire.Field> fields, int depth, List<NetworkSeed> seeds,
                             Map<Long, Profile> profiles, Local local) {
        Long node = number(fields, NODE_ID);
        String alias = text(fields, SELF_ALIAS);
        // 0xC9 is the node connection/public egress address, not the virtual interface IP.
        String publicIp = text(fields, PUBLIC_IP);
        String virtualIp = ipv4(fields, VIRTUAL_IP);
        String virtualIpv6 = ipv6(fields, VIRTUAL_IP);
        if (node != null && depth <= 1) {
            local.nodeId = node;
            local.name = firstNonBlank(alias, local.name);
            local.virtualIp = firstNonBlank(virtualIp, local.virtualIp);
            Long power = scalar(fields, 0x11);
            if (power != null && (power == 0 || power == 1)) local.power = power == 1;
        }
        if (node != null && depth >= 2 && alias != null) {
            profiles.putIfAbsent(node, profile(fields, alias, publicIp, virtualIp, virtualIpv6));
        }
        for (RadminWire.Field field : fields) {
            if (!field.isContainer()) continue;
            if (field.id() == NETWORK_CONTAINER) seeds.add(parseNetwork(field.children()));
            else if (field.id() == PROFILE_CONTAINER) {
                Profile profile = parseProfile(field.children());
                if (profile.nodeId >= 0) profiles.put(profile.nodeId, profile);
            } else walk(field.children(), depth + 1, seeds, profiles, local);
        }
    }

    private static NetworkSeed parseNetwork(List<RadminWire.Field> children) {
        String name = text(children, NETWORK_NAME, "Unnamed network");
        String guid = java.util.HexFormat.of().formatHex(bytes(children, NETWORK_GUID));
        List<Long> members = new ArrayList<>();
        Map<Long, Long> memberRoles = new HashMap<>();
        for (RadminWire.Field field : children) {
            if (field.id() != MEMBER_CONTAINER || !field.isContainer()) continue;
            Long node = number(field.children(), NODE_ID);
            if (node != null) {
                members.add(node);
                Long role = scalar(field.children(), NETWORK_TYPE);
                if (role != null) memberRoles.put(node, role);
            }
            for (RadminWire.Field nested : field.children()) if (nested.isContainer()) {
                Long nestedNode = number(nested.children(), NODE_ID);
                if (nestedNode != null) {
                    members.add(nestedNode);
                    Long role = scalar(nested.children(), NETWORK_TYPE);
                    if (role != null) memberRoles.put(nestedNode, role);
                }
            }
        }
        Long flags = scalar(children, NETWORK_FLAGS);
        Boolean enabled = flags == null ? boolScalar(children, 0x08) : Boolean.valueOf((flags & 1) != 0);
        Boolean blockNewMembers = flags == null ? boolScalar(children, 0x23) : Boolean.valueOf((flags & 2) != 0);
        Boolean allowPasswordless = flags == null ? boolScalar(children, 0x3F) : Boolean.valueOf((flags & 4) != 0);
        return new NetworkSeed(name, guid, members, memberRoles, intScalar(children, NETWORK_TYPE),
                enabled, allowPasswordless, blockNewMembers);
    }

    /** Extracts the complete public-network directory returned by command 0x13. */
    public static List<String> parsePublicNetworkNames(byte[] raw, String query, Collection<String> joinedNames) {
        LinkedHashSet<String> names = new LinkedHashSet<>();
        Set<String> joined = joinedNames == null ? Set.of() : joinedNames.stream()
                .filter(name -> name != null && !name.isBlank())
                .map(name -> name.trim().toLowerCase(Locale.ROOT))
                .collect(java.util.stream.Collectors.toSet());
        List<byte[]> frames = RadminWire.splitFrames(raw == null ? new byte[0] : raw);
        if (frames.isEmpty() && raw != null && raw.length > 0) frames = List.of(raw);
        for (byte[] frame : frames) collectPublicNames(RadminWire.parseFrame(frame), names);
        String needle = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        return names.stream()
                .filter(name -> name.length() <= 128)
                .filter(name -> !joined.contains(name.toLowerCase(Locale.ROOT)))
                .filter(name -> needle.isEmpty() || name.toLowerCase(Locale.ROOT).contains(needle)
                        || fuzzySubsequence(name.toLowerCase(Locale.ROOT), needle))
                .sorted()
                .toList();
    }

    private static void collectPublicNames(List<RadminWire.Field> fields, Set<String> names) {
        for (RadminWire.Field field : fields) {
            if (field.id() == NETWORK_NAME && field.type() == 0x03 && field.stringValue() != null) {
                String name = field.stringValue();
                if (!name.isBlank() && name.codePointCount(0, name.length()) <= 128) names.add(name);
            }
            if (field.isContainer()) collectPublicNames(field.children(), names);
        }
    }

    private static boolean fuzzySubsequence(String value, String query) {
        int[] valuePoints = value.codePoints().toArray();
        int[] queryPoints = query.codePoints().toArray();
        int cursor = 0;
        for (int point : queryPoints) {
            while (cursor < valuePoints.length && valuePoints[cursor] != point) cursor++;
            if (cursor == valuePoints.length) return false;
            cursor++;
        }
        return true;
    }

    private static Long scalar(List<RadminWire.Field> fields, int id) {
        for (RadminWire.Field field : fields) {
            if (field.id() == id && (field.type() == 0x01 || field.type() == 0x0B)) return field.numberValue();
        }
        return null;
    }

    private static Integer intScalar(List<RadminWire.Field> fields, int id) {
        Long value = scalar(fields, id);
        return value == null || value > Integer.MAX_VALUE ? null : value.intValue();
    }

    private static Boolean boolScalar(List<RadminWire.Field> fields, int id) {
        Long value = scalar(fields, id);
        return value == null ? null : value != 0;
    }

    private static boolean isPublicNetworkName(String name) {
        if (name == null) return false;
        String trimmed = name.trim();
        int open = trimmed.lastIndexOf('[');
        if (open <= 0 || !trimmed.endsWith("]") || open + 2 >= trimmed.length()) return false;
        String marker = trimmed.substring(open + 1, trimmed.length() - 1);
        return marker.codePoints().anyMatch(Character::isDigit)
                && marker.codePoints().anyMatch(Character::isLetter);
    }

    private static Profile parseProfile(List<RadminWire.Field> children) {
        Long node = number(children, NODE_ID);
        String alias = text(children, SELF_ALIAS, "");
        // Keep the reported node address separate from the virtual interface addresses.
        String publicIp = text(children, PUBLIC_IP, "");
        String virtualIp = ipv4(children, VIRTUAL_IP);
        String virtualIpv6 = ipv6(children, VIRTUAL_IP);
        if (virtualIp.isBlank()) virtualIp = validIpv4(alias);
        long status = unsignedValue(number(children, 0x21, -1));
        long channel = number(children, 0x26, -1);
        long latency = unsignedValue(number(children, 0x3E, -1));
        String version = text(children, 0xED, "");
        return new Profile(node == null ? -1 : node, alias, publicIp, virtualIp, virtualIpv6, status, channel, latency, version);
    }

    private static Profile profile(List<RadminWire.Field> fields, String alias, String publicIp, String virtualIp, String virtualIpv6) {
        return new Profile(number(fields, NODE_ID, -1), alias, publicIp, virtualIp, virtualIpv6,
                unsignedValue(number(fields, 0x21, -1)), number(fields, 0x26, -1),
                unsignedValue(number(fields, 0x3E, -1)), text(fields, 0xED, ""));
    }

    private static long unsignedValue(long value) { return value == 0xFFFF_FFFFL ? -1 : value; }

    private static String text(List<RadminWire.Field> fields, int id) { return text(fields, id, null); }
    private static String text(List<RadminWire.Field> fields, int id, String fallback) {
        for (RadminWire.Field field : fields) if (field.id() == id) {
            if (field.stringValue() != null) return field.stringValue();
            if (field.bytesValue() != null) {
                String decoded = decodeStringBytes(field.bytesValue());
                if (decoded != null) return decoded;
            }
        }
        return fallback;
    }
    private static Long number(List<RadminWire.Field> fields, int id) {
        for (RadminWire.Field field : fields) if (field.id() == id && field.numberValue() != null) return field.numberValue();
        return null;
    }
    private static long number(List<RadminWire.Field> fields, int id, long fallback) {
        Long value = number(fields, id);
        return value == null ? fallback : value;
    }
    private static byte[] bytes(List<RadminWire.Field> fields, int id) {
        for (RadminWire.Field field : fields) if (field.id() == id && field.bytesValue() != null) return field.bytesValue();
        return new byte[0];
    }
    /** Field 0x05 carries either a UInt32 virtual IPv4 or a 16-byte IPv6 value. */
    private static String ipv4(List<RadminWire.Field> fields, int id) {
        for (RadminWire.Field field : fields) {
            if (field.id() != id) continue;
            Long numeric = field.numberValue();
            if (numeric != null && (field.type() == 0x01 || field.type() == 0x0B)) {
                if (numeric < 0 || numeric > 0xFFFF_FFFFL) return "";
                return validIpv4(formatIpv4(numeric));
            }
            String value = validIpv4(RadminWire.ipv4(field.bytesValue()));
            if (!value.isBlank()) return value;
        }
        return "";
    }

    private static String ipv6(List<RadminWire.Field> fields, int id) {
        for (RadminWire.Field field : fields) {
            if (field.id() != id || field.type() != 0x09) continue;
            byte[] value = field.bytesValue();
            if (value == null || value.length != 16) continue;
            try {
                java.net.Inet6Address address = java.net.Inet6Address.getByAddress(null, value, -1);
                if (!address.isAnyLocalAddress()) return address.getHostAddress();
            } catch (java.net.UnknownHostException ignored) {
                return "";
            }
        }
        return "";
    }

    private static String formatIpv4(long value) {
        return ((value >>> 24) & 0xff) + "." + ((value >>> 16) & 0xff) + "."
                + ((value >>> 8) & 0xff) + "." + (value & 0xff);
    }

    private static String validIpv4(String value) {
        if (value.isBlank()) return "";
        String[] parts = value.split("\\.");
        if (parts.length != 4) return "";
        int first = Integer.parseInt(parts[0]);
        boolean unspecified = value.equals("0.0.0.0") || value.equals("255.255.255.255");
        boolean multicast = first >= 224;
        return unspecified || multicast ? "" : value;
    }
    private static String firstNonBlank(String value, String fallback) { return value == null || value.isBlank() ? fallback : value; }
    private static String decodeStringBytes(byte[] bytes) {
        if (bytes.length < 2 || (bytes.length & 1) != 0) return null;
        int length = bytes.length;
        if (bytes[length - 2] == 0 && bytes[length - 1] == 1) length -= 2;
        String value = new String(bytes, 0, length, java.nio.charset.StandardCharsets.UTF_16BE);
        return value.isBlank() ? null : value;
    }

    private record NetworkSeed(String name, String guid, List<Long> members, Map<Long, Long> memberRoles,
                               Integer networkType, Boolean enabled, Boolean allowPasswordless,
                               Boolean blockNewMembers) {}
    private record Profile(long nodeId, String name, String publicIp, String virtualIp, String virtualIpv6,
                           long status, long channel, long latency, String version) {
        String state() { return status == 0 ? "offline" : status == 1 ? "online" : "unknown"; }
        boolean online() { return status == 1; }
        String connectionType() {
            return switch ((int) channel) {
                case 1 -> "UDP/nat";
                case 2 -> "UDP/nat/random";
                case 3 -> "TCP/nat";
                case 4 -> "Relay";
                case 5 -> "TcpRelay";
                default -> channel < 0 ? "" : "channel " + channel;
            };
        }
    }
    private static final class Local { String name = ""; String virtualIp = ""; long nodeId = -1; Boolean power; }
}

