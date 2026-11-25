package com.ootruffle.clienttag;

import com.ootruffle.clienttag.lunar.LunarAuthenticator;
import com.ootruffle.clienttag.lunar.LunarSocket;
import com.ootruffle.clienttag.config.ClientTagSettings;
import com.ootruffle.clienttag.render.ClientIcon;
import com.ootruffle.clienttag.platform.Platform;
import com.ootruffle.clienttag.platform.SessionJoins;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashSet;
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
import net.fabricmc.loader.api.FabricLoader;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Keeps one Lunar game websocket open and asks {@code LoadTabLogos} about players in
 * the tab list and the world, in batches, the way Lunar itself does for tab icons. Results
 * land in a UUID-keyed cache that the nametag renderer reads; a player who isn't in the
 * response is simply not on Lunar.
 * <p>
 * All socket work runs on one background thread - the client thread only snapshots the
 * player list and never waits on the network.
 * <p>
 * Players asked about with {@code /clienttag who} (see {@link WhoCommand#wanted}) count as in
 * view until it has answered, so they're looked up even from across the map.
 */
public final class LunarTagManager {

    private static final Logger LOGGER = LogManager.getLogger("ClientTag");

    private static final int SYNC_INTERVAL_TICKS = 20;
    private static final int MAX_TRACKED = 512;
    private static final int MAX_BATCH = 100;
    private static final long MIN_BACKOFF_MS = 10_000;
    private static final long MAX_BACKOFF_MS = 300_000;
    private static final long STABLE_CONNECTION_MS = 300_000;

    private static final Map<UUID, Integer> lunarColors = new ConcurrentHashMap<>();
    private static final Answers answers = new Answers(0);
    private static final AtomicReference<Set<UUID>> pendingVisible = new AtomicReference<>();
    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor(r -> {
        final Thread thread = new Thread(r, "ClientTag");
        thread.setDaemon(true);
        return thread;
    });

    // Client thread only.
    private static String lastAddress;
    private static boolean wasEnabled = true;
    private static int tickCounter;

    // Background thread only (generation is also read by response callbacks).
    private static boolean active;
    private static LunarSocket socket;
    private static long connectedAt;
    private static final Set<UUID> asked = new HashSet<>();
    private static long nextConnectAttempt;
    private static long backoff = MIN_BACKOFF_MS;
    private static volatile int generation;

    private LunarTagManager() {}

    /** The player's Lunar icon color (RGB), or null if they're not on Lunar (or not known yet). */
    public static Integer getLunarColor(UUID uuid) {
        final Integer color = lunarColors.get(uuid);
        // We may learn a player runs ClientTag after Lunar already answered for them.
        return color == null || ClientTagUsers.hides(uuid, ClientIcon.LUNAR) ? null : color;
    }

    /** Whether Lunar has said if this player is on it, since we joined this server. */
    public static boolean hasAnswered(UUID uuid) {
        return answers.has(uuid);
    }

    public static void onClientTick() {
        final Platform platform = Platform.get();
        // Turned off in the settings: leave the server as far as this client knows, then disconnect.
        final boolean enabled = ClientTagSettings.isEnabled(ClientIcon.LUNAR);
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
                EXECUTOR.execute(LunarTagManager::disconnect);
            }
        }
        if (address == null || ++tickCounter < SYNC_INTERVAL_TICKS) {
            return;
        }
        tickCounter = 0;

        final UUID self = platform.localPlayerId();
        // Players asked about with /clienttag who first, so they're never past the limit.
        final Set<UUID> visible = new LinkedHashSet<>();
        for (UUID uuid : WhoCommand.wanted()) {
            addVisible(visible, uuid, self);
        }
        platform.forEachWorldPlayer((uuid, textures) -> addVisible(visible, uuid, self));
        platform.forEachTabListPlayer((uuid, textures) -> addVisible(visible, uuid, self));
        // Coalesce: if the background thread is still busy (e.g. connecting), the newest snapshot wins.
        if (pendingVisible.getAndSet(visible) == null) {
            EXECUTOR.execute(LunarTagManager::sync);
        }
    }

    private static void addVisible(Set<UUID> visible, UUID uuid, UUID self) {
        // Real accounts have v4 UUIDs; server-side NPCs (v2 on Hypixel) can't be Lunar users.
        // The real client never looks itself up either. ClientTag users who aren't really
        // on Lunar would show up as Lunar users, since ClientTag logs them in.
        if (uuid != null && uuid.version() == 4 && !uuid.equals(self) && visible.size() < MAX_TRACKED
                && !ClientTagUsers.hides(uuid, ClientIcon.LUNAR)) {
            visible.add(uuid);
        }
    }

    private static void changeServer(boolean nowActive) {
        generation++;
        asked.clear();
        lunarColors.clear();
        answers.clear();
        active = nowActive;
    }

    private static void sync() {
        final Set<UUID> visible = pendingVisible.getAndSet(null);
        if (visible == null || !active || !ensureConnected()) {
            return;
        }

        final List<UUID> added = new ArrayList<>();
        for (UUID uuid : visible) {
            if (asked.add(uuid)) {
                added.add(uuid);
            }
        }
        for (int i = 0; i < added.size(); i += MAX_BATCH) {
            lookup(new ArrayList<>(added.subList(i, Math.min(i + MAX_BATCH, added.size()))));
        }
    }

    private static void lookup(List<UUID> batch) {
        final int gen = generation;
        socket.loadTabLogos(batch).whenComplete((entries, error) -> {
            if (gen != generation) {
                return;
            }
            if (error != null) {
                LOGGER.warn("LoadTabLogos failed: {}", error.toString());
                // Let the next sync ask about these players again.
                EXECUTOR.execute(() -> {
                    if (gen == generation) {
                        asked.removeAll(batch);
                    }
                });
                return;
            }
            for (LunarSocket.Entry entry : entries) {
                lunarColors.put(entry.uuid, entry.color);
            }
            answers.answered(batch);
            LOGGER.debug("LoadTabLogos: asked about {} players, {} on Lunar ({} known this server)",
                    batch.size(), entries.size(), lunarColors.size());
        });
    }

    private static void onLogo(LunarSocket.Entry entry) {
        lunarColors.put(entry.uuid, entry.color);
        answers.answered(entry.uuid);
    }

    /** Closes the socket; the next sync after the client is turned back on reconnects. */
    private static void disconnect() {
        if (socket != null) {
            socket.close();
            socket = null;
            LOGGER.info("Disconnected from Lunar (turned off in settings)");
        }
        asked.clear();
        lunarColors.clear();
        answers.clear();
        active = false;
    }

    private static boolean ensureConnected() {
        if (socket != null && socket.isOpen()) {
            return true;
        }
        final long now = System.currentTimeMillis();
        if (socket != null) {
            LOGGER.info("Lunar socket closed");
            if (now - connectedAt >= STABLE_CONNECTION_MS) {
                backoff = MIN_BACKOFF_MS;
            }
            socket = null;
            asked.clear();
            scheduleRetry(now);
            return false;
        }
        if (now < nextConnectAttempt) {
            return false;
        }

        final Platform platform = Platform.get();
        final int gen = generation;
        try {
            final String jwt = LunarAuthenticator.fetchToken(platform.sessionId(), platform.sessionName(), SessionJoins::join);
            final LunarSocket connected = LunarSocket.connect(platform.sessionId(), platform.sessionName(), jwt, installationId());
            try {
                connected.onLogo(entry -> {
                    if (gen == generation) {
                        onLogo(entry);
                    }
                });
                connected.login();
            } catch (Exception e) {
                connected.close();
                throw e;
            }
            socket = connected;
            connectedAt = System.currentTimeMillis();
            LOGGER.info("Connected to Lunar");
            return true;
        } catch (Exception e) {
            LOGGER.warn("Couldn't connect to Lunar ({}), retrying in {}s", e.toString(), backoff / 1000);
            scheduleRetry(now);
            return false;
        }
    }

    private static void scheduleRetry(long now) {
        nextConnectAttempt = now + backoff;
        backoff = Math.min(backoff * 2, MAX_BACKOFF_MS);
    }

    /** A random ID persisted per install, like the launcher's own installation ID. */
    private static String installationId() {
        final File file = FabricLoader.getInstance().getConfigDir().resolve("clienttag/installation-id.txt").toFile();
        try {
            if (file.isFile()) {
                final String id = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8).trim();
                if (!id.isEmpty()) {
                    return id;
                }
            }
            final String id = UUID.randomUUID().toString();
            file.getParentFile().mkdirs();
            Files.write(file.toPath(), id.getBytes(StandardCharsets.UTF_8));
            return id;
        } catch (Exception e) {
            LOGGER.warn("Couldn't persist installation ID: {}", e.toString());
            return UUID.randomUUID().toString();
        }
    }

}
