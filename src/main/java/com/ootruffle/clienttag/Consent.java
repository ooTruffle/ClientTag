package com.ootruffle.clienttag;

import com.ootruffle.clienttag.platform.Platform;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import net.fabricmc.loader.api.FabricLoader;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * The first-launch warning. Nothing connects anywhere until it's accepted - in the prompt, or
 * by setting {@code accepted=true} in config/clienttag/consent.properties (e.g. for a modpack).
 * <p>
 * It's an in-game screen over whatever is open once the game has loaded, opened again if another
 * mod (or a client like Lunar) replaces it before it's answered. Its buttons only work after
 * {@link #DELAY_SECONDS}. Declining keeps ClientTag idle
 * until the next launch, which asks again. Bumping {@link #VERSION} asks everyone again.
 */
public final class Consent {

    private static final Logger LOGGER = LogManager.getLogger("ClientTag");

    private static final int VERSION = 2;
    private static final int DELAY_SECONDS = 4;
    /** Ticks between attempts to put the in-game question back after something replaced it. */
    private static final int REOPEN_TICKS = 40;

    private static final String TITLE = "ClientTag - please read";
    private static final String MESSAGE =
            "ClientTag finds out which client other players use by logging your Minecraft account into "
            + "Lunar Client, Dawn Client, Essential, NoRiskClient, LabyMod and PolyPlus services.\n"
            + "This may break those services' terms of service, and they could ban or restrict your account "
            + "or your access to them.\n"
            + "\n"
            + "ClientTag also tells the ClientTag server which client you're really on, so other ClientTag "
            + "users see your real tag. Anyone can look players up on that server - including those clients' "
            + "developers - so it could be used to find ClientTag users. You can turn it off in /clienttag "
            + "(needs OneConfig).\n"
            + "\n"
            + "Enable ClientTag?";
    private static final String YES = "I understand, enable";
    private static final String NO = "Not now";

    private static Boolean accepted;
    private static boolean declined;
    private static int reopenCooldown;

    private Consent() {}

    /** Whether the warning has been accepted; until then nothing connects. Client thread only. */
    public static boolean accepted() {
        if (accepted == null) {
            accepted = read();
        }
        return accepted;
    }

    /** Called every client tick until the warning is accepted or declined. */
    public static void askIfNeeded() {
        if (accepted() || declined) {
            return;
        }
        final Platform platform = Platform.get();
        if (!platform.canAsk() || platform.isAsking() || --reopenCooldown > 0) {
            return;
        }
        reopenCooldown = REOPEN_TICKS;
        platform.ask(TITLE, MESSAGE, YES, NO, DELAY_SECONDS, Consent::answer);
    }

    private static void answer(boolean yes) {
        if (yes) {
            accepted = true;
            write();
            LOGGER.info("Warning accepted - ClientTag is on");
        } else {
            declined = true;
            LOGGER.info("Warning declined - ClientTag stays off until the next launch");
        }
    }

    private static Path file() {
        return FabricLoader.getInstance().getConfigDir().resolve("clienttag").resolve("consent.properties");
    }

    /** Whether the file says the current warning was accepted; writes a template if there's no file yet. */
    private static boolean read() {
        if (!Files.exists(file())) {
            // Earlier builds kept just the accepted version in "accepted-warning".
            final Path old = file().resolveSibling("accepted-warning");
            boolean wasAccepted = false;
            try {
                wasAccepted = Files.exists(old)
                        && String.valueOf(VERSION).equals(new String(Files.readAllBytes(old), StandardCharsets.UTF_8).trim());
                Files.deleteIfExists(old);
            } catch (IOException ignored) {
                // Just ask again.
            }
            save(wasAccepted);
            return wasAccepted;
        }
        final Properties properties = new Properties();
        try (InputStream in = Files.newInputStream(file())) {
            properties.load(in);
        } catch (IOException e) {
            LOGGER.warn("Couldn't read {}: {}", file(), e.toString());
            return false;
        }
        // Hand-edited files may leave the version out; that means the current warning.
        int version = VERSION;
        try {
            version = Integer.parseInt(properties.getProperty("version", String.valueOf(VERSION)).trim());
        } catch (NumberFormatException ignored) {
            // Treated as current.
        }
        return Boolean.parseBoolean(properties.getProperty("accepted", "false").trim()) && version >= VERSION;
    }

    private static void write() {
        save(true);
    }

    private static void save(boolean accepted) {
        final String text = "# ClientTag logs your Minecraft account into Lunar Client, Dawn Client, Essential,\n"
                + "# NoRiskClient, LabyMod and PolyPlus services to show which client other players use. This\n"
                + "# may break those services' terms of service and get your account banned or restricted.\n"
                + "# It also tells the ClientTag server (clienttags.fluffykiwi.net) which client you're on,\n"
                + "# and anyone can look players up there.\n"
                + "#\n"
                + "# Set accepted=true to agree to this without the in-game prompt.\n"
                + "accepted=" + accepted + "\n"
                + "version=" + VERSION + "\n";
        try {
            Files.createDirectories(file().getParent());
            try (OutputStream out = Files.newOutputStream(file())) {
                out.write(text.getBytes(StandardCharsets.UTF_8));
            }
        } catch (IOException e) {
            LOGGER.warn("Couldn't save {}: {}", file(), e.toString());
        }
    }

}
