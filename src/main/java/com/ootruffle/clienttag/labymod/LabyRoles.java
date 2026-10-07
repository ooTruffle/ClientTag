package com.ootruffle.clienttag.labymod;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * LabyMod's public role list ({@code GET https://laby.net/api/v3/roles}), which gives each role
 * ID its color: {@code {"roles": [{"id": 10, "colorHex": "...", ...}, ...]}}. LabyMod tints its
 * indicator with the color of a player's first role.
 */
public final class LabyRoles {

    private static final String ROLES_URL = "https://laby.net/api/v3/roles";

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private static final Map<Integer, Integer> COLORS = new ConcurrentHashMap<>();

    private LabyRoles() {}

    /** The role's RGB color, or null if it's unknown (or the list isn't loaded). */
    public static Integer color(int roleId) {
        return COLORS.get(roleId);
    }

    /** Loads the role list; blocks on the network. */
    public static void fetch() throws Exception {
        final HttpRequest request = HttpRequest.newBuilder(URI.create(ROLES_URL))
                .timeout(Duration.ofSeconds(15))
                .header("Accept", "application/json")
                .GET()
                .build();
        final HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IOException("roles returned HTTP " + response.statusCode());
        }
        // Minecraft 1.8.9 ships Gson 2.2.4, so only its API is used here.
        final JsonElement parsed = new JsonParser().parse(response.body());
        final JsonElement roles = parsed.isJsonObject() ? parsed.getAsJsonObject().get("roles") : null;
        if (roles == null || !roles.isJsonArray()) {
            throw new IOException("roles response has no role list");
        }
        for (JsonElement role : roles.getAsJsonArray()) {
            if (!role.isJsonObject()) {
                continue;
            }
            final JsonElement id = role.getAsJsonObject().get("id");
            final JsonElement hex = role.getAsJsonObject().get("colorHex");
            if (id == null || hex == null || !id.isJsonPrimitive() || !hex.isJsonPrimitive()) {
                continue;
            }
            try {
                COLORS.put(id.getAsInt(), Integer.parseInt(hex.getAsString().replace("#", ""), 16) & 0xFFFFFF);
            } catch (NumberFormatException ignored) {
                // A role without a usable color keeps the default.
            }
        }
    }

}
