package com.ootruffle.clienttag.alpine;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

/**
 * Just enough MessagePack to talk to Alpine's socket. Its frames are bare runs of values with
 * no wrapper, so this reads and writes them one at a time, the way msgpack-core's
 * {@code MessagePacker}/{@code MessageUnpacker} do. Integers are written in their smallest
 * encoding, as msgpack-core does.
 */
final class MsgPack {

    private MsgPack() {}

    static final class Writer {
        private final ByteArrayOutputStream out = new ByteArrayOutputStream();

        Writer packLong(long v) {
            if (v >= 0) {
                if (v < 0x80) {
                    out.write((int) v);
                } else if (v < 0x100) {
                    out.write(0xCC);
                    write(v, 1);
                } else if (v < 0x10000) {
                    out.write(0xCD);
                    write(v, 2);
                } else if (v < 0x100000000L) {
                    out.write(0xCE);
                    write(v, 4);
                } else {
                    out.write(0xCF);
                    write(v, 8);
                }
            } else if (v >= -32) {
                out.write((int) v & 0xFF);
            } else if (v >= Byte.MIN_VALUE) {
                out.write(0xD0);
                write(v, 1);
            } else if (v >= Short.MIN_VALUE) {
                out.write(0xD1);
                write(v, 2);
            } else if (v >= Integer.MIN_VALUE) {
                out.write(0xD2);
                write(v, 4);
            } else {
                out.write(0xD3);
                write(v, 8);
            }
            return this;
        }

        Writer packArrayHeader(int n) {
            if (n < 16) {
                out.write(0x90 | n);
            } else if (n < 0x10000) {
                out.write(0xDC);
                write(n, 2);
            } else {
                out.write(0xDD);
                write(n, 4);
            }
            return this;
        }

        byte[] toByteArray() {
            return out.toByteArray();
        }

        /** The low {@code bytes} bytes of {@code v}, big-endian. */
        private void write(long v, int bytes) {
            for (int shift = (bytes - 1) * 8; shift >= 0; shift -= 8) {
                out.write((int) (v >>> shift) & 0xFF);
            }
        }
    }

    static final class Reader {
        private final ByteBuffer in;

        Reader(byte[] data) {
            this.in = ByteBuffer.wrap(data);
        }

        boolean hasNext() {
            return in.hasRemaining();
        }

        long unpackLong() throws IOException {
            final int b = u8();
            if (b <= 0x7F) {
                return b;
            }
            if (b >= 0xE0) {
                return (byte) b;
            }
            try {
                switch (b) {
                    case 0xCC: return u8();
                    case 0xCD: return in.getShort() & 0xFFFF;
                    case 0xCE: return in.getInt() & 0xFFFFFFFFL;
                    case 0xCF: return in.getLong();
                    case 0xD0: return in.get();
                    case 0xD1: return in.getShort();
                    case 0xD2: return in.getInt();
                    case 0xD3: return in.getLong();
                    default: throw new IOException("expected an integer, got 0x" + Integer.toHexString(b));
                }
            } catch (BufferUnderflowException e) {
                throw new IOException("truncated integer", e);
            }
        }

        int unpackInt() throws IOException {
            final long v = unpackLong();
            if (v < Integer.MIN_VALUE || v > Integer.MAX_VALUE) {
                throw new IOException("integer out of range: " + v);
            }
            return (int) v;
        }

        boolean unpackBoolean() throws IOException {
            final int b = u8();
            if (b == 0xC2 || b == 0xC3) {
                return b == 0xC3;
            }
            throw new IOException("expected a boolean, got 0x" + Integer.toHexString(b));
        }

        /** A string, or null for nil. */
        String unpackString() throws IOException {
            final int b = u8();
            final int length;
            if (b >= 0xA0 && b <= 0xBF) {
                length = b & 0x1F;
            } else if (b == 0xD9) {
                length = u8();
            } else if (b == 0xDA) {
                length = in.getShort() & 0xFFFF;
            } else if (b == 0xDB) {
                length = in.getInt();
            } else if (b == 0xC0) {
                return null;
            } else {
                throw new IOException("expected a string, got 0x" + Integer.toHexString(b));
            }
            if (length < 0 || length > in.remaining()) {
                throw new IOException("truncated string");
            }
            final byte[] bytes = new byte[length];
            in.get(bytes);
            return new String(bytes, StandardCharsets.UTF_8);
        }

