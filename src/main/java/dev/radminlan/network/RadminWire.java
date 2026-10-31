package dev.radminlan.network;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Independent decoder for Radmin VPN's length-delimited control stream. */
public final class RadminWire {
    public static final int MAX_FRAME_SIZE = 64 * 1024 * 1024;
    private RadminWire() {}

    public static List<byte[]> splitFrames(byte[] bytes) {
        List<byte[]> frames = new ArrayList<>();
        int offset = 0;
        while (offset + 4 <= bytes.length) {
            int length = readIntLE(bytes, offset);
            if (length <= 0 || length > MAX_FRAME_SIZE || offset + 4L + length > bytes.length) break;
            frames.add(Arrays.copyOfRange(bytes, offset + 4, offset + 4 + length));
            offset += 4 + length;
        }
        return frames;
    }

    public static List<Field> parseFrame(byte[] body) {
        List<Field> fields = new ArrayList<>();
        int offset = 0;
        while (offset + 8 <= body.length) {
            int next = tryRead(body, offset, fields);
            if (next <= offset) break;
            offset = next;
        }
        return fields;
    }

    private static int tryRead(byte[] body, int offset, List<Field> sink) {
        if (offset + 8 > body.length) return -1;
        int size = readIntBE(body, offset);
        int type = body[offset + 4] & 0xff;
        int tag = body[offset + 6] & 0xff;
        int id = body[offset + 7] & 0xff;
        if (size < 0 || size > MAX_FRAME_SIZE || offset + 8L + size > body.length) return -1;
        byte[] payload = Arrays.copyOfRange(body, offset + 8, offset + 8 + size);
        if (type == 0 && tag == 0x13) {
            sink.add(Field.container(id, type, tag, parseFrame(payload)));
        } else if (type == 0x03) {
            String value = decodeString(payload);
            sink.add(Field.scalar(id, type, tag, value == null ? payload.clone() : value));
        } else if (type == 0x01 || type == 0x0b) {
            sink.add(Field.scalar(id, type, tag, readUnsignedBigEndian(payload)));
        } else if (type == 0x02 || type == 0x08 || type == 0x09 || type == 0x0d || type == 0x10) {
            sink.add(Field.scalar(id, type, tag, payload.clone()));
        } else {
            sink.add(Field.scalar(id, type, tag, payload.clone()));
        }
        return offset + 8 + size;
    }

    private static String decodeString(byte[] payload) {
        if (payload.length < 2) return null;
        int length = payload.length;
        if ((payload.length & 1) == 0 && payload[length - 2] == 0 && payload[length - 1] == 1) length -= 2;
        if ((length & 1) != 0) return null;
        if (length <= 0) return null;
        String value = new String(payload, 0, length, StandardCharsets.UTF_16BE);
        if (value.isBlank()) return null;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (Character.isISOControl(c) && c != '\t' && c != '\r' && c != '\n') return null;
        }
        return value;
    }

    private static long readUnsignedBigEndian(byte[] payload) {
        if (payload.length == 0 || payload.length > 8) return 0;
        long value = 0;
        for (byte b : payload) value = (value << 8) | (b & 0xffL);
        return value;
    }

    public static int readIntBE(byte[] b, int o) { return ByteBuffer.wrap(b, o, 4).order(ByteOrder.BIG_ENDIAN).getInt(); }
    public static int readIntLE(byte[] b, int o) { return ByteBuffer.wrap(b, o, 4).order(ByteOrder.LITTLE_ENDIAN).getInt(); }

    public static String ipv4(byte[] value) {
        if (value == null) return "";
        int offset;
        if (value.length == 4) {
            offset = 0;
        } else if (value.length == 16) {
            // Radmin uses both raw IPv4 and IPv4-mapped/zero-padded 16-byte values.
            boolean mapped = true;
            for (int i = 0; i < 10; i++) if (value[i] != 0) mapped = false;
            if (!(mapped && (value[10] == 0 || (value[10] == (byte) 0xff && value[11] == (byte) 0xff)))) return "";
            offset = 12;
        } else {
            return "";
        }
        return (value[offset] & 0xff) + "." + (value[offset + 1] & 0xff) + "." + (value[offset + 2] & 0xff) + "." + (value[offset + 3] & 0xff);
    }

    public record Field(int id, int type, int tag, Object value, List<Field> children) {
        public static Field scalar(int id, int type, int tag, Object value) { return new Field(id, type, tag, value, List.of()); }
        public static Field container(int id, int type, int tag, List<Field> children) { return new Field(id, type, tag, null, List.copyOf(children)); }
        public boolean isContainer() { return !children.isEmpty(); }
        public String stringValue() { return value instanceof String s ? s : null; }
        public Long numberValue() {
            if (value instanceof Number n) return n.longValue();
            if (value instanceof byte[] bytes && bytes.length > 0 && bytes.length <= 8) {
                long result = 0;
                for (byte b : bytes) result = (result << 8) | (b & 0xffL);
                return result;
            }
            return null;
        }
        public byte[] bytesValue() { return value instanceof byte[] b ? b : null; }
    }
}

