package com.ootruffle.clienttag.lunar;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * Minimal schema-less protobuf encoder - just enough for the handful of messages the
 * nametag lookup needs. Fields are written in call order.
 */
public final class ProtoWriter {

    private final ByteArrayOutputStream out = new ByteArrayOutputStream();

    public ProtoWriter varint(int field, long value) {
        tag(field, 0);
        writeVarint(value);
        return this;
    }

    public ProtoWriter fixed64(int field, long value) {
        tag(field, 1);
        for (int i = 0; i < 8; i++) {
            out.write((int) (value >>> (8 * i)));
        }
        return this;
    }

    public ProtoWriter bytes(int field, byte[] value) {
        tag(field, 2);
        writeVarint(value.length);
        out.write(value, 0, value.length);
        return this;
    }

    public ProtoWriter string(int field, String value) {
        return bytes(field, value.getBytes(StandardCharsets.UTF_8));
    }

    public ProtoWriter message(int field, ProtoWriter value) {
        return bytes(field, value.toByteArray());
    }

    public byte[] toByteArray() {
        return out.toByteArray();
    }

    /** UUIDs travel as { 1: fixed64 most significant bits, 2: fixed64 least significant bits }. */
    public static ProtoWriter uuid(UUID uuid) {
        return new ProtoWriter()
                .fixed64(1, uuid.getMostSignificantBits())
                .fixed64(2, uuid.getLeastSignificantBits());
    }

    private void tag(int field, int wireType) {
        writeVarint(((long) field << 3) | wireType);
    }

    private void writeVarint(long value) {
        while ((value & ~0x7FL) != 0) {
            out.write((int) ((value & 0x7F) | 0x80));
            value >>>= 7;
        }
        out.write((int) value);
    }

}
