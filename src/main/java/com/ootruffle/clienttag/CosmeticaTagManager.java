package com.ootruffle.clienttag;

import com.ootruffle.clienttag.config.ClientTagSettings;
import com.ootruffle.clienttag.cosmetica.CosmeticaApi;
import com.ootruffle.clienttag.platform.Platform;
import com.ootruffle.clienttag.render.ClientIcon;
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
 * Finds Cosmetica users through Cosmetica's public user lookup (see {@link CosmeticaApi}),
 * which needs no login. There's no batch lookup, so world players plus the tab list are
 * asked about a few at a time: new players first, then whoever was checked longest ago.
 * Users are checked again every minute (they may go online or offline), everyone else
 * every ten.
 * <p>
 * Every Cosmetica user gets Cosmetica's halo - its per-modpack icons aren't used.
 * All network work runs on one background thread - the client thread only snapshots the
 * player list and never waits on the network.
 */
public final class CosmeticaTagManager {

    private static final Logger LOGGER = LogManager.getLogger("ClientTag");

    /** White: the halo is drawn in its own colors. */
    private static final int COLOR = 0xFFFFFF;

    private static final int SYNC_INTERVAL_TICKS = 20;
    private static final int MAX_TRACKED = 512;
    /** Lookups per sync (one a second), so at most 4 requests a second. */
    private static final int LOOKUPS_PER_SYNC = 4;
    private static final long USER_REFRESH_MS = 60_000;
    private static final long NON_USER_REFRESH_MS = 600_000;
    private static final long MIN_BACKOFF_MS = 10_000;
    private static final long MAX_BACKOFF_MS = 300_000;

    private static final Set<UUID> online = ConcurrentHashMap.newKeySet();
    private static final AtomicReference<Set<UUID>> pendingTracked = new AtomicReference<>();
    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor(r -> {
        final Thread thread = new Thread(r, "ClientTag-Cosmetica");
        thread.setDaemon(true);
        return thread;
    });

    // Client thread only.
    private static String lastAddress;
    private static boolean wasEnabled = true;
    private static int tickCounter;

    // Background thread only.
    private static boolean active;
    /** Players looked up on this server -> when to look them up again. */
    private static final Map<UUID, Long> nextCheck = new HashMap<>();
    private static long nextAttempt;
    private static long backoff = MIN_BACKOFF_MS;

    private CosmeticaTagManager() {}

    /** The player's Cosmetica indicator color (RGB), or null if they're not online on Cosmetica (or not known yet). */
    public static Integer getCosmeticaColor(UUID uuid) {
        // ClientTag never logs anyone into Cosmetica, so unlike the other clients there's nothing to hide.
        return online.contains(uuid) ? COLOR : null;
    }

    public static void onClientTick() {
        final Platform platform = Platform.get();
        final boolean enabled = ClientTagSettings.isEnabled(ClientIcon.COSMETICA);
        final String address = enabled ? platform.currentServerAddress() : null;
        if (!Objects.equals(address, lastAddress)) {
            lastAddress = address;
            tickCounter = 0;
            final boolean nowActive = address != null;
            EXECUTOR.execute(() -> changeServer(nowActive));
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
            EXECUTOR.execute(CosmeticaTagManager::sync);
        }
    }

    private static void addTracked(Set<UUID> tracked, UUID uuid, UUID self) {
        // Real accounts have v4 UUIDs; server-side NPCs can't be Cosmetica users.
        if (uuid != null && uuid.version() == 4 && !uuid.equals(self) && tracked.size() < MAX_TRACKED) {
            tracked.add(uuid);
        }
    }

    private static void changeServer(boolean nowActive) {
        active = nowActive;
        nextCheck.clear();
        online.clear();
    }

    private static void sync() {
        final Set<UUID> tracked = pendingTracked.getAndSet(null);
        if (tracked == null || !active) {
            return;
        }
        // Players who left are forgotten.
        nextCheck.keySet().retainAll(tracked);
        online.retainAll(tracked);

        final long now = System.currentTimeMillis();
        if (now < nextAttempt) {
            return;
        }
        final List<UUID> due = new ArrayList<>();
        for (UUID uuid : tracked) {
            if (nextCheck.getOrDefault(uuid, 0L) <= now) {
                due.add(uuid);
            }
        }
        // Never-checked players (0) first, then the most overdue.
        due.sort((a, b) -> Long.compare(nextCheck.getOrDefault(a, 0L), nextCheck.getOrDefault(b, 0L)));

        for (UUID uuid : due.subList(0, Math.min(LOOKUPS_PER_SYNC, due.size()))) {
            final CosmeticaApi.Status status;
            try {
                status = CosmeticaApi.lookup(uuid);
            } catch (Exception e) {
                LOGGER.warn("Couldn't reach Cosmetica ({}), retrying in {}s", e.toString(), backoff / 1000);
                nextAttempt = now + backoff;
                backoff = Math.min(backoff * 2, MAX_BACKOFF_MS);
                return;
            }
            backoff = MIN_BACKOFF_MS;
            nextCheck.put(uuid, now + (status == CosmeticaApi.Status.NOT_A_USER ? NON_USER_REFRESH_MS : USER_REFRESH_MS));
            if (status == CosmeticaApi.Status.ONLINE) {
                online.add(uuid);
            } else {
                online.remove(uuid);
            }
        }
    }

}
