package com.ootruffle.clienttag;

import com.ootruffle.clienttag.config.ClientTagSettings;
import com.ootruffle.clienttag.platform.Platform;
import com.ootruffle.clienttag.platform.SessionJoins;
import com.ootruffle.clienttag.presence.PresenceApi;
import com.ootruffle.clienttag.render.ClientIcon;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
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
 * Tells ClientTag users apart from everyone else. ClientTag logs its user into every client's
 * service, so to those services they look like they're on all of them at once. Through the
 * ClientTag server (see {@link PresenceApi}) each ClientTag user says which clients it's
 * really running on (see {@link NativeClients}), and only those of their tags are shown.
 * <p>
 * Players the server doesn't know fall back to the old guess: someone online on PolyPlus is
 * probably running ClientTag, so their other tags are hidden.
 * <p>
 * While on a server, the players around us are looked up as they appear, and everyone is
 * looked up again (which also keeps our own presence alive) every {@link #HEARTBEAT_MS}.
 * All network work runs on one background thread.
 */
public final class ClientTagUsers {

    private static final Logger LOGGER = LogManager.getLogger("ClientTag");

    // At most one request every 5 s (the server allows 30 a minute).
    private static final int SYNC_INTERVAL_TICKS = 100;
    private static final int MAX_TRACKED = 512;
    private static final long HEARTBEAT_MS = 30_000;
    private static final long TOKEN_REFRESH_MARGIN_MS = 60_000;
    private static final long MIN_BACKOFF_MS = 10_000;
    private static final long MAX_BACKOFF_MS = 300_000;
    /** Spotted players are collected and sent at most this often, with the next heartbeat after it. */
    private static final long SEEN_INTERVAL_MS = 300_000;
    /** The server takes at most this many spotted players per sync. */
    private static final int MAX_SEEN = 512;

    /** ClientTag users around us -> the clients they're really on (empty for plain Fabric/Ornithe). */
    private static final Map<UUID, Set<ClientIcon>> users = new ConcurrentHashMap<>();
    /** ClientTag users around us whose ClientTag icon is shown -> its color, set by the server's admin (white by default). */
    private static final Map<UUID, Integer> tagColors = new ConcurrentHashMap<>();
    private static final Answers answers = new Answers(0);
    private static final AtomicReference<Snapshot> pendingTracked = new AtomicReference<>();
    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor(r -> {
        final Thread thread = new Thread(r, "ClientTag-Server");
        thread.setDaemon(true);
        return thread;
    });

    // Client thread only.
    private static String lastAddress;
    private static int tickCounter;

    // Background thread only.
    private static boolean active;
    private static PresenceApi.Token token;
    private static boolean present;
    private static final Set<UUID> looked = new HashSet<>();
    private static long lastHeartbeat;
    private static long nextAttempt;
    private static long backoff = MIN_BACKOFF_MS;
    /** Everyone spotted since the last batch went out, with every client they were seen on. */
    private static final Map<UUID, Set<String>> pendingSeen = new LinkedHashMap<>();
    /** When the first player of the current batch was spotted. */
    private static long seenSince;

    /** The players around us, and the clients we've spotted them on (see {@link #spotted}). */
    private static final class Snapshot {
        final Set<UUID> tracked;
        final Map<UUID, List<String>> seen;

        Snapshot(Set<UUID> tracked, Map<UUID, List<String>> seen) {
            this.tracked = tracked;
            this.seen = seen;
        }
    }

    private ClientTagUsers() {}

    /** Whether the client has said if this player is on it (see {@link WhoCommand}). */
    public static boolean hasAnswered(UUID uuid) {
        return answers.has(uuid);
    }

    /**
     * The ClientTag icon color (RGB) for ClientTag users who chose to show it or aren't really on
     * any other client, else null.
     */
    public static Integer getClientTagColor(UUID uuid) {
        return tagColors.get(uuid);
    }

    /** Whether the ClientTag server says this player runs ClientTag but isn't really on {@code icon}'s client. */
    public static boolean isKnownNotOn(UUID uuid, ClientIcon icon) {
        final Set<ClientIcon> clients = users.get(uuid);
        return clients != null && !clients.contains(icon);
    }

    /** Whether {@code icon} should be hidden for this player, because ClientTag rather than its client put it there. */
    public static boolean hides(UUID uuid, ClientIcon icon) {
        final Set<ClientIcon> clients = users.get(uuid);
        if (clients != null) {
            return !clients.contains(icon);
        }
        return icon != ClientIcon.POLYPLUS && OneConfigCompat.isPolyPlusUser(uuid);
    }

    public static void onClientTick() {
        final Platform platform = Platform.get();
        // Turned off in the settings counts as being out of a world, which leaves the server.
        final String address = ClientTagSettings.useServer() ? platform.currentServerAddress() : null;
        if (!Objects.equals(address, lastAddress)) {
            lastAddress = address;
            tickCounter = SYNC_INTERVAL_TICKS;
            final boolean nowActive = address != null;
            EXECUTOR.execute(() -> changeServer(nowActive));
        }
        if (address == null || ++tickCounter < SYNC_INTERVAL_TICKS) {
            return;
        }
        tickCounter = 0;

        final UUID self = platform.localPlayerId();
        // Players asked about with /clienttag who first, so they're never past the limit.
        final Set<UUID> tracked = new LinkedHashSet<>();
        for (UUID uuid : WhoCommand.wanted()) {
            addTracked(tracked, uuid, self);
        }
        platform.forEachWorldPlayer((uuid, textures) -> addTracked(tracked, uuid, self));
        platform.forEachTabListPlayer((uuid, textures) -> addTracked(tracked, uuid, self));
        final Map<UUID, List<String>> seen = new HashMap<>();
        if (ClientTagSettings.shareSeen()) {
            for (UUID uuid : tracked) {
                final List<String> ids = spotted(uuid);
                if (ids != null) {
                    seen.put(uuid, ids);
                }
            }
        }
        // Coalesce: if the background thread is still busy, the newest snapshot wins.
        if (pendingTracked.getAndSet(new Snapshot(tracked, seen)) == null) {
            EXECUTOR.execute(ClientTagUsers::sync);
        }
    }

    /**
     * The clients we've seen a player on, for the ClientTag server's statistics. Null if we
     * haven't seen them on any, or can't trust what we've seen yet: they run ClientTag (they
     * report their own clients, and the other tags on them are ClientTag's doing), or the
     * ClientTag server hasn't said yet whether they do.
     */
    private static List<String> spotted(UUID uuid) {
        if (users.containsKey(uuid) || !hasAnswered(uuid)) {
            return null;
        }
        final List<String> ids = new ArrayList<>();
        for (ClientIcon icon : ClientIcon.values()) {
            if (icon != ClientIcon.CLIENTTAG && icon != ClientIcon.FLAGGED && icon.reportedColor(uuid) != null) {
                ids.add(icon.id());
            }
        }
        return ids.isEmpty() ? null : ids;
    }

    private static void addTracked(Set<UUID> tracked, UUID uuid, UUID self) {
        if (uuid != null && uuid.version() == 4 && !uuid.equals(self) && tracked.size() < MAX_TRACKED) {
            tracked.add(uuid);
        }
    }

    private static void changeServer(boolean nowActive) {
        active = nowActive;
        looked.clear();
        answers.clear();
        users.clear();
        tagColors.clear();
        lastHeartbeat = 0;
        // Out of a world nobody can see our tags, so there's no point staying listed.
        if (!nowActive && present && token != null) {
            try {
                PresenceApi.leave(token);
            } catch (Exception e) {
                LOGGER.debug("Couldn't leave the ClientTag server: {}", e.toString());
            }
        }
        present = false;
    }

    private static void sync() {
        final Snapshot snapshot = pendingTracked.getAndSet(null);
        final long now = System.currentTimeMillis();
        if (snapshot == null) {
            return;
        }
        // Collected even while waiting to retry, so a short outage loses nothing.
        collectSeen(snapshot.seen, now);
        if (!active || now < nextAttempt) {
            return;
        }
        final Set<UUID> tracked = snapshot.tracked;

        // Players who left are forgotten; new ones are looked up now, everyone on the heartbeat.
        looked.retainAll(tracked);
        users.keySet().retainAll(tracked);
        tagColors.keySet().retainAll(tracked);
        answers.retain(tracked);
        final boolean heartbeat = now - lastHeartbeat >= HEARTBEAT_MS;
        final List<UUID> lookup = new ArrayList<>();
        for (UUID uuid : tracked) {
            if (heartbeat || !looked.contains(uuid)) {
                lookup.add(uuid);
            }
        }
        if (!heartbeat && lookup.isEmpty()) {
            return;
        }

        try {
            if (token == null || now >= token.expiresAt - TOKEN_REFRESH_MARGIN_MS) {
                final Platform platform = Platform.get();
                token = PresenceApi.login(platform.sessionName(), SessionJoins::join);
                LOGGER.info("Connected to the ClientTag server ({})", PresenceApi.URL);
            }
            // Turned off since they were collected: they're never sent.
            if (!ClientTagSettings.shareSeen()) {
                pendingSeen.clear();
            }
            // Spotted players go in one batch every SEEN_INTERVAL_MS, with a heartbeat rather than a request of their own.
            final boolean sendSeen = heartbeat && !pendingSeen.isEmpty() && now - seenSince >= SEEN_INTERVAL_MS;
            final Map<UUID, List<String>> seen = new LinkedHashMap<>();
            if (sendSeen) {
                pendingSeen.forEach((uuid, ids) -> seen.put(uuid, new ArrayList<>(ids)));
            }
            final PresenceApi.SyncResult found = PresenceApi.sync(token, ownClients(), ClientTagSettings.showOwnTag(), lookup, seen);
            if (sendSeen) {
                pendingSeen.clear();
            }
            present = true;
            if (heartbeat) {
                lastHeartbeat = now;
            }
            for (UUID uuid : lookup) {
                final List<String> ids = found.users.get(uuid);
                if (ids == null) {
                    users.remove(uuid);
                } else {
                    users.put(uuid, toIcons(ids));
                }
                final Integer color = found.tags.get(uuid);
                if (color == null) {
                    tagColors.remove(uuid);
                } else {
                    tagColors.put(uuid, color);
                }
            }
            looked.addAll(lookup);
            answers.answered(lookup);
            backoff = MIN_BACKOFF_MS;
        } catch (PresenceApi.UnauthorizedException e) {
            token = null;
            retryLater(now, e);
        } catch (Exception e) {
            retryLater(now, e);
        }
    }

    /** Adds what was just spotted to the batch waiting to be sent; a failed send keeps it for the next try. */
    private static void collectSeen(Map<UUID, List<String>> seen, long now) {
        for (Map.Entry<UUID, List<String>> entry : seen.entrySet()) {
            Set<String> ids = pendingSeen.get(entry.getKey());
            if (ids == null) {
                if (pendingSeen.size() >= MAX_SEEN) {
                    continue;
                }
                if (pendingSeen.isEmpty()) {
                    seenSince = now;
                }
                ids = new LinkedHashSet<>();
                pendingSeen.put(entry.getKey(), ids);
            }
            ids.addAll(entry.getValue());
        }
    }

    private static void retryLater(long now, Exception e) {
        LOGGER.warn("Couldn't reach the ClientTag server ({}), retrying in {}s", e.toString(), backoff / 1000);
        nextAttempt = now + backoff;
        backoff = Math.min(backoff * 2, MAX_BACKOFF_MS);
    }

    private static List<String> ownClients() {
        final List<String> ids = new ArrayList<>();
        for (ClientIcon icon : ClientIcon.values()) {
            if (NativeClients.isRunning(icon)) {
                ids.add(icon.id());
            }
        }
        return ids;
    }

    private static Set<ClientIcon> toIcons(List<String> ids) {
        final Set<ClientIcon> icons = EnumSet.noneOf(ClientIcon.class);
        for (String id : ids) {
            final ClientIcon icon = ClientIcon.byId(id);
            if (icon != null) {
                icons.add(icon);
            }
        }
        return Collections.unmodifiableSet(icons);
    }

}
