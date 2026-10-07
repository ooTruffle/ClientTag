package com.ootruffle.clienttag.essential;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.net.http.WebSocketHandshakeException;
import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;

/**
 * Essential's connection manager socket (wss://connect.essential.gg/v1), used only to read
 * which players have something equipped in the {@code ICON} cosmetic slot - that's what
 * Essential draws as its nametag indicator.
 * <p>
 * Every message is a binary frame of big-endian fields:
 * {@code int32 typeId, int32 len + UTF-8 packet ID, int32 len + UTF-8 JSON body}.
 * Type IDs are assigned per connection and per direction: ID 0 is always the register
 * packet ({@code {"a": name, "b": id}}), and each side registers a packet name before it
 * first sends one. Names are class paths below Essential's packet package.
 * <ul>
 *     <li>C->S {@code SubscriptionUpdatePacket} {a: uuids, b: unsubscribe all, c: subscribe (false = unsubscribe)}</li>
 *     <li>C->S {@code ClientCosmeticOutfitSelectedRequestPacket} {a: uuid}</li>
 *     <li>S->C {@code ServerCosmeticOutfitSelectedResponsePacket} {uuid, equippedCosmetics: slot -> id, ...}</li>
 *     <li>S->C {@code ServerCosmeticsUserEquippedPacket} {a: uuid, b: slot -> id}, pushed on outfit changes</li>
 *     <li>{@code ConnectionKeepAlivePacket} {} is echoed back with the same packet ID</li>
 * </ul>
 * Layout written from a protocol write-up of Essential 1.5.0.1 (protocol version 10).
 */
public final class EssentialSocket implements WebSocket.Listener {

    private static final String SOCKET_URL = "wss://connect.essential.gg/v1";
    private static final String MOD_VERSION = "1.5.0.1";
    private static final String MAX_PROTOCOL_VERSION = "11";

    /** WebSocket close code for a suspended account. */
    public static final int CLOSE_SUSPENDED = 4008;

    private static final String REGISTER = "connection.ConnectionRegisterPacketTypeIdPacket";
    private static final String KEEP_ALIVE = "connection.ConnectionKeepAlivePacket";
    private static final String SUBSCRIPTION_UPDATE = "subscription.SubscriptionUpdatePacket";
    private static final String OUTFIT_REQUEST = "cosmetic.outfit.ClientCosmeticOutfitSelectedRequestPacket";
    private static final String OUTFIT_RESPONSE = "cosmetic.outfit.ServerCosmeticOutfitSelectedResponsePacket";
    private static final String USER_EQUIPPED = "cosmetic.ServerCosmeticsUserEquippedPacket";

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    /** Called on the socket's own thread; implementations hand off to their own. */
    public interface Listener {
        /** A player's equipped cosmetics (slot -> cosmetic ID), empty if they have none. */
        void onEquipped(UUID player, Map<String, String> equipped);
    }

    /** The upgrade was refused with HTTP 404/410: Essential considers this client version outdated. */
    public static final class OutdatedException extends IOException {
        OutdatedException(int status) {
            super("connection manager rejected client version (HTTP " + status + ")");
        }
    }

    private final Listener listener;
    private final ByteArrayOutputStream partial = new ByteArrayOutputStream();
    private final CompletableFuture<String> closed = new CompletableFuture<>();
    // Guarded by this.
    private final Map<String, Integer> outIds = new HashMap<>();
    // Socket thread only.
    private final Map<Integer, String> inIds = new HashMap<>();
    private CompletableFuture<WebSocket> sendChain;
    private volatile WebSocket ws;
    private volatile int closeCode;

    private EssentialSocket(Listener listener) {
        this.listener = listener;
        outIds.put(REGISTER, 0);
        inIds.put(0, REGISTER);
    }

