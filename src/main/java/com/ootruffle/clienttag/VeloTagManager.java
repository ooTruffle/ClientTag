package com.ootruffle.clienttag;

import com.ootruffle.clienttag.config.ClientTagSettings;
import com.ootruffle.clienttag.platform.Platform;
import com.ootruffle.clienttag.render.ClientIcon;
import com.ootruffle.clienttag.velo.VeloApi;
import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Finds Velo Client users through Velo's public online list (see {@link VeloApi}), which needs
 * no login. The list covers every Velo user online anywhere, so one request answers for every
 * player at once; it's fetched every 15 seconds while we're on a server (the real client asks
 * every 5). Players are matched by UUID only - unlike Velo itself, which falls back to names
 * and so would badge anyone sharing a Velo user's name on offline-mode servers.
 * <p>
 * Velo Plus users get gold, everyone else Velo blue. All network work runs on one background
 * thread - the client thread only snapshots the player list and never waits on the network.
 */
public final class VeloTagManager {

    private static final Logger LOGGER = LogManager.getLogger("ClientTag");

    /** Our own colors for the indicator; Velo's badge art isn't used. */
    private static final int COLOR = 0x4DA6FF;
    private static final int PLUS_COLOR = 0xFFC94A;

    private static final int SYNC_INTERVAL_TICKS = 20;
    private static final long REFRESH_MS = 15_000;
    private static final long MIN_BACKOFF_MS = 10_000;
    private static final long MAX_BACKOFF_MS = 300_000;

    /** The last online list (uuid -> Plus); replaced whole, so readers never see it half-built. */
    private static volatile Map<UUID, Boolean> online = Collections.emptyMap();
    private static final Answers answers = new Answers(0);
    private static final AtomicReference<Set<UUID>> pendingTracked = new AtomicReference<>();
    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor(r -> {
        final Thread thread = new Thread(r, "ClientTag-Velo");
        thread.setDaemon(true);
        return thread;
    });

    // Client thread only.
    private static String lastAddress;
    private static int tickCounter;

    // Background thread only.
    private static boolean active;
    private static boolean fetched;
    private static long nextFetch;
    private static long backoff = MIN_BACKOFF_MS;

    private VeloTagManager() {}

    /** Whether the client has said if this player is on it (see {@link WhoCommand}). */
    public static boolean hasAnswered(UUID uuid) {
        return answers.has(uuid);
    }

    /** The player's Velo indicator color (RGB), or null if they're not online on Velo (or not known yet). */
    public static Integer getVeloColor(UUID uuid) {
        // ClientTag never logs anyone into Velo, so unlike most clients there's nothing to hide.
        final Boolean plus = online.get(uuid);
        return plus == null ? null : plus ? PLUS_COLOR : COLOR;
    }

    public static void onClientTick() {
        final Platform platform = Platform.get();
        final boolean enabled = ClientTagSettings.isEnabled(ClientIcon.VELO);
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
        final Set<UUID> tracked = new HashSet<>(WhoCommand.wanted());
        platform.forEachWorldPlayer((uuid, textures) -> tracked.add(uuid));
        platform.forEachTabListPlayer((uuid, textures) -> tracked.add(uuid));
        tracked.remove(null);
        tracked.remove(self);
        // Coalesce: if the background thread is still busy, the newest snapshot wins.
        if (pendingTracked.getAndSet(tracked) == null) {
            EXECUTOR.execute(VeloTagManager::sync);
        }
    }

    private static void changeServer(boolean nowActive) {
        active = nowActive;
        fetched = false;
        // Fetch straight away on the new server; the list itself isn't per-server, but answers are.
        nextFetch = 0;
        answers.clear();
        if (!nowActive) {
            online = Collections.emptyMap();
        }
    }

    private static void sync() {
        final Set<UUID> tracked = pendingTracked.getAndSet(null);
        if (tracked == null || !active) {
            return;
        }
        answers.retain(tracked);
        final long now = System.currentTimeMillis();
        if (now >= nextFetch) {
            try {
                online = Collections.unmodifiableMap(VeloApi.online());
                fetched = true;
                backoff = MIN_BACKOFF_MS;
                nextFetch = now + REFRESH_MS;
            } catch (Exception e) {
                LOGGER.warn("Couldn't reach Velo ({}), retrying in {}s", e.toString(), backoff / 1000);
                nextFetch = now + backoff;
                backoff = Math.min(backoff * 2, MAX_BACKOFF_MS);
            }
        }
        // The list names every online Velo user, so anyone not on it has been answered for too.
        if (fetched) {
            answers.answered(tracked);
        }
    }

}
