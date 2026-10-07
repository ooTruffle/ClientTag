package com.ootruffle.clienttag.lunar;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The game's asset socket (wss://websocket.lunarclientprod.com/game) and its protobuf
 * RPC envelope:
 * <ul>
 *     <li>Request: { 1: request ID (string, counts up), 2: service, 3: method, 4: body }</li>
 *     <li>Response: { 1: { 1: request ID, 2: body } }</li>
 *     <li>Push: { 2: google.protobuf.Any } - ignored, nothing here needs them</li>
 * </ul>
 * Message layouts come from a decrypted capture of Lunar v2.23.0-2640 (6 Oct 2026).
 */
public final class LunarSocket {

    static final String CLIENT_VERSION = "v2.23.0-2640";
    static final String LAUNCHER_VERSION = "3.7.15-ow";
    static final String USER_AGENT = "Lunar Client " + CLIENT_VERSION;

    private static final String SOCKET_URL = "wss://websocket.lunarclientprod.com/game";
    private static final String SUBSCRIPTION_SERVICE = "lunarclient.websocket.subscription.v1.SubscriptionService";
    private static final long CALL_TIMEOUT_MS = 10_000;

    /** A Lunar user found by SubscribeV2, with their nametag icon color (RGB). */
    public static final class Entry {
        public final UUID uuid;
        public final int color;

        Entry(UUID uuid, int color) {
            this.uuid = uuid;
            this.color = color;
        }
    }

    private final WsConnection ws;
    private final AtomicInteger nextId = new AtomicInteger(1);
    private final Map<String, CompletableFuture<ProtoMessage>> pending = new ConcurrentHashMap<>();

    private LunarSocket(WsConnection ws) {
        this.ws = ws;
        ws.setHandler(this::onMessage);
        ws.onClosed(reason -> {
            final IOException e = new IOException("socket closed: " + reason);
            pending.values().forEach(f -> f.completeExceptionally(e));
            pending.clear();
        });
    }

    public static LunarSocket connect(UUID uuid, String name, String jwt, String installationId) throws IOException {
        final WsConnection ws = WsConnection.open(SOCKET_URL, Collections.singletonMap("User-Agent", USER_AGENT));
        ws.send(loginFrame(uuid, name, jwt, installationId).toByteArray());
        return new LunarSocket(ws);
    }

    public boolean isOpen() {
        return ws.isOpen();
    }

    public void close() {
        ws.close();
    }

    public CompletableFuture<ProtoMessage> call(String service, String method, ProtoWriter body) {
        final String id = String.valueOf(nextId.getAndIncrement());
        final ProtoWriter frame = new ProtoWriter().string(1, id).string(2, service).string(3, method);
        if (body != null) {
            frame.message(4, body);
        }
        final CompletableFuture<ProtoMessage> future = new CompletableFuture<>();
        pending.put(id, future);
        ws.send(frame.toByteArray());
        return future.orTimeout(CALL_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .whenComplete((r, e) -> pending.remove(id));
    }

    /** Logs in to each service the way the real client does after connecting. */
    public void login() throws Exception {
        for (String service : new String[]{"friend.v1.FriendService", "cosmetic.v2.CosmeticService", "socials.v1.SocialsService"}) {
            await(call("lunarclient.websocket." + service, "Login", null));
        }
    }

    /**
     * Reports which server we're on. The real client always does this before its first
     * SubscribeV2, and lookups came back empty without it, so Lunar most likely only
     * answers for players on the reported server.
     *
     * @param hostname the address connected to, e.g. "mc.hypixel.net"
     * @param location the server's base domain, e.g. "hypixel.net"
     */
    public void reportLocation(String hostname, String location) throws Exception {
        await(call("lunarclient.websocket.server.v1.ServerService", "CheckAuthorizedFeatures",
                new ProtoWriter().string(1, hostname + ".")));
        await(call("lunarclient.websocket.friend.v1.FriendService", "BroadcastLocationChange",
                new ProtoWriter()
                        .message(1, new ProtoWriter().message(1, new ProtoWriter().string(1, location)))
                        .varint(2, 1)));
    }

    /** SubscribeV2: { 1: repeated uuid } -> { 1: repeated entry }, entries only for Lunar users. */
    public CompletableFuture<List<Entry>> subscribe(Collection<UUID> uuids) {
        final ProtoWriter body = new ProtoWriter();
        for (UUID uuid : uuids) {
            body.message(1, ProtoWriter.uuid(uuid));
        }
        return call(SUBSCRIPTION_SERVICE, "SubscribeV2", body).thenApply(res -> {
            final List<Entry> entries = new ArrayList<>();
            for (ProtoMessage e : res.messages(1)) {
                final ProtoMessage player = e.message(1);
                if (player == null) {
                    continue;
                }
                final ProtoMessage color = e.message(2);
                entries.add(new Entry(player.toUuid(), color == null ? 0xFFFFFF : (int) color.varint(1, 0xFFFFFF)));
            }
            return entries;
        });
    }

    public CompletableFuture<ProtoMessage> unsubscribe(UUID uuid) {
        return call(SUBSCRIPTION_SERVICE, "Unsubscribe", new ProtoWriter().message(1, ProtoWriter.uuid(uuid)));
    }

    private void onMessage(byte[] buf) {
        final ProtoMessage msg;
        try {
            msg = ProtoMessage.parse(buf);
        } catch (IllegalArgumentException e) {
            return;
        }
        final ProtoMessage response = msg.message(1);
        if (response == null) {
            return;
        }
        final CompletableFuture<ProtoMessage> future = pending.get(response.string(1));
        if (future == null) {
            return;
        }
        // Only field 2 (the body) has been seen on success; anything else is treated as an error.
        if (response.has(2)) {
            future.complete(ProtoMessage.parse(response.bytes(2)));
        } else if (response.fieldNumbers().size() > 1) {
            future.completeExceptionally(new IOException("call failed, response fields " + response.fieldNumbers()));
        } else {
            future.complete(ProtoMessage.EMPTY);
        }
    }

    private static void await(CompletableFuture<ProtoMessage> call) throws Exception {
        call.get(CALL_TIMEOUT_MS + 1000, TimeUnit.MILLISECONDS);
    }

    /**
     * First frame: identity + JWT + client info, mirroring the real client (minus its GL
     * extension strings and build commit hashes).
     */
    private static ProtoWriter loginFrame(UUID uuid, String name, String jwt, String installationId) {
        return new ProtoWriter()
                .message(1, new ProtoWriter()
                        .message(1, new ProtoWriter().message(1, ProtoWriter.uuid(uuid)).string(2, name))
                        .varint(2, 2) // Microsoft account
                        .string(3, jwt))
                .message(2, new ProtoWriter().string(1, LAUNCHER_VERSION))
                .string(3, installationId)
                .string(5, "Windows 10")
                .string(6, "AMD64")
                .message(7, new ProtoWriter().string(2, "en_US"))
                .message(8, new ProtoWriter()
                        .message(1, new ProtoWriter().string(1, "v1_8"))
                        .message(2, new ProtoWriter().string(1, "master").string(3, CLIENT_VERSION))
                        .string(4, "lunar-platform")
                        .string(4, "common")
                        .string(4, "optifine")
                        .string(4, "lunar")
                        .message(6, new ProtoWriter()
                                .string(1, "not supplied")
                                .string(4, "not supplied")
                                .string(5, "not supplied")
                                .string(6, "not supplied"))
                        .string(8, UUID.randomUUID().toString()))
                .string(11, "10.0")
                .string(12, "not supplied");
    }

}