    /** {@code authorization} comes from {@link EssentialAuthenticator#authorize}, just before this. */
    public static EssentialSocket connect(UUID uuid, String name, String authorization, Listener listener) throws IOException {
        final EssentialSocket socket = new EssentialSocket(listener);
        final WebSocket.Builder builder = HTTP.newWebSocketBuilder().connectTimeout(Duration.ofSeconds(10));
        builder.header("Authorization", authorization);
        builder.header("Essential-Max-Protocol-Version", MAX_PROTOCOL_VERSION);
        builder.header("Essential-User-UUID", uuid.toString());
        builder.header("Essential-User-Name", name);
        builder.header("Essential-Mod-Version", MOD_VERSION);
        builder.header("Essential-Minecraft-Version", "1.8.9");
        builder.header("Essential-Minecraft-Modloader", "FORGE");
        try {
            builder.buildAsync(URI.create(SOCKET_URL), socket).get(15, TimeUnit.SECONDS);
        } catch (Exception e) {
            final Throwable cause = e.getCause();
            if (cause instanceof WebSocketHandshakeException) {
                final int status = ((WebSocketHandshakeException) cause).getResponse().statusCode();
                if (status == 404 || status == 410) {
                    throw new OutdatedException(status);
                }
                throw new IOException("couldn't open " + SOCKET_URL + ": HTTP " + status, e);
            }
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

    /** The close code the server sent, or 0 if it didn't send one (yet). */
    public int closeCode() {
        return closeCode;
    }

    public void close() {
        if (ws != null && isOpen()) {
            ws.sendClose(WebSocket.NORMAL_CLOSURE, "").whenComplete((w, e) -> ws.abort());
        }
        closed.complete("closed locally");
    }

    /** Subscribes to (or, with {@code subscribe} false, unsubscribes from) the players' cosmetic updates. */
    public void updateSubscription(Collection<UUID> players, boolean subscribe) {
        final JsonArray list = new JsonArray();
        for (UUID uuid : players) {
            list.add(new JsonPrimitive(uuid.toString()));
        }
        final JsonObject body = new JsonObject();
        body.add("a", list);
        body.addProperty("b", false);
        body.addProperty("c", subscribe);
        send(SUBSCRIPTION_UPDATE, UUID.randomUUID().toString(), body);
    }

    /** Asks for a subscribed player's current outfit; answered through {@link Listener#onEquipped}. */
    public void requestOutfit(UUID player) {
        final JsonObject body = new JsonObject();
        body.addProperty("a", player.toString());
        send(OUTFIT_REQUEST, UUID.randomUUID().toString(), body);
    }

    /**
     * Sends one packet, registering its type first if this connection hasn't sent it before.
     * JDK WebSockets reject overlapping sends, so each send waits for the previous one.
     */
    private synchronized void send(String name, String packetId, JsonObject body) {
        Integer id = outIds.get(name);
        if (id == null) {
            id = outIds.size();
            outIds.put(name, id);
            final JsonObject register = new JsonObject();
            register.addProperty("a", name);
            register.addProperty("b", id);
            sendFrame(0, "", register);
        }
        sendFrame(id, packetId, body);
    }

    private void sendFrame(int typeId, String packetId, JsonObject body) {
        final byte[] idBytes = packetId.getBytes(StandardCharsets.UTF_8);
        final byte[] bodyBytes = body.toString().getBytes(StandardCharsets.UTF_8);
        final ByteBuffer frame = ByteBuffer.allocate(12 + idBytes.length + bodyBytes.length);
        frame.putInt(typeId).putInt(idBytes.length).put(idBytes).putInt(bodyBytes.length).put(bodyBytes).flip();
        sendChain = sendChain.thenCompose(w -> w.sendBinary(frame, true));
    }

    private void onMessage(byte[] message) {
        final ByteBuffer buf = ByteBuffer.wrap(message);
        final int typeId;
        final String packetId;
        final JsonObject body;
        try {
            typeId = buf.getInt();
            packetId = readString(buf);
            body = parseObject(readString(buf));
        } catch (BufferUnderflowException | IllegalArgumentException e) {
            return;
        }
        final String name = inIds.get(typeId);
        if (name == null || body == null) {
            return;
        }
        switch (name) {
            case REGISTER: {
                final String registered = string(body, "a");
                final JsonElement registeredId = body.get("b");
                if (registered != null && registeredId != null && registeredId.isJsonPrimitive()) {
                    inIds.put(registeredId.getAsInt(), registered);
                }
                break;
            }
            case KEEP_ALIVE:
                send(KEEP_ALIVE, packetId, new JsonObject());
                break;
            case OUTFIT_RESPONSE:
                onEquipped(string(body, "uuid"), body.get("equippedCosmetics"));
                break;
            case USER_EQUIPPED:
                onEquipped(string(body, "a"), body.get("b"));
                break;
            default:
                // Friends, chat, cosmetic catalog and everything else - nothing to do with the indicator.
                break;
        }
    }

    private void onEquipped(String player, JsonElement slots) {
        final UUID uuid = parseUuid(player);
        if (uuid == null) {
            return;
        }
        final Map<String, String> equipped = new HashMap<>();
        if (slots != null && slots.isJsonObject()) {
            for (Map.Entry<String, JsonElement> slot : slots.getAsJsonObject().entrySet()) {
                if (slot.getValue().isJsonPrimitive()) {
                    equipped.put(slot.getKey(), slot.getValue().getAsString());
                }
            }
        }
        listener.onEquipped(uuid, equipped);
    }

    private static String readString(ByteBuffer buf) {
        final int length = buf.getInt();
        if (length < 0 || length > buf.remaining()) {
            throw new IllegalArgumentException("bad string length " + length);
        }
        final byte[] bytes = new byte[length];
        buf.get(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
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
    public CompletionStage<?> onBinary(WebSocket webSocket, ByteBuffer data, boolean last) {
        final byte[] chunk = new byte[data.remaining()];
        data.get(chunk);
        partial.write(chunk, 0, chunk.length);
        if (last) {
            final byte[] message = partial.toByteArray();
            partial.reset();
            try {
                onMessage(message);
            } catch (RuntimeException ignored) {
                // A malformed frame shouldn't take the socket down.
            }
        }
        webSocket.request(1);
        return null;
    }

    @Override
    public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
        webSocket.request(1);
        return null;
    }

    @Override
    public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
        closeCode = statusCode;
        closed.complete(statusCode + " " + reason);
        return null;
    }

    @Override
    public void onError(WebSocket webSocket, Throwable error) {
        closed.complete(String.valueOf(error));
    }

    private static UUID parseUuid(String s) {
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

    private static JsonObject parseObject(String text) {
        try {
            final JsonElement parsed = new JsonParser().parse(text);
            return parsed != null && parsed.isJsonObject() ? parsed.getAsJsonObject() : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String string(JsonObject obj, String key) {
        final JsonElement element = obj.get(key);
        return element != null && element.isJsonPrimitive() ? element.getAsString() : null;
    }

}
