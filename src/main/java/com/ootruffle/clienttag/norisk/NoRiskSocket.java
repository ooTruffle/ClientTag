package com.ootruffle.clienttag.norisk;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Collection;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * NoRisk's core websocket (wss://api.norisk.gg/api/v1/core/ws). Everything nametag-related
 * runs on the {@code nrc_core:core} channel, whose payload is a {@code CoreFrame}
 * {@code {rpcId, packet}}:
 * <ul>
 *     <li>C->S {@code NrcSubscriptionUpdate {uuids, subscribe}} adds or removes watched players</li>
 *     <li>S->C {@code MinimalBatchEvent {events}} right after subscribing, then
 *         {@code MinimalChangedEvent {uuid, minimal}} live; a null {@code minimal} means not on NoRisk.
 *         So does a record without the required {@code uuid}/{@code ign} - the server sends those
 *         for non-users too.</li>
 * </ul>
 * Frames are binary by default, as the real client sends them:
 * {@code u16 channelLength, channel, i32 cborLength, CBOR(CoreFrame)}. Starting the game with
 * {@code -Dclienttag.norisk.format=text} switches to the text form
 * {@code "<channel> <jsonLength> <json> "} instead. Pings are answered by the JDK.
 * <p>
 * Layout taken from NoRiskClient build 26.3.x.
 */
public final class NoRiskSocket implements WebSocket.Listener {

    private static final Logger LOGGER = LogManager.getLogger("ClientTag");
    private static final AtomicInteger loggedEmptyRecords = new AtomicInteger();

    private static final String SOCKET_URL = "wss://api.norisk.gg:443/api/v1/core/ws";
    private static final String CHANNEL = "nrc_core:core";
    private static final String PACKAGE = "gg.norisk.networking.model.core.";
    private static final boolean BINARY = !"text".equalsIgnoreCase(System.getProperty("clienttag.norisk.format"));
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    /** Called on the socket's own thread; implementations hand off to their own. */
    public interface Listener {
        /** A player's {@code NoRiskUserMinimal}, or null if they aren't (or are no longer) an online NoRisk user. */
        void onMinimal(UUID player, JsonObject minimal);
    }

    private final Listener listener;
    private final StringBuilder partialText = new StringBuilder();
    private final ByteArrayOutputStream partialBinary = new ByteArrayOutputStream();
    private final CompletableFuture<String> closed = new CompletableFuture<>();
    private CompletableFuture<WebSocket> sendChain;
    private volatile WebSocket ws;

    private NoRiskSocket(Listener listener) {
        this.listener = listener;
    }

    public static NoRiskSocket connect(String jwt, UUID self, String name, Listener listener) throws IOException {
        final NoRiskSocket socket = new NoRiskSocket(listener);
        String url = SOCKET_URL + "?uuid=" + self + "&ign=" + NoRiskApi.encode(name)
                + "&broker=nats&dist=" + NoRiskApi.encode(System.getProperty("norisk.pack", "dev"));
        if (BINARY) {
            url += "&format=binary";
        }
        try {
            HTTP.newWebSocketBuilder()
                    .connectTimeout(Duration.ofSeconds(10))
                    .header("Authorization", "Bearer " + jwt)
                    .header("User-Agent", NoRiskApi.USER_AGENT)
                    .buildAsync(URI.create(url), socket)
                    .get(15, TimeUnit.SECONDS);
        } catch (Exception e) {
            // The URL has the player's name in it, so only the endpoint gets logged.
            throw new IOException("couldn't open " + SOCKET_URL + ": " + e, e);
        }
        return socket;
    }

    public boolean isOpen() {
        return !closed.isDone();
    }

    public String closeReason() {
        return closed.getNow(null);
    }

    public void close() {
        if (ws != null && isOpen()) {
            ws.sendClose(WebSocket.NORMAL_CLOSURE, "").whenComplete((w, e) -> ws.abort());
        }
        closed.complete("closed locally");
    }

    /** Adds ({@code subscribe}) or removes players from the watched set; the server caps it at 150. */
    public void updateSubscription(Collection<UUID> players, boolean subscribe) {
        final JsonArray uuids = new JsonArray();
        for (UUID uuid : players) {
            uuids.add(new JsonPrimitive(uuid.toString()));
        }
        final JsonObject packet = new JsonObject();
        packet.addProperty("type", PACKAGE + "NrcSubscriptionUpdate");
        packet.add("uuids", uuids);
        packet.addProperty("subscribe", subscribe);
        final JsonObject frame = new JsonObject();
        frame.add("rpcId", JsonNull.INSTANCE);
        frame.add("packet", packet);
        send(frame);
    }

    private synchronized void send(JsonObject frame) {
        if (BINARY) {
            final byte[] channel = CHANNEL.getBytes(StandardCharsets.UTF_8);
            final byte[] cbor = Cbor.encode(frame);
            final ByteBuffer buffer = ByteBuffer.allocate(2 + channel.length + 4 + cbor.length);
            buffer.putShort((short) channel.length).put(channel).putInt(cbor.length).put(cbor).flip();
            // JDK WebSockets reject overlapping sends, so each send waits for the previous one.
            sendChain = sendChain.thenCompose(w -> w.sendBinary(buffer, true));
        } else {
            final String json = frame.toString();
            final String text = CHANNEL + " " + json.length() + " " + json + " ";
            sendChain = sendChain.thenCompose(w -> w.sendText(text, true));
        }
    }

