package com.ootruffle.clienttag;

import com.ootruffle.clienttag.cmclient.CMClientSocket;
import com.ootruffle.clienttag.config.ClientTagSettings;
import com.ootruffle.clienttag.platform.Platform;
import com.ootruffle.clienttag.render.ClientIcon;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
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
import java.util.regex.Pattern;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Looks players up by name on CMClient's session socket. The real client asks about each
 * player once, as they show up, draining its queue once a second; this does the same, with
 * one lookup in flight at a time (its answer lists only the CMClient users, so everyone else
 * in that lookup is not on it) and up to 3 tries for lookups that go unanswered. Answers stay
 * until we change servers. CMClient's role badges aren't used, so every user gets the same color.
 * <p>
 * All socket work runs on one background thread - the client thread only snapshots the
 * player list and never waits on the network.
 */
public final class CMClientTagManager {

    private static final Logger LOGGER = LogManager.getLogger("ClientTag");

    /** Our own color for the indicator; CMClient's logo isn't used. */
    private static final int COLOR = 0xC77DFF;

    /** Names the real client is willing to look up. */
    private static final Pattern VALID_NAME = Pattern.compile("[A-Za-z0-9_]{1,16}");

    private static final int SYNC_INTERVAL_TICKS = 20;
    private static final int MAX_BATCH = 100;
    private static final int MAX_ATTEMPTS = 3;
    private static final long LOOKUP_INTERVAL_MS = 1_000;
    private static final long LOOKUP_TIMEOUT_MS = 10_000;
    private static final long MIN_BACKOFF_MS = 10_000;
    private static final long MAX_BACKOFF_MS = 300_000;
    private static final long STABLE_CONNECTION_MS = 300_000;

    private static final Set<UUID> cmUsers = ConcurrentHashMap.newKeySet();
    private static final Answers answers = new Answers(0);
    private static final AtomicReference<Map<UUID, String>> pendingTracked = new AtomicReference<>();
    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor(r -> {
        final Thread thread = new Thread(r, "ClientTag-CMClient");
        thread.setDaemon(true);
        return thread;
    });

    // Client thread only.
    private static String lastAddress;
    private static boolean wasEnabled = true;
    private static int tickCounter;

    // Background thread only.
    private static boolean active;
    private static CMClientSocket socket;
    private static long connectedAt;
    private static long lastLookup;
    /** The lookup waiting for its answer, by lowercase name; empty when none is. */
    private static final Map<String, UUID> inFlight = new HashMap<>();
    private static final Map<UUID, Integer> attempts = new HashMap<>();
    private static long nextConnectAttempt;
    private static long backoff = MIN_BACKOFF_MS;

    private CMClientTagManager() {}

    /** Whether the client has said if this player is on it (see {@link WhoCommand}). */
    public static boolean hasAnswered(UUID uuid) {
        return answers.has(uuid);
    }

    /** The color of the player's CMClient indicator (RGB), or null if they're not on CMClient (or not known yet). */
    public static Integer getCMClientColor(UUID uuid) {
        // ClientTag logs its users into every client, so theirs are only shown if they said they're really on it.
        return cmUsers.contains(uuid) && !ClientTagUsers.hides(uuid, ClientIcon.CMCLIENT) ? COLOR : null;
    }

    public static void onClientTick() {
        final Platform platform = Platform.get();
        final boolean enabled = ClientTagSettings.isEnabled(ClientIcon.CMCLIENT);
        final String address = enabled ? platform.currentServerAddress() : null;
        if (!Objects.equals(address, lastAddress)) {
            lastAddress = address;
            tickCounter = 0;
            EXECUTOR.execute(() -> changeServer(address));
        }
        if (enabled != wasEnabled) {
            wasEnabled = enabled;
            if (!enabled) {
                EXECUTOR.execute(CMClientTagManager::disconnect);
            }
        }
        if (address == null || ++tickCounter < SYNC_INTERVAL_TICKS) {
            return;
        }
        tickCounter = 0;

        final UUID self = platform.localPlayerId();
        final Set<UUID> wanted = WhoCommand.wanted();
        // Players asked about with /clienttag who first, so they're never past the batch limit.
        final Map<UUID, String> first = new LinkedHashMap<>();
        final Map<UUID, String> rest = new LinkedHashMap<>();
        platform.forEachPlayerName((uuid, name) -> {
            // Real accounts have v4 UUIDs; server-side NPCs (v2 on Hypixel) can't be CMClient users.
            if (uuid != null && name != null && uuid.version() == 4 && !uuid.equals(self)
                    && VALID_NAME.matcher(name).matches() && !ClientTagUsers.hides(uuid, ClientIcon.CMCLIENT)) {
                (wanted.contains(uuid) ? first : rest).putIfAbsent(uuid, name);
            }
        });
        rest.forEach(first::putIfAbsent);
        // Coalesce: if the background thread is still busy (e.g. connecting), the newest snapshot wins.
        if (pendingTracked.getAndSet(first) == null) {
            EXECUTOR.execute(CMClientTagManager::sync);
        }
    }

