package com.ootruffle.clienttag;

import com.ootruffle.clienttag.lunar.LunarAuthenticator;
import com.ootruffle.clienttag.lunar.LunarSocket;
import com.ootruffle.clienttag.lunar.ServerMappings;
import com.ootruffle.clienttag.config.ClientTagSettings;
import com.ootruffle.clienttag.render.ClientIcon;
import com.ootruffle.clienttag.platform.Platform;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
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
import net.fabricmc.loader.api.FabricLoader;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Keeps the Lunar asset socket in sync with the players in the current world, the way
 * the real client does: SubscribeV2 for players entering view, Unsubscribe for players
 * leaving it, about every 150 ms. Results land in a UUID-keyed cache that the nametag
 * renderer reads; a player with no entry is simply not on Lunar.
 * <p>
 * All socket work runs on one background thread - the client thread only snapshots the
 * player list and never waits on the network.
 */
public final class LunarTagManager {

    private static final Logger LOGGER = LogManager.getLogger("ClientTag");

    private static final int SYNC_INTERVAL_TICKS = 3;
    private static final int MAX_BATCH = 100;
    private static final long MIN_BACKOFF_MS = 10_000;
    private static final long MAX_BACKOFF_MS = 300_000;
    private static final long STABLE_CONNECTION_MS = 300_000;

    private static final Map<UUID, Integer> lunarColors = new ConcurrentHashMap<>();
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
    private static ServerMappings mappings;
    private static String hostname;
    private static String reportedHost;
    private static String location;
    private static LunarSocket socket;
    private static long connectedAt;
    private static boolean locationReported;
    private static final Set<UUID> subscribed = new HashSet<>();
    private static long nextConnectAttempt;
    private static long backoff = MIN_BACKOFF_MS;
    private static volatile int generation;

    private LunarTagManager() {}

    /** The player's Lunar icon color (RGB), or null if they're not on Lunar (or not known yet). */
    public static Integer getLunarColor(UUID uuid) {
        final Integer color = lunarColors.get(uuid);
        // PolyPlus may learn about a player after Lunar already answered for them.
        return color == null || OneConfigCompat.isPolyPlusUser(uuid) ? null : color;
    }

