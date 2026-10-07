package com.ootruffle.clienttag;

import com.ootruffle.clienttag.config.ClientTagSettings;
import com.ootruffle.clienttag.platform.Platform;
import com.ootruffle.clienttag.platform.SessionJoins;
import com.ootruffle.clienttag.presence.PresenceApi;
import com.ootruffle.clienttag.render.ClientIcon;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
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
    /** Our own color for the ClientTag icon. */
    private static final int COLOR = 0xB48CFF;

    private static final int SYNC_INTERVAL_TICKS = 100;
    private static final int MAX_TRACKED = 512;
    private static final long HEARTBEAT_MS = 30_000;
    private static final long TOKEN_REFRESH_MARGIN_MS = 60_000;
    private static final long MIN_BACKOFF_MS = 10_000;
    private static final long MAX_BACKOFF_MS = 300_000;

    /** ClientTag users around us -> the clients they're really on (empty for plain Fabric/Ornithe). */
    private static final Map<UUID, Set<ClientIcon>> users = new ConcurrentHashMap<>();
    private static final AtomicReference<Set<UUID>> pendingTracked = new AtomicReference<>();
    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor(r -> {
        final Thread thread = new Thread(r, "ClientTag-Server");
        thread.setDaemon(true);
        return thread;
    });

    // Client thread only.
    private static String lastAddress;
    private static boolean wasEnabled = true;
    private static int tickCounter;

    // Background thread only.
    private static boolean active;
    private static PresenceApi.Token token;
    private static boolean present;
    private static final Set<UUID> looked = new HashSet<>();
    private static long lastHeartbeat;
    private static long nextAttempt;
    private static long backoff = MIN_BACKOFF_MS;

    private ClientTagUsers() {}

    /** The ClientTag icon color (RGB) for ClientTag users who aren't really on any other client, else null. */
    public static Integer getClientTagColor(UUID uuid) {
        final Set<ClientIcon> clients = users.get(uuid);
        return clients != null && clients.isEmpty() ? COLOR : null;
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
        final boolean enabled = ClientTagSettings.useServer();
        final String address = enabled ? platform.currentServerAddress() : null;
        if (!Objects.equals(address, lastAddress)) {
            lastAddress = address;
            tickCounter = SYNC_INTERVAL_TICKS;
            final boolean nowActive = address != null;
            EXECUTOR.execute(() -> changeServer(nowActive));
        }
        if (enabled != wasEnabled) {
            wasEnabled = enabled;
            if (!enabled) {
                EXECUTOR.execute(() -> changeServer(false));
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
        // Coalesce: if the background thread is still busy, the newest snapshot wins.
        if (pendingTracked.getAndSet(tracked) == null) {
            EXECUTOR.execute(ClientTagUsers::sync);
        }
    }

    private static void addTracked(Set<UUID> tracked, UUID uuid, UUID self) {
        if (uuid != null && uuid.version() == 4 && !uuid.equals(self) && tracked.size() < MAX_TRACKED) {
            tracked.add(uuid);
        }
    }

    private static void changeServer(boolean nowActive) {
        active = nowActive;
        looked.clear();
        users.clear();
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
        final Set<UUID> tracked = pendingTracked.getAndSet(null);
        final long now = System.currentTimeMillis();
        if (tracked == null || !active || now < nextAttempt) {
            return;
        }

        // Players who left are forgotten; new ones are looked up now, everyone on the heartbeat.
        looked.retainAll(tracked);
        users.keySet().retainAll(tracked);
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
            final Map<UUID, List<String>> found = PresenceApi.sync(token, ownClients(), lookup);
            present = true;
            if (heartbeat) {
                lastHeartbeat = now;
            }
            for (UUID uuid : lookup) {
                final List<String> ids = found.get(uuid);
                if (ids == null) {
                    users.remove(uuid);
                } else {
                    users.put(uuid, toIcons(ids));
                }
            }
            looked.addAll(lookup);
            backoff = MIN_BACKOFF_MS;
        } catch (PresenceApi.UnauthorizedException e) {
            token = null;
            retryLater(now, e);
        } catch (Exception e) {
            retryLater(now, e);
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
