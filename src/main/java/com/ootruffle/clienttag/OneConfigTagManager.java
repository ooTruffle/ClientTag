package com.ootruffle.clienttag;

import com.ootruffle.clienttag.config.ClientTagSettings;
import com.ootruffle.clienttag.oneconfig.OneConfigAuthenticator;
import com.ootruffle.clienttag.oneconfig.OneConfigSocket;
import com.ootruffle.clienttag.render.ClientIcon;
import com.ootruffle.clienttag.platform.Platform;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import net.fabricmc.loader.api.FabricLoader;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Finds PolyPlus users when PolyPlus itself isn't installed, the way PolyPlus does: the
 * UUIDs of world players plus the tab list (at most 512) are subscribed on its websocket,
 * which answers with the ones online on PolyPlus and pushes presence changes after that.
 * <p>
 * Only runs without PolyPlus - with it installed, {@link OneConfigCompat} asks PolyPlus
 * directly and PolyPlus draws its own badge. All socket work runs on one background
 * thread - the client thread only snapshots the player list and never waits on the network.
 */
public final class OneConfigTagManager {

    private static final Logger LOGGER = LogManager.getLogger("ClientTag");

    private static final int SYNC_INTERVAL_TICKS = 20;
    private static final int MAX_TRACKED = 512;
    private static final int MAX_BATCH = 64;
    private static final long RETRY_FAILED_MS = 30_000;
    private static final long TOKEN_TTL_MS = 2 * 60 * 60 * 1000L - 5 * 60 * 1000L;
    private static final long MIN_BACKOFF_MS = 10_000;
    private static final long MAX_BACKOFF_MS = 300_000;
    private static final long STABLE_CONNECTION_MS = 300_000;

    private static final Set<UUID> online = ConcurrentHashMap.newKeySet();
    private static final AtomicReference<Set<UUID>> pendingTracked = new AtomicReference<>();
    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor(r -> {
        final Thread thread = new Thread(r, "ClientTag-PolyPlus");
        thread.setDaemon(true);
        return thread;
    });

    // Client thread only.
    private static String lastAddress;
    private static boolean wasEnabled = true;
    private static int tickCounter;

    // Background thread only.
    private static boolean active;
    private static OneConfigSocket socket;
    private static String token;
    private static long tokenExpiresAt;
    private static long connectedAt;
    private static final Set<UUID> subscribed = new HashSet<>();
    private static final Map<Long, List<UUID>> pendingRequests = new HashMap<>();
    private static final Set<UUID> failed = new HashSet<>();
    private static long failedClearedAt;
    private static long nextRequestId;
    private static long nextConnectAttempt;
    private static long backoff = MIN_BACKOFF_MS;

    private OneConfigTagManager() {}

    /** Whether PolyPlus reports the player online right now (only tracked without PolyPlus installed). */
    static boolean isOnline(UUID uuid) {
        return online.contains(uuid);
    }

    public static void onClientTick() {
        final Platform platform = Platform.get();
        // Turned off in the settings: leave the server as far as this client knows, then disconnect.
        final boolean enabled = ClientTagSettings.isEnabled(ClientIcon.POLYPLUS);
        final String address = enabled ? platform.currentServerAddress() : null;
        if (!Objects.equals(address, lastAddress)) {
            lastAddress = address;
            tickCounter = 0;
            final boolean nowActive = address != null;
            EXECUTOR.execute(() -> changeServer(nowActive));
        }
        if (enabled != wasEnabled) {
            wasEnabled = enabled;
            if (!enabled) {
                EXECUTOR.execute(OneConfigTagManager::disconnect);
            }
        }
        if (address == null || ++tickCounter < SYNC_INTERVAL_TICKS) {
            return;
        }
        tickCounter = 0;

        final UUID self = platform.localPlayerId();
        final Set<UUID> tracked = new HashSet<>();
        platform.forEachWorldPlayer((uuid, textures) -> addTracked(tracked, uuid, self));
        platform.forEachTabListPlayer((uuid, textures) -> addTracked(tracked, uuid, self));
        // Coalesce: if the background thread is still busy (e.g. connecting), the newest snapshot wins.
        if (pendingTracked.getAndSet(tracked) == null) {
            EXECUTOR.execute(OneConfigTagManager::sync);
        }
    }

    private static void addTracked(Set<UUID> tracked, UUID uuid, UUID self) {
        // Real accounts have v4 UUIDs; PolyPlus ignores everything else.
        if (uuid != null && uuid.version() == 4 && !uuid.equals(self) && tracked.size() < MAX_TRACKED) {
            tracked.add(uuid);
        }
    }

    private static void changeServer(boolean nowActive) {
        active = nowActive;
        if (socket != null && socket.isOpen() && !subscribed.isEmpty()) {
            unsubscribe(new ArrayList<>(subscribed));
        }
        subscribed.clear();
        pendingRequests.clear();
        failed.clear();
        online.clear();
    }

