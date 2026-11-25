package com.ootruffle.clienttag.velo;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.fabricmc.loader.api.FabricLoader;

/**
 * Velo Client's public online list ({@code GET https://api.velo-client.com/v1/online}). It
 * needs no login: it answers {@code {"users":[{uuid, username, plus, ...}]}} for every Velo
 * user currently online anywhere, not just on our server.
 */
public final class VeloApi {

    private static final String URL = "https://api.velo-client.com/v1/online";
    /** "clienttag/1.0.0" - honest about which client is asking. */
    private static final String USER_AGENT = "clienttag/" + FabricLoader.getInstance().getModContainer("clienttag")
            .map(mod -> mod.getMetadata().getVersion().getFriendlyString())
            .orElse("unknown");

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    private static final Duration TIMEOUT = Duration.ofSeconds(15);

    private VeloApi() {}

    /** Every online Velo user -> whether they show the Velo Plus badge. */
    public static Map<UUID, Boolean> online() throws IOException {
        final HttpRequest request = HttpRequest.newBuilder(URI.create(URL))
                .timeout(TIMEOUT)
                .header("Accept", "application/json")
                .header("User-Agent", USER_AGENT)
                .GET()
                .build();
        final HttpResponse<String> response;
        try {
            response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted", e);
        }
        if (response.statusCode() / 100 != 2) {
            throw new IOException("Velo returned HTTP " + response.statusCode());
        }
        final JsonElement json = new JsonParser().parse(response.body());
        final JsonElement users = json.isJsonObject() ? json.getAsJsonObject().get("users") : null;
        if (users == null || !users.isJsonArray()) {
            throw new IOException("Velo sent no user list");
        }
        final JsonArray list = users.getAsJsonArray();
        final Map<UUID, Boolean> online = new HashMap<>(list.size() * 2);
        for (JsonElement element : list) {
            if (!element.isJsonObject()) {
                continue;
            }
            final JsonObject user = element.getAsJsonObject();
            final UUID uuid = parseUuid(user.get("uuid"));
            if (uuid != null) {
                final JsonElement plus = user.get("plus");
                online.put(uuid, plus != null && plus.isJsonPrimitive() && plus.getAsBoolean());
            }
        }
        return online;
    }

    /** Velo sends UUIDs without dashes; accepts them with dashes too. */
    private static UUID parseUuid(JsonElement element) {
        if (element == null || !element.isJsonPrimitive()) {
            return null;
        }
        final String hex = element.getAsString().replace("-", "");
        if (hex.length() != 32) {
            return null;
        }
        try {
            return new UUID(Long.parseUnsignedLong(hex.substring(0, 16), 16), Long.parseUnsignedLong(hex.substring(16), 16));
        } catch (NumberFormatException e) {
            return null;
        }
    }

}
