package com.ootruffle.clienttag.labymod;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.OutputStream;
import java.math.BigInteger;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.Collection;
import java.util.TimeZone;
import java.util.Timer;
import java.util.TimerTask;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * LabyConnect (play.laby.net:30336), LabyMod's own service, used only to read which players
 * are on LabyMod and their role - that's what LabyMod draws its wolf indicator from.
 * <p>
 * Plain TCP, not a websocket. Each frame is {@code VarInt length + payload}, and each payload
 * {@code 'P' + McString namespace ("laby") + McString packet name + fields} (see {@link LabyCodec}).
 * After the encryption exchange the payloads - but not the length prefixes - are AES/CFB8
 * encrypted, key = IV = a random 16-byte secret, one continuous cipher per direction.
 * <ol>
 *     <li>C->S {@code handshake} {version: 4, deviceType: CLIENT, clientVersion}</li>
 *     <li>S->C {@code encryption_request} {serverId, publicKey (X.509 RSA), verifyToken}</li>
 *     <li>C->S {@code encryption_response} {RSA(secret), RSA(verifyToken padded to 11 bytes)}, then encryption is on</li>
 *     <li>S->C {@code handshake_complete}</li>
 *     <li>C->S {@code client_options}, {@code viewport_register("player_list")}</li>
 *     <li>C->S {@code authenticate_mojang} {name, uuid, Minecraft server ID hash of serverId + secret + key}</li>
 *     <li>S->C {@code authenticate_result} {OK | INVALID_TOKEN | INVALID_UUID, uuid}</li>
 *     <li>C->S {@code subscribe("user_data", "player_list")}</li>
 * </ol>
 * After that, {@code viewport_players} says which players we can see, and the server answers
 * with {@code user_data_snapshot} for added players and {@code user_data_update} for changes.
 * Layout written from a protocol write-up of LabyConnect protocol version 4.
 */
public final class LabyConnectSocket {

    private static final String HOST = "play.laby.net";
    private static final int PORT = 30336;
    private static final int PROTOCOL_VERSION = 4;
    /** What LabyMod itself sends: the Minecraft version, not a LabyMod build. */
    private static final String CLIENT_VERSION = "1.8.9";
    private static final String NAMESPACE = "laby";
    private static final int PACKET_MARKER = 0x50;
    private static final int MAX_FRAME = 2_097_151;

    private static final int CONNECT_TIMEOUT_MS = 5_000;
    private static final int LOGIN_TIMEOUT_MS = 15_000;
    private static final int READ_TIMEOUT_MS = 120_000;
    private static final long KEEP_ALIVE_MS = 5_000;

    /** The viewport for the tab list; it's the one subscribed to user data. */
    private static final String VIEWPORT = "player_list";

    // Enum ordinals.
    private static final int DEVICE_CLIENT = 0;
    private static final int STATUS_ONLINE = 0;
    private static final int AUTH_OK = 0;
    private static final String[] AUTH_RESULTS = {"OK", "INVALID_TOKEN", "INVALID_UUID"};
    private static final int VIEWPORT_SNAPSHOT = 0;
    private static final int VIEWPORT_DELTA = 1;
    private static final int UPDATE_REMOVED = 2;

    private static final SecureRandom RANDOM = new SecureRandom();

    /** Performs the Mojang session server join for the given server ID. */
    @FunctionalInterface
    public interface SessionJoiner {
        void join(String serverId) throws Exception;
    }

    /** Called on the socket's own thread; implementations hand off to their own. */
    public interface Listener {
        /** A viewport player's data: whether they're on LabyMod, and their visible role (0 = none). */
        void onUserData(UUID player, boolean usingLabyMod, int roleId);

        /** The server dropped what it knew about a player. */
        void onUserRemoved(UUID player);
    }

    /** A player put into the viewport, with their skin and cape texture IDs if known. */
    public static final class ViewportPlayer {
        final UUID uuid;
        final Long skinId;
        final Long capeId;

        private ViewportPlayer(UUID uuid, Long skinId, Long capeId) {
            this.uuid = uuid;
            this.skinId = skinId;
            this.capeId = capeId;
        }