    public static void onClientTick() {
        final Platform platform = Platform.get();
        // Turned off in the settings: leave the server as far as this client knows, then disconnect.
        final boolean enabled = ClientTagSettings.isEnabled(ClientIcon.LUNAR);
        final String address = enabled ? platform.currentServerAddress() : null;
        if (!Objects.equals(address, lastAddress)) {
            lastAddress = address;
            tickCounter = 0;
            EXECUTOR.execute(() -> changeServer(address));
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
        final Set<UUID> visible = new HashSet<>();
        platform.forEachWorldPlayer((uuid, textures) -> {
            // Real accounts have v4 UUIDs; server-side NPCs (v2 on Hypixel) can't be Lunar users.
            // The real client never looks itself up either. PolyPlus users are most likely
            // running this mod, which would make them show up as Lunar users.
            if (uuid.version() == 4 && !uuid.equals(self) && !OneConfigCompat.isPolyPlusUser(uuid)) {
                visible.add(uuid);
            }
        });
        // Coalesce: if the background thread is still busy (e.g. connecting), the newest snapshot wins.
        if (pendingVisible.getAndSet(visible) == null) {
            EXECUTOR.execute(LunarTagManager::sync);
        }
    }

    private static void changeServer(String address) {
        generation++;
        subscribed.clear();
        lunarColors.clear();
        locationReported = false;
        hostname = address == null ? null : parseHostname(address);
        reportedHost = null;
        location = null;
        if (hostname == null) {
            return;
        }
        // Lunar knows listed servers by one canonical address - "truffle.hypixel.net" has to be
        // reported as "mc.hypixel.net", or Lunar answers for nobody. Anything it doesn't list
        // is reported as typed in.
        final ServerMappings.Server server = isIpLiteral(hostname) ? null : mappings().resolve(hostname);
        reportedHost = server != null ? server.primaryAddress : hostname;
        location = server != null ? server.domain : baseDomain(hostname);
    }

    private static void sync() {
        final Set<UUID> visible = pendingVisible.getAndSet(null);
        if (visible == null || hostname == null || !ensureConnected()) {
            return;
        }
        if (!locationReported) {
            try {
                socket.reportLocation(reportedHost, location);
                locationReported = true;
                LOGGER.info("Reported location {} as {} (connected via {})", location, reportedHost, hostname);
            } catch (Exception e) {
                LOGGER.warn("Couldn't report server location to Lunar: {}", e.toString());
                socket.close();
                return;
            }
        }

        final List<UUID> removed = new ArrayList<>();
        for (UUID uuid : subscribed) {
            if (!visible.contains(uuid)) {
                removed.add(uuid);
            }
        }
        for (UUID uuid : removed) {
            subscribed.remove(uuid);
            socket.unsubscribe(uuid);
        }

        final List<UUID> added = new ArrayList<>();
        for (UUID uuid : visible) {
            if (subscribed.add(uuid)) {
                added.add(uuid);
            }
        }
        for (int i = 0; i < added.size(); i += MAX_BATCH) {
            lookup(new ArrayList<>(added.subList(i, Math.min(i + MAX_BATCH, added.size()))));
        }
    }

    private static void lookup(List<UUID> batch) {
        final int gen = generation;
        socket.subscribe(batch).whenComplete((entries, error) -> {
            if (gen != generation) {
                return;
            }
            if (error != null) {
                LOGGER.warn("SubscribeV2 failed: {}", error.toString());
                // Let the next sync ask about these players again.
                EXECUTOR.execute(() -> {
                    if (gen == generation) {
                        subscribed.removeAll(batch);
                    }
                });
                return;
            }
            for (LunarSocket.Entry entry : entries) {
                lunarColors.put(entry.uuid, entry.color);
            }
            LOGGER.debug("SubscribeV2: asked about {} players, {} on Lunar ({} known this server)",
                    batch.size(), entries.size(), lunarColors.size());
        });
    }

    /** Closes the socket; the next sync after the client is turned back on reconnects. */
    private static void disconnect() {
        if (socket != null) {
            socket.close();
            socket = null;
            LOGGER.info("Disconnected from Lunar (turned off in settings)");
        }
        subscribed.clear();
        lunarColors.clear();
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
            subscribed.clear();
            scheduleRetry(now);
            return false;
        }
        if (now < nextConnectAttempt) {
            return false;
        }

        final Platform platform = Platform.get();
        try {
            final String jwt = LunarAuthenticator.fetchToken(platform.sessionId(), platform.sessionName(), platform::joinServer);
            final LunarSocket connected = LunarSocket.connect(platform.sessionId(), platform.sessionName(), jwt, installationId());
            try {
                connected.login();
            } catch (Exception e) {
                connected.close();
                throw e;
            }
            socket = connected;
            connectedAt = System.currentTimeMillis();
            locationReported = false;
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

    private static ServerMappings mappings() {
        if (mappings == null) {
            mappings = new ServerMappings(FabricLoader.getInstance().getConfigDir().resolve("clienttag/lunar-servers.json").toFile());
        }
        return mappings;
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

    /** "mc.hypixel.net:25565" -> "mc.hypixel.net", "[::1]:25565" -> "::1". No DNS/SRV lookups. */
    static String parseHostname(String address) {
        String host = address.trim().toLowerCase(Locale.ROOT);
        if (host.startsWith("[")) {
            final int end = host.indexOf(']');
            host = end > 0 ? host.substring(1, end) : host.substring(1);
        } else if (host.indexOf(':') == host.lastIndexOf(':') && host.indexOf(':') >= 0) {
            host = host.substring(0, host.indexOf(':'));
        }
        while (host.endsWith(".")) {
            host = host.substring(0, host.length() - 1);
        }
        return host;
    }

    /** Fallback for servers Lunar doesn't list: "play.example.net" -> "example.net"; IP literals are returned unchanged. */
    static String baseDomain(String hostname) {
        if (isIpLiteral(hostname)) {
            return hostname;
        }
        final String[] labels = hostname.split("\\.");
        return labels.length <= 2 ? hostname : labels[labels.length - 2] + "." + labels[labels.length - 1];
    }

    private static boolean isIpLiteral(String hostname) {
        return hostname.contains(":") || hostname.matches("[0-9.]+");
    }

}
