package com.ootruffle.clienttag.update;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.ootruffle.clienttag.Consent;
import com.ootruffle.clienttag.config.ClientTagSettings;
import com.ootruffle.clienttag.config.UpdateNotifications;
import com.ootruffle.clienttag.platform.Platform;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.fabricmc.loader.api.metadata.CustomValue;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Updates the mod from its GitHub releases.
 * <ol>
 *     <li>Once per launch (after the first-launch warning is accepted, and with Check for Updates
 *     on) it asks GitHub for the latest release of {@code ooTruffle/ClientTag}. Nothing is sent but
 *     the request itself.</li>
 *     <li>If that release is newer than this jar, it's offered: as a OneConfig notification, or on
 *     the title screen without OneConfig. "Update" downloads the release's jar for this build
 *     ({@code ClientTag-<version>-<build>.jar}, the build being e.g. {@code 1.21.8_fabric}, read
 *     from fabric.mod.json) into {@code config/clienttag/update} (outside {@code mods}, so it's never
 *     loaded from there) and checks it against the SHA-256 GitHub lists for it; a release with no
 *     hash isn't offered.</li>
 *     <li>When the game closes, {@link UpdateApplier} (its own process) waits for it to exit, puts
 *     the new jar where the old one was and deletes the old one.</li>
 * </ol>
 * A download that couldn't be installed stays pending and is tried again on the next exit. Off in
 * the dev environment (except {@code CLIENTTAG_UPDATER_TEST}), and when the mod isn't a plain
 * jar in a folder (nested in another jar, say).
 */
public final class Updater {

    private static final Logger LOGGER = LogManager.getLogger("ClientTag");

    private static final String MOD_ID = "clienttag";
    private static final String REPO = "ooTruffle/ClientTag";
    private static final String LATEST = "https://api.github.com/repos/" + REPO + "/releases/latest";
    private static final String DOWNLOADS = "https://github.com/" + REPO + "/releases/download/";
    private static final long MAX_JAR = 64L * 1024 * 1024;
    /** Release notes are cut to this many characters in the fallback screen. */
    private static final int MAX_NOTES = 400;
    static final String PENDING_FILE = "pending.txt";

    /** A release newer than this jar. */
    private static final class Release {
        final String version;
        final String notes;
        final String assetName;
        final String url;
        final String sha256;
        final long size;

        Release(String version, String notes, String assetName, String url, String sha256, long size) {
            this.version = version;
            this.notes = notes;
            this.assetName = assetName;
            this.url = url;
            this.sha256 = sha256;
            this.size = size;
        }
    }

    /**
     * {@code CLIENTTAG_UPDATER_TEST=1} (dev only): pretend to be version 1.0.0 installed as a fake
     * jar in {@code run/updater-test/mods}, so the real latest release is offered, then download it
     * as if Update were clicked and quit, which installs it there.
     */
    private static final boolean TEST = FabricLoader.getInstance().isDevelopmentEnvironment()
            && System.getenv("CLIENTTAG_UPDATER_TEST") != null;

    private static volatile Release available;
    private static boolean checked;
    private static boolean asked;
    private static volatile boolean downloading;
    private static volatile boolean hookAdded;
    /** Work for the client thread: notification buttons are clicked on OneConfig's thread. */
    private static final Queue<Runnable> TASKS = new ConcurrentLinkedQueue<>();
    /** The "update available" notification, closed once the download starts. */
    private static volatile Object offer;
    /** Download progress, 0-1. */
    private static volatile float progress;

    private Updater() {}

    /** Called every client tick; does nothing until the first-launch warning has been accepted. */
    public static void onClientTick() {
        if (!(Consent.accepted() || TEST) || !ClientTagSettings.checkForUpdates()) {
            return;
        }
        if (!checked) {
            checked = true;
            if (!ownJar().isPresent() || buildTarget() == null) {
                return;
            }
            resumePending();
            final Thread thread = new Thread(Updater::check, "ClientTag-Updater");
            thread.setDaemon(true);
            thread.start();
            return;
        }
        for (Runnable task; (task = TASKS.poll()) != null; ) {
            task.run();
        }
        final Release release = available;
        final Platform platform = Platform.get();
        // A OneConfig notification can go up anywhere; the fallback prompt waits for the title screen.
        if (release != null && !asked && !hookAdded && platform.canAsk()
                && (UpdateNotifications.available() || platform.isOnTitleScreen())) {
            asked = true;
            ask(release);
            if (TEST) {
                LOGGER.info("[updater test] offered {}; downloading it", release.version);
                startDownload(release);
            }
        }
    }