        /** {@code textures} is the profile's base64 "textures" property, or null. */
        public static ViewportPlayer of(UUID uuid, String textures) {
            Long skinId = null, capeId = null;
            if (textures != null) {
                try {
                    final JsonElement parsed = new JsonParser().parse(
                            new String(Base64.getDecoder().decode(textures), StandardCharsets.UTF_8));
                    final JsonElement all = parsed.isJsonObject() ? parsed.getAsJsonObject().get("textures") : null;
                    if (all != null && all.isJsonObject()) {
                        skinId = textureId(all.getAsJsonObject(), "SKIN");
                        capeId = textureId(all.getAsJsonObject(), "CAPE");
                    }
                } catch (RuntimeException ignored) {
                    // Unreadable textures just aren't sent.
                }
            }
            return new ViewportPlayer(uuid, skinId, capeId);
        }

        /** The first 16 hex digits of the texture hash (the URL's last path segment), as a long. */
        private static Long textureId(JsonObject textures, String type) {
            final JsonElement texture = textures.get(type);
            final JsonElement url = texture != null && texture.isJsonObject() ? texture.getAsJsonObject().get("url") : null;
            if (url == null || !url.isJsonPrimitive()) {
                return null;
            }
            final String s = url.getAsString();
            final String hash = s.substring(s.lastIndexOf('/') + 1);
            try {
                return hash.length() < 16 ? null : Long.parseUnsignedLong(hash.substring(0, 16), 16);
            } catch (NumberFormatException e) {
                return null;
            }
        }
    }

    private interface Fields {
        void write(LabyCodec.Writer w) throws IOException;
    }

    private static final class Packet {
        final String name;
        final LabyCodec.Reader fields;

        Packet(String name, LabyCodec.Reader fields) {
            this.name = name;
            this.fields = fields;
        }
    }

    private final Socket socket;
    private final DataInputStream in;
    private final OutputStream out;
    private final Listener listener;
    private final CompletableFuture<String> closed = new CompletableFuture<>();
    private final Timer keepAlive = new Timer("ClientTag-LabyMod-KeepAlive", true);
    // Guarded by this.
    private Cipher encrypt;
    // Login thread, then the reader thread.
    private Cipher decrypt;

    private LabyConnectSocket(Socket socket, Listener listener) throws IOException {
        this.socket = socket;
        this.in = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
        this.out = socket.getOutputStream();
        this.listener = listener;
    }

    /** Connects and logs in with the game session; blocks until LabyConnect accepts or rejects it. */
    public static LabyConnectSocket connect(String name, UUID uuid, SessionJoiner joiner, Listener listener) throws Exception {
        final Socket raw = new Socket();
        LabyConnectSocket socket = null;
        try {
            raw.setTcpNoDelay(true);
            raw.connect(new InetSocketAddress(HOST, PORT), CONNECT_TIMEOUT_MS);
            raw.setSoTimeout(LOGIN_TIMEOUT_MS);
            socket = new LabyConnectSocket(raw, listener);
            socket.login(name, uuid, joiner);
            raw.setSoTimeout(READ_TIMEOUT_MS);
        } catch (Exception e) {
            raw.close();
            throw e;
        }
        socket.start();
        return socket;
    }

    public boolean isOpen() {
        return !closed.isDone();
    }

    public String closeReason() {
        return closed.getNow(null);
    }

    public void close() {
        markClosed("closed locally");
    }

    /**
     * Updates the viewport: a snapshot replaces it with {@code players}, a delta adds
     * {@code players} and drops {@code removed}.
     */
    public void sendViewport(boolean snapshot, Collection<ViewportPlayer> players, Collection<UUID> removed) {
        trySend("viewport_players", w -> {
            w.writeString(VIEWPORT);
            w.writeEnum(snapshot ? VIEWPORT_SNAPSHOT : VIEWPORT_DELTA);
            w.writePresent(true);
            w.writeInt32(players.size());
            for (ViewportPlayer player : players) {
                w.writePresent(true);
                w.writeUuid(player.uuid);
                final boolean hasMetadata = player.skinId != null || player.capeId != null;
                w.writePresent(hasMetadata);
                if (hasMetadata) {
                    w.writeLong(player.capeId);
                    w.writeLong(player.skinId);
                }
            }
            w.writePresent(true);
            w.writeInt32(removed.size());
            for (UUID uuid : removed) {
                w.writeUuid(uuid);
            }
        });
    }

