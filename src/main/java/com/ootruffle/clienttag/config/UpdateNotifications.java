package com.ootruffle.clienttag.config;

import java.util.concurrent.Callable;
import net.fabricmc.loader.api.FabricLoader;
import org.apache.logging.log4j.LogManager;
import org.polyfrost.oneconfig.api.notifications.v1.Notification;
import org.polyfrost.oneconfig.api.notifications.v1.NotificationAction;
import org.polyfrost.oneconfig.api.notifications.v1.NotificationType;
import org.polyfrost.oneconfig.api.notifications.v1.Notifications;

/**
 * The updater's OneConfig notifications (see {@link com.ootruffle.clienttag.update.Updater}).
 * Every method returns null / false without OneConfig, or if the notification couldn't be shown,
 * so the caller falls back to an in-game screen. Notifications are handed out as plain objects, so
 * callers never touch OneConfig's classes; {@link Present} is never loaded without OneConfig.
 */
public final class UpdateNotifications {

    private static final boolean LOADED = FabricLoader.getInstance().isModLoaded("oneconfigv1");

    private UpdateNotifications() {}

    public static boolean available() {
        return LOADED;
    }

    /** A notification that stays until it's answered or closed, with a primary and a secondary button. */
    public static Object ask(String title, String message, boolean success, String yes, Runnable onYes, String no, Runnable onNo) {
        try {
            return LOADED ? Present.ask(title, message, success, yes, onYes, no, onNo) : null;
        } catch (Throwable t) {
            warn(t);
            return null;
        }
    }

    /** A progress bar that stays until {@link #dismiss}. */
    public static Object progress(String title, String message, Callable<Float> progress) {
        try {
            return LOADED ? Present.progress(title, message, progress) : null;
        } catch (Throwable t) {
            warn(t);
            return null;
        }
    }

    public static boolean error(String title, String message) {
        try {
            return LOADED && Present.error(title, message) != null;
        } catch (Throwable t) {
            warn(t);
            return false;
        }
    }

    public static void dismiss(Object notification) {
        if (!LOADED || notification == null) {
            return;
        }
        try {
            Present.dismiss(notification);
        } catch (Throwable t) {
            warn(t);
        }
    }

    private static void warn(Throwable t) {
        LogManager.getLogger("ClientTag").warn("Couldn't show a OneConfig notification: {}", t.toString());
    }

    private static final class Present {
        static Object ask(String title, String message, boolean success, String yes, Runnable onYes, String no, Runnable onNo) {
            return Notifications.builder(title, message)
                    .type(success ? NotificationType.SUCCESS : NotificationType.INFO)
                    .persistent()
                    .action(new NotificationAction(yes, true, onYes))
                    .action(new NotificationAction(no, false, onNo))
                    .send();
        }

        static Object progress(String title, String message, Callable<Float> progress) {
            return Notifications.builder(title, message)
                    .type(NotificationType.PROGRESS)
                    .persistent()
                    .progress(progress)
                    .send();
        }

        static Object error(String title, String message) {
            return Notifications.error(title, message);
        }

        static void dismiss(Object notification) {
            if (notification instanceof Notification) {
                Notifications.dismiss((Notification) notification);
            }
        }
    }

}