    // ---- asking ----

    private static void ask(Release release) {
        final Object notification = UpdateNotifications.ask(
                "ClientTag " + release.version + " is available",
                "You have " + currentVersion() + ". Update downloads it from GitHub and installs it when you close the game.",
                false, "Update", () -> TASKS.add(() -> startDownload(release)),
                "Not now", () -> skipped(release));
        if (notification != null) {
            offer = notification;
            return;
        }
        final StringBuilder message = new StringBuilder("ClientTag " + release.version + " is out (you have "
                + currentVersion() + ").\n\n");
        if (!release.notes.isEmpty()) {
            message.append(release.notes).append("\n\n");
        }
        message.append("Update downloads it from GitHub and installs it when you close the game.");
        Platform.get().ask("ClientTag update", message.toString(), "Update", "Not now", 0, yes -> {
            if (yes) {
                startDownload(release);
            } else {
                skipped(release);
            }
        });
    }

    private static void skipped(Release release) {
        LOGGER.info("ClientTag {} skipped for now; asking again next launch", release.version);
    }

    private static void startDownload(Release release) {
        if (downloading) {
            return;
        }
        downloading = true;
        UpdateNotifications.dismiss(offer);
        offer = null;
        progress = 0;
        final Object bar = UpdateNotifications.progress("Updating ClientTag", "Downloading " + release.version + "...", () -> progress);
        final Thread thread = new Thread(() -> {
            String error = null;
            try {
                download(release);
            } catch (Exception e) {
                error = e.getMessage() == null ? e.toString() : e.getMessage();
                LOGGER.warn("Couldn't download ClientTag {}: {}", release.version, e.toString());
            } finally {
                downloading = false;
            }
            final String failure = error;
            // Tasks only run on the client thread, which shows screens and quits the game.
            TASKS.add(() -> finished(release, bar, failure));
        }, "ClientTag-Updater");
        thread.setDaemon(true);
        thread.start();
    }

    private static void finished(Release release, Object bar, String failure) {
        if (TEST) {
            LOGGER.info("[updater test] download {}; quitting", failure == null ? "ok" : "failed: " + failure);
            UpdateNotifications.dismiss(bar);
            Platform.get().quit();
            return;
        }
        UpdateNotifications.dismiss(bar);
        if (bar != null) {
            if (failure != null) {
                UpdateNotifications.error("ClientTag update failed",
                        failure + ". You can get it from github.com/" + REPO + "/releases.");
                return;
            }
            if (UpdateNotifications.ask("ClientTag " + release.version + " is ready",
                    "It's installed when you close the game, and runs from the next launch.", true,
                    "Quit and install", () -> TASKS.add(() -> Platform.get().quit()), "Later", () -> { }) != null) {
                return;
            }
        }
        if (failure == null) {
            Platform.get().ask("ClientTag update", "ClientTag " + release.version + " is downloaded. It's installed when "
                    + "you close the game, and runs from the next launch.", "Quit and install", "Later", 0, quit -> {
                if (quit) {
                    TASKS.add(() -> Platform.get().quit());
                }
            });
        } else {
            Platform.get().ask("ClientTag update", "The update couldn't be downloaded: " + failure
                    + "\n\nYou can get it from github.com/" + REPO + "/releases.", "OK", "Close", 0, ok -> { });
        }
    }

    // ---- what's running ----

