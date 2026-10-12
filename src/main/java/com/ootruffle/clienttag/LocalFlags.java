package com.ootruffle.clienttag;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.ootruffle.clienttag.config.ClientTagSettings;
import com.ootruffle.clienttag.platform.Platform;
import com.ootruffle.clienttag.platform.Platform.ChatPart;
import com.ootruffle.clienttag.render.ClientIcon;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Queue;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;
import net.fabricmc.loader.api.FabricLoader;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Players the user flagged for their own reasons. Purely local: flags live in
 * config/clienttag/flags.json and are never sent anywhere. A flagged player gets the
 * {@link ClientIcon#FLAGGED} warning triangle, and while on a server, chat says (once per
 * player each time we join) when one of them is in the world or the tab list, with the reason.
 * <p>
 * Edited through {@code /clienttag flag|unflag|flags} (see config.FlagCommands, needs OneConfig)
 * or by hand while the game is closed. A player who isn't online is looked up on Mojang's API.
 * Works whether or not the first-launch warning was accepted, since it connects nowhere but Mojang.
 */
public final class LocalFlags {

    private static final Logger LOGGER = LogManager.getLogger("ClientTag");

    /** Minecraft's gold, unless the settings give the triangle a custom color. */
    static final int FLAG_COLOR = 0xFFAA00;
    // As in WhoCommand.
    private static final int TEXT = 0xAAAAAA;
    private static final int NAME = 0xFFFFFF;
    private static final int PREFIX = 0x55FFFF;
    private static final int ERROR = 0xFF5555;
    private static final int CHECK_INTERVAL_TICKS = 20;
    private static final Pattern VALID_NAME = Pattern.compile("\\w{1,16}");
    private static final String MOJANG_LOOKUP = "https://api.mojang.com/users/profiles/minecraft/";
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private static final class Flag {
        final String name;
        final String reason;
        final long added;

        Flag(String name, String reason, long added) {
            this.name = name;
            this.reason = reason;
            this.added = added;
        }
    }

    private static final Map<UUID, Flag> flags = new ConcurrentHashMap<>();
    /** Chat lines waiting for the client thread. */
    private static final Queue<List<ChatPart>> messages = new ConcurrentLinkedQueue<>();
    /** Mojang lookups and saving. */
    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor(r -> {
        final Thread thread = new Thread(r, "ClientTag-Flags");
        thread.setDaemon(true);
        return thread;
    });

    // Client thread only.
    private static String lastAddress;
    private static int tickCounter;
    /** Flagged players already announced on this server. */
    private static final Set<UUID> announced = new HashSet<>();

    private LocalFlags() {}

    /** Loads the saved flags; call once from the mod initializer. */
    public static void init() {
        load();
    }

    /** The warning triangle's color (RGB) for a flagged player, else null. */
    public static Integer getFlagColor(UUID uuid) {
        return uuid != null && flags.containsKey(uuid) ? FLAG_COLOR : null;
    }

    /** Called every client tick, even before the first-launch warning is accepted. */
    public static void onClientTick() {
        final Platform platform = Platform.get();
        for (List<ChatPart> message; (message = messages.poll()) != null; ) {
            platform.showMessage(message);
        }

        final String address = platform.currentServerAddress();
        if (!Objects.equals(address, lastAddress)) {
            lastAddress = address;
            announced.clear();
            tickCounter = CHECK_INTERVAL_TICKS;
        }
        if (address == null || flags.isEmpty() || !ClientTagSettings.flagAlerts() || ++tickCounter < CHECK_INTERVAL_TICKS) {
            return;
        }
        tickCounter = 0;

        final UUID self = platform.localPlayerId();
        platform.forEachPlayerName((uuid, name) -> {
            if (!uuid.equals(self)) {
                rename(uuid, name);
                announce(uuid);
            }
        });
    }

    private static void announce(UUID uuid) {
        final Flag flag = flags.get(uuid);
        if (flag != null && announced.add(uuid)) {
            say(withReason(line(new ChatPart(flag.name, NAME), new ChatPart(" is here", TEXT)), flag.reason));
        }
    }

