package com.ootruffle.clienttag.dawn;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;

/**
 * Dawn's gateway websocket (wss://api.dawn.gg/v2/ws), subscribed only to the
 * {@code players.tracked} channel that drives nametag logos. All frames are JSON text:
 * <ul>
 *     <li>C->S {@code subscribe}, then {@code track} frames with {@code add}/{@code remove} UUID lists</li>
 *     <li>S->C {@code event} frames whose {@code payload.kind} is snapshot, snapshot_batch, rank or equipped</li>
 *     <li>{@code ping} is answered with {@code pong} in the same form (bare text or JSON)</li>
 * </ul>
 * Layout taken from Dawn Client build stable/b8631c76.
 */
public final class DawnSocket implements WebSocket.Listener {

    static final String API_URL = "https://api.dawn.gg/v2";
    static final String CLIENT_BUILD = "b8631c76";

    private static final String SOCKET_URL = "wss://api.dawn.gg/v2/ws";
    private static final String CHANNEL = "players.tracked";
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    /** Called on the socket's own thread; implementations hand off to their own. */
    public interface Listener {
        /** The channel subscription was acknowledged - anything tracked before must be sent again. */
        void onSubscribed();

        /**
         * A player's rank: from a snapshot (mcUuid set) or a rank change (mcUuid or dawnId set).
         * A null or RANK_UNSPECIFIED rank means no logo.
         */
        void onRank(UUID mcUuid, String dawnId, String rank);
    }

    private final Listener listener;
    private final StringBuilder partial = new StringBuilder();
    private final CompletableFuture<String> closed = new CompletableFuture<>();
    private CompletableFuture<WebSocket> sendChain;
    private volatile WebSocket ws;
    private volatile boolean subscribed;

    private DawnSocket(Listener listener) {
        this.listener = listener;
    }

    public static DawnSocket connect(String jwt, Listener listener) throws IOException {
        final DawnSocket socket = new DawnSocket(listener);
        final WebSocket.Builder builder = HTTP.newWebSocketBuilder().connectTimeout(Duration.ofSeconds(10));
        builder.header("Authorization", "Bearer " + jwt);
        clientHeaders().forEach(builder::header);
        try {
            builder.buildAsync(URI.create(SOCKET_URL), socket).get(15, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new IOException("couldn't open " + SOCKET_URL + ": " + e, e);
        }
        socket.subscribe();
        return socket;
    }

    /** Headers the real client sends on both REST calls and the websocket upgrade. */
    static Map<String, String> clientHeaders() {
        final Map<String, String> headers = new LinkedHashMap<>();
        headers.put("User-Agent", "DawnClient/standalone-" + CLIENT_BUILD);
        headers.put("X-Dawn-Surface", "client");
        headers.put("X-Dawn-OS", osName());
        headers.put("X-Dawn-Edition", "dawn");
        return headers;
    }

    public boolean isOpen() {
        return !closed.isDone();
    }

    public boolean isSubscribed() {
        return subscribed && isOpen();
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

    /** Sends one track frame; callers keep each list at 100 UUIDs or fewer. */
    public void track(Collection<UUID> add, Collection<UUID> remove) {
        final JsonObject payload = new JsonObject();
        if (!add.isEmpty()) {
            payload.add("add", uuidList(add));
        }
        if (!remove.isEmpty()) {
            payload.add("remove", uuidList(remove));
        }
        final JsonObject frame = new JsonObject();
        frame.addProperty("type", "track");
        frame.addProperty("channel", CHANNEL);
        frame.add("payload", payload);
        send(frame.toString());
    }

    /** Rotates the access token without reconnecting. */
    public void reauth(String jwt) {
        final JsonObject frame = new JsonObject();
        frame.addProperty("type", "reauth");
        frame.addProperty("id", "reauth");
        frame.addProperty("token", jwt);
        send(frame.toString());
    }

    private void subscribe() {
        final JsonObject frame = new JsonObject();
        frame.addProperty("type", "subscribe");
        frame.addProperty("channel", CHANNEL);
        frame.addProperty("id", CHANNEL);
        send(frame.toString());
    }

    /** JDK WebSockets reject overlapping sends, so each send waits for the previous one. */
    private synchronized void send(String text) {
        sendChain = sendChain.thenCompose(w -> w.sendText(text, true));
    }

    private void onMessage(String text) {
        if (text.equals("ping")) {
            send("pong");
            return;
        }
        final JsonElement parsed = parse(text);
        if (parsed != null && parsed.isJsonArray()) {
            final JsonArray array = parsed.getAsJsonArray();
            if (array.size() > 0 && "duplicateConnection".equals(asString(array.get(0)))) {
                // The same account connected elsewhere (most likely Dawn itself is running).
                closed.complete("duplicate connection");
                ws.abort();
            }
            return;
        }
        if (parsed == null || !parsed.isJsonObject()) {
            return;
        }
        final JsonObject msg = parsed.getAsJsonObject();
        final String type = string(msg, "type");
        if (type == null) {
            return;
        }
        switch (type) {
            case "ping":
                send("{\"type\":\"pong\"}");
                break;
            case "subscribed":
                if (CHANNEL.equals(string(msg, "channel"))) {
                    subscribed = true;
                    listener.onSubscribed();
                }
                break;
            case "error":
                if (CHANNEL.equals(string(msg, "channel")) || "UNKNOWN_CHANNEL".equals(string(msg, "code"))) {
                    closed.complete("gateway error " + string(msg, "code"));
                    ws.abort();
                }
                break;
            case "event":
                if (CHANNEL.equals(string(msg, "channel")) && msg.has("payload") && msg.get("payload").isJsonObject()) {
                    onPlayersTracked(msg.getAsJsonObject("payload"));
                }
                break;
            default:
                break;
        }
    }

    private void onPlayersTracked(JsonObject payload) {
        final String kind = string(payload, "kind");
        if ("snapshot".equals(kind) || "rank".equals(kind)) {
            onPlayer(payload);
        } else if ("snapshot_batch".equals(kind) && payload.has("snapshots") && payload.get("snapshots").isJsonArray()) {
            for (JsonElement snapshot : payload.getAsJsonArray("snapshots")) {
                if (snapshot.isJsonObject()) {
                    onPlayer(snapshot.getAsJsonObject());
                }
            }
        }
        // "equipped" only carries cosmetics - nothing to do with the logo.
    }

    private void onPlayer(JsonObject player) {
        final UUID mcUuid = parseUuid(string(player, "mc_uuid"));
        final String dawnId = string(player, "dawn_id");
        if (mcUuid != null || dawnId != null) {
            listener.onRank(mcUuid, dawnId, string(player, "rank"));
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
            final JsonObject entry = new JsonObject();
            entry.addProperty("mc_uuid", uuid.toString());
            list.add(entry);
        }
        return list;
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

    private static String osName() {
        final String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        return os.contains("win") ? "windows" : os.contains("mac") ? "macos" : "linux";
    }

    // Minecraft 1.8.9 ships Gson 2.2.4, so only its API is used here.

    private static JsonElement parse(String text) {
        try {
            return new JsonParser().parse(text);
        } catch (RuntimeException e) {
            return null;
        }
    }

    static JsonObject parseObject(String text) {
        final JsonElement parsed = parse(text);
        return parsed != null && parsed.isJsonObject() ? parsed.getAsJsonObject() : null;
    }

    static String string(JsonObject obj, String key) {
        return obj == null ? null : asString(obj.get(key));
    }

    private static String asString(JsonElement element) {
        return element != null && element.isJsonPrimitive() ? element.getAsString() : null;
    }

}