    /** The running jar, if the mod was loaded from a plain jar file in a folder. */
    private static Optional<Path> ownJar() {
        if (TEST) {
            final Path fake = FabricLoader.getInstance().getGameDir().resolve("updater-test/mods/ClientTag-1.0.0-test.jar");
            try {
                if (!Files.isRegularFile(fake)) {
                    Files.createDirectories(fake.getParent());
                    Files.write(fake, "not a real jar".getBytes(StandardCharsets.UTF_8));
                }
            } catch (IOException e) {
                return Optional.empty();
            }
            return Optional.of(fake.toAbsolutePath());
        }
        if (FabricLoader.getInstance().isDevelopmentEnvironment()) {
            return Optional.empty();
        }
        final Optional<ModContainer> mod = FabricLoader.getInstance().getModContainer(MOD_ID);
        if (!mod.isPresent()) {
            return Optional.empty();
        }
        try {
            final List<Path> paths = mod.get().getOrigin().getPaths();
            if (paths.size() == 1 && Files.isRegularFile(paths.get(0)) && paths.get(0).toString().endsWith(".jar")) {
                return Optional.of(paths.get(0).toAbsolutePath());
            }
        } catch (RuntimeException e) {
            // Nested or otherwise not a plain file: can't be replaced.
        }
        return Optional.empty();
    }

    private static String currentVersion() {
        if (TEST) {
            return "1.0.0";
        }
        return FabricLoader.getInstance().getModContainer(MOD_ID)
                .map(m -> m.getMetadata().getVersion().getFriendlyString()).orElse("0");
    }

    /** Which release jar is this build's, e.g. {@code 1.21.8_fabric}; null if fabric.mod.json doesn't say. */
    private static String buildTarget() {
        final String target = FabricLoader.getInstance().getModContainer(MOD_ID)
                .map(m -> m.getMetadata().getCustomValue("clienttag:build"))
                .filter(v -> v.getType() == CustomValue.CvType.STRING)
                .map(CustomValue::getAsString)
                .orElse(null);
        return target != null && target.matches("[0-9][0-9.]*_[a-z]+") ? target : null;
    }

    private static Path dir() {
        return FabricLoader.getInstance().getConfigDir().resolve("clienttag").resolve("update");
    }

    // ---- checking ----

    private static void check() {
        try {
            final Release release = latest();
            if (release == null || hookAdded) {
                return; // Up to date, or already downloaded and waiting for the game to close.
            }
            available = release;
            LOGGER.info("ClientTag {} is available (running {})", release.version, currentVersion());
        } catch (Exception e) {
            LOGGER.info("Couldn't check for ClientTag updates: {}", e.toString());
        }
    }

    /** The latest release if it's newer than this jar and has a jar for this build, else null. */
    private static Release latest() throws IOException {
        final JsonObject json = new JsonParser().parse(new String(get(LATEST, 1024 * 1024, "application/vnd.github+json", -1),
                StandardCharsets.UTF_8)).getAsJsonObject();
        if (bool(json, "draft") || bool(json, "prerelease")) {
            return null;
        }
        final String tag = string(json, "tag_name");
        final String version = tag == null ? null : tag.replaceFirst("^[vV]", "");
        if (version == null || compare(version, currentVersion()) <= 0) {
            return null;
        }
        // ClientTag-1.2.2-1.21.11_fabric.jar; the "-" keeps 1.21.1 from matching 1.21.11.
        final String suffix = "-" + buildTarget() + ".jar";
        final JsonArray assets = json.has("assets") && json.get("assets").isJsonArray() ? json.getAsJsonArray("assets") : new JsonArray();
        for (JsonElement element : assets) {
            if (!element.isJsonObject()) {
                continue;
            }
            final JsonObject asset = element.getAsJsonObject();
            final String name = string(asset, "name");
            final String url = string(asset, "browser_download_url");
            final String digest = string(asset, "digest");
            if (name == null || url == null || !name.startsWith("ClientTag-") || !name.endsWith(suffix)
                    || !url.startsWith(DOWNLOADS) || name.contains("/") || name.contains("\\") || name.contains("..")) {
                continue;
            }
            final String sha = digest != null && digest.startsWith("sha256:") ? digest.substring(7).toLowerCase(Locale.ROOT) : null;
            if (sha == null || !sha.matches("[0-9a-f]{64}")) {
                LOGGER.info("ClientTag {} has no SHA-256 for {}; not offering it", version, name);
                continue;
            }
            final long size = asset.has("size") ? asset.get("size").getAsLong() : -1;
            String notes = string(json, "body");
            notes = notes == null ? "" : notes.replace("\r", "").replaceAll("(?m)^#+\\s*", "").replace("**", "").trim();
            if (notes.length() > MAX_NOTES) {
                notes = notes.substring(0, MAX_NOTES).trim() + "...";
            }
            return new Release(version, notes, name, url, sha, size);
        }
        LOGGER.info("ClientTag {} has no jar for {}; not offering it", version, buildTarget());
        return null;
    }

