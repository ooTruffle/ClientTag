package com.ootruffle.clienttag;

import com.ootruffle.clienttag.essential.EssentialAuthenticator;
import com.ootruffle.clienttag.essential.EssentialSocket;
import com.ootruffle.clienttag.config.ClientTagSettings;
import com.ootruffle.clienttag.render.ClientIcon;
import com.ootruffle.clienttag.platform.Platform;
import java.util.ArrayList;
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
 * Finds Essential users the way Essential does: the UUIDs of world players plus the tab list
 * (at most 512) are subscribed on its connection manager, each new one's selected outfit is
 * requested, and outfit changes are pushed after that. A player whose outfit has something
 * in the {@code ICON} slot gets the indicator - that's exactly what Essential itself draws.
 * <p>
 * Only runs without Essential - with it installed, Essential draws its own indicator and
 * holds its own connection on this account. All socket work runs on one background
 * thread - the client thread only snapshots the player list and never waits on the network.
 */
public final class EssentialTagManager {

    private static final Logger LOGGER = LogManager.getLogger("ClientTag");

    /** Our own color for the indicator; Essential's own icon art isn't used. */
    private static final int COLOR = 0x47A3FF;
    private static final String ICON_SLOT = "ICON";

    private static final int SYNC_INTERVAL_TICKS = 20;
    private static final int MAX_TRACKED = 512;
    private static final int MAX_BATCH = 100;
    private static final long MIN_BACKOFF_MS = 10_000;
    private static final long MAX_BACKOFF_MS = 300_000;
    private static final long STABLE_CONNECTION_MS = 300_000;

    /**
     * Essential's loader stub registers as a mod, while the real mod is downloaded and loaded
     * later - so its classes are looked for too. This class is first loaded on the first
     * client tick, by which point Essential is loaded if it's going to be.
     */
    private static final boolean ESSENTIAL_INSTALLED = FabricLoader.getInstance().isModLoaded("essential")
            || FabricLoader.getInstance().isModLoaded("essential-container")
            || EssentialTagManager.class.getClassLoader().getResource("gg/essential/Essential.class") != null;

    private static final Set<UUID> iconUsers = ConcurrentHashMap.newKeySet();
    private static final AtomicReference<Set<UUID>> pendingTracked = new AtomicReference<>();
    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor(r -> {
        final Thread thread = new Thread(r, "ClientTag-Essential");
        thread.setDaemon(true);
        return thread;
    });

    // Client thread only.
    private static String lastAddress;
    private static boolean wasEnabled = true;
    private static int tickCounter;

    // Background thread only.
    private static boolean active;
    private static boolean disabled;
    private static EssentialSocket socket;
    private static long connectedAt;
    private static final Set<UUID> subscribed = new HashSet<>();
    private static long nextConnectAttempt;
    private static long backoff = MIN_BACKOFF_MS;

    private EssentialTagManager() {}

    /** The Essential indicator color (RGB), or null if they have no Essential icon (or it's not known yet). */
    public static Integer getEssentialColor(UUID uuid) {
        // PolyPlus users are most likely running this mod, which logs them into Essential.
        return iconUsers.contains(uuid) && !OneConfigCompat.isPolyPlusUser(uuid) ? COLOR : null;
    }

    /** Whether Essential itself is installed - it then draws its own indicator. */
    public static boolean isEssentialInstalled() {
        return ESSENTIAL_INSTALLED;
    }

