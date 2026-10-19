package com.ootruffle.clienttag;

import com.ootruffle.clienttag.alpine.AlpineSocket;
import com.ootruffle.clienttag.config.ClientTagSettings;
import com.ootruffle.clienttag.render.ClientIcon;
import com.ootruffle.clienttag.platform.Platform;
import com.ootruffle.clienttag.platform.SessionJoins;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
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
 * Looks players up on Alpine's socket at the real client's pace: at most one lookup every
 * 5 s, of up to 500 players nobody has answered for yet, each asked about at most 5 times.
 * Answers stay until we change servers; lookups start again 15 s after that. Like the real
 * client, it tells Alpine which server we're on (packet 162) after logging in and on every
 * server change. Alpine reports no per-player color, so every Alpine user gets the same one.
 * <p>
 * All socket work runs on one background thread - the client thread only snapshots the
 * player list and never waits on the network.
 */
public final class AlpineTagManager {

    private static final Logger LOGGER = LogManager.getLogger("ClientTag");

    /** Our own color for the indicator; Alpine's logo isn't used. */
    private static final int COLOR = 0x4FD1C5;

    private static final int SYNC_INTERVAL_TICKS = 20;
    private static final int MAX_BATCH = 500;
    private static final int MAX_ATTEMPTS = 5;
    private static final long LOOKUP_INTERVAL_MS = 5_000;
    private static final long SERVER_CHANGE_DELAY_MS = 15_000;
    private static final long AUTH_TIMEOUT_MS = 30_000;
    private static final int MAX_LOGGED_LOOKUPS = 5;
    private static final long MIN_BACKOFF_MS = 10_000;
    private static final long MAX_BACKOFF_MS = 300_000;
    private static final long STABLE_CONNECTION_MS = 300_000;

    private static final Set<UUID> alpineUsers = ConcurrentHashMap.newKeySet();
    private static final AtomicReference<Set<UUID>> pendingTracked = new AtomicReference<>();
    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor(r -> {
        final Thread thread = new Thread(r, "ClientTag-Alpine");
        thread.setDaemon(true);
        return thread;
    });

    // Client thread only.
    private static String lastAddress;
    private static boolean wasEnabled = true;
    private static int tickCounter;

    // Background thread only.
    private static boolean active;
    private static String serverAddress;
    private static AlpineSocket socket;
    private static long connectedAt;
    private static long lookupsFrom;
    private static long lastLookup;
    private static int loggedLookups;
    private static final Set<UUID> answered = new HashSet<>();
    private static final Map<UUID, Integer> attempts = new HashMap<>();
    private static long nextConnectAttempt;
    private static long backoff = MIN_BACKOFF_MS;

    private AlpineTagManager() {}

    /** The color of the player's Alpine indicator (RGB), or null if they're not on Alpine (or not known yet). */
    public static Integer getAlpineColor(UUID uuid) {
        // ClientTag logs its users into every client, so theirs are only shown if they said they're really on it.
        return alpineUsers.contains(uuid) && !ClientTagUsers.hides(uuid, ClientIcon.ALPINE) ? COLOR : null;
    }

