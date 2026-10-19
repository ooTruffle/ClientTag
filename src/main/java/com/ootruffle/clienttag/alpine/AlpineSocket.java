package com.ootruffle.clienttag.alpine;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import net.fabricmc.loader.api.FabricLoader;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Alpine Client's socket (wss://socket.alpineclient.com). Every binary frame is a bare run of
 * MessagePack values: an int packet ID, then that packet's fields. A UUID is two int64s, most
 * significant first. Only the packets nametags need are handled:
 * <ul>
 *     <li>S->C {@code 160 {challenge}}: hash it as a Minecraft server ID, join that through Mojang's
 *         session server, then answer C->S {@code 160} with no body</li>
 *     <li>S->C {@code 161 {authenticated}}: the result of that</li>
 *     <li>C->S {@code 163 [N x {uuidMost, uuidLeast}]} looks up to 500 players; S->C
 *         {@code 163 [N x {uuidMost, uuidLeast, isAlpineUser, (cosmetics [M x {type, id}] if a user)}]}
 *         answers it</li>
 *     <li>S->C {@code 0 {code, message}} is (most likely) an error, and only logged</li>
 * </ul>
 * Text frames are only ever logged by the real client, so they're ignored. Pings are answered by the JDK.
 * <p>
 * Layout taken from Alpine Client 1.7.0-beta.r4 (commit 42b6986).
 */
public final class AlpineSocket implements WebSocket.Listener {

    private static final Logger LOGGER = LogManager.getLogger("ClientTag");

    private static final String SOCKET_URL = "wss://socket.alpineclient.com";
    private static final String CLIENT_VERSION = "1.7.0-beta.r4";
    private static final String GIT_COMMIT = "42b6986";
    private static final String GIT_BRANCH = "production";
    private static final String PROTOCOL_VERSION = "1";

    private static final int ERROR = 0;
    private static final int AUTH_CHALLENGE = 160;
    private static final int AUTH_RESULT = 161;
    private static final int PLAYER_LOOKUP = 163;

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    /** Called on the socket's own thread; implementations hand off to their own. */
    public interface Listener {
        /** The server wants a session join for {@code serverId}, then {@link #answerChallenge}. */
        void onChallenge(AlpineSocket socket, String serverId);

        /** The server accepted the join; lookups can be sent from now on. */
        void onAuthenticated();

        /** Whether a player we looked up is on Alpine. */
        void onLookup(UUID player, boolean alpineUser);
    }

    private final Listener listener;
    private final ByteArrayOutputStream partial = new ByteArrayOutputStream();
    private final CompletableFuture<String> closed = new CompletableFuture<>();
    private CompletableFuture<WebSocket> sendChain;
    private volatile WebSocket ws;
    private volatile boolean authenticated;

    private AlpineSocket(Listener listener) {
        this.listener = listener;
    }

    public static AlpineSocket connect(UUID self, String name, Listener listener) throws IOException {
        final AlpineSocket socket = new AlpineSocket(listener);
        final WebSocket.Builder builder = HTTP.newWebSocketBuilder().connectTimeout(Duration.ofSeconds(10));
        clientHeaders(self, name).forEach(builder::header);
        try {
            builder.buildAsync(URI.create(SOCKET_URL), socket).get(15, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new IOException("couldn't open " + SOCKET_URL + ": " + e, e);
        }
        return socket;
    }

    /** The handshake headers the real client sends. */
    private static Map<String, String> clientHeaders(UUID self, String name) {
        final Map<String, String> headers = new LinkedHashMap<>();
        headers.put("client", "alpineclient-v" + CLIENT_VERSION + "-" + GIT_COMMIT + "/" + GIT_BRANCH);
        headers.put("clientVersion", CLIENT_VERSION);
        headers.put("gitBranch", GIT_BRANCH);
        headers.put("gitCommitId", GIT_COMMIT);
        headers.put("mcVersion", FabricLoader.getInstance().getModContainer("minecraft")
                .map(mod -> mod.getMetadata().getVersion().getFriendlyString())
                .orElse("1.8.9"));
        headers.put("operatingSystem", System.getProperty("os.name", "unknown"));
        headers.put("protocolVersion", PROTOCOL_VERSION);
        headers.put("playerId", self.toString());
        headers.put("username", name);
        return headers;
    }

    public boolean isOpen() {
        return !closed.isDone();
    }

    public boolean isAuthenticated() {
        return authenticated && isOpen();
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

    /** Tells the server the session join for its challenge is done. */
    public void answerChallenge() {
        send(new MsgPack.Writer().packLong(AUTH_CHALLENGE).toByteArray());
    }

    /** Looks players up; callers keep it to 500 at a time, as the real client does. */
    public void lookup(Collection<UUID> players) {
        final MsgPack.Writer out = new MsgPack.Writer().packLong(PLAYER_LOOKUP).packArrayHeader(players.size());
        for (UUID uuid : players) {
            out.packLong(uuid.getMostSignificantBits()).packLong(uuid.getLeastSignificantBits());
        }
        send(out.toByteArray());
    }

    /** JDK WebSockets reject overlapping sends, so each send waits for the previous one. */
    private synchronized void send(byte[] frame) {
        sendChain = sendChain.thenCompose(w -> w.sendBinary(ByteBuffer.wrap(frame), true));
    }

    private void onMessage(byte[] data) throws Exception {
        final MsgPack.Reader in = new MsgPack.Reader(data);
        switch (in.unpackInt()) {
            case AUTH_CHALLENGE:
                listener.onChallenge(this, serverId(in.unpackString()));
                break;
            case AUTH_RESULT:
                if (in.unpackBoolean()) {
                    authenticated = true;
                    listener.onAuthenticated();
                } else {
                    closed.complete("authentication rejected");
                    ws.abort();
                }
                break;
            case PLAYER_LOOKUP:
                onLookupReply(in);
                break;
            case ERROR:
                LOGGER.info("Alpine socket said: {} {}", in.hasNext() ? in.unpackLong() : "", in.hasNext() ? in.unpackString() : "");
                break;
            default:
                break;
        }
    }

    private void onLookupReply(MsgPack.Reader in) throws IOException {
        final int n = in.unpackArrayHeader();
        for (int i = 0; i < n; i++) {
            final UUID uuid = new UUID(in.unpackLong(), in.unpackLong());
            final boolean alpineUser = in.unpackBoolean();
            if (alpineUser) {
                // Equipped cosmetics, which don't change the indicator.
                final int cosmetics = in.unpackArrayHeader();
                for (int j = 0; j < cosmetics; j++) {
                    in.unpackString();
                    in.unpackString();
                }
            }
            listener.onLookup(uuid, alpineUser);
        }
    }

    /** Minecraft's signed SHA-1 hex digest, as used for session server IDs. */
    private static String serverId(String challenge) throws Exception {
        final MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
        return new BigInteger(sha1.digest(String.valueOf(challenge).getBytes(StandardCharsets.UTF_8))).toString(16);
    }

    @Override
    public void onOpen(WebSocket webSocket) {
        // Set up here rather than after buildAsync returns: the challenge can arrive before that.
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
            } catch (Exception ignored) {
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
        closed.complete(statusCode + " " + reason);
        return null;
    }

    @Override
    public void onError(WebSocket webSocket, Throwable error) {
        closed.complete(String.valueOf(error));
    }

}
