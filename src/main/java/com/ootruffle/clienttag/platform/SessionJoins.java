package com.ootruffle.clienttag.platform;

import java.io.IOException;
import org.apache.logging.log4j.LogManager;

/**
 * Every service logs in with its own Mojang session server join (each checks a join with its
 * own server ID, so they can't be shared), and they all want one as soon as we're on a server.
 * Mojang rate-limits joins per account, so they're taken one at a time, {@link #MIN_GAP_MS}
 * apart, and a rate-limited join pauses all of them for {@link #RATE_LIMIT_PAUSE_MS}.
 * <p>
 * Called from each service's background thread; waiting here never blocks the game.
 */
public final class SessionJoins {

    private static final long MIN_GAP_MS = 2_500;
    private static final long RATE_LIMIT_PAUSE_MS = 60_000;

    private static final Object LOCK = new Object();
    private static long lastJoinAt;
    private static long pausedUntil;

    private SessionJoins() {}

    /** {@link Platform#joinServer}, spaced out from every other join this mod makes. */
    public static void join(String serverId) throws Exception {
        synchronized (LOCK) {
            final long now = System.currentTimeMillis();
            if (now < pausedUntil) {
                // Fail fast: the caller backs off and retries, rather than queueing up behind the pause.
                throw new IOException("Mojang session joins rate limited, pausing for " + (pausedUntil - now) / 1000 + "s");
            }
            final long wait = lastJoinAt + MIN_GAP_MS - now;
            if (wait > 0) {
                Thread.sleep(wait);
            }
            try {
                Platform.get().joinServer(serverId);
            } catch (Exception e) {
                if (String.valueOf(e.getMessage()).contains("RateLimiter")) {
                    pausedUntil = System.currentTimeMillis() + RATE_LIMIT_PAUSE_MS;
                    LogManager.getLogger("ClientTag").warn("Mojang rate-limited a session join; pausing all logins for {}s",
                            RATE_LIMIT_PAUSE_MS / 1000);
                }
                throw e;
            } finally {
                lastJoinAt = System.currentTimeMillis();
            }
        }
    }

}