    private static void sync() {
        final Set<UUID> tracked = pendingTracked.getAndSet(null);
        if (tracked == null || !active || !ensureConnected()) {
            return;
        }

        final long now = System.currentTimeMillis();
        if (now - failedClearedAt >= RETRY_FAILED_MS) {
            failedClearedAt = now;
            failed.clear();
        }

        final List<UUID> removed = new ArrayList<>();
        for (UUID uuid : subscribed) {
            if (!tracked.contains(uuid)) {
                removed.add(uuid);
            }
        }
        if (!removed.isEmpty()) {
            subscribed.removeAll(removed);
            removed.forEach(online::remove);
            unsubscribe(removed);
        }

        final List<UUID> added = new ArrayList<>();
        for (UUID uuid : tracked) {
            if (!failed.contains(uuid) && subscribed.add(uuid)) {
                added.add(uuid);
            }
        }
        for (int i = 0; i < added.size(); i += MAX_BATCH) {
            final List<UUID> batch = new ArrayList<>(added.subList(i, Math.min(i + MAX_BATCH, added.size())));
            final long requestId = ++nextRequestId;
            pendingRequests.put(requestId, batch);
            socket.subscribe(batch, requestId);
        }
    }

    private static void unsubscribe(List<UUID> players) {
        for (int i = 0; i < players.size(); i += MAX_BATCH) {
            socket.unsubscribe(new ArrayList<>(players.subList(i, Math.min(i + MAX_BATCH, players.size()))));
        }
    }

    private static void onSnapshot(long requestId, List<UUID> users, List<UUID> rejected) {
        pendingRequests.remove(requestId);
        for (UUID uuid : users) {
            // Late answers for players who already left (or a previous server) don't count.
            if (subscribed.contains(uuid)) {
                online.add(uuid);
            }
        }
        // Rejected players stay marked as subscribed, so they aren't asked about again on this server.
        if (!rejected.isEmpty()) {
            LOGGER.debug("PolyPlus rejected {} subscription(s)", rejected.size());
        }
        LOGGER.debug("PolyPlus snapshot: {} online ({} known this server)", users.size(), online.size());
    }

    private static void onPresence(UUID player, boolean isOnline) {
        if (isOnline && subscribed.contains(player)) {
            online.add(player);
        } else {
            online.remove(player);
        }
    }

    private static void onError(long requestId, String code) {
        final List<UUID> batch = pendingRequests.remove(requestId);
        if (batch == null) {
            LOGGER.debug("PolyPlus error: {}", code);
            return;
        }
        LOGGER.warn("PolyPlus rejected subscription request {} ({}); retrying {} player(s) later",
                requestId, code, batch.size());
        subscribed.removeAll(batch);
        failed.addAll(batch);
    }

    /** Closes the socket; the next sync after the client is turned back on reconnects. */
    private static void disconnect() {
        if (socket != null) {
            socket.close();
            socket = null;
            LOGGER.info("Disconnected from PolyPlus (turned off in settings)");
        }
        subscribed.clear();
        pendingRequests.clear();
        failed.clear();
        online.clear();
    }

    private static boolean ensureConnected() {
        if (socket != null && socket.isOpen()) {
            return true;
        }
        final long now = System.currentTimeMillis();
        if (socket != null) {
            final String reason = socket.closeReason();
            LOGGER.info("PolyPlus socket closed: {}", reason);
            if (now - connectedAt >= STABLE_CONNECTION_MS) {
                backoff = MIN_BACKOFF_MS;
            }
            if (reason != null && (reason.contains("401") || reason.contains("Unauthorized"))) {
                token = null;
            }
            socket = null;
            subscribed.clear();
            pendingRequests.clear();
            online.clear();
            scheduleRetry(now);
            return false;
        }
        if (now < nextConnectAttempt) {
            return false;
        }

        try {
            if (token == null || now >= tokenExpiresAt) {
                token = fetchToken();
                tokenExpiresAt = now + TOKEN_TTL_MS;
            }
            socket = connect(token);
            connectedAt = System.currentTimeMillis();
            LOGGER.info("Connected to PolyPlus");
            return true;
        } catch (Exception e) {
            // A rejected handshake doesn't say why, so the token may be the problem - get a new one next time.
            token = null;
            LOGGER.warn("Couldn't connect to PolyPlus ({}), retrying in {}s", e.toString(), backoff / 1000);
            scheduleRetry(now);
            return false;
        }
    }

    private static OneConfigSocket connect(String jwt) throws Exception {
        return OneConfigSocket.connect(jwt, new OneConfigSocket.Listener() {
            @Override
            public void onSnapshot(long requestId, List<UUID> users, List<UUID> rejected) {
                EXECUTOR.execute(() -> OneConfigTagManager.onSnapshot(requestId, users, rejected));
            }

            @Override
            public void onPresence(UUID player, boolean isOnline) {
                EXECUTOR.execute(() -> OneConfigTagManager.onPresence(player, isOnline));
            }

            @Override
            public void onError(long requestId, String code) {
                EXECUTOR.execute(() -> OneConfigTagManager.onError(requestId, code));
            }
        });
    }

    private static String fetchToken() throws Exception {
        final Platform platform = Platform.get();
        return OneConfigAuthenticator.fetchToken(platform.sessionName(), clientVersion(), "1.8.9", platform::joinServer);
    }

    /** "clienttag/1.0.0" - honest about which client is asking. */
    private static String clientVersion() {
        return "clienttag/" + FabricLoader.getInstance().getModContainer("clienttag")
                .map(mod -> mod.getMetadata().getVersion().getFriendlyString())
                .orElse("unknown");
    }

    private static void scheduleRetry(long now) {
        nextConnectAttempt = now + backoff;
        backoff = Math.min(backoff * 2, MAX_BACKOFF_MS);
    }

}
