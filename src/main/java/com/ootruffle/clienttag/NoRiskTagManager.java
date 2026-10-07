package com.ootruffle.clienttag;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.ootruffle.clienttag.norisk.NoRiskApi;
import com.ootruffle.clienttag.norisk.NoRiskSocket;
import com.ootruffle.clienttag.config.ClientTagSettings;
import com.ootruffle.clienttag.render.ClientIcon;
import com.ootruffle.clienttag.platform.Platform;
import com.ootruffle.clienttag.platform.SessionJoins;
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
 * Finds NoRiskClient users the way NoRiskClient does: every second the tab list plus world
 * players (at most 150) are diffed against the socket's subscription and sent as
 * {@code NrcSubscriptionUpdate}s. The server answers with each player's
 * {@code NoRiskUserMinimal} record (or null), and pushes changes after that. A player with a
 * record is an online NoRisk user; their selected icon and NoRisk+ pick the color.
 * <p>
 * While the socket is down, the REST {@code users/online} lookup fills in instead.
 * All network work runs on one background thread - the client thread only snapshots the
 * player list and never waits on the network.
 */
public final class NoRiskTagManager {

    private static final Logger LOGGER = LogManager.getLogger("ClientTag");

    /** Our own colors for the indicator; NoRisk's icon art isn't used. */
    private static final int COLOR = 0xFF5A5A;
    private static final int PLUS_COLOR = 0xFFC83D;
    private static final Map<UUID, Integer> ICON_COLORS = new HashMap<>();

    static {
        ICON_COLORS.put(UUID.fromString("6f377aad-d56c-4f48-941e-212472faec80"), 0xE53935); // ADMIN
        ICON_COLORS.put(UUID.fromString("fec7a04c-9354-42a4-9b25-21b6b131b425"), 0xFF7043); // DEVELOPER
        ICON_COLORS.put(UUID.fromString("ac46e7e9-111d-4daa-9d72-23547379ccee"), 0xAB47BC); // DESIGNER
        ICON_COLORS.put(UUID.fromString("39c239c6-2ed0-45fb-98f2-6959ed1b4ce7"), 0x42A5F5); // HELPER
        ICON_COLORS.put(UUID.fromString("3d3eef4a-cace-463b-980f-19af523b1321"), 0x66BB6A); // BUG_HUNTER
        ICON_COLORS.put(UUID.fromString("63f403ea-7bed-47ea-913b-04c52df0b68b"), 0xFFD54F); // VIP
        ICON_COLORS.put(UUID.fromString("f9add58d-d466-4d6d-ace0-e20d71f68f0f"), 0xFFB300); // GOLD_DONATOR
        ICON_COLORS.put(UUID.fromString("66f167bc-6762-4750-b6ce-3a6bb837d5f0"), 0xCFD8DC); // SILVER_DONATOR
        ICON_COLORS.put(UUID.fromString("ecaf77d3-b0bb-47a7-b922-d72318fef104"), 0xCD7F32); // BRONZE_DONATOR
    }

    private static final int SYNC_INTERVAL_TICKS = 20;
    private static final int MAX_TRACKED = 150;
    private static final int MAX_BATCH = 100;
    private static final int MAX_REST_BATCH = 80;
    private static final long REST_REFRESH_MS = 60_000;
    private static final long TOKEN_REFRESH_MARGIN_MS = 60_000;
    private static final long MIN_BACKOFF_MS = 10_000;
    private static final long MAX_BACKOFF_MS = 300_000;
    private static final long STABLE_CONNECTION_MS = 300_000;

    private static final Map<UUID, Integer> noRiskColors = new ConcurrentHashMap<>();
    private static final AtomicReference<Set<UUID>> pendingTracked = new AtomicReference<>();
    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor(r -> {
        final Thread thread = new Thread(r, "ClientTag-NoRisk");
        thread.setDaemon(true);
        return thread;
    });

    // Client thread only.
    private static String lastAddress;
    private static boolean wasEnabled = true;
    private static int tickCounter;

    // Background thread only.
    private static boolean active;
    private static NoRiskSocket socket;
    private static NoRiskApi.Token token;
    private static long connectedAt;
    private static final Set<UUID> subscribed = new HashSet<>();
    private static final Map<UUID, Long> restCheckedAt = new HashMap<>();
    private static long nextConnectAttempt;
    private static long backoff = MIN_BACKOFF_MS;
    // Diagnostics: a few sample records and a periodic count, to check what the server sends.
    private static final int SAMPLE_RECORDS = 8;
    private static final long SUMMARY_INTERVAL_MS = 30_000;
    private static int sampledRecords;
    private static int nullRecords;
    private static long lastSummary;

    private NoRiskTagManager() {}