    /** Keeps the saved name current after a name change, so unflag finds it. */
    private static void rename(UUID uuid, String name) {
        final Flag flag = flags.get(uuid);
        if (flag != null && name != null && !name.isEmpty() && !name.equals(flag.name)) {
            flags.put(uuid, new Flag(name, flag.reason, flag.added));
            saveLater();
        }
    }

    /** Flags the player (or changes their reason). Client thread only. */
    public static void flag(String name, String reason) {
        final String trimmed = reason.trim();
        final Platform platform = Platform.get();
        final String[] found = {null};
        final UUID[] foundId = {null};
        platform.forEachPlayerName((uuid, online) -> {
            if (foundId[0] == null && online.equalsIgnoreCase(name)) {
                foundId[0] = uuid;
                found[0] = online;
            }
        });
        if (foundId[0] == null && !VALID_NAME.matcher(name).matches()) {
            say(error(name + " isn't a valid player name."));
            return;
        }
        if (foundId[0] != null) {
            // They're right here: no need to tell the user again on the next check.
            announced.add(foundId[0]);
            put(foundId[0], found[0], trimmed);
            return;
        }
        EXECUTOR.execute(() -> {
            try {
                final JsonObject profile = lookUp(name);
                if (profile == null) {
                    say(error("No player is called " + name + "."));
                    return;
                }
                put(parseUndashed(profile.get("id").getAsString()), profile.get("name").getAsString(), trimmed);
            } catch (Exception e) {
                LOGGER.warn("Couldn't look up {} on Mojang's API: {}", name, e.toString());
                say(error("Couldn't look up " + name + " - they need to be online, or Mojang's API reachable."));
            }
        });
    }

    private static void put(UUID uuid, String name, String reason) {
        final Flag old = flags.put(uuid, new Flag(name, reason, System.currentTimeMillis()));
        saveLater();
        say(withReason(line(new ChatPart(old == null ? "Flagged " : "Updated ", TEXT), new ChatPart(name, NAME)), reason));
    }

    /** Removes the flag from the player, found by their saved name or, failing that, online. Client thread only. */
    public static void unflag(String name) {
        UUID target = null;
        for (Map.Entry<UUID, Flag> entry : flags.entrySet()) {
            if (entry.getValue().name.equalsIgnoreCase(name)) {
                target = entry.getKey();
            }
        }
        if (target == null) {
            final UUID[] online = {null};
            Platform.get().forEachPlayerName((uuid, onlineName) -> {
                if (onlineName.equalsIgnoreCase(name)) {
                    online[0] = uuid;
                }
            });
            target = online[0];
        }
        final Flag removed = target == null ? null : flags.remove(target);
        if (removed == null) {
            say(error(name + " isn't flagged."));
            return;
        }
        announced.remove(target);
        saveLater();
        say(line(new ChatPart("Unflagged ", TEXT), new ChatPart(removed.name, NAME)));
    }

    /** Lists every flag in chat. Client thread only. */
    public static void list() {
        if (flags.isEmpty()) {
            say(line(new ChatPart("Nobody is flagged. Use ", TEXT), new ChatPart("/clienttag flag <player> <reason>", NAME)));
            return;
        }
        final Map<String, Flag> sorted = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        for (Flag flag : flags.values()) {
            sorted.put(flag.name, flag);
        }
        say(line(new ChatPart("Flagged players (" + sorted.size() + "):", TEXT)));
        for (Flag flag : sorted.values()) {
            say(withReason(line(new ChatPart(flag.name, NAME)), flag.reason));
        }
    }

    /** Names in the tab list, for suggestions. */
    public static List<String> onlineNames() {
        final List<String> names = new ArrayList<>();
        Platform.get().forEachPlayerName((uuid, name) -> names.add(name));
        return names;
    }

    /** Saved names of flagged players, for suggestions. */
    public static List<String> flaggedNames() {
        final List<String> names = new ArrayList<>();
        for (Flag flag : flags.values()) {
            names.add(flag.name);
        }
        return names;
    }

    /** The reason this player was flagged ("" for none), or null if they aren't. For /clienttag who. */
    public static String reason(UUID uuid) {
        final Flag flag = flags.get(uuid);
        return flag == null ? null : flag.reason;
    }

