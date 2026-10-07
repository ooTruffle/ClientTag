package com.ootruffle.clienttag;

import com.ootruffle.clienttag.config.ClientTagSettings;
import com.ootruffle.clienttag.labymod.LabyConnectSocket;
import com.ootruffle.clienttag.labymod.LabyRoles;
import com.ootruffle.clienttag.render.ClientIcon;
import com.ootruffle.clienttag.platform.Platform;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Finds LabyMod users the way LabyMod does: the tab list and world players (at most 512) go
 * into LabyConnect's {@code player_list} viewport, and the server answers with each one's
 * user data - whether they're on LabyMod and their role. Changes go out as deltas, with a
 * full snapshot at most once a minute.
 * <p>
 * Only runs without LabyMod - with it installed, LabyMod draws its own indicator and holds
 * its own connection on this account. All socket work runs on one background thread - the
 * client thread only snapshots the player list and never waits on the network.
 */
public final class LabyModTagManager {

    private static final Logger LOGGER = LogManager.getLogger("ClientTag");

    /** For players without a role; LabyMod's plain wolf is grey, and its art isn't used here. */
    private static final int DEFAULT_COLOR = 0xD8D8D8;

    private static final int SYNC_INTERVAL_TICKS = 60;
    private static final int MAX_TRACKED = 512;
    private static final long SNAPSHOT_INTERVAL_MS = 60_000;
    private static final long MIN_BACKOFF_MS = 10_000;
    private static final long MAX_BACKOFF_MS = 300_000;
    private static final long STABLE_CONNECTION_MS = 300_000;

    private static final boolean LABYMOD_INSTALLED =
            LabyModTagManager.class.getClassLoader().getResource("net/labymod/api/Laby.class") != null;

    /** LabyMod users -> their visible role (0 = none). */
    private static final Map<UUID, Integer> users = new ConcurrentHashMap<>();
    private static final AtomicReference<Map<UUID, String>> pendingTracked = new AtomicReference<>();
    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor(r -> {
        final Thread thread = new Thread(r, "ClientTag-LabyMod-Sync");
        thread.setDaemon(true);
        return thread;
    });

    // Client thread only.
    private static String lastAddress;
    private static boolean wasEnabled = true;
    private static int tickCounter;

    // Background thread only.
    private static boolean active;
    private static boolean rolesLoaded;
    private static LabyConnectSocket socket;
    private static long connectedAt;
    private static final Map<UUID, LabyConnectSocket.ViewportPlayer> viewport = new HashMap<>();
    private static long lastSnapshotAt;
    private static long nextConnectAttempt;
    private static long backoff = MIN_BACKOFF_MS;

    private LabyModTagManager() {}

    /** The LabyMod indicator color (RGB), or null if they're not on LabyMod (or it's not known yet). */
    public static Integer getLabyModColor(UUID uuid) {
        final Integer role = users.get(uuid);
        // PolyPlus users are most likely running this mod, which logs them into LabyConnect.
        if (role == null || OneConfigCompat.isPolyPlusUser(uuid)) {
            return null;
        }
        final Integer color = role == 0 ? null : LabyRoles.color(role);
        return color != null ? color : DEFAULT_COLOR;
    }

    /** Whether LabyMod itself is running - it then draws its own indicator. */
    public static boolean isLabyModInstalled() {
        return LABYMOD_INSTALLED;
    }