    /** Compares dotted versions number by number ("1.10.0" is newer than "1.9.2"; "1.1" equals "1.1.0"). */
    static int compare(String a, String b) {
        final long[] x = numbers(a);
        final long[] y = numbers(b);
        for (int i = 0; i < Math.max(x.length, y.length); i++) {
            final long p = i < x.length ? x[i] : 0;
            final long q = i < y.length ? y[i] : 0;
            if (p != q) {
                return Long.compare(p, q);
            }
        }
        return 0;
    }

    private static long[] numbers(String version) {
        // Only the leading dotted numbers: "1.1.0+1.8.9" is 1.1.0.
        final Matcher m = Pattern.compile("^(\\d+(?:\\.\\d+)*)").matcher(version.trim());
        if (!m.find()) {
            return new long[0];
        }
        final String[] parts = m.group(1).split("\\.");
        final long[] out = new long[parts.length];
        for (int i = 0; i < parts.length; i++) {
            try {
                out[i] = Long.parseLong(parts[i]);
            } catch (NumberFormatException e) {
                out[i] = Long.MAX_VALUE;
            }
        }
        return out;
    }

    // ---- downloading and installing ----

    private static void download(Release release) throws IOException {
        final Path jar = ownJar().orElseThrow(() -> new IOException("this copy of the mod can't be updated in place"));
        if (release.size > MAX_JAR) {
            throw new IOException("the release is too large (" + release.size + " bytes)");
        }
        final byte[] data = get(release.url, MAX_JAR, "application/octet-stream", release.size);
        final String sha = sha256(data);
        if (!sha.equals(release.sha256)) {
            throw new IOException("the download doesn't match GitHub's SHA-256 (got " + sha + ")");
        }
        if (data.length < 4 || data[0] != 'P' || data[1] != 'K') {
            throw new IOException("the download isn't a jar");
        }
        Files.createDirectories(dir());
        final Path staged = dir().resolve(release.assetName);
        final Path tmp = dir().resolve(release.assetName + ".tmp");
        Files.write(tmp, data);
        Files.move(tmp, staged, StandardCopyOption.REPLACE_EXISTING);
        final Path target = jar.resolveSibling(release.assetName);
        Files.write(dir().resolve(PENDING_FILE), (release.version + "\n" + staged + "\n" + jar + "\n" + target + "\n")
                .getBytes(StandardCharsets.UTF_8));
        installOnExit(release.version, staged, jar, target);
        LOGGER.info("Downloaded ClientTag {} ({} bytes, SHA-256 ok); it's installed when the game closes",
                release.version, data.length);
    }

    /** A download from an earlier session that wasn't installed yet: try again when this one closes. */
    private static void resumePending() {
        final Path pending = dir().resolve(PENDING_FILE);
        if (!Files.isRegularFile(pending)) {
            return;
        }
        try {
            final List<String> lines = Files.readAllLines(pending, StandardCharsets.UTF_8);
            final Path jar = ownJar().orElse(null);
            if (lines.size() < 4 || jar == null || compare(lines.get(0), currentVersion()) <= 0) {
                cleanUp(lines.size() >= 2 ? lines.get(1) : null);
                return;
            }
            final Path staged = Path.of(lines.get(1));
            // A download for another build (the mod was moved to another Minecraft version since) isn't installed.
            if (!Files.isRegularFile(staged) || !staged.getFileName().toString().endsWith("-" + buildTarget() + ".jar")) {
                cleanUp(lines.get(1));
                return;
            }
            // Point at the jar that's running now, in case it was moved or renamed since.
            installOnExit(lines.get(0), staged, jar, jar.resolveSibling(staged.getFileName().toString()));
            LOGGER.info("ClientTag {} was downloaded before but not installed; trying again when the game closes", lines.get(0));
        } catch (Exception e) {
            LOGGER.warn("Couldn't read the pending update: {}", e.toString());
        }
    }