    /** The player's NoRisk indicator color (RGB), or null if they're not on NoRisk (or not known yet). */
    public static Integer getNoRiskColor(UUID uuid) {
        final Integer color = noRiskColors.get(uuid);
        // ClientTag logs its users into every client, so theirs are only shown if they said they're really on it.
        return color == null || ClientTagUsers.hides(uuid, ClientIcon.NORISK) ? null : color;
    }

    public static void onClientTick() {
        final Platform platform = Platform.get();
        // Turned off in the settings: leave the server as far as this client knows, then disconnect.
        final boolean enabled = ClientTagSettings.isEnabled(ClientIcon.NORISK);
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
                EXECUTOR.execute(NoRiskTagManager::disconnect);
            }
        }
        if (address == null || ++tickCounter < SYNC_INTERVAL_TICKS) {
            return;
        }
        tickCounter = 0;

        final UUID self = platform.localPlayerId();
        final Set<UUID> tracked = new HashSet<>();
        // Tab list first, like the real client; world players fill whatever room is left.
        platform.forEachTabListPlayer((uuid, textures) -> addTracked(tracked, uuid, self));
        platform.forEachWorldPlayer((uuid, textures) -> addTracked(tracked, uuid, self));
        // Coalesce: if the background thread is still busy (e.g. connecting), the newest snapshot wins.
        if (pendingTracked.getAndSet(tracked) == null) {
            EXECUTOR.execute(NoRiskTagManager::sync);
        }
    }

    private static void addTracked(Set<UUID> tracked, UUID uuid, UUID self) {
        // Real accounts have v4 UUIDs; server-side NPCs (v2 on Hypixel) can't be NoRisk users.
        if (uuid != null && uuid.version() == 4 && !uuid.equals(self) && tracked.size() < MAX_TRACKED
                && !ClientTagUsers.hides(uuid, ClientIcon.NORISK)) {
            tracked.add(uuid);
        }
    }

    private static void changeServer(boolean nowActive) {
        active = nowActive;
        if (socket != null && socket.isOpen() && !subscribed.isEmpty()) {
            updateSubscription(new ArrayList<>(subscribed), false);
        }
        subscribed.clear();
        restCheckedAt.clear();
        noRiskColors.clear();
    }

    private static void sync() {
        final Set<UUID> tracked = pendingTracked.getAndSet(null);
        if (tracked == null || !active) {
            return;
        }
        if (!ensureConnected()) {
            restFallback(tracked);
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
            removed.forEach(noRiskColors::remove);
            updateSubscription(removed, false);
        }

        final List<UUID> added = new ArrayList<>();
        for (UUID uuid : tracked) {
            if (subscribed.add(uuid)) {
                added.add(uuid);
            }
        }
        if (!added.isEmpty()) {
            // The server answers a subscription with the players' current records.
            updateSubscription(added, true);
        }
        final long now = System.currentTimeMillis();
        if (now - lastSummary >= SUMMARY_INTERVAL_MS) {
            lastSummary = now;
            LOGGER.debug("NoRisk: {} of {} subscribed players have a record ({} null answers so far)",
                    noRiskColors.size(), subscribed.size(), nullRecords);
        }
    }

    /** Sends subscription updates in packets of at most 100 UUIDs. */
    private static void updateSubscription(List<UUID> players, boolean subscribe) {
        for (int i = 0; i < players.size(); i += MAX_BATCH) {
            socket.updateSubscription(new ArrayList<>(players.subList(i, Math.min(i + MAX_BATCH, players.size()))), subscribe);
        }
    }

    private static void onMinimal(UUID player, JsonObject minimal) {
        if (minimal == null) {
            nullRecords++;
        } else if (sampledRecords < SAMPLE_RECORDS) {
            sampledRecords++;
            LOGGER.debug("NoRisk record for {}: ign={} rank={} lastSeen={} icon={} plus={}", player,
                    NoRiskSocket.string(minimal, "ign"), NoRiskSocket.string(minimal, "rank"),
                    minimal.get("lastSeen"), minimal.get("customIconInfo"), minimal.get("noRiskPlusExpirationDate"));
        }
        // Late answers for players who already left (or a previous server) don't count.
        final Integer color = minimal == null ? null : color(minimal);
        if (color == null || !subscribed.contains(player)) {
            noRiskColors.remove(player);
        } else {
            noRiskColors.put(player, color);
        }
    }

    /** Indicator color for a {@code NoRiskUserMinimal}: staff/special icons first, then NoRisk+. */
    static Integer color(JsonObject minimal) {
        final JsonElement iconInfo = minimal.get("customIconInfo");
        if (iconInfo != null && iconInfo.isJsonObject()) {
            final UUID icon = NoRiskSocket.uuid(iconInfo.getAsJsonObject(), "currentIcon");
            if (icon != null && ICON_COLORS.containsKey(icon)) {
                return ICON_COLORS.get(icon);
            }
        }
        final JsonElement plusUntil = minimal.get("noRiskPlusExpirationDate");
        if (plusUntil != null && plusUntil.isJsonPrimitive() && plusUntil.getAsJsonPrimitive().isNumber()
                && plusUntil.getAsLong() > System.currentTimeMillis()) {
            return PLUS_COLOR;
        }
        return COLOR;
    }

    /** While the socket is down: asks REST about players not checked in the last minute. */
    private static void restFallback(Set<UUID> tracked) {
        restCheckedAt.keySet().retainAll(tracked);
        noRiskColors.keySet().retainAll(tracked);
        if (token == null || !token.isValid(TOKEN_REFRESH_MARGIN_MS)) {
            return;
        }
        final long now = System.currentTimeMillis();
        final List<UUID> due = new ArrayList<>();
        for (UUID uuid : tracked) {
            final Long checked = restCheckedAt.get(uuid);
            if (checked == null || now - checked >= REST_REFRESH_MS) {
                due.add(uuid);
            }
        }
        final UUID self = Platform.get().sessionId();
        for (int i = 0; i < due.size(); i += MAX_REST_BATCH) {
            final List<UUID> batch = due.subList(i, Math.min(i + MAX_REST_BATCH, due.size()));
            try {
                final JsonArray users = NoRiskApi.fetchOnline(token, self, batch);
                final Set<UUID> online = new HashSet<>();
                for (JsonElement user : users) {
                    if (user.isJsonObject()) {
                        final UUID uuid = NoRiskSocket.uuid(user.getAsJsonObject(), "uuid");
                        if (uuid != null && batch.contains(uuid) && NoRiskSocket.isUserRecord(user.getAsJsonObject())) {
                            online.add(uuid);
                            noRiskColors.put(uuid, color(user.getAsJsonObject()));
                        }
                    }
                }
                for (UUID uuid : batch) {
                    restCheckedAt.put(uuid, now);
                    if (!online.contains(uuid)) {
                        noRiskColors.remove(uuid);
                    }
                }
            } catch (Exception e) {
                LOGGER.warn("NoRisk online lookup failed: {}", e.toString());
                // Don't hammer the endpoint; try these again next round.
                for (UUID uuid : due) {
                    restCheckedAt.put(uuid, now);
                }
                return;
            }
        }
    }

    /** Closes the socket; the next sync after the client is turned back on reconnects. */
    private static void disconnect() {
        if (socket != null) {
            socket.close();
            socket = null;
            LOGGER.info("Disconnected from NoRisk (turned off in settings)");
        }
        subscribed.clear();
        restCheckedAt.clear();
        noRiskColors.clear();
    }

    private static boolean ensureConnected() {
        if (socket != null && socket.isOpen()) {
            return true;
        }
        final long now = System.currentTimeMillis();
        if (socket != null) {
            final String reason = socket.closeReason();
            LOGGER.info("NoRisk socket closed: {}", reason);
            if (now - connectedAt >= STABLE_CONNECTION_MS) {
                backoff = MIN_BACKOFF_MS;
            } else {
                // Dropped early - most likely the token was rejected, so get a fresh one next time.
                token = null;
            }
            if (reason != null && reason.toLowerCase().contains("duplicate")) {
                // NoRiskClient itself is probably running on this account; don't fight it for the socket.
                backoff = MAX_BACKOFF_MS;
            }
            socket = null;
            subscribed.clear();
            scheduleRetry(now);
            return false;
        }
        if (now < nextConnectAttempt) {
            return false;
        }

        try {
            final Platform platform = Platform.get();
            if (token == null || !token.isValid(TOKEN_REFRESH_MARGIN_MS)) {
                token = fetchToken();
            }
            socket = NoRiskSocket.connect(token.jwt, platform.sessionId(), platform.sessionName(),
                    (player, minimal) -> EXECUTOR.execute(() -> onMinimal(player, minimal)));
            connectedAt = System.currentTimeMillis();
            subscribed.clear();
            // The socket's live data replaces whatever REST found.
            restCheckedAt.clear();
            noRiskColors.clear();
            LOGGER.info("Connected to NoRisk");
            return true;
        } catch (Exception e) {
            LOGGER.warn("Couldn't connect to NoRisk ({}), retrying in {}s", e.toString(), backoff / 1000);
            // The token may be what got rejected; a fresh one costs one Mojang join per (backed-off) retry.
            token = null;
            scheduleRetry(now);
            return false;
        }
    }

    private static NoRiskApi.Token fetchToken() throws Exception {
        final NoRiskApi.Token launcherToken = NoRiskApi.launcherToken();
        if (launcherToken != null && launcherToken.isValid(TOKEN_REFRESH_MARGIN_MS)) {
            return launcherToken;
        }
        final Platform platform = Platform.get();
        return NoRiskApi.fetchToken(platform.sessionName(), SessionJoins::join);
    }

    private static void scheduleRetry(long now) {
        nextConnectAttempt = now + backoff;
        backoff = Math.min(backoff * 2, MAX_BACKOFF_MS);
    }

}
