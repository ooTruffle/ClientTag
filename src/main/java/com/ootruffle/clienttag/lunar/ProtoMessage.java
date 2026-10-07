package com.ootruffle.clienttag.lunar;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Minimal schema-less protobuf decoder. Varint and fixed64 values are stored as Long,
 * fixed32 as Integer, and length-delimited values stay raw byte[] until a caller decides
 * whether they're a string or a nested message.
 */
public final class ProtoMessage {

    public static final ProtoMessage EMPTY = new ProtoMessage();

    private final Map<Integer, List<Object>> fields = new HashMap<>();

    private ProtoMessage() {}

    public static ProtoMessage parse(byte[] buf) {
        final ProtoMessage msg = new ProtoMessage();
        final int[] pos = {0};
        while (pos[0] < buf.length) {
            final long key = readVarint(buf, pos);
            final int field = (int) (key >>> 3);
            final int wireType = (int) (key & 7);
            final Object value;
            switch (wireType) {
                case 0:
                    value = readVarint(buf, pos);
                    break;
                case 1:
                    value = readLittleEndian(buf, pos, 8);
                    break;
                case 2: {
                    final long len = readVarint(buf, pos);
                    if (len < 0 || pos[0] + len > buf.length) {
                        throw new IllegalArgumentException("truncated length-delimited field " + field);
                    }
                    final byte[] bytes = new byte[(int) len];
                    System.arraycopy(buf, pos[0], bytes, 0, bytes.length);
                    pos[0] += bytes.length;
                    value = bytes;
                    break;
                }
                case 5:
                    value = (int) readLittleEndian(buf, pos, 4);
                    break;
                default:
                    throw new IllegalArgumentException("unsupported wire type " + wireType);
            }
            msg.fields.computeIfAbsent(field, k -> new ArrayList<>()).add(value);
        }
        return msg;
    }

    public boolean has(int field) {
        return fields.containsKey(field);
    }

    public Set<Integer> fieldNumbers() {
        return Collections.unmodifiableSet(fields.keySet());
    }

    public long varint(int field, long def) {
        final Object v = first(field);
        return v instanceof Number ? ((Number) v).longValue() : def;
    }

    public byte[] bytes(int field) {
        final Object v = first(field);
        return v instanceof byte[] ? (byte[]) v : null;
    }

    public String string(int field) {
        final byte[] b = bytes(field);
        return b == null ? null : new String(b, StandardCharsets.UTF_8);
    }

    public ProtoMessage message(int field) {
        final byte[] b = bytes(field);
        return b == null ? null : parse(b);
    }

    public List<ProtoMessage> messages(int field) {
        final List<ProtoMessage> out = new ArrayList<>();
        for (Object v : fields.getOrDefault(field, Collections.emptyList())) {
            if (v instanceof byte[]) {
                out.add(parse((byte[]) v));
            }
        }
        return out;
    }

    /** Reads this message as a { 1: fixed64 msb, 2: fixed64 lsb } UUID. */
    public UUID toUuid() {
        return new UUID(varint(1, 0), varint(2, 0));
    }

    private Object first(int field) {
        final List<Object> values = fields.get(field);
        return values == null || values.isEmpty() ? null : values.get(0);
    }

    private static long readVarint(byte[] buf, int[] pos) {
        long result = 0;
        for (int shift = 0; shift < 64; shift += 7) {
            if (pos[0] >= buf.length) {
                throw new IllegalArgumentException("truncated varint");
            }
            final byte b = buf[pos[0]++];
            result |= (long) (b & 0x7F) << shift;
            if ((b & 0x80) == 0) {
                return result;
            }
        }
        throw new IllegalArgumentException("varint too long");
    }

    private static long readLittleEndian(byte[] buf, int[] pos, int size) {
        if (pos[0] + size > buf.length) {
            throw new IllegalArgumentException("truncated fixed field");
        }
        long result = 0;
        for (int i = 0; i < size; i++) {
            result |= (long) (buf[pos[0]++] & 0xFF) << (8 * i);
        }
        return result;
    }

}
