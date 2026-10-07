package com.ootruffle.clienttag.norisk;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;

/**
 * Just enough CBOR to talk to NoRisk's binary socket format, mapped onto Gson trees so both
 * wire formats share one code path. Polymorphic values are written by kotlinx-serialization
 * as {@code [className, {fields}]}; these are turned into (and back from) objects with a
 * {@code type} key, the same shape the JSON format uses.
 */
final class Cbor {

    private static final int BREAK = 0xFF;

    private Cbor() {}

    // ---- Decoding ----

    static JsonElement decode(byte[] data) throws IOException {
        return new Reader(ByteBuffer.wrap(data)).read();
    }

    private static final class Reader {
        private final ByteBuffer in;

        Reader(ByteBuffer in) {
            this.in = in;
        }

        JsonElement read() throws IOException {
            final int initial = u8();
            final int major = initial >>> 5, info = initial & 0x1F;
            switch (major) {
                case 0:
                    return new JsonPrimitive(length(info));
                case 1:
                    return new JsonPrimitive(-1 - length(info));
                case 2:
                    return new JsonPrimitive(Base64.getEncoder().encodeToString(bytes(info, 2)));
                case 3:
                    return new JsonPrimitive(new String(bytes(info, 3), StandardCharsets.UTF_8));
                case 4:
                    return polymorphic(array(info));
                case 5:
                    return map(info);
                case 6:
                    length(info); // Tags carry nothing we need.
                    return read();
                default:
                    return simple(info);
            }
        }

        private JsonArray array(int info) throws IOException {
            final JsonArray array = new JsonArray();
            if (info == 31) {
                while (peek() != BREAK) {
                    array.add(read());
                }
                in.get();
            } else {
                for (long i = length(info); i > 0; i--) {
                    array.add(read());
                }
            }
            return array;
        }

        private JsonObject map(int info) throws IOException {
            final JsonObject object = new JsonObject();
            if (info == 31) {
                while (peek() != BREAK) {
                    put(object);
                }
                in.get();
            } else {
                for (long i = length(info); i > 0; i--) {
                    put(object);
                }
            }
            return object;
        }

        private void put(JsonObject object) throws IOException {
            final JsonElement key = read();
            object.add(key.isJsonPrimitive() ? key.getAsString() : key.toString(), read());
        }

        private JsonElement simple(int info) throws IOException {
            switch (info) {
                case 20:
                    return new JsonPrimitive(false);
                case 21:
                    return new JsonPrimitive(true);
                case 25:
                    return new JsonPrimitive(halfToFloat(in.getShort() & 0xFFFF));
                case 26:
                    return new JsonPrimitive(in.getFloat());
                case 27:
                    return new JsonPrimitive(in.getDouble());
                default:
                    return JsonNull.INSTANCE; // null, undefined, and unassigned simple values
            }
        }

        /** Byte or text string contents; indefinite strings are a run of definite chunks. */
        private byte[] bytes(int info, int major) throws IOException {
            if (info != 31) {
                final byte[] out = new byte[checkedLength(length(info))];
                in.get(out);
                return out;
            }
            final ByteArrayOutputStream out = new ByteArrayOutputStream();
            while (peek() != BREAK) {
                final int chunk = u8();
                if (chunk >>> 5 != major) {
                    throw new IOException("bad CBOR string chunk");
                }
                out.write(bytes(chunk & 0x1F, major));
            }
            in.get();
            return out.toByteArray();
        }

        private long length(int info) throws IOException {
            if (info < 24) {
                return info;
            }
            switch (info) {
                case 24:
                    return u8();
                case 25:
                    return in.getShort() & 0xFFFFL;
                case 26:
                    return in.getInt() & 0xFFFFFFFFL;
                case 27:
                    return in.getLong();
                default:
                    throw new IOException("bad CBOR length " + info);
            }
        }

        private int checkedLength(long length) throws IOException {
            if (length < 0 || length > in.remaining()) {
                throw new IOException("CBOR length past end of data");
            }
            return (int) length;
        }