    private static void changeServer(String address) {
        active = address != null;
        forget();
    }

    private static void sync() {
        final Map<UUID, String> tracked = pendingTracked.getAndSet(null);
        if (tracked == null || !active || !ensureConnected()) {
            return;
        }
        final long now = System.currentTimeMillis();
        if (!inFlight.isEmpty()) {
            if (now - lastLookup < LOOKUP_TIMEOUT_MS) {
                return;
            }
            // Never answered: those players are tried again, up to MAX_ATTEMPTS.
            inFlight.clear();
        }
        if (now - lastLookup < LOOKUP_INTERVAL_MS) {
            return;
        }

        final List<String> batch = new ArrayList<>();
        for (Map.Entry<UUID, String> player : tracked.entrySet()) {
            final UUID uuid = player.getKey();
            final String name = player.getValue();
            if (!answers.has(uuid) && attempts.getOrDefault(uuid, 0) < MAX_ATTEMPTS
                    && inFlight.putIfAbsent(name.toLowerCase(Locale.ROOT), uuid) == null) {
                attempts.merge(uuid, 1, Integer::sum);
                batch.add(name);
                if (batch.size() == MAX_BATCH) {
                    break;
                }
            }
        }
        if (batch.isEmpty()) {
            return;
        }
        socket.lookup(batch);
        lastLookup = now;
    }

    /** The answer to the lookup in flight: these names are on CMClient, the rest of it isn't. */
    private static void onOnlineList(CMClientSocket from, List<String> online) {
        // An answer to a lookup from before the last server change (or timed out) is stale.
        if (from != socket || inFlight.isEmpty()) {
            return;
        }
        for (String name : online) {
            final UUID uuid = inFlight.remove(name.toLowerCase(Locale.ROOT));
            if (uuid != null) {
                cmUsers.add(uuid);
                answers.answered(uuid);
            }
        }
        for (UUID uuid : inFlight.values()) {
            cmUsers.remove(uuid);
            answers.answered(uuid);
        }
        inFlight.clear();
    }

    private static void forget() {
        answers.clear();
        attempts.clear();
        inFlight.clear();
        cmUsers.clear();
    }

    /** Closes the socket; the next sync after the client is turned back on reconnects. */
    private static void disconnect() {
        if (socket != null) {
            socket.close();
            socket = null;
            LOGGER.info("Disconnected from CMClient (turned off in settings)");
        }
        forget();
    }

    private static boolean ensureConnected() {
        if (socket != null && socket.isOpen()) {
            return true;
        }
        final long now = System.currentTimeMillis();
        if (socket != null) {
            LOGGER.info("CMClient socket closed: {}", socket.closeReason());
            if (now - connectedAt >= STABLE_CONNECTION_MS) {
                backoff = MIN_BACKOFF_MS;
            }
            socket = null;
            inFlight.clear();
            scheduleRetry(now);
            return false;
        }
        if (now < nextConnectAttempt) {
            return false;
        }

        try {
            final CMClientSocket[] opened = new CMClientSocket[1];
            opened[0] = CMClientSocket.connect(Platform.get().sessionName(),
                    names -> EXECUTOR.execute(() -> onOnlineList(opened[0], names)));
            socket = opened[0];
            connectedAt = System.currentTimeMillis();
            LOGGER.info("Connected to CMClient");
            return true;
        } catch (Exception e) {
            LOGGER.warn("Couldn't connect to CMClient ({}), retrying in {}s", e.toString(), backoff / 1000);
            scheduleRetry(now);
            return false;
        }
    }

    private static void scheduleRetry(long now) {
        nextConnectAttempt = now + backoff;
        backoff = Math.min(backoff * 2, MAX_BACKOFF_MS);
    }

}