    private void login(String name, UUID uuid, SessionJoiner joiner) throws Exception {
        send("handshake", w -> {
            w.writeVarInt(PROTOCOL_VERSION);
            w.writeEnum(DEVICE_CLIENT);
            w.writeString(CLIENT_VERSION);
        });

        final LabyCodec.Reader request = expect("encryption_request");
        final String serverId = request.readString();
        final byte[] publicKeyBytes = request.readBytes();
        final byte[] verifyToken = request.readBytes();
        if (serverId == null || publicKeyBytes == null || verifyToken == null) {
            throw new IOException("incomplete encryption request");
        }
        final PublicKey publicKey = KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(publicKeyBytes));
        final byte[] secret = new byte[16];
        RANDOM.nextBytes(secret);
        // The token goes in an 11-byte buffer, zero padded.
        final byte[] paddedToken = new byte[11];
        System.arraycopy(verifyToken, 0, paddedToken, 0, Math.min(verifyToken.length, paddedToken.length));
        final byte[] encryptedSecret = rsa(publicKey, secret);
        final byte[] encryptedToken = rsa(publicKey, paddedToken);
        send("encryption_response", w -> {
            w.writeBytes(encryptedSecret);
            w.writeBytes(encryptedToken);
        });
        enableEncryption(secret);
        expect("handshake_complete");

        send("client_options", w -> {
            w.writeBoolean(false);
            w.writeEnum(STATUS_ONLINE);
            w.writeString(TimeZone.getDefault().getID());
        });
        send("viewport_register", w -> w.writeString(VIEWPORT));

        final String serverIdHash = serverIdHash(serverId, secret, publicKeyBytes);
        joiner.join(serverIdHash);
        send("authenticate_mojang", w -> {
            w.writeString(name);
            w.writeUuid(uuid);
            w.writeString(serverIdHash);
        });
        final int result = expect("authenticate_result").readEnum();
        if (result != AUTH_OK) {
            throw new IOException("LabyConnect rejected the login: "
                    + (result >= 0 && result < AUTH_RESULTS.length ? AUTH_RESULTS[result] : String.valueOf(result)));
        }