    private static void say(List<ChatPart> message) {
        messages.add(message);
    }

    /** "ClientTag » ⚠ " in the triangle's color, then the parts. */
    private static List<ChatPart> line(ChatPart... parts) {
        final List<ChatPart> line = new ArrayList<>();
        line.add(new ChatPart("ClientTag » ", PREFIX));
        line.add(new ChatPart("⚠ ", ClientTagSettings.color(ClientIcon.FLAGGED, FLAG_COLOR)));
        line.addAll(Arrays.asList(parts));
        return line;
    }

    private static List<ChatPart> withReason(List<ChatPart> line, String reason) {
        if (!reason.isEmpty()) {
            line.add(new ChatPart(": ", TEXT));
            line.add(new ChatPart(reason, NAME));
        }
        return line;
    }

    private static List<ChatPart> error(String text) {
        return line(new ChatPart(text, ERROR));
    }

    /** Mojang's profile for the name ({ id, name }), or null if there's no such player. */
    private static JsonObject lookUp(String name) throws IOException, InterruptedException {
        final HttpRequest request = HttpRequest.newBuilder(URI.create(MOJANG_LOOKUP + name))
                .timeout(Duration.ofSeconds(15))
                .GET()
                .build();
        final HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() == 204 || response.statusCode() == 404) {
            return null;
        }
        if (response.statusCode() / 100 != 2) {
            throw new IOException("HTTP " + response.statusCode());
        }
        final JsonElement json = new JsonParser().parse(response.body());
        if (!json.isJsonObject() || !json.getAsJsonObject().has("id") || !json.getAsJsonObject().has("name")) {
            return null;
        }
        return json.getAsJsonObject();
    }

    private static UUID parseUndashed(String id) {
        return UUID.fromString(id.replaceFirst("(\\w{8})(\\w{4})(\\w{4})(\\w{4})(\\w{12})", "$1-$2-$3-$4-$5"));
    }

    private static Path file() {
        return FabricLoader.getInstance().getConfigDir().resolve("clienttag").resolve("flags.json");
    }

    private static void load() {
        if (!Files.exists(file())) {
            return;
        }
        try {
            final JsonElement json = new JsonParser().parse(new String(Files.readAllBytes(file()), StandardCharsets.UTF_8));
            if (!json.isJsonObject()) {
                return;
            }
            for (Map.Entry<String, JsonElement> entry : json.getAsJsonObject().entrySet()) {
                if (!entry.getValue().isJsonObject()) {
                    continue;
                }
                final JsonObject flag = entry.getValue().getAsJsonObject();
                try {
                    flags.put(UUID.fromString(entry.getKey()), new Flag(
                            flag.has("name") ? flag.get("name").getAsString() : entry.getKey(),
                            flag.has("reason") ? flag.get("reason").getAsString() : "",
                            flag.has("added") ? flag.get("added").getAsLong() : 0L));
                } catch (RuntimeException e) {
                    LOGGER.warn("Skipping bad flag {} in {}: {}", entry.getKey(), file(), e.toString());
                }
            }
        } catch (Exception e) {
            LOGGER.warn("Couldn't read {}: {}", file(), e.toString());
        }
    }

    private static void saveLater() {
        EXECUTOR.execute(LocalFlags::save);
    }

    private static void save() {
        final JsonObject json = new JsonObject();
        for (Map.Entry<UUID, Flag> entry : new TreeMap<>(flags).entrySet()) {
            final JsonObject flag = new JsonObject();
            flag.addProperty("name", entry.getValue().name);
            flag.addProperty("reason", entry.getValue().reason);
            flag.addProperty("added", entry.getValue().added);
            json.add(entry.getKey().toString(), flag);
        }
        try {
            Files.createDirectories(file().getParent());
            final Path temp = file().resolveSibling("flags.json.tmp");
            Files.write(temp, new GsonBuilder().setPrettyPrinting().create().toJson(json).getBytes(StandardCharsets.UTF_8));
            Files.move(temp, file(), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            LOGGER.warn("Couldn't save {}: {}", file(), e.toString());
        }
    }

}
