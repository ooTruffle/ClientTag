package com.ootruffle.clienttag;

import com.ootruffle.clienttag.dawn.DawnAuthenticator;
import com.ootruffle.clienttag.dawn.DawnSocket;
import com.ootruffle.clienttag.config.ClientTagSettings;
import com.ootruffle.clienttag.render.ClientIcon;
import com.ootruffle.clienttag.platform.Platform;
import com.ootruffle.clienttag.platform.SessionJoins;
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
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Keeps Dawn's {@code players.tracked} channel in sync with the players around us, the
 * way the real client does: every second, the UUIDs of world players plus the tab list
 * (at most 512) are diffed against what was last sent and sent as add/remove lists, with
 * a full resync every 45 s. Snapshots land in a UUID-keyed rank cache that the renderers
 * read; a player with no snapshot is simply not on Dawn.
 * <p>
 * All socket work runs on one background thread - the client thread only snapshots the
 * player list and never waits on the network.
 */
public final class DawnTagManager {

    private static final Logger LOGGER = LogManager.getLogger("ClientTag");

    private static final int SYNC_INTERVAL_TICKS = 20;
    private static final int MAX_TRACKED = 512;
    private static final int MAX_BATCH = 100;
    private static final long RESYNC_MS = 45_000;
    private static final long TOKEN_REFRESH_MARGIN_MS = 60_000;
    private static final long MIN_BACKOFF_MS = 10_000;
    private static final long MAX_BACKOFF_MS = 300_000;
    private static final long STABLE_CONNECTION_MS = 300_000;

    private static final Map<UUID, Integer> dawnColors = new ConcurrentHashMap<>();
    private static final AtomicReference<Set<UUID>> pendingTracked = new AtomicReference<>();
    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor(r -> {
        final Thread thread = new Thread(r, "ClientTag-Dawn");
        thread.setDaemon(true);
        return thread;
    });

    // Client thread only.
    private static String lastAddress;
    private static boolean wasEnabled = true;
    private static int tickCounter;

    // Background thread only.
    private static boolean active;
    private static DawnSocket socket;
    private static long connectedAt;
    private static long tokenExpiresAt;
    private static long lastResync;
    private static final Set<UUID> sent = new HashSet<>();
    private static final Map<String, UUID> dawnIds = new HashMap<>();
    private static long nextConnectAttempt;
    private static long backoff = MIN_BACKOFF_MS;

    private DawnTagManager() {}

    /** The player's Dawn logo color (RGB), or null if they're not on Dawn (or not known yet). */
    public static Integer getDawnColor(UUID uuid) {
        final Integer color = dawnColors.get(uuid);
        // ClientTag logs its users into every client, so theirs are only shown if they said they're really on it.
        return color == null || ClientTagUsers.hides(uuid, ClientIcon.DAWN) ? null : color;
    }

