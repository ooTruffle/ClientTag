package com.ootruffle.clienttag.norisk;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Base64;
import java.util.Collection;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * NoRisk's REST side: getting a JWT, and the batch "which of these players are online NoRisk
 * users" lookup used while the socket is down.
 * <p>
 * A token comes from {@code -Dnorisk.token} / {@code NORISK_TOKEN} when set (the NoRisk
 * launcher passes one), otherwise through the Mojang "server join" proof:
 * <ol>
 *     <li>{@code POST /launcher/auth/request-server-id} -> { serverId }</li>
 *     <li>Mojang session server join with that server ID</li>
 *     <li>{@code POST /launcher/auth/validate/v2?...&server_id=...} -> { value: jwt }</li>
 * </ol>
 * Layout taken from NoRiskClient build 26.3.x.
 */
public final class NoRiskApi {

    static final String USER_AGENT = "NoRiskClient/3.0";

    private static final String LAUNCHER_URL = "https://api.norisk.gg/api/v1/launcher";
    private static final String CORE_URL = "https://api.norisk.gg/api/v1/core";
    private static final Pattern EXP = Pattern.compile("\"exp\"\\s*:\\s*(\\d+)");
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    private static final Duration TIMEOUT = Duration.ofSeconds(15);

    /** Performs the Mojang session server join for the given server ID. */
    @FunctionalInterface
    public interface SessionJoiner {
        void join(String serverId) throws Exception;
    }

    /** An access token and when it stops being usable (0 if the token didn't say). */
    public static final class Token {
        public final String jwt;
        public final long expiresAt;

        Token(String jwt) {
            this.jwt = jwt;
            this.expiresAt = expiry(jwt);
        }

        public boolean isValid(long marginMs) {
            return expiresAt == 0 || System.currentTimeMillis() < expiresAt - marginMs;
        }
    }

    private NoRiskApi() {}

    /** The token the NoRisk launcher handed the game, if any (and not obviously expired). */
    public static Token launcherToken() {
        String jwt = System.getProperty("norisk.token");
        if (isPlaceholder(jwt)) {
            jwt = System.getenv("NORISK_TOKEN");
        }
        if (isPlaceholder(jwt)) {
            return null;
        }
        final Token token = new Token(jwt.trim());
        return token.isValid(0) ? token : null;
    }

    public static Token fetchToken(String name, SessionJoiner joiner) throws Exception {
        final JsonObject serverIdResponse = post("request-server-id", LAUNCHER_URL + "/auth/request-server-id", null, "{}").getAsJsonObject();
        final String serverId = NoRiskSocket.string(serverIdResponse, "serverId");
        if (serverId == null || serverId.isEmpty()) {
            throw new IOException("NoRisk sent no server ID");
        }
        joiner.join(serverId);

        final String query = "?force=false&hwid=" + hwid() + "&username=" + encode(name) + "&server_id=" + encode(serverId);
        final JsonElement validated = post("validate", LAUNCHER_URL + "/auth/validate/v2" + query, null, "{}");
        final String jwt = validated.isJsonObject() ? NoRiskSocket.string(validated.getAsJsonObject(), "value") : null;
        if (jwt == null || jwt.isEmpty()) {
            throw new IOException("NoRisk did not issue a token");
        }
        return new Token(jwt);
    }

    /**
     * Looks up which of {@code players} (at most 80) are online NoRisk users. Returns their
     * {@code NoRiskUserMinimal} records; players who aren't on NoRisk are left out.
     */
    public static JsonArray fetchOnline(Token token, UUID self, Collection<UUID> players) throws Exception {
        final JsonArray body = new JsonArray();
        for (UUID uuid : players) {
            body.add(new JsonPrimitive(uuid.toString()));
        }
        final JsonElement response = post("users/online", CORE_URL + "/users/online?uuid=" + self, token, body.toString());
        if (!response.isJsonArray()) {
            throw new IOException("NoRisk sent a non-array response");
        }
        return response.getAsJsonArray();
    }

    /** {@code label} names the endpoint in errors, so nothing from the URL ever gets logged. */
    private static JsonElement post(String label, String url, Token token, String body) throws Exception {
        final HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(url))
                .timeout(TIMEOUT)
                .header("Accept", "application/json")
                .header("Content-Type", "application/json")
                .header("User-Agent", USER_AGENT)
                .POST(HttpRequest.BodyPublishers.ofString(body));
        if (token != null) {
            request.header("Authorization", "Bearer " + token.jwt);
        }
        final HttpResponse<String> response = HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IOException(label + " returned HTTP " + response.statusCode());
        }
        final JsonElement json = NoRiskSocket.parse(response.body());
        if (json == null) {
            throw new IOException("NoRisk sent a non-JSON response");
        }
        return json;
    }

    /** The real client's hardware ID: SHA-256 of os.name + os.arch, lowercase hex. */
    private static String hwid() throws Exception {
        final byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest((System.getProperty("os.name") + System.getProperty("os.arch")).getBytes(StandardCharsets.UTF_8));
        final StringBuilder hex = new StringBuilder(digest.length * 2);
        for (byte b : digest) {
            hex.append(String.format(Locale.ROOT, "%02x", b));
        }
        return hex.toString();
    }

    /** The JWT's {@code exp} claim in epoch ms, or 0 if it can't be read. */
    private static long expiry(String jwt) {
        final String[] parts = jwt.split("\\.");
        if (parts.length < 2) {
            return 0;
        }
        try {
            final Matcher matcher = EXP.matcher(new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8));
            return matcher.find() ? Long.parseLong(matcher.group(1)) * 1000 : 0;
        } catch (IllegalArgumentException e) {
            return 0;
        }
    }

    private static boolean isPlaceholder(String jwt) {
        return jwt == null || jwt.trim().isEmpty() || jwt.equals("dev") || jwt.equals("unvalided");
    }

    static String encode(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20");
    }

}
