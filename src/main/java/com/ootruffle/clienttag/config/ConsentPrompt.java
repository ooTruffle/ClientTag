package com.ootruffle.clienttag.config;

import java.util.function.Consumer;
import net.fabricmc.loader.api.FabricLoader;
import org.apache.logging.log4j.LogManager;

/**
 * The consent question as a PolyCompose screen (see {@link ConsentScreen}), when PolyCompose is
 * installed - on its own, or with the OneConfig versions that depend on it. {@link #ask} returns
 * false without PolyCompose, or if the screen couldn't be shown, and from then on
 * this stays off for the session, so {@link com.ootruffle.clienttag.Consent} falls back to the in-game
 * screen. {@link ConsentScreen} - and Kotlin - is never loaded without PolyCompose.
 */
public final class ConsentPrompt {

    private static final boolean LOADED = FabricLoader.getInstance().isModLoaded("polycompose");
    /** Opens without an answer before falling back, in case the Compose screen keeps failing to stay up. */
    private static final int MAX_UNANSWERED = 3;
    private static boolean failed;
    private static int unanswered;

    private ConsentPrompt() {}

    /** Shows the question, or returns false if the caller should fall back to the vanilla screen. */
    public static boolean ask(String title, String message, String yes, String no, int delaySeconds, Consumer<Boolean> onAnswer) {
        if (!LOADED || failed) {
            return false;
        }
        if (++unanswered > MAX_UNANSWERED) {
            fail(new IllegalStateException("closed " + MAX_UNANSWERED + " times without an answer"));
            return false;
        }
        try {
            ConsentScreen.open(title, message, yes, no, delaySeconds, onAnswer);
            return true;
        } catch (Throwable t) {
            fail(t);
            return false;
        }
    }

    /** Whether the OneConfig question is the open screen. */
    public static boolean isAsking() {
        if (!LOADED || failed) {
            return false;
        }
        try {
            return ConsentScreen.isShowing();
        } catch (Throwable t) {
            fail(t);
            return false;
        }
    }

    private static void fail(Throwable t) {
        failed = true;
        LogManager.getLogger("ClientTag").warn("Couldn't show the consent question with PolyCompose, using the plain one: {}", t.toString());
    }

}