        private int u8() {
            return in.get() & 0xFF;
        }

        private int peek() {
            return in.get(in.position()) & 0xFF;
        }
    }

    /** {@code ["some.Class", {...}]} -> {@code {"type": "some.Class", ...}}; anything else unchanged. */
    private static JsonElement polymorphic(JsonArray array) {
        if (array.size() != 2 || !array.get(1).isJsonObject() || !array.get(0).isJsonPrimitive()
                || !array.get(0).getAsJsonPrimitive().isString() || !array.get(0).getAsString().contains(".")) {
            return array;
        }
        final JsonObject object = new JsonObject();
        object.addProperty("type", array.get(0).getAsString());
        for (Map.Entry<String, JsonElement> entry : array.get(1).getAsJsonObject().entrySet()) {
            object.add(entry.getKey(), entry.getValue());
        }
        return object;
    }

    private static float halfToFloat(int half) {
        final int exponent = half >>> 10 & 0x1F, mantissa = half & 0x3FF;
        final float value = exponent == 0 ? mantissa * 0x1p-24f
                : exponent == 31 ? (mantissa == 0 ? Float.POSITIVE_INFINITY : Float.NaN)
                : (1 + mantissa / 1024f) * (float) Math.pow(2, exponent - 15);
        return (half & 0x8000) != 0 ? -value : value;
    }

    // ---- Encoding ----

    /** Encodes a Gson tree; objects with a {@code type} key are written as polymorphic pairs. */
    static byte[] encode(JsonElement element) {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        write(out, element);
        return out.toByteArray();
    }

    private static void write(ByteArrayOutputStream out, JsonElement element) {
        if (element == null || element.isJsonNull()) {
            out.write(0xF6);
        } else if (element.isJsonArray()) {
            final JsonArray array = element.getAsJsonArray();
            head(out, 4, array.size());
            for (JsonElement item : array) {
                write(out, item);
            }
        } else if (element.isJsonObject()) {
            final JsonObject object = element.getAsJsonObject();
            final JsonElement type = object.get("type");
            if (type != null && type.isJsonPrimitive()) {
                head(out, 4, 2);
                text(out, type.getAsString());
            }
            int fields = 0;
            for (Map.Entry<String, JsonElement> entry : object.entrySet()) {
                if (type == null || !entry.getKey().equals("type")) {
                    fields++;
                }
            }
            head(out, 5, fields);
            for (Map.Entry<String, JsonElement> entry : object.entrySet()) {
                if (type == null || !entry.getKey().equals("type")) {
                    text(out, entry.getKey());
                    write(out, entry.getValue());
                }
            }
        } else {
            final JsonPrimitive primitive = element.getAsJsonPrimitive();
            if (primitive.isBoolean()) {
                out.write(primitive.getAsBoolean() ? 0xF5 : 0xF4);
            } else if (primitive.isNumber()) {
                final long value = primitive.getAsLong();
                if (value >= 0) {
                    head(out, 0, value);
                } else {
                    head(out, 1, -1 - value);
                }
            } else {
                text(out, primitive.getAsString());
            }
        }
    }

    private static void text(ByteArrayOutputStream out, String s) {
        final byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
        head(out, 3, bytes.length);
        out.write(bytes, 0, bytes.length);
    }

    private static void head(ByteArrayOutputStream out, int major, long n) {
        final int type = major << 5;
        if (n < 24) {
            out.write(type | (int) n);
        } else if (n <= 0xFF) {
            out.write(type | 24);
            out.write((int) n);
        } else if (n <= 0xFFFF) {
            out.write(type | 25);
            out.write((int) (n >>> 8));
            out.write((int) n);
        } else if (n <= 0xFFFFFFFFL) {
            out.write(type | 26);
            for (int shift = 24; shift >= 0; shift -= 8) {
                out.write((int) (n >>> shift));
            }
        } else {
            out.write(type | 27);
            for (int shift = 56; shift >= 0; shift -= 8) {
                out.write((int) (n >>> shift));
            }
        }
    }

}