    private static void cleanUp(String staged) {
        try {
            if (staged != null && Path.of(staged).startsWith(dir())) {
                Files.deleteIfExists(Path.of(staged));
            }
            Files.deleteIfExists(dir().resolve(PENDING_FILE));
        } catch (Exception ignored) {
            // Left for next time.
        }
    }

    private static synchronized void installOnExit(String version, Path staged, Path oldJar, Path target) {
        if (hookAdded) {
            return;
        }
        hookAdded = true;
        available = null;
        final String java = ProcessHandle.current().info().command().orElse(
                Path.of(System.getProperty("java.home"), "bin", "java").toString());
        final long pid = ProcessHandle.current().pid();
        // The installer runs from a copy of its class taken from this jar, not from the download:
        // the new jar might not have it (an older release), or have a different one.
        final Path classes = dir().resolve("installer");
        try {
            final String name = UpdateApplier.class.getName().replace('.', '/') + ".class";
            final Path file = classes.resolve(name);
            Files.createDirectories(file.getParent());
            try (InputStream in = Updater.class.getClassLoader().getResourceAsStream(name)) {
                if (in == null) {
                    throw new IOException("the installer class isn't in this jar");
                }
                Files.copy(in, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            LOGGER.warn("Can't install ClientTag {} when the game closes: {}", version, e.toString());
            return;
        }
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                new ProcessBuilder(java, "-cp", classes.toString(), UpdateApplier.class.getName(),
                        Long.toString(pid), staged.toString(), oldJar.toString(), target.toString())
                        .redirectErrorStream(true)
                        .redirectOutput(ProcessBuilder.Redirect.appendTo(dir().resolve("updater-process.log").toFile()))
                        .start();
            } catch (Exception e) {
                System.err.println("[ClientTag] couldn't start the updater: " + e);
            }
        }, "ClientTag-Update-Installer"));
    }

    // ---- helpers ----

    /** Downloads {@code url}; with {@code expected} bytes known, {@link #progress} follows it. */
    private static byte[] get(String url, long max, String accept, long expected) throws IOException {
        final HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setConnectTimeout(10_000);
        conn.setReadTimeout(30_000);
        conn.setRequestProperty("User-Agent", "ClientTag/" + currentVersion());
        conn.setRequestProperty("Accept", accept);
        try {
            final int code = conn.getResponseCode();
            if (code != 200) {
                throw new IOException("HTTP " + code + " from " + conn.getURL().getHost());
            }
            try (InputStream in = conn.getInputStream()) {
                final ByteArrayOutputStream out = new ByteArrayOutputStream();
                final byte[] buf = new byte[65536];
                int n;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                    if (expected > 0) {
                        progress = Math.min(1f, out.size() / (float) expected);
                    }
                    if (out.size() > max) {
                        throw new IOException("response too large");
                    }
                }
                return out.toByteArray();
            }
        } finally {
            conn.disconnect();
        }
    }

    private static String string(JsonObject object, String key) {
        final JsonElement value = object.get(key);
        return value != null && value.isJsonPrimitive() ? value.getAsString() : null;
    }

    private static boolean bool(JsonObject object, String key) {
        final JsonElement value = object.get(key);
        return value != null && value.isJsonPrimitive() && value.getAsBoolean();
    }

    private static String sha256(byte[] data) {
        try {
            final StringBuilder hex = new StringBuilder();
            for (byte b : MessageDigest.getInstance("SHA-256").digest(data)) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

}
