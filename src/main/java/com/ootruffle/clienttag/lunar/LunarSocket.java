package com.ootruffle.clienttag.lunar;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * The game AssetServer socket ({@code wss://websocket.lunarclientprod.com/game})
 * and its protobuf RPC envelope:
 * <ul>
 *     <li>First frame: a raw {@code Handshake} carrying the authenticator JWT</li>
 *     <li>Request: { 1: request ID (ASCII "1", "2", …), 2: service, 3: method, 4: body }</li>
 *     <li>Response: { 1: { 1: request ID, 2: body } }</li>
 *     <li>Push: { 2: google.protobuf.Any }</li>
 * </ul>
 * Nametag / tab icons come from {@code CosmeticService.LoadTabLogos}, and from
 * {@code PlayerCosmeticsPushV2} if the server sends one.
 */
public final class LunarSocket {

    static final String CLIENT_VERSION = "v2.23.0-2640";
    static final String LAUNCHER_VERSION = "3.7.27-ow";
    static final String USER_AGENT = "Lunar Client " + CLIENT_VERSION;
    static final String LAUNCHER_USER_AGENT = "Lunar Client Launcher v" + LAUNCHER_VERSION;

    private static final String SOCKET_URL = "wss://websocket.lunarclientprod.com/game";
    private static final String COSMETIC_SERVICE = "lunarclient.websocket.cosmetic.v2.CosmeticService";
    private static final long CALL_TIMEOUT_MS = 10_000;

    /** A Lunar user, with their nametag icon color (RGB). */
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
    private volatile Consumer<Entry> logoHandler;

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
        final Map<String, String> headers = new LinkedHashMap<>();
        headers.put("User-Agent", USER_AGENT);
        headers.put("Accept", "application/x-protobuf");
        final WsConnection ws = WsConnection.open(SOCKET_URL, headers);
        ws.send(handshake(uuid, name, jwt, installationId).toByteArray());
        return new LunarSocket(ws);
    }

    public boolean isOpen() {
        return ws.isOpen();
    }

    public void close() {
        ws.close();
    }

    /** Called when the server pushes logo colour for a player ({@code PlayerCosmeticsPushV2}). */
    public void onLogo(Consumer<Entry> handler) {
        this.logoHandler = handler;
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

    /** CosmeticService.Login; empty request. Not required just to call LoadTabLogos, but the launcher does it. */
    public void login() throws Exception {
        await(call(COSMETIC_SERVICE, "Login", null));
    }

    /**
     * LoadTabLogos: { 1: repeated uuid } → { 1: repeated TabLogo }. The response lists only
     * Lunar users; each entry has logo colour, optional Lunar+ colour, and badge id.
     */
    public CompletableFuture<List<Entry>> loadTabLogos(Collection<UUID> uuids) {
        final ProtoWriter body = new ProtoWriter();
        for (UUID uuid : uuids) {
            body.message(1, ProtoWriter.uuid(uuid));
        }
        return call(COSMETIC_SERVICE, "LoadTabLogos", body).thenApply(res -> {
            final List<Entry> entries = new ArrayList<>();
            for (ProtoMessage e : res.messages(1)) {
                final Entry entry = logoEntry(e);
                if (entry != null) {
                    entries.add(entry);
                }
            }
            return entries;
        });
    }

    private void onMessage(byte[] buf) {
        final ProtoMessage msg;
        try {
            msg = ProtoMessage.parse(buf);
        } catch (IllegalArgumentException e) {
            return;
        }
        final ProtoMessage any = msg.message(2);
        if (any != null) {
            onPush(any);
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

    private void onPush(ProtoMessage any) {
        final String type = any.string(1);
        if (type == null || !type.endsWith("PlayerCosmeticsPushV2") || logoHandler == null) {
            return;
        }
        final byte[] value = any.bytes(2);
        if (value == null) {
            return;
        }
        final Entry entry;
        try {
            entry = logoEntry(ProtoMessage.parse(value));
        } catch (IllegalArgumentException e) {
            return;
        }
        if (entry != null) {
            logoHandler.accept(entry);
        }
    }

    /** TabLogo / PlayerCosmeticsPushV2: { 1: uuid, 2: logo_color }. */
    private static Entry logoEntry(ProtoMessage e) {
        final ProtoMessage player = e.message(1);
        if (player == null) {
            return null;
        }
        final ProtoMessage color = e.message(2);
        final int rgb = color == null ? 0xFFFFFF : (int) color.varint(1, 0xFFFFFF) & 0xFFFFFF;
        return new Entry(player.toUuid(), rgb);
    }

    private static void await(CompletableFuture<ProtoMessage> call) throws Exception {
        call.get(CALL_TIMEOUT_MS + 1000, TimeUnit.MILLISECONDS);
    }

    /**
     * First frame: identity + JWT + game handshake. The launcher-v3 socket is Cloudflare
     * locked to Electron; this is the game path {@code lunar.jar} uses.
     */
    private static ProtoWriter handshake(UUID uuid, String name, String jwt, String installationId) {
        return new ProtoWriter()
                .message(1, new ProtoWriter()
                        .message(1, ProtoWriter.uuidAndUsername(uuid, name))
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
                .string(11, "10.0");
    }

}