    public static void onClientTick() {
        final Platform platform = Platform.get();
        // Turned off in the settings: leave the server as far as this client knows, then disconnect.
        final boolean enabled = ClientTagSettings.isEnabled(ClientIcon.LABYMOD);
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
                EXECUTOR.execute(LabyModTagManager::disconnect);
            }
        }
        if (address == null || ++tickCounter < SYNC_INTERVAL_TICKS) {
            return;
        }
        tickCounter = 0;

        // UUID -> base64 textures property (or null), for the skin/cape IDs LabyMod sends along.
        final UUID self = platform.localPlayerId();
        final Map<UUID, String> tracked = new HashMap<>();
        platform.forEachTabListPlayer((uuid, textures) -> addTracked(tracked, uuid, textures, self));
        platform.forEachWorldPlayer((uuid, textures) -> addTracked(tracked, uuid, textures, self));
        // Coalesce: if the background thread is still busy (e.g. connecting), the newest snapshot wins.
        if (pendingTracked.getAndSet(tracked) == null) {
            EXECUTOR.execute(LabyModTagManager::sync);
        }
    }

    private static void addTracked(Map<UUID, String> tracked, UUID uuid, String textures, UUID self) {
        // Real accounts have v4 UUIDs; LabyMod leaves NPCs and offline players out too.
        if (uuid == null || uuid.version() != 4 || uuid.equals(self) || tracked.containsKey(uuid)
                || tracked.size() >= MAX_TRACKED || OneConfigCompat.isPolyPlusUser(uuid)) {
            return;
        }
        tracked.put(uuid, textures);
    }

    private static void changeServer(boolean nowActive) {
        active = nowActive;
        // LabyMod empties the viewport on a server switch, then fills it with the new one.
        if (socket != null && socket.isOpen() && !viewport.isEmpty()) {
            socket.sendViewport(false, Collections.<LabyConnectSocket.ViewportPlayer>emptyList(), new ArrayList<>(viewport.keySet()));
        }
        viewport.clear();
        users.clear();
    }

    private static void sync() {
        final Map<UUID, String> tracked = pendingTracked.getAndSet(null);
        if (tracked == null || !active || !ensureConnected()) {
            return;
        }

        final List<UUID> removed = new ArrayList<>();
        for (UUID uuid : viewport.keySet()) {
            if (!tracked.containsKey(uuid)) {
                removed.add(uuid);
            }
        }
        removed.forEach(viewport::remove);
        removed.forEach(users::remove);

        final List<LabyConnectSocket.ViewportPlayer> added = new ArrayList<>();
        for (Map.Entry<UUID, String> entry : tracked.entrySet()) {
            if (!viewport.containsKey(entry.getKey())) {
                final LabyConnectSocket.ViewportPlayer player = LabyConnectSocket.ViewportPlayer.of(entry.getKey(), entry.getValue());
                viewport.put(entry.getKey(), player);
                added.add(player);
            }
        }

        final long now = System.currentTimeMillis();
        if (now - lastSnapshotAt >= SNAPSHOT_INTERVAL_MS) {
            lastSnapshotAt = now;
            socket.sendViewport(true, new ArrayList<>(viewport.values()), Collections.<UUID>emptyList());
        } else if (!added.isEmpty() || !removed.isEmpty()) {
            socket.sendViewport(false, added, removed);
        }
    }

    private static void onUserData(UUID player, boolean usingLabyMod, int roleId) {
        // Late answers for players who already left (or a previous server) don't count.
        if (usingLabyMod && viewport.containsKey(player)) {
            users.put(player, roleId);
        } else {
            users.remove(player);
        }
    }

    /** Closes the socket; the next sync after the client is turned back on reconnects. */
    private static void disconnect() {
        if (socket != null) {
            socket.close();
            socket = null;
            LOGGER.info("Disconnected from LabyConnect (turned off in settings)");
        }
        viewport.clear();
        users.clear();
    }

    private static boolean ensureConnected() {
        if (socket != null && socket.isOpen()) {
            return true;
        }
        final long now = System.currentTimeMillis();
        if (socket != null) {
            final String reason = socket.closeReason();
            LOGGER.info("LabyConnect socket closed: {}", reason);
            if (now - connectedAt >= STABLE_CONNECTION_MS) {
                backoff = MIN_BACKOFF_MS;
            }
            socket = null;
            viewport.clear();
            users.clear();
            if (reason != null && reason.contains("server_restart")) {
                // A planned restart: come back shortly, the way LabyMod does.
                nextConnectAttempt = now + 100 + ThreadLocalRandom.current().nextInt(400);
            } else {
                scheduleRetry(now);
            }
            return false;
        }
        if (now < nextConnectAttempt) {
            return false;
        }

        if (!rolesLoaded) {
            try {
                LabyRoles.fetch();
                rolesLoaded = true;
            } catch (Exception e) {
                // Players still get the indicator, just without role colors; tried again on the next connect.
                LOGGER.warn("Couldn't load LabyMod roles ({})", e.toString());
            }
        }

        try {
            final Platform platform = Platform.get();
            socket = LabyConnectSocket.connect(platform.sessionName(), platform.sessionId(),
                    platform::joinServer,
                    new LabyConnectSocket.Listener() {
                        @Override
                        public void onUserData(UUID player, boolean usingLabyMod, int roleId) {
                            EXECUTOR.execute(() -> LabyModTagManager.onUserData(player, usingLabyMod, roleId));
                        }

                        @Override
                        public void onUserRemoved(UUID player) {
                            EXECUTOR.execute(() -> users.remove(player));
                        }
                    });
            connectedAt = System.currentTimeMillis();
            // A new connection has an empty viewport: start with a full snapshot.
            lastSnapshotAt = 0;
            LOGGER.info("Connected to LabyConnect");
            return true;
        } catch (Exception e) {
            LOGGER.warn("Couldn't connect to LabyConnect ({}), retrying in {}s", e.toString(), backoff / 1000);
            scheduleRetry(now);
            return false;
        }
    }

    private static void scheduleRetry(long now) {
        nextConnectAttempt = now + backoff;
        backoff = Math.min(backoff * 2, MAX_BACKOFF_MS);
    }

}