    public static void onClientTick() {
        final Platform platform = Platform.get();
        // Turned off in the settings: forget the server as far as this client knows, then disconnect.
        final boolean enabled = ClientTagSettings.isEnabled(ClientIcon.ALPINE);
        final String address = enabled ? platform.currentServerAddress() : null;
        if (!Objects.equals(address, lastAddress)) {
            lastAddress = address;
            tickCounter = 0;
            EXECUTOR.execute(() -> changeServer(address));
        }
        if (enabled != wasEnabled) {
            wasEnabled = enabled;
            if (!enabled) {
                EXECUTOR.execute(AlpineTagManager::disconnect);
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
            EXECUTOR.execute(AlpineTagManager::sync);
        }
    }

    private static void addTracked(Set<UUID> tracked, UUID uuid, UUID self) {
        // Real accounts have v4 UUIDs; server-side NPCs (v2 on Hypixel) can't be Alpine users.
        if (uuid != null && uuid.version() == 4 && !uuid.equals(self) && !ClientTagUsers.hides(uuid, ClientIcon.ALPINE)) {
            tracked.add(uuid);
        }
    }

    private static void changeServer(String address) {
        active = address != null;
        serverAddress = address;
        forget();
        lookupsFrom = System.currentTimeMillis() + SERVER_CHANGE_DELAY_MS;
        if (socket != null && socket.isAuthenticated()) {
            sendStatus(socket);
        }
    }

    /**
     * Tells Alpine where we are, as the real client does on joining a world and on leaving one.
     * Without it the server takes us for a client in the menus and ignores lookups.
     */
    private static void sendStatus(AlpineSocket to) {
        if (serverAddress == null) {
            to.status(AlpineSocket.MENUS, AlpineSocket.MENUS);
        } else if (serverAddress.equals(Platform.LAN_HOST)) {
            // Hosting an Open to LAN world: the real client reports its integrated server as singleplayer.
            to.status("singleplayer", "singleplayer");
        } else {
            to.status(host(serverAddress), serverAddress.toLowerCase(Locale.ROOT));
        }
    }

    /** The address without its port or a trailing dot, lowercase, as the real client reports the host. */
    private static String host(String address) {
        String host = address;
        if (host.startsWith("[")) {
            final int end = host.indexOf(']');
            host = end > 0 ? host.substring(1, end) : host;
        } else if (host.indexOf(':') == host.lastIndexOf(':') && host.indexOf(':') >= 0) {
            host = host.substring(0, host.indexOf(':'));
        }
        if (host.endsWith(".")) {
            host = host.substring(0, host.length() - 1);
        }
        return host.toLowerCase(Locale.ROOT);
    }

    private static void sync() {
        final Set<UUID> tracked = pendingTracked.getAndSet(null);
        if (tracked == null || !active || !ensureConnected()) {
            return;
        }
        final long now = System.currentTimeMillis();
        if (!socket.isAuthenticated()) {
            if (now - connectedAt >= AUTH_TIMEOUT_MS) {
                // The login never finished; the next sync reconnects after the backoff.
                LOGGER.warn("Alpine didn't finish logging in within {}s", AUTH_TIMEOUT_MS / 1000);
                socket.close();
            }
            return;
        }
        if (now < lookupsFrom || now - lastLookup < LOOKUP_INTERVAL_MS) {
            return;
        }

        final List<UUID> batch = new ArrayList<>();
        for (UUID uuid : tracked) {
            if (!answered.contains(uuid) && attempts.getOrDefault(uuid, 0) < MAX_ATTEMPTS) {
                batch.add(uuid);
                if (batch.size() == MAX_BATCH) {
                    break;
                }
            }
        }
        if (batch.isEmpty()) {
            return;
        }
        for (UUID uuid : batch) {
            attempts.merge(uuid, 1, Integer::sum);
        }
        socket.lookup(batch);
        lastLookup = now;
        if (loggedLookups++ < MAX_LOGGED_LOOKUPS) {
            LOGGER.info("Asked Alpine about {} players", batch.size());
        }
    }

    private static void onLookup(UUID uuid, boolean alpineUser) {
        // Answers for players asked about before the last server change are stale.
        if (!attempts.containsKey(uuid)) {
            return;
        }
        answered.add(uuid);
        if (alpineUser) {
            alpineUsers.add(uuid);
        } else {
            alpineUsers.remove(uuid);
        }
    }

    private static void authenticate(AlpineSocket from, String serverId) {
        if (from != socket) {
            return;
        }
        try {
            SessionJoins.join(serverId);
            from.answerChallenge();
        } catch (Exception e) {
            LOGGER.warn("Couldn't log in to Alpine ({})", e.toString());
            from.close();
        }
    }

    private static void forget() {
        answered.clear();
        attempts.clear();
        alpineUsers.clear();
    }

    /** Closes the socket; the next sync after the client is turned back on reconnects. */
    private static void disconnect() {
        if (socket != null) {
            socket.close();
            socket = null;
            LOGGER.info("Disconnected from Alpine (turned off in settings)");
        }
        forget();
    }

    private static boolean ensureConnected() {
        if (socket != null && socket.isOpen()) {
            return true;
        }
        final long now = System.currentTimeMillis();
        if (socket != null) {
            LOGGER.info("Alpine socket closed: {}", socket.closeReason());
            if (now - connectedAt >= STABLE_CONNECTION_MS) {
                backoff = MIN_BACKOFF_MS;
            }
            socket = null;
            scheduleRetry(now);
            return false;
        }
        if (now < nextConnectAttempt) {
            return false;
        }

        try {
            final Platform platform = Platform.get();
            socket = AlpineSocket.connect(platform.sessionId(), platform.sessionName(), new AlpineSocket.Listener() {
                @Override
                public void onChallenge(AlpineSocket from, String serverId) {
                    EXECUTOR.execute(() -> authenticate(from, serverId));
                }

                @Override
                public void onAuthenticated(AlpineSocket from) {
                    LOGGER.info("Authenticated with Alpine");
                    EXECUTOR.execute(() -> {
                        if (from == socket) {
                            sendStatus(from);
                        }
                    });
                }

                @Override
                public void onLookup(UUID player, boolean alpineUser) {
                    EXECUTOR.execute(() -> AlpineTagManager.onLookup(player, alpineUser));
                }
            });
            connectedAt = System.currentTimeMillis();
            LOGGER.info("Connected to Alpine");
            return true;
        } catch (Exception e) {
            LOGGER.warn("Couldn't connect to Alpine ({}), retrying in {}s", e.toString(), backoff / 1000);
            scheduleRetry(now);
            return false;
        }
    }

    private static void scheduleRetry(long now) {
        nextConnectAttempt = now + backoff;
        backoff = Math.min(backoff * 2, MAX_BACKOFF_MS);
    }

}
