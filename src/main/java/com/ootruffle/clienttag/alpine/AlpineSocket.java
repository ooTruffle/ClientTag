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
import java.util.Locale;
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
 *     <li>C->S {@code 162 {host, address}} says where we are: the server's host and the address as
 *         typed, both lowercase, or {@code "menus"} twice out of a world. Lookups are sent only after it,
 *         as the server ignores them from clients in the menus.</li>
 *     <li>C->S {@code 163 [N x {uuidMost, uuidLeast}]} looks up to 500 players; S->C
 *         {@code 163 [N x {uuidMost, uuidLeast, isAlpineUser, cosmetics [M x {type, id}]}]}
 *         answers it</li>
 *     <li>S->C {@code 0 {code, message}} is (most likely) an error, and only logged</li>
 * </ul>
 * Text frames are only ever logged by the real client, so they're ignored. Pings are answered by the JDK.
 * Starting the game with {@code -Dclienttag.alpine.debug=true} logs every frame both ways.
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
    private static final int STATUS = 162;
    private static final int PLAYER_LOOKUP = 163;
    private static final int SESSION = 164;

    /** What the real client reports for both of 162's strings when it isn't on a server. */
    public static final String MENUS = "menus";

    /** How many replies and unreadable frames each connection logs. */
    private static final int MAX_LOGGED = 5;
    private static final int HEX_LIMIT = 256;
    /** {@code -Dclienttag.alpine.debug=true} logs every frame sent and received. */
    private static final boolean DEBUG = Boolean.getBoolean("clienttag.alpine.debug");

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    /** Called on the socket's own thread; implementations hand off to their own. */
    public interface Listener {
        /** The server wants a session join for {@code serverId}, then {@link #answerChallenge}. */
        void onChallenge(AlpineSocket socket, String serverId);

        /** The server accepted the join; send {@link #status}, then lookups can follow. */
        void onAuthenticated(AlpineSocket socket);

        /** Whether a player we looked up is on Alpine. */
        void onLookup(UUID player, boolean alpineUser);
    }

    private final Listener listener;
    private final ByteArrayOutputStream partial = new ByteArrayOutputStream();
    private final CompletableFuture<String> closed = new CompletableFuture<>();
    private CompletableFuture<WebSocket> sendChain;
    private volatile WebSocket ws;
    private volatile boolean authenticated;
    // Socket thread only.
    private int loggedReplies;
    private int loggedFailures;

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
        headers.put("operatingSystem", osName());
        headers.put("protocolVersion", PROTOCOL_VERSION);
        // Straight from Minecraft's session, which keeps the UUID without dashes.
        headers.put("playerId", self.toString().replace("-", ""));
        headers.put("username", name);
        return headers;
    }

    /** The first of Alpine's OS names (or their aliases) that os.name contains, as the real client picks it. */
    private static String osName() {
        final String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        for (String[] names : new String[][] {{"linux"}, {"solaris"}, {"windows"}, {"macos", "osx", "mac os x"}}) {
            for (String name : names) {
                if (os.contains(name)) {
                    return names[0];
                }
            }
        }
        return "unknown";
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

    /** Says which server we're on (see the class comment), or {@link #MENUS} for both when on none. */
    public void status(String host, String address) {
        send(new MsgPack.Writer().packLong(STATUS).packString(host).packString(address).toByteArray());
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
        if (DEBUG) {
            LOGGER.info("Alpine frame sent: {}", hex(frame));
        }
        sendChain = sendChain.thenCompose(w -> w.sendBinary(ByteBuffer.wrap(frame), true));
    }

    private void onMessage(byte[] data) throws Exception {
        if (DEBUG) {
            // 164 carries the account's cloud session token, which has no place in a log people share.
            final boolean session = data.length >= 2 && (data[0] & 0xFF) == 0xCC && (data[1] & 0xFF) == SESSION;
            LOGGER.info("Alpine frame received: {}", session ? "cc a4 (cloud session, " + data.length + " bytes)" : hex(data));
        }
        final MsgPack.Reader in = new MsgPack.Reader(data);
        switch (in.unpackInt()) {
            case AUTH_CHALLENGE:
                listener.onChallenge(this, serverId(in.unpackString()));
                break;
            case AUTH_RESULT:
                if (in.unpackBoolean()) {
                    authenticated = true;
                    listener.onAuthenticated(this);
                } else {
                    closed.complete("authentication rejected");
                    ws.abort();
                }
                break;
            case PLAYER_LOOKUP:
                onLookupReply(in);
                break;
            case ERROR:
                LOGGER.info("Alpine socket said: {}", hex(data));
                break;
            default:
                break;
        }
    }

    /** The frame in hex, cut off after {@link #HEX_LIMIT} bytes. */
    private static String hex(byte[] data) {
        final StringBuilder out = new StringBuilder();
        for (int i = 0; i < Math.min(data.length, HEX_LIMIT); i++) {
            out.append(String.format("%02x ", data[i]));
        }
        return out.append(data.length > HEX_LIMIT ? "... (" + data.length + " bytes)" : "").toString().trim();
    }

    private void onLookupReply(MsgPack.Reader in) throws IOException {
        final int n = in.unpackArrayHeader();
        int users = 0;
        for (int i = 0; i < n; i++) {
            final UUID uuid = new UUID(in.unpackLong(), in.unpackLong());
            final boolean alpineUser = in.unpackBoolean();
            // The real client reads the cosmetics for every player, users or not.
            skipCosmetics(in);
            if (alpineUser) {
                users++;
            }
            listener.onLookup(uuid, alpineUser);
        }
        if (loggedReplies++ < MAX_LOGGED) {
            LOGGER.info("Alpine answered for {} players, {} on Alpine", n, users);
        }
    }

    /**
     * Equipped cosmetics, which don't change the indicator: {@code M x {type, id}}. Each one may come
     * as two bare strings or wrapped in its own array or map, so anything that isn't a string is skipped whole.
     */
    private static void skipCosmetics(MsgPack.Reader in) throws IOException {
        if (in.peekIsNil()) {
            in.skipValue();
            return;
        }
        final int cosmetics = in.unpackArrayHeader();
        for (int j = 0; j < cosmetics; j++) {
            if (in.peekIsString()) {
                in.unpackString();
                in.unpackString();
            } else {
                in.skipValue();
            }
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
            } catch (Exception e) {
                // A malformed frame shouldn't take the socket down, but the layout may have changed.
                if (loggedFailures++ < MAX_LOGGED) {
                    LOGGER.warn("Couldn't read an Alpine frame ({}): {}", e.toString(), hex(message));
                }
            }
        }
        webSocket.request(1);
        return null;
    }

    @Override
    public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
        if (DEBUG) {
            LOGGER.info("Alpine text frame received: {}", data);
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
