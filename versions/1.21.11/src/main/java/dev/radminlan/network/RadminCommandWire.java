package dev.radminlan.network;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;

/**
 * Builds the control messages accepted by Radmin's GUI pipe.
 *
 * <p>The layout mirrors the verified command builders in RvpnKit: the outer
 * pipe envelope is added by {@link RadminPipeClient}, while this class returns
 * the frame body (big-endian TLV records).</p>
 */
final class RadminCommandWire {
    private static final SecureRandom RANDOM = new SecureRandom();

    private RadminCommandWire() {}

    static byte[] create(String name) {
        return create(name, "");
    }

    static byte[] create(String name, String password) {
        byte[] key;
        if (password == null || password.isEmpty()) {
            key = new byte[28];
            RANDOM.nextBytes(key);
        } else {
            key = passwordToken(password);
        }
        byte[] inner = concat(
                u32Record(0x08, 0),
                stringRecord(0x06, name),
                blobRecord(0x0d, 0x08, key));
        return concat(head(0x02), container(0x132b, inner));
    }

    static byte[] join(String name) {
        return join(name, "");
    }

    static byte[] join(String name, String password) {
        boolean passwordProtected = password != null && !password.isEmpty();
        byte[] inner = passwordProtected
                ? concat(u32Record(0x3f, 0), stringRecord(0x06, name), blobRecord(0x0d, 0x08, passwordToken(password)))
                : concat(u32Record(0x3f, 1), stringRecord(0x06, name));
        return concat(head(0x03), container(0x131b, inner));
    }

    static byte[] enumPublicNetworks() {
        return enumPublicNetworks("");
    }

    static byte[] enumPublicNetworks(String prefix) {
        return concat(head(0x13), stringRecord(0x06, prefix == null ? "" : prefix));
    }

    static byte[] setPower(boolean enabled) {
        return concat(head(0x04), u32Record(0x11, enabled ? 1 : 0));
    }

    static byte[] setAlias(String alias) {
        return concat(head(0x12), stringRecord(0x04, alias));
    }

    static byte[] leave(String guid) {
        return concat(head(0x05), guidRecord(parseGuid(guid)));
    }

    static byte[] delete(String guid) {
        return concat(head(0x0f), guidRecord(parseGuid(guid)));
    }

    static byte[] setEnabled(String guid, boolean enabled) {
        return concat(head(0x08), guidRecord(parseGuid(guid)), u32Record(0x16, enabled ? 1 : 0));
    }

    static byte[] kick(String guid, long nodeId, String networkName) {
        return memberOperation(0x06, guid, nodeId, networkName);
    }

    static byte[] approve(String guid, long nodeId, String networkName) {
        return memberOperation(0x0a, guid, nodeId, networkName);
    }

    static byte[] memberOperation(int command, String guid, long nodeId, String networkName) {
        return concat(head(command), guidRecord(parseGuid(guid)), nodeRecord(nodeId), stringRecord(0x06, networkName));
    }

    private static byte[] head(int command) {
        int requestId = 1 + RANDOM.nextInt(0xffff);
        return concat(standardU32Record(0x4a, requestId), standardU32Record(0x00, command));
    }

    private static byte[] stringRecord(int field, String value) {
        byte[] text = (value == null ? "" : value).getBytes(StandardCharsets.UTF_16BE);
        byte[] payload = concat(text, new byte[] {0, 1});
        return record(payload.length, 0x03, field, payload);
    }

    private static byte[] u32Record(int field, int value) {
        byte[] payload = new byte[] {
                (byte) (value >>> 24), (byte) (value >>> 16), (byte) (value >>> 8), (byte) value
        };
        return record(4, 0x0b, field, payload);
    }

    private static byte[] standardU32Record(int field, int value) {
        byte[] payload = new byte[] {
                (byte) (value >>> 24), (byte) (value >>> 16), (byte) (value >>> 8), (byte) value
        };
        return record(4, 0x01, field, payload);
    }

    private static byte[] guidRecord(byte[] guid) {
        return record(16, 0x0d, 0x09, guid);
    }

    private static byte[] blobRecord(int field, int type, byte[] payload) {
        return record(payload.length, type, field, payload);
    }

    private static byte[] passwordToken(String password) {
        byte[] encoded = password.getBytes(StandardCharsets.UTF_16LE);
        byte[] padded = new byte[encoded.length + 2];
        System.arraycopy(encoded, 0, padded, 0, encoded.length);
        byte[] random = new byte[padded.length];
        RANDOM.nextBytes(random);
        byte[] token = new byte[random.length * 2];
        for (int i = 0; i < random.length; i++) token[i] = (byte) (random[i] ^ padded[i]);
        System.arraycopy(random, 0, token, random.length, random.length);
        return token;
    }

    private static byte[] nodeRecord(long nodeId) {
        byte[] payload = new byte[8];
        for (int i = 7; i >= 0; i--) {
            payload[i] = (byte) nodeId;
            nodeId >>>= 8;
        }
        return recordWithTag(8, 0x02, 0x01, 0xe1, payload);
    }

    private static byte[] record(int size, int type, int field, byte[] payload) {
        return recordWithTag(size, type, 0x03, field, payload);
    }

    private static byte[] recordWithTag(int size, int type, int tag, int field, byte[] payload) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(8 + payload.length);
        writeU32(out, size);
        out.write(type & 0xff);
        out.write(0);
        out.write(tag & 0xff);
        out.write(field & 0xff);
        out.writeBytes(payload);
        return out.toByteArray();
    }

    private static byte[] container(int tag, byte[] inner) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(8 + inner.length);
        writeU32(out, inner.length);
        writeU32(out, tag);
        out.writeBytes(inner);
        return out.toByteArray();
    }

    private static void writeU32(ByteArrayOutputStream out, int value) {
        out.write((value >>> 24) & 0xff);
        out.write((value >>> 16) & 0xff);
        out.write((value >>> 8) & 0xff);
        out.write(value & 0xff);
    }

    private static byte[] parseGuid(String value) {
        if (value == null) throw new IllegalArgumentException("Network GUID is required");
        String hex = value.replace("-", "").replace(" ", "");
        if (hex.length() != 32) throw new IllegalArgumentException("Network GUID must contain 16 bytes");
        try {
            return java.util.HexFormat.of().parseHex(hex);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid network GUID", e);
        }
    }

    private static byte[] concat(byte[]... parts) {
        int size = 0;
        for (byte[] part : parts) size += part.length;
        byte[] result = new byte[size];
        int offset = 0;
        for (byte[] part : parts) {
            System.arraycopy(part, 0, result, offset, part.length);
            offset += part.length;
        }
        return result;
    }
}
