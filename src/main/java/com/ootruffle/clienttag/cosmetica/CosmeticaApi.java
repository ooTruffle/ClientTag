package com.ootruffle.clienttag.cosmetica;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.UUID;
import net.fabricmc.loader.api.FabricLoader;

/**
 * Cosmetica's public user lookup ({@code GET https://api.cosmetica.cc/users/{uuid}}, see
 * https://api.cosmetica.cc/docs). It needs no login: it answers 404 for players who aren't
 * Cosmetica users, and a user record with {@code online} for those who are.
 */
public final class CosmeticaApi {

    private static final String URL = "https://api.cosmetica.cc/users/";
    /** "clienttag/1.0.0" - honest about which client is asking. */
    private static final String USER_AGENT = "clienttag/" + FabricLoader.getInstance().getModContainer("clienttag")
            .map(mod -> mod.getMetadata().getVersion().getFriendlyString())
            .orElse("unknown");

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    private static final Duration TIMEOUT = Duration.ofSeconds(15);

    public enum Status {
        NOT_A_USER, OFFLINE, ONLINE
    }

    private CosmeticaApi() {}

    public static Status lookup(UUID uuid) throws IOException {
        final HttpRequest request = HttpRequest.newBuilder(URI.create(URL + uuid))
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
        if (response.statusCode() == 404) {
            return Status.NOT_A_USER;
        }
        if (response.statusCode() / 100 != 2) {
            throw new IOException("Cosmetica returned HTTP " + response.statusCode());
        }
        final JsonElement json = new JsonParser().parse(response.body());
        if (!json.isJsonObject()) {
            throw new IOException("Cosmetica sent no user");
        }
        final JsonObject user = json.getAsJsonObject();
        final JsonElement online = user.get("online");
        return online != null && online.isJsonPrimitive() && online.getAsBoolean() ? Status.ONLINE : Status.OFFLINE;
    }

}
