package com.ootruffle.clienttag.cmclient;

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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * CMClient's session socket (ws://session.cmclient.pl:8001, plain - not TLS). Every frame is a
 * JSON text object with a {@code type}. Only the messages nametags need are handled:
 * <ul>
 *     <li>C->S {@code {"type":"hello","playerName","gameVersion","clientVersion"}} right after
 *         connecting. There's no login: the server takes the name as given.</li>
 *     <li>C->S {@code {"type":"isOnlineList","playerNames":[...]}} looks players up by name; S->C
 *         {@code {"type":"isOnlineListResponse","playerNames":[...]}} lists only the ones on CMClient</li>
 * </ul>
 * Anything else (server join/quit, emotes) is ignored. Starting the game with
 * {@code -Dclienttag.cmclient.debug=true} logs every frame both ways.
 * <p>
 * Layout taken from CMClient 2.8.16 for 1.8.9.
 */
public final class CMClientSocket implements WebSocket.Listener {

    private static final Logger LOGGER = LogManager.getLogger("ClientTag");

    private static final String SOCKET_URL = "ws://session.cmclient.pl:8001";
    private static final String GAME_VERSION = "1.8.9";
    private static final String CLIENT_VERSION = "2.8.16";
    private static final boolean DEBUG = Boolean.getBoolean("clienttag.cmclient.debug");

    /** How many unreadable frames each connection logs. */
    private static final int MAX_LOGGED = 5;

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    /** Called on the socket's own thread; implementations hand off to their own. */
    public interface Listener {
        /** The names (as the server spelled them) that are on CMClient, out of the last lookup. */
        void onOnlineList(List<String> names);
    }

    private final Listener listener;
    private final StringBuilder partial = new StringBuilder();
    private final CompletableFuture<String> closed = new CompletableFuture<>();
    private CompletableFuture<WebSocket> sendChain;
    private volatile WebSocket ws;
    // Socket thread only.
    private int loggedFailures;

    private CMClientSocket(Listener listener) {
        this.listener = listener;
    }

    public static CMClientSocket connect(String name, Listener listener) throws IOException {
        final CMClientSocket socket = new CMClientSocket(listener);
        try {
            HTTP.newWebSocketBuilder()
                    .connectTimeout(Duration.ofSeconds(10))
                    .buildAsync(URI.create(SOCKET_URL), socket)
                    .get(15, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new IOException("couldn't open " + SOCKET_URL + ": " + e, e);
        }
        final JsonObject hello = new JsonObject();
        hello.addProperty("type", "hello");
        hello.addProperty("playerName", name);
        hello.addProperty("gameVersion", GAME_VERSION);
        hello.addProperty("clientVersion", CLIENT_VERSION);
        socket.send(hello);
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

    /** Asks which of these players are on CMClient. */
    public void lookup(Collection<String> names) {
        final JsonArray array = new JsonArray();
        for (String name : names) {
            // JsonArray.add(String) is newer than the Gson 1.8.9 ships.
            array.add(new JsonPrimitive(name));
        }
        final JsonObject query = new JsonObject();
        query.addProperty("type", "isOnlineList");
        query.add("playerNames", array);
        send(query);
    }

    /** JDK WebSockets reject overlapping sends, so each send waits for the previous one. */
    private synchronized void send(JsonObject message) {
        final String text = message.toString();
        if (DEBUG) {
            LOGGER.info("CMClient -> {}", text);
        }
        sendChain = sendChain.thenCompose(w -> w.sendText(text, true));
    }

    private void onMessage(String text) {
        if (DEBUG) {
            LOGGER.info("CMClient <- {}", text);
        }
        final JsonElement parsed = new JsonParser().parse(text);
        if (!parsed.isJsonObject()) {
            return;
        }
        final JsonObject message = parsed.getAsJsonObject();
        final JsonElement type = message.get("type");
        if (type == null || !type.isJsonPrimitive() || !"isOnlineListResponse".equals(type.getAsString())) {
            return;
        }
        final JsonElement names = message.get("playerNames");
        if (names == null || !names.isJsonArray()) {
            throw new IllegalStateException("no playerNames array");
        }
        final List<String> online = new ArrayList<>();
        for (JsonElement name : names.getAsJsonArray()) {
            if (name.isJsonPrimitive()) {
                online.add(name.getAsString());
            }
        }
        listener.onOnlineList(online);
    }

    @Override
    public void onOpen(WebSocket webSocket) {
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
            } catch (Exception e) {
                // A malformed frame shouldn't take the socket down, but the layout may have changed.
                if (loggedFailures++ < MAX_LOGGED) {
                    LOGGER.warn("Couldn't read a CMClient frame ({}): {}", e.toString(),
                            message.length() > 256 ? message.substring(0, 256) + "..." : message);
                }
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

}