        int unpackArrayHeader() throws IOException {
            final int b = u8();
            final long n;
            if (b >= 0x90 && b <= 0x9F) {
                n = b & 0x0F;
            } else if (b == 0xDC) {
                n = in.getShort() & 0xFFFF;
            } else if (b == 0xDD) {
                n = in.getInt() & 0xFFFFFFFFL;
            } else {
                throw new IOException("expected an array, got 0x" + Integer.toHexString(b));
            }
            // Every element takes at least a byte, so a bigger count can only be garbage.
            if (n > in.remaining()) {
                throw new IOException("array longer than the frame: " + n);
            }
            return (int) n;
        }

        boolean peekIsNil() throws IOException {
            return peek() == 0xC0;
        }

        boolean peekIsString() throws IOException {
            final int b = peek();
            return b >= 0xA0 && b <= 0xBF || b >= 0xD9 && b <= 0xDB;
        }

        /** Skips one value of any type, including everything inside an array or map. */
        void skipValue() throws IOException {
            long pending = 1;
            while (pending-- > 0) {
                final int b = u8();
                if (b <= 0x7F || b >= 0xE0 || b == 0xC0 || b == 0xC2 || b == 0xC3) {
                    continue;
                }
                if (b >= 0xA0 && b <= 0xBF) {
                    skip(b & 0x1F);
                } else if (b >= 0x90 && b <= 0x9F) {
                    pending += b & 0x0F;
                } else if (b >= 0x80 && b <= 0x8F) {
                    pending += 2L * (b & 0x0F);
                } else {
                    try {
                        switch (b) {
                            case 0xCC: case 0xD0: skip(1); break;
                            case 0xCD: case 0xD1: skip(2); break;
                            case 0xCE: case 0xD2: case 0xCA: skip(4); break;
                            case 0xCF: case 0xD3: case 0xCB: skip(8); break;
                            case 0xD9: case 0xC4: skip(u8()); break;
                            case 0xDA: case 0xC5: skip(in.getShort() & 0xFFFF); break;
                            case 0xDB: case 0xC6: skip(in.getInt()); break;
                            case 0xDC: pending += in.getShort() & 0xFFFF; break;
                            case 0xDD: pending += in.getInt() & 0xFFFFFFFFL; break;
                            case 0xDE: pending += 2L * (in.getShort() & 0xFFFF); break;
                            case 0xDF: pending += 2L * (in.getInt() & 0xFFFFFFFFL); break;
                            case 0xD4: skip(2); break;
                            case 0xD5: skip(3); break;
                            case 0xD6: skip(5); break;
                            case 0xD7: skip(9); break;
                            case 0xD8: skip(17); break;
                            case 0xC7: skip(u8() + 1); break;
                            case 0xC8: skip((in.getShort() & 0xFFFF) + 1); break;
                            case 0xC9: skip(in.getInt() + 1); break;
                            default: throw new IOException("unknown type 0x" + Integer.toHexString(b));
                        }
                    } catch (BufferUnderflowException e) {
                        throw new IOException("truncated value", e);
                    }
                }
                // Every value takes at least a byte, so more than are left can only be garbage.
                if (pending > in.remaining()) {
                    throw new IOException("value longer than the frame");
                }
            }
        }

        private void skip(int bytes) throws IOException {
            if (bytes < 0 || bytes > in.remaining()) {
                throw new IOException("truncated value");
            }
            in.position(in.position() + bytes);
        }

        private int peek() throws IOException {
            if (!in.hasRemaining()) {
                throw new IOException("unexpected end of frame");
            }
            return in.get(in.position()) & 0xFF;
        }

        private int u8() throws IOException {
            if (!in.hasRemaining()) {
                throw new IOException("unexpected end of frame");
            }
            return in.get() & 0xFF;
        }
    }

}