        send("subscribe", w -> {
            w.writeString("user_data");
            w.writeString(VIEWPORT);
        });
    }

    /** Reads packets until the named one arrives, skipping everything else. */
    private LabyCodec.Reader expect(String name) throws IOException {
        while (true) {
            final Packet packet = readPacket();
            if (packet.name.equals(name)) {
                return packet.fields;
            }
            if (packet.name.equals("disconnect")) {
                throw new IOException("LabyConnect disconnected: " + packet.fields.readString());
            }
        }
    }

    private void start() {
        final Thread reader = new Thread(this::readLoop, "ClientTag-LabyMod");
        reader.setDaemon(true);
        reader.start();
        keepAlive.scheduleAtFixedRate(new TimerTask() {
            @Override
            public void run() {
                trySend("keepalive_request", w -> w.writeInt64(System.currentTimeMillis()));
            }
        }, KEEP_ALIVE_MS, KEEP_ALIVE_MS);
    }

    private void readLoop() {
        try {
            while (isOpen()) {
                final Packet packet = readPacket();
                try {
                    handle(packet);
                } catch (IOException e) {
                    // A packet that doesn't parse the way we expect shouldn't take the socket down.
                }
            }
        } catch (EOFException e) {
            markClosed("connection closed by server");
        } catch (Exception e) {
            markClosed(String.valueOf(e));
        }
    }

    private void handle(Packet packet) throws IOException {
        final LabyCodec.Reader r = packet.fields;
        switch (packet.name) {
            case "user_data_snapshot": {
                final int count = r.readArrayLength();
                for (int i = 0; i < count; i++) {
                    readUserData(r, null);
                }
                break;
            }
            case "user_data_update": {
                final UUID uuid = r.readUuid();
                final boolean hadUser = readUserData(r, uuid);
                final int type = r.readEnum();
                if (uuid != null && (type == UPDATE_REMOVED || !hadUser)) {
                    listener.onUserRemoved(uuid);
                }
                break;
            }
            case "disconnect":
                markClosed("disconnect: " + r.readString());
                break;
            default:
                // Keepalive responses, subscription answers, friends, chat - nothing to do with the indicator.
                break;
        }
    }

    /**
     * Reads a nullable {@code UserData} and reports it, under {@code fallbackUuid} if it carries
     * none. Returns whether one was present.
     */
    private boolean readUserData(LabyCodec.Reader r, UUID fallbackUuid) throws IOException {
        if (!r.readPresent()) {
            return false;
        }
        final UUID readUuid = r.readUuid();
        r.readString(); // name
        final int[] roleIds = r.readIntArray();
        r.readIntArray(); // emotes
        r.readIntArray(); // spray packs
        final int cosmetics = r.readArrayLength();
        for (int i = 0; i < cosmetics; i++) {
            if (r.readPresent()) {
                r.readVarInt(); // cosmetic ID
                final int options = r.readArrayLength();
                for (int j = 0; j < options; j++) {
                    r.readString();
                }
            }
        }
        final boolean usingLabyMod = r.readBool();
        r.readBool(); // daily emote flat
        final int levels = r.readArrayLength();
        for (int i = 0; i < levels; i++) {
            if (r.readPresent()) {
                r.readString(); // type
                r.readVarInt(); // level
            }
        }

        final UUID uuid = readUuid != null ? readUuid : fallbackUuid;
        if (uuid != null) {
            listener.onUserData(uuid, usingLabyMod, roleIds != null && roleIds.length > 0 ? roleIds[0] : 0);
        }
        return true;
    }

    private Packet readPacket() throws IOException {
        final int length = readFrameLength();
        byte[] payload = new byte[length];
        in.readFully(payload);
        if (decrypt != null) {
            payload = decrypt.update(payload);
        }
        final LabyCodec.Reader reader = new LabyCodec.Reader(payload);
        if (reader.readByte() != PACKET_MARKER) {
            throw new IOException("bad packet marker (cipher out of sync?)");
        }
        reader.readMcString(); // namespace
        return new Packet(reader.readMcString(), reader);
    }

    /** The frame length prefix: a VarInt of at most 3 bytes, never encrypted. */
    private int readFrameLength() throws IOException {
        int value = 0;
        for (int i = 0; i < 3; i++) {
            final int b = in.readUnsignedByte();
            value |= (b & 0x7F) << (7 * i);
            if ((b & 0x80) == 0) {
                if (value <= 0 || value > MAX_FRAME) {
                    throw new IOException("bad frame length " + value);
                }
                return value;
            }
        }
        throw new IOException("frame length too long");
    }

    private void trySend(String name, Fields fields) {
        if (!isOpen()) {
            return;
        }
        try {
            send(name, fields);
        } catch (IOException e) {
            markClosed(String.valueOf(e));
        }
    }

    private synchronized void send(String name, Fields fields) throws IOException {
        final LabyCodec.Writer payload = new LabyCodec.Writer();
        payload.writeByte(PACKET_MARKER);
        payload.writeMcString(NAMESPACE);
        payload.writeMcString(name);
        fields.write(payload);
        byte[] bytes = payload.toByteArray();
        if (bytes.length > MAX_FRAME) {
            throw new IOException(name + " too large (" + bytes.length + " bytes)");
        }
        if (encrypt != null) {
            bytes = encrypt.update(bytes);
        }
        final LabyCodec.Writer frame = new LabyCodec.Writer();
        frame.writeVarInt(bytes.length);
        out.write(frame.toByteArray());
        out.write(bytes);
        out.flush();
    }

    private synchronized void enableEncryption(byte[] secret) throws Exception {
        encrypt = aes(Cipher.ENCRYPT_MODE, secret);
        decrypt = aes(Cipher.DECRYPT_MODE, secret);
    }

    private void markClosed(String reason) {
        if (closed.complete(reason)) {
            keepAlive.cancel();
            try {
                socket.close();
            } catch (IOException ignored) {
                // Already gone.
            }
        }
    }

    private static Cipher aes(int mode, byte[] secret) throws Exception {
        final Cipher cipher = Cipher.getInstance("AES/CFB8/NoPadding");
        cipher.init(mode, new SecretKeySpec(secret, "AES"), new IvParameterSpec(secret));
        return cipher;
    }

    private static byte[] rsa(PublicKey key, byte[] data) throws Exception {
        final Cipher cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding");
        cipher.init(Cipher.ENCRYPT_MODE, key);
        return cipher.doFinal(data);
    }

    /** Minecraft's signed SHA-1 hex digest of serverId (ISO-8859-1) + secret + public key. */
    private static String serverIdHash(String serverId, byte[] secret, byte[] publicKey) throws Exception {
        final MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
        sha1.update(serverId.getBytes(StandardCharsets.ISO_8859_1));
        sha1.update(secret);
        sha1.update(publicKey);
        return new BigInteger(sha1.digest()).toString(16);
    }

}