    /** Binary messages: one or more {@code u16 channelLength, channel, i32 length, CBOR} payloads. */
    private void onBinaryMessage(byte[] data) throws IOException {
        final ByteBuffer in = ByteBuffer.wrap(data);
        while (in.remaining() >= 6) {
            final byte[] channel = new byte[in.getShort() & 0xFFFF];
            in.get(channel);
            final int length = in.getInt();
            if (length < 0 || length > in.remaining()) {
                return;
            }
            final byte[] payload = new byte[length];
            in.get(payload);
            if (CHANNEL.equals(new String(channel, StandardCharsets.UTF_8))) {
                onFrame(Cbor.decode(payload));
            }
        }
    }

    /** Text messages: one or more {@code "<channel> <jsonLength> <json> "} payloads. */
    private void onTextMessage(String text) {
        int pos = 0;
        while (pos < text.length()) {
            final int channelEnd = text.indexOf(' ', pos);
            final int lengthEnd = channelEnd < 0 ? -1 : text.indexOf(' ', channelEnd + 1);
            if (lengthEnd < 0) {
                return;
            }
            final int length = Integer.parseInt(text.substring(channelEnd + 1, lengthEnd));
            final int jsonEnd = Math.min(text.length(), lengthEnd + 1 + length);
            if (CHANNEL.equals(text.substring(pos, channelEnd))) {
                onFrame(parse(text.substring(lengthEnd + 1, jsonEnd)));
            }
            pos = jsonEnd + 1; // the trailing space
        }
    }

    private void onFrame(JsonElement frame) {
        if (frame == null || !frame.isJsonObject() || !frame.getAsJsonObject().has("packet")
                || !frame.getAsJsonObject().get("packet").isJsonObject()) {
            return;
        }
        final JsonObject packet = frame.getAsJsonObject().getAsJsonObject("packet");
        final String type = string(packet, "type");
        if ((PACKAGE + "MinimalChangedEvent").equals(type)) {
            onChanged(packet);
        } else if ((PACKAGE + "MinimalBatchEvent").equals(type) && packet.has("events") && packet.get("events").isJsonArray()) {
            for (JsonElement event : packet.getAsJsonArray("events")) {
                if (event.isJsonObject()) {
                    onChanged(event.getAsJsonObject());
                }
            }
        }
    }

    private void onChanged(JsonObject event) {
        final UUID uuid = parseUuid(string(event, "uuid"));
        if (uuid != null) {
            final JsonElement minimal = event.get("minimal");
            if (minimal == null || !minimal.isJsonObject()) {
                listener.onMinimal(uuid, null);
            } else if (!isUserRecord(minimal.getAsJsonObject())) {
                if (loggedEmptyRecords.getAndIncrement() < 3) {
                    LOGGER.debug("NoRisk sent a record with no user in it, treating as not on NoRisk: {}", minimal);
                }
                listener.onMinimal(uuid, null);
            } else {
                listener.onMinimal(uuid, minimal.getAsJsonObject());
            }
        }
    }

    @Override
    public void onOpen(WebSocket webSocket) {
        // Set up here rather than after buildAsync returns: frames can arrive before that.
        synchronized (this) {
            ws = webSocket;
            sendChain = CompletableFuture.completedFuture(webSocket);
        }
        webSocket.request(1);
    }

    @Override
    public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
        partialText.append(data);
        if (last) {
            final String message = partialText.toString();
            partialText.setLength(0);
            try {
                onTextMessage(message);
            } catch (RuntimeException ignored) {
                // A malformed frame shouldn't take the socket down.
            }
        }
        webSocket.request(1);
        return null;
    }

    @Override
    public CompletionStage<?> onBinary(WebSocket webSocket, ByteBuffer data, boolean last) {
        final byte[] chunk = new byte[data.remaining()];
        data.get(chunk);
        partialBinary.write(chunk, 0, chunk.length);
        if (last) {
            final byte[] message = partialBinary.toByteArray();
            partialBinary.reset();
            try {
                onBinaryMessage(message);
            } catch (IOException | RuntimeException ignored) {
                // A malformed frame shouldn't take the socket down.
            }
        }
        webSocket.request(1);
        return null;
    }

    @Override
    public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
        closed.complete(statusCode + " " + reason);
        return null;
    }

    @Override
    public void onError(WebSocket webSocket, Throwable error) {
        closed.complete(String.valueOf(error));
    }

    /** Whether a {@code NoRiskUserMinimal} is a real user: {@code uuid} and {@code ign} are required fields. */
    public static boolean isUserRecord(JsonObject minimal) {
        return uuid(minimal, "uuid") != null && string(minimal, "ign") != null;
    }

    /** A UUID field of a record (dashes optional), or null if it's missing or malformed. */
    public static UUID uuid(JsonObject obj, String key) {
        return parseUuid(string(obj, key));
    }

    static UUID parseUuid(String s) {
        if (s == null) {
            return null;
        }
        try {
            return s.length() == 32
                    ? UUID.fromString(s.replaceFirst("(\\w{8})(\\w{4})(\\w{4})(\\w{4})(\\w{12})", "$1-$2-$3-$4-$5"))
                    : UUID.fromString(s);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    // Minecraft 1.8.9 ships Gson 2.2.4, so only its API is used here.

    static JsonElement parse(String text) {
        try {
            return new JsonParser().parse(text);
        } catch (RuntimeException e) {
            return null;
        }
    }

    public static String string(JsonObject obj, String key) {
        if (obj == null) {
            return null;
        }
        final JsonElement element = obj.get(key);
        return element != null && element.isJsonPrimitive() ? element.getAsString() : null;
    }

}
