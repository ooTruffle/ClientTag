package com.ootruffle.clienttag.dawn;

import com.google.gson.JsonObject;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * Gets a Dawn access token (JWT) through the Mojang "server join" proof:
 * <ol>
 *     <li>{@code GET /minecraft/server-id} -> { token: serverId }</li>
 *     <li>Mojang session server join with that server ID</li>
 *     <li>{@code GET /minecraft/has-joined/{name}?token={serverId}} -> { token, expiresIn, ... }</li>
 * </ol>
 */
public final class DawnAuthenticator {

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    private static final Duration TIMEOUT = Duration.ofSeconds(15);

    /** Performs the Mojang session server join for the given server ID. */
    @FunctionalInterface
    public interface SessionJoiner {
        void join(String serverId) throws Exception;
    }

    /** An access token and when it stops being usable (0 if the server didn't say). */
    public static final class Token {
        public final String jwt;
        public final long expiresAt;

        Token(String jwt, long expiresAt) {
            this.jwt = jwt;
            this.expiresAt = expiresAt;
        }
    }

    private DawnAuthenticator() {}

    public static Token fetchToken(String name, SessionJoiner joiner) throws Exception {
        final String serverId = DawnSocket.string(getJson("server-id", "/minecraft/server-id"), "token");
        if (serverId == null || serverId.isEmpty()) {
            throw new IOException("Dawn sent no server ID");
        }
        joiner.join(serverId);

        final JsonObject joined = getJson("has-joined", "/minecraft/has-joined/" + encode(name) + "?token=" + encode(serverId));
        final String jwt = DawnSocket.string(joined, "token");
        if (jwt == null || jwt.isEmpty()) {
            throw new IOException("Dawn did not issue a token");
        }
        final long expiresIn = joined.has("expiresIn") && joined.get("expiresIn").isJsonPrimitive()
                ? joined.get("expiresIn").getAsLong() : 0;
        return new Token(jwt, expiresIn > 0 ? System.currentTimeMillis() + expiresIn * 1000 : 0);
    }

    /** {@code label} names the endpoint in errors, so the server ID in the URL never gets logged. */
    private static JsonObject getJson(String label, String path) throws Exception {
        final HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(DawnSocket.API_URL + path))
                .timeout(TIMEOUT)
                .header("Accept", "application/json")
                .GET();
        DawnSocket.clientHeaders().forEach(request::header);
        final HttpResponse<String> response = HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IOException(label + " returned HTTP " + response.statusCode());
        }
        final JsonObject json = DawnSocket.parseObject(response.body());
        if (json == null) {
            throw new IOException("Dawn sent a non-object response");
        }
        return json;
    }

    private static String encode(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20");
    }

}