    public static void onClientTick() {
        final Platform platform = Platform.get();
        // Turned off in the settings: leave the server as far as this client knows, then disconnect.
        final boolean enabled = ClientTagSettings.isEnabled(ClientIcon.ESSENTIAL);
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
                EXECUTOR.execute(EssentialTagManager::disconnect);
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
            EXECUTOR.execute(EssentialTagManager::sync);
        }
    }

    private static void addTracked(Set<UUID> tracked, UUID uuid, UUID self) {
        // Real accounts have v4 UUIDs; Essential skips NPCs and nicked players (anything else) too.
        if (uuid != null && uuid.version() == 4 && !uuid.equals(self) && tracked.size() < MAX_TRACKED
                && !OneConfigCompat.isPolyPlusUser(uuid)) {
            tracked.add(uuid);
        }
    }

    private static void changeServer(boolean nowActive) {
        active = nowActive;
        if (socket != null && socket.isOpen() && !subscribed.isEmpty()) {
            updateSubscription(new ArrayList<>(subscribed), false);
        }
        subscribed.clear();
        iconUsers.clear();
    }

    private static void sync() {
        final Set<UUID> tracked = pendingTracked.getAndSet(null);
        if (tracked == null || !active || !ensureConnected()) {
            return;
        }

        final List<UUID> removed = new ArrayList<>();
        for (UUID uuid : subscribed) {
            if (!tracked.contains(uuid)) {
                removed.add(uuid);
            }
        }
        if (!removed.isEmpty()) {
            subscribed.removeAll(removed);
            removed.forEach(iconUsers::remove);
            updateSubscription(removed, false);
        }

        final List<UUID> added = new ArrayList<>();
        for (UUID uuid : tracked) {
            if (subscribed.add(uuid)) {
                added.add(uuid);
            }
        }
        if (!added.isEmpty()) {
            updateSubscription(added, true);
            // Subscribing only brings future changes - the current outfit has to be asked for.
            added.forEach(socket::requestOutfit);
        }
    }

    /** Sends subscription updates in packets of at most 100 UUIDs. */
    private static void updateSubscription(List<UUID> players, boolean subscribe) {
        for (int i = 0; i < players.size(); i += MAX_BATCH) {
            socket.updateSubscription(new ArrayList<>(players.subList(i, Math.min(i + MAX_BATCH, players.size()))), subscribe);
        }
    }

    private static void onEquipped(UUID player, Map<String, String> equipped) {
        // Late answers for players who already left (or a previous server) don't count.
        if (equipped.containsKey(ICON_SLOT) && subscribed.contains(player)) {
            iconUsers.add(player);
        } else {
            iconUsers.remove(player);
        }
    }

    /** Closes the socket; the next sync after the client is turned back on reconnects. */
    private static void disconnect() {
        if (socket != null) {
            socket.close();
            socket = null;
            LOGGER.info("Disconnected from Essential (turned off in settings)");
        }
        subscribed.clear();
        iconUsers.clear();
    }

    private static boolean ensureConnected() {
        if (disabled) {
            return false;
        }
        if (socket != null && socket.isOpen()) {
            return true;
        }
        final long now = System.currentTimeMillis();
        if (socket != null) {
            LOGGER.info("Essential socket closed: {}", socket.closeReason());
            if (socket.closeCode() == EssentialSocket.CLOSE_SUSPENDED) {
                LOGGER.warn("Essential says this account is suspended; Essential indicators are off until restart");
                disabled = true;
            }
            if (now - connectedAt >= STABLE_CONNECTION_MS) {
                backoff = MIN_BACKOFF_MS;
            }
            socket = null;
            subscribed.clear();
            iconUsers.clear();
            scheduleRetry(now);
            return false;
        }
        if (now < nextConnectAttempt) {
            return false;
        }

        try {
            final Platform platform = Platform.get();
            final String authorization = EssentialAuthenticator.authorize(platform.sessionName(), platform::joinServer);
            socket = EssentialSocket.connect(platform.sessionId(), platform.sessionName(), authorization,
                    (player, equipped) -> EXECUTOR.execute(() -> onEquipped(player, equipped)));
            connectedAt = System.currentTimeMillis();
            LOGGER.info("Connected to Essential");
            return true;
        } catch (EssentialSocket.OutdatedException e) {
            // Retrying won't help until the protocol version in EssentialSocket is updated.
            LOGGER.warn("Essential indicators are off until restart: {}", e.getMessage());
            disabled = true;
            return false;
        } catch (Exception e) {
            LOGGER.warn("Couldn't connect to Essential ({}), retrying in {}s", e.toString(), backoff / 1000);
            scheduleRetry(now);
            return false;
        }
    }

    private static void scheduleRetry(long now) {
        nextConnectAttempt = now + backoff;
        backoff = Math.min(backoff * 2, MAX_BACKOFF_MS);
    }

}
