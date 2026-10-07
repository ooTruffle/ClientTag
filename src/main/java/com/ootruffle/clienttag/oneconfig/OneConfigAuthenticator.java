package com.ootruffle.clienttag.oneconfig;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Gets a PolyPlus access token through the Mojang "server join" proof, the way PolyPlus does:
 * <ol>
 *     <li>Mojang session server join with a random 32-letter server ID</li>
 *     <li>{@code POST /account/login?server_id=...&username=...&...} -> { token }</li>
 * </ol>
 * The client fields identify this mod as itself rather than as PolyPlus.
 */
public final class OneConfigAuthenticator {

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

    private OneConfigAuthenticator() {}

    public static String fetchToken(String name, String clientVersion, String minecraftVersion, SessionJoiner joiner) throws Exception {
        final String serverId = serverId();
        joiner.join(serverId);

        final Map<String, String> params = new LinkedHashMap<>();
        params.put("server_id", serverId);
        params.put("username", name);
        params.put("client_version", clientVersion);
        params.put("minecraft_version", minecraftVersion);
        params.put("loader", "fabric");
        params.put("os", System.getProperty("os.name", "unknown"));
        params.put("os_version", System.getProperty("os.version", "unknown"));
        params.put("java_version", System.getProperty("java.version", "unknown"));
        final String query = params.entrySet().stream()
                .map(e -> encode(e.getKey()) + "=" + encode(e.getValue()))
                .collect(Collectors.joining("&"));

        final HttpRequest request = HttpRequest.newBuilder(URI.create(OneConfigSocket.API_URL + "/account/login?" + query))
                .timeout(TIMEOUT)
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();
        final HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
        // The URL carries the server ID, so only the status gets logged.
        if (response.statusCode() != 200) {
            throw new IOException("login returned HTTP " + response.statusCode());
        }
        final String token = OneConfigSocket.string(OneConfigSocket.parseObject(response.body()), "token");
        if (token == null || token.isEmpty()) {
            throw new IOException("PolyPlus did not issue a token");
        }
        return token;
    }

    private static String serverId() {
        final StringBuilder sb = new StringBuilder(32);
        for (int i = 0; i < 32; i++) {
            sb.append(LETTERS.charAt(RANDOM.nextInt(LETTERS.length())));
        }
        return sb.toString();
    }

    private static String encode(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20");
    }

}
