package com.ootruffle.clienttag.presence;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.fabricmc.loader.api.FabricLoader;

/**
 * The ClientTag server (see server/ in the repository): ClientTag users tell it which client
 * they're really playing on, and ask it about the players around them.
 * <ul>
 *     <li>{@code POST /v1/login} { name, serverId } -> { token, expiresIn }, after a Mojang session server join with serverId</li>
 *     <li>{@code POST /v1/sync} { clients, showTag, lookup } -> { users: { uuid: [client ids] }, tags: { uuid: rgb } },
 *     refreshes our own presence</li>
 *     <li>{@code POST /v1/leave} -> drops our presence right away</li>
 * </ul>
 * The server is {@value #DEFAULT_URL} unless {@code -Dclienttag.server=<url>} says otherwise.
 */
public final class PresenceApi {

    public static final String DEFAULT_URL = "https://clienttags.fluffykiwi.net";
    public static final String URL = stripSlash(System.getProperty("clienttag.server", DEFAULT_URL));
    /** e.g. "ClientTag/1.0.0 (Minecraft 26.2; Fabric)" - shown in the server's request log. */
    public static final String USER_AGENT = userAgent();

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    private static final Duration TIMEOUT = Duration.ofSeconds(15);
    private static final String LETTERS = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ";
    private static final SecureRandom RANDOM = new SecureRandom();

    /** Performs the Mojang session server join for the given server ID. */
    @FunctionalInterface
    public interface SessionJoiner {
        void join(String serverId) throws Exception;
    }

    /** Thrown when the server no longer accepts our token. */
    public static final class UnauthorizedException extends IOException {
        UnauthorizedException() {
            super("token rejected");
        }
    }

    public static final class Token {
        public final String value;
        public final long expiresAt;

        Token(String value, long expiresAt) {
            this.value = value;
            this.expiresAt = expiresAt;
        }
    }

    /** What a sync found out about the players looked up. */
    public static final class SyncResult {
        /** ClientTag users -> the clients they're really on. */
        public final Map<UUID, List<String>> users = new HashMap<>();
        /** ClientTag users whose ClientTag icon should be drawn -> its color (RGB). */
        public final Map<UUID, Integer> tags = new HashMap<>();
    }

    private PresenceApi() {}

    public static Token login(String name, SessionJoiner joiner) throws Exception {
        final String serverId = serverId();
        joiner.join(serverId);

        final JsonObject body = new JsonObject();
        body.addProperty("name", name);
        body.addProperty("serverId", serverId);
        final JsonObject response = post("/v1/login", null, body);
        final JsonElement token = response.get("token");
        if (token == null || !token.isJsonPrimitive()) {
            throw new IOException("ClientTag server sent no token");
        }
        final JsonElement expiresIn = response.get("expiresIn");
        final long ttl = expiresIn != null && expiresIn.isJsonPrimitive() ? expiresIn.getAsLong() * 1000 : 3_600_000L;
        return new Token(token.getAsString(), System.currentTimeMillis() + ttl);
    }

    /**
     * Refreshes our presence as a user of {@code clients} (asking for our ClientTag icon to be
     * shown to others if {@code showTag}) and returns which of {@code lookup} are ClientTag users,
     * with the clients they're really on and the color of their ClientTag icon if it's shown.
     */
    public static SyncResult sync(Token token, Collection<String> clients, boolean showTag, Collection<UUID> lookup) throws IOException {
        final JsonObject body = new JsonObject();
        final JsonArray clientArray = new JsonArray();
        // add(JsonPrimitive), not add(String): 1.8.9 ships Gson 2.2.4.
        clients.forEach(id -> clientArray.add(new JsonPrimitive(id)));
        body.add("clients", clientArray);
        body.addProperty("showTag", showTag);
        final JsonArray lookupArray = new JsonArray();
        lookup.forEach(uuid -> lookupArray.add(new JsonPrimitive(uuid.toString())));
        body.add("lookup", lookupArray);

        final JsonObject response = post("/v1/sync", token, body);
        final SyncResult result = new SyncResult();
        final JsonElement tags = response.get("tags");
        if (tags != null && tags.isJsonObject()) {
            for (Map.Entry<String, JsonElement> entry : tags.getAsJsonObject().entrySet()) {
                final UUID uuid = parseUuid(entry.getKey());
                if (uuid != null && entry.getValue().isJsonPrimitive() && entry.getValue().getAsJsonPrimitive().isNumber()) {
                    result.tags.put(uuid, entry.getValue().getAsInt() & 0xFFFFFF);
                }
            }
        }
        final JsonElement users = response.get("users");
        if (users == null || !users.isJsonObject()) {
            return result;
        }
        for (Map.Entry<String, JsonElement> entry : users.getAsJsonObject().entrySet()) {
            final UUID uuid = parseUuid(entry.getKey());
            if (uuid == null) {
                continue;
            }
            final List<String> ids = new ArrayList<>();
            if (entry.getValue().isJsonArray()) {
                for (JsonElement id : entry.getValue().getAsJsonArray()) {
                    if (id.isJsonPrimitive()) {
                        ids.add(id.getAsString());
                    }
                }
            }
            result.users.put(uuid, ids);
        }
        return result;
    }

    private static UUID parseUuid(String text) {
        try {
            return UUID.fromString(text);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    public static void leave(Token token) throws IOException {
        post("/v1/leave", token, new JsonObject());
    }

    private static JsonObject post(String path, Token token, JsonObject body) throws IOException {
        final HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(URL + path))
                .timeout(TIMEOUT)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .header("User-Agent", USER_AGENT)
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()));
        if (token != null) {
            request.header("Authorization", "Bearer " + token.value);
        }
        final HttpResponse<String> response;
        try {
            response = HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted", e);
        }
        if (response.statusCode() == 401) {
            throw new UnauthorizedException();
        }
        if (response.statusCode() / 100 != 2) {
            throw new IOException(path + " returned HTTP " + response.statusCode());
        }
        final String text = response.body();
        if (text == null || text.isEmpty()) {
            return new JsonObject();
        }
        final JsonElement json = new JsonParser().parse(text);
        return json.isJsonObject() ? json.getAsJsonObject() : new JsonObject();
    }

    private static String serverId() {
        final StringBuilder sb = new StringBuilder(32);
        for (int i = 0; i < 32; i++) {
            sb.append(LETTERS.charAt(RANDOM.nextInt(LETTERS.length())));
        }
        return sb.toString();
    }

    private static String userAgent() {
        final String mod = version("clienttag");
        final String minecraft = version("minecraft");
        // 1.8.9 runs on Ornithe, which is Fabric Loader underneath.
        final String loader = minecraft.startsWith("1.8") ? "Ornithe" : "Fabric";
        return "ClientTag/" + mod + " (Minecraft " + minecraft + "; " + loader + ")";
    }

    private static String version(String modId) {
        return FabricLoader.getInstance().getModContainer(modId)
                .map(mod -> mod.getMetadata().getVersion().getFriendlyString())
                .orElse("unknown");
    }

    private static String stripSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

}
