package com.ootruffle.clienttag.labymod;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * LabyConnect's field encoding. Packet fields are written in declaration order; every
 * non-primitive field gets a presence byte ({@code 0} = null, nothing follows; {@code 1} = value
 * follows), primitives don't.
 * <ul>
 *     <li>{@code int}: VarInt; {@code long}: int64; {@code boolean}: 1 byte</li>
 *     <li>{@code String}, {@code byte[]}: int32 length + bytes (UTF-8 for strings)</li>
 *     <li>{@code UUID}: msb, lsb as int64; {@code enum}: int32 ordinal</li>
 *     <li>{@code T[]}: int32 length, then each element as {@code T}</li>
 *     <li>other objects: their fields, recursively</li>
 * </ul>
 * Only the packet's namespace and identifier are VarInt-prefixed ("McString").
 */
final class LabyCodec {

    private LabyCodec() {}

    static final class Writer {
        private final ByteArrayOutputStream out = new ByteArrayOutputStream();

        byte[] toByteArray() {
            return out.toByteArray();
        }

        void writeByte(int b) {
            out.write(b);
        }

        void writeVarInt(int value) {
            while ((value & ~0x7F) != 0) {
                out.write(value & 0x7F | 0x80);
                value >>>= 7;
            }
            out.write(value);
        }

        void writeMcString(String s) {
            final byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
            writeVarInt(bytes.length);
            out.write(bytes, 0, bytes.length);
        }

        void writePresent(boolean present) {
            out.write(present ? 1 : 0);
        }

        void writeInt32(int value) {
            out.write(value >>> 24);
            out.write(value >>> 16);
            out.write(value >>> 8);
            out.write(value);
        }

        void writeInt64(long value) {
            writeInt32((int) (value >>> 32));
            writeInt32((int) value);
        }

        /** A nullable {@code Long} field. */
        void writeLong(Long value) {
            writePresent(value != null);
            if (value != null) {
                writeInt64(value);
            }
        }

        /** A nullable {@code Boolean} field. */
        void writeBoolean(Boolean value) {
            writePresent(value != null);
            if (value != null) {
                out.write(value ? 1 : 0);
            }
        }

        void writeString(String s) {
            writePresent(s != null);
            if (s != null) {
                final byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
                writeInt32(bytes.length);
                out.write(bytes, 0, bytes.length);
            }
        }

        void writeBytes(byte[] bytes) {
            writePresent(bytes != null);
            if (bytes != null) {
                writeInt32(bytes.length);
                out.write(bytes, 0, bytes.length);
            }
        }

        void writeUuid(UUID uuid) {
            writePresent(uuid != null);
            if (uuid != null) {
                writeInt64(uuid.getMostSignificantBits());
                writeInt64(uuid.getLeastSignificantBits());
            }
        }

        void writeEnum(int ordinal) {
            writePresent(true);
            writeInt32(ordinal);
        }
    }

    /** Reads one packet's payload; malformed input throws {@link IOException}. */
    static final class Reader {
        private final ByteBuffer buf;

        Reader(byte[] payload) {
            this.buf = ByteBuffer.wrap(payload);
        }

        int readByte() throws IOException {
            try {
                return buf.get() & 0xFF;
            } catch (BufferUnderflowException e) {
                throw new IOException("packet ended early");
            }
        }

        int readVarInt() throws IOException {
            int value = 0;
            for (int shift = 0; shift < 35; shift += 7) {
                final int b = readByte();
                value |= (b & 0x7F) << shift;
                if ((b & 0x80) == 0) {
                    return value;
                }
            }
            throw new IOException("VarInt too long");
        }

        String readMcString() throws IOException {
            return utf8(checkLength(readVarInt()));
        }

        boolean readPresent() throws IOException {
            return readByte() != 0;
        }

        int readInt32() throws IOException {
            try {
                return buf.getInt();
            } catch (BufferUnderflowException e) {
                throw new IOException("packet ended early");
            }
        }

        long readInt64() throws IOException {
            try {
                return buf.getLong();
            } catch (BufferUnderflowException e) {
                throw new IOException("packet ended early");
            }
        }

        boolean readBool() throws IOException {
            return readByte() != 0;
        }

        String readString() throws IOException {
            return readPresent() ? utf8(checkLength(readInt32())) : null;
        }

        byte[] readBytes() throws IOException {
            if (!readPresent()) {
                return null;
            }
            final byte[] bytes = new byte[checkLength(readInt32())];
            buf.get(bytes);
            return bytes;
        }

        UUID readUuid() throws IOException {
            return readPresent() ? new UUID(readInt64(), readInt64()) : null;
        }

        /** An enum's ordinal, or -1 for null. */
        int readEnum() throws IOException {
            return readPresent() ? readInt32() : -1;
        }

        /** An {@code int[]} (bare VarInts), or null. */
        int[] readIntArray() throws IOException {
            if (!readPresent()) {
                return null;
            }
            final int[] values = new int[checkLength(readInt32())];
            for (int i = 0; i < values.length; i++) {
                values[i] = readVarInt();
            }
            return values;
        }

        /** The length of an object array that's present, or -1 for null. Elements follow. */
        int readArrayLength() throws IOException {
            return readPresent() ? checkLength(readInt32()) : -1;
        }

        /** Every element takes at least a byte, so a longer count than that is garbage. */
        private int checkLength(int length) throws IOException {
            if (length < 0 || length > buf.remaining()) {
                throw new IOException("bad length " + length);
            }
            return length;
        }

        private String utf8(int length) {
            final byte[] bytes = new byte[length];
            buf.get(bytes);
            return new String(bytes, StandardCharsets.UTF_8);
        }
    }

}