    public static void onClientTick() {
        final Platform platform = Platform.get();
        // Turned off in the settings: leave the server as far as this client knows, then disconnect.
        final boolean enabled = ClientTagSettings.isEnabled(ClientIcon.DAWN);
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
                EXECUTOR.execute(DawnTagManager::disconnect);
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
            EXECUTOR.execute(DawnTagManager::sync);
        }
    }

    private static void addTracked(Set<UUID> tracked, UUID uuid, UUID self) {
        // Real accounts have v4 UUIDs; server-side NPCs (v2 on Hypixel) can't be Dawn users.
        if (uuid != null && uuid.version() == 4 && !uuid.equals(self) && tracked.size() < MAX_TRACKED
                && !ClientTagUsers.hides(uuid, ClientIcon.DAWN)) {
            tracked.add(uuid);
        }
    }

    private static void changeServer(boolean nowActive) {
        active = nowActive;
        if (socket != null && socket.isSubscribed() && !sent.isEmpty()) {
            sendTrack(new ArrayList<>(), new ArrayList<>(sent));
        }
        sent.clear();
        dawnIds.clear();
        dawnColors.clear();
    }

    private static void sync() {
        final Set<UUID> tracked = pendingTracked.getAndSet(null);
        if (tracked == null || !active || !ensureConnected()) {
            return;
        }
        refreshTokenIfDue();
        if (!socket.isSubscribed()) {
            return;
        }

        final long now = System.currentTimeMillis();
        final List<UUID> removed = new ArrayList<>();
        for (UUID uuid : sent) {
            if (!tracked.contains(uuid)) {
                removed.add(uuid);
            }
        }
        sent.removeAll(removed);
        removed.forEach(dawnColors::remove);

        final List<UUID> added = new ArrayList<>();
        if (now - lastResync >= RESYNC_MS) {
            lastResync = now;
            added.addAll(tracked);
            sent.addAll(tracked);
        } else {
            for (UUID uuid : tracked) {
                if (sent.add(uuid)) {
                    added.add(uuid);
                }
            }
        }
        sendTrack(added, removed);
    }

    /** Sends add/remove lists in frames of at most 100 UUIDs. */
    private static void sendTrack(List<UUID> added, List<UUID> removed) {
        for (int i = 0; i < Math.max(added.size(), removed.size()); i += MAX_BATCH) {
            socket.track(slice(added, i), slice(removed, i));
        }
    }

    private static List<UUID> slice(List<UUID> list, int from) {
        return from >= list.size() ? new ArrayList<>() : new ArrayList<>(list.subList(from, Math.min(from + MAX_BATCH, list.size())));
    }

    private static void onSubscribed() {
        // A fresh subscription knows nothing - send everything again on the next sync.
        sent.clear();
        lastResync = System.currentTimeMillis();
        LOGGER.info("Subscribed to Dawn players.tracked");
    }

    private static void onRank(UUID mcUuid, String dawnId, String rank) {
        if (mcUuid == null) {
            mcUuid = dawnIds.get(dawnId);
            if (mcUuid == null) {
                return;
            }
        } else if (dawnId != null) {
            dawnIds.put(dawnId, mcUuid);
        }
        final Integer color = rankColor(rank);
        if (color == null || !sent.contains(mcUuid)) {
            dawnColors.remove(mcUuid);
        } else {
            dawnColors.put(mcUuid, color);
        }
    }

    /** Logo color per rank, as the real client picks it; null means no logo. */
    static Integer rankColor(String rank) {
        if (rank == null || rank.isEmpty() || rank.equals("RANK_UNSPECIFIED")) {
            return null;
        }
        switch (rank) {
            case "RANK_OWNER":
            case "RANK_DEVELOPER":
                return 0xFF0005;
            case "RANK_ADMIN":
                return 0x9B5AB3;
            case "RANK_STAFF":
                return 0x0075FF;
            case "RANK_PRO":
                return 0xF8FC42;
            case "RANK_PARTNER":
                return 0xFFD700;
            case "RANK_CREATOR":
                return 0x32D5DF;
            default:
                return 0xFFFFFF;
        }
    }

    /** Closes the socket; the next sync after the client is turned back on reconnects. */
    private static void disconnect() {
        if (socket != null) {
            socket.close();
            socket = null;
            LOGGER.info("Disconnected from Dawn (turned off in settings)");
        }
        sent.clear();
        dawnIds.clear();
        dawnColors.clear();
    }

    private static boolean ensureConnected() {
        if (socket != null && socket.isOpen()) {
            return true;
        }
        final long now = System.currentTimeMillis();
        if (socket != null) {
            final String reason = socket.closeReason();
            LOGGER.info("Dawn socket closed: {}", reason);
            if (now - connectedAt >= STABLE_CONNECTION_MS) {
                backoff = MIN_BACKOFF_MS;
            }
            if ("duplicate connection".equals(reason)) {
                // Dawn itself is probably running on this account; don't fight it for the socket.
                backoff = MAX_BACKOFF_MS;
            }
            socket = null;
            sent.clear();
            dawnColors.clear();
            scheduleRetry(now);
            return false;
        }
        if (now < nextConnectAttempt) {
            return false;
        }

        try {
            final DawnAuthenticator.Token token = fetchToken();
            socket = DawnSocket.connect(token.jwt, new DawnSocket.Listener() {
                @Override
                public void onSubscribed() {
                    EXECUTOR.execute(DawnTagManager::onSubscribed);
                }

                @Override
                public void onRank(UUID mcUuid, String dawnId, String rank) {
                    EXECUTOR.execute(() -> DawnTagManager.onRank(mcUuid, dawnId, rank));
                }
            });
            connectedAt = System.currentTimeMillis();
            tokenExpiresAt = token.expiresAt;
            LOGGER.info("Connected to Dawn");
            return true;
        } catch (Exception e) {
            LOGGER.warn("Couldn't connect to Dawn ({}), retrying in {}s", e.toString(), backoff / 1000);
            scheduleRetry(now);
            return false;
        }
    }

    /** Rotates the token shortly before it expires, without dropping the connection. */
    private static void refreshTokenIfDue() {
        if (tokenExpiresAt == 0 || System.currentTimeMillis() < tokenExpiresAt - TOKEN_REFRESH_MARGIN_MS) {
            return;
        }
        try {
            final DawnAuthenticator.Token token = fetchToken();
            socket.reauth(token.jwt);
            tokenExpiresAt = token.expiresAt;
        } catch (Exception e) {
            LOGGER.warn("Couldn't refresh Dawn token: {}", e.toString());
            // Stop retrying every second; the server will drop us once it expires and we reconnect.
            tokenExpiresAt = 0;
        }
    }

    private static DawnAuthenticator.Token fetchToken() throws Exception {
        final Platform platform = Platform.get();
        return DawnAuthenticator.fetchToken(platform.sessionName(), SessionJoins::join);
    }

    private static void scheduleRetry(long now) {
        nextConnectAttempt = now + backoff;
        backoff = Math.min(backoff * 2, MAX_BACKOFF_MS);
    }

}
