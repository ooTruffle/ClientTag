package com.ootruffle.clienttag.lunar;

import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
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
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Lunar's public server list (servermappings.lunarclientcdn.com/servers.json), which maps
 * each server's domains to the canonical address Lunar knows it by - e.g. "hypixel.net" and
 * "hypixel.io" both to "mc.hypixel.net". Only the address fields are read out of the
 * (several MB) file.
 * <p>
 * A local copy is kept and used as-is for a day. After that, the CDN is asked whether the
 * file changed (by ETag), and it's only downloaded again if it did. Without a connection the
 * local copy keeps being used, however old.
 * <p>
 * Not thread safe - used from the Lunar background thread only.
 */
public final class ServerMappings {

    private static final Logger LOGGER = LogManager.getLogger("ClientTag");

    private static final String URL = "https://servermappings.lunarclientcdn.com/servers.json";
    private static final long CHECK_INTERVAL_MS = 24 * 60 * 60 * 1000L;
    private static final long RETRY_MS = 30 * 60 * 1000L;
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    /** A known server: the address Lunar expects to hear, and its main domain. */
    public static final class Server {
        public final String primaryAddress;
        public final String domain;

        Server(String primaryAddress, String domain) {
            this.primaryAddress = primaryAddress;
            this.domain = domain;
        }
    }

    private final File cacheFile;
    /** Holds the local copy's ETag; its modification time is when the CDN was last checked. */
    private final File etagFile;
    private Map<String, Server> byDomain = new HashMap<>();
    private boolean loaded;
    private long nextCheckAttempt;

    public ServerMappings(File cacheFile) {
        this.cacheFile = cacheFile;
        this.etagFile = new File(cacheFile.getParentFile(), cacheFile.getName() + ".etag");
    }

    /**
     * The server a hostname belongs to - "truffle.hypixel.net" matches "hypixel.net" - or
     * null if Lunar doesn't list it. May block on the network when the local copy is due a check.
     */
    public Server resolve(String hostname) {
        refreshIfNeeded();
        String host = hostname.toLowerCase(Locale.ROOT);
        while (true) {
            final Server server = byDomain.get(host);
            if (server != null) {
                return server;
            }
            final int dot = host.indexOf('.');
            if (dot < 0) {
                return null;
            }
            host = host.substring(dot + 1);
        }
    }

    private void refreshIfNeeded() {
        if (!loaded) {
            loaded = true;
            loadLocalCopy();
        }
        final long now = System.currentTimeMillis();
        final boolean due = !cacheFile.isFile() || !etagFile.isFile() || now - etagFile.lastModified() >= CHECK_INTERVAL_MS;
        if (due && now >= nextCheckAttempt) {
            try {
                check(now);
            } catch (Exception e) {
                LOGGER.warn("Couldn't update Lunar server mappings: {}", e.toString());
                nextCheckAttempt = now + RETRY_MS;
            }
        }
    }

    private void loadLocalCopy() {
        if (!cacheFile.isFile()) {
            return;
        }
        try (Reader reader = new InputStreamReader(Files.newInputStream(cacheFile.toPath()), StandardCharsets.UTF_8)) {
            byDomain = parse(reader);
            LOGGER.info("Loaded {} Lunar server domains from the local copy", byDomain.size());
        } catch (Exception e) {
            LOGGER.warn("Local Lunar server mappings are unreadable, downloading again: {}", e.toString());
            // Without an ETag the next check downloads the whole file.
            etagFile.delete();
        }
    }

    /** Asks the CDN for the file unless it still matches the local copy's ETag. */
    private void check(long now) throws Exception {
        final HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(URL))
                .timeout(Duration.ofSeconds(30))
                .GET();
        final String etag = cacheFile.isFile() && etagFile.isFile()
                ? new String(Files.readAllBytes(etagFile.toPath()), StandardCharsets.UTF_8).trim() : "";
        if (!etag.isEmpty()) {
            request.header("If-None-Match", etag);
        }
        final File parent = cacheFile.getParentFile();
        parent.mkdirs();
        final File temp = new File(parent, cacheFile.getName() + ".tmp");
        final HttpResponse<Path> response = HTTP.send(request.build(), HttpResponse.BodyHandlers.ofFile(temp.toPath()));
        if (response.statusCode() == 304) {
            Files.deleteIfExists(temp.toPath());
            etagFile.setLastModified(now);
            LOGGER.info("Lunar server mappings are up to date");
            return;
        }
        if (response.statusCode() != 200) {
            Files.deleteIfExists(temp.toPath());
            throw new IOException("servers.json returned HTTP " + response.statusCode());
        }
        // Check it parses before replacing a good local copy with it.
        final Map<String, Server> parsed;
        try (Reader reader = new InputStreamReader(Files.newInputStream(temp.toPath()), StandardCharsets.UTF_8)) {
            parsed = parse(reader);
            if (parsed.isEmpty()) {
                throw new IOException("servers.json listed no servers");
            }
        } catch (Exception e) {
            Files.deleteIfExists(temp.toPath());
            throw e;
        }
        Files.move(temp.toPath(), cacheFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
        Files.write(etagFile.toPath(), response.headers().firstValue("ETag").orElse("").getBytes(StandardCharsets.UTF_8));
        byDomain = parsed;
        LOGGER.info("Downloaded {} Lunar server domains", byDomain.size());
    }

    /** [{ addresses: [domain...], primaryAddress, ... }, ...] -> domain -> server. */
    static Map<String, Server> parse(Reader json) throws IOException {
        final Map<String, Server> byDomain = new HashMap<>();
        final JsonReader reader = new JsonReader(json);
        reader.beginArray();
        while (reader.hasNext()) {
            String primary = null;
            final List<String> addresses = new ArrayList<>();
            reader.beginObject();
            while (reader.hasNext()) {
                final String key = reader.nextName();
                if (key.equals("primaryAddress") && reader.peek() == JsonToken.STRING) {
                    primary = reader.nextString().trim().toLowerCase(Locale.ROOT);
                } else if (key.equals("addresses") && reader.peek() == JsonToken.BEGIN_ARRAY) {
                    reader.beginArray();
                    while (reader.hasNext()) {
                        if (reader.peek() == JsonToken.STRING) {
                            addresses.add(reader.nextString().trim().toLowerCase(Locale.ROOT));
                        } else {
                            reader.skipValue();
                        }
                    }
                    reader.endArray();
                } else {
                    reader.skipValue();
                }
            }
            reader.endObject();
            if (primary != null && !primary.isEmpty() && !addresses.isEmpty()) {
                final Server server = new Server(primary, addresses.get(0));
                for (String address : addresses) {
                    byDomain.putIfAbsent(address, server);
                }
            }
        }
        reader.endArray();
        return byDomain;
    }

}
