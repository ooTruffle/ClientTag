package com.ootruffle.clienttag.oneconfig;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;

/**
 * PolyPlus's websocket (wss://plus.polyfrost.org/websocket), used only for player presence.
 * All frames are JSON text tagged with a {@code type} field:
 * <ul>
 *     <li>C->S {@code SubscribePlayers} { players, request_id } and {@code UnsubscribePlayers} { players }</li>
 *     <li>S->C {@code SubscriptionSnapshot} { users, rejected, request_id, ... } answering a subscribe</li>
 *     <li>S->C {@code PlayerPresence} { player, online } when a subscribed player comes or goes</li>
 *     <li>S->C {@code Error} { error_code, message, request_id }</li>
 * </ul>
 * Layout taken from PolyPlus's open source client (github.com/Polyfrost/PolyPlus).
 */
public final class OneConfigSocket implements WebSocket.Listener {

    static final String API_URL = "https://plus.polyfrost.org";

    private static final String SOCKET_URL = "wss://plus.polyfrost.org/websocket";
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    /** Called on the socket's own thread; implementations hand off to their own. */
    public interface Listener {
        /** A subscribe was answered: {@code online} are the PolyPlus users among them right now. */
        void onSnapshot(long requestId, List<UUID> online, List<UUID> rejected);

        void onPresence(UUID player, boolean online);

        /** A request failed; {@code requestId} is -1 if the server didn't say which. */
        void onError(long requestId, String code);
    }

    private final Listener listener;
    private final StringBuilder partial = new StringBuilder();
    private final CompletableFuture<String> closed = new CompletableFuture<>();
    private CompletableFuture<WebSocket> sendChain;
    private volatile WebSocket ws;

    private OneConfigSocket(Listener listener) {
        this.listener = listener;
    }

    public static OneConfigSocket connect(String token, Listener listener) throws IOException {
        final OneConfigSocket socket = new OneConfigSocket(listener);
        final WebSocket.Builder builder = HTTP.newWebSocketBuilder().connectTimeout(Duration.ofSeconds(10));
        builder.header("Authorization", "Bearer " + token);
        try {
            builder.buildAsync(URI.create(SOCKET_URL), socket).get(15, TimeUnit.SECONDS);
        } catch (Exception e) {
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

    /** Callers keep each batch at 64 players or fewer, like the real client. */
    public void subscribe(Collection<UUID> players, long requestId) {
        final JsonObject frame = new JsonObject();
        frame.addProperty("type", "SubscribePlayers");
        frame.add("players", uuidList(players));
        frame.addProperty("request_id", requestId);
        send(frame.toString());
    }

    public void unsubscribe(Collection<UUID> players) {
        final JsonObject frame = new JsonObject();
        frame.addProperty("type", "UnsubscribePlayers");
        frame.add("players", uuidList(players));
        send(frame.toString());
    }

    /** JDK WebSockets reject overlapping sends, so each send waits for the previous one. */
    private synchronized void send(String text) {
        sendChain = sendChain.thenCompose(w -> w.sendText(text, true));
    }

    private void onMessage(String text) {
        final JsonObject msg = parseObject(text);
        final String type = string(msg, "type");
        if (type == null) {
            return;
        }
        switch (type) {
            case "SubscriptionSnapshot":
                listener.onSnapshot(requestId(msg), uuids(msg, "users"), uuids(msg, "rejected"));
                break;
            case "PlayerPresence": {
                final UUID player = parseUuid(string(msg, "player"));
                if (player != null && msg.has("online") && msg.get("online").isJsonPrimitive()) {
                    listener.onPresence(player, msg.get("online").getAsBoolean());
                }
                break;
            }
            case "Error":
                listener.onError(requestId(msg), string(msg, "error_code"));
                break;
            default:
                // Cosmetics, emotes, chat and friends traffic - nothing to do with the badge.
                break;
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
        partial.append(data);
        if (last) {
            final String message = partial.toString();
            partial.setLength(0);
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
    public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
        closed.complete(statusCode + " " + reason);
        return null;
    }

    @Override
    public void onError(WebSocket webSocket, Throwable error) {
        closed.complete(String.valueOf(error));
    }

    private static JsonArray uuidList(Collection<UUID> uuids) {
        final JsonArray list = new JsonArray();
        for (UUID uuid : uuids) {
            list.add(new JsonPrimitive(uuid.toString()));
        }
        return list;
    }

    private static List<UUID> uuids(JsonObject obj, String key) {
        final List<UUID> uuids = new ArrayList<>();
        if (obj.has(key) && obj.get(key).isJsonArray()) {
            for (JsonElement element : obj.getAsJsonArray(key)) {
                final UUID uuid = element.isJsonPrimitive() ? parseUuid(element.getAsString()) : null;
                if (uuid != null) {
                    uuids.add(uuid);
                }
            }
        }
        return uuids;
    }

    private static long requestId(JsonObject obj) {
        return obj.has("request_id") && obj.get("request_id").isJsonPrimitive() ? obj.get("request_id").getAsLong() : -1;
    }

    private static UUID parseUuid(String s) {
        if (s == null) {
            return null;
        }
        try {
            return UUID.fromString(s);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    // Minecraft 1.8.9 ships Gson 2.2.4, so only its API is used here.

    static JsonObject parseObject(String text) {
        try {
            final JsonElement parsed = new JsonParser().parse(text);
            return parsed != null && parsed.isJsonObject() ? parsed.getAsJsonObject() : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    static String string(JsonObject obj, String key) {
        if (obj == null) {
            return null;
        }
        final JsonElement element = obj.get(key);
        return element != null && element.isJsonPrimitive() ? element.getAsString() : null;
    }

}
