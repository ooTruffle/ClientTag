package com.ootruffle.clienttag;

import com.ootruffle.clienttag.render.ClientIcon;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;
import net.fabricmc.loader.api.FabricLoader;
import org.apache.logging.log4j.LogManager;

/**
 * Knows which of the supported clients this game is itself running on (or has installed).
 * That client then draws its own indicators, and a second connection with the same account
 * could knock its own one off - so ClientTag leaves that client alone entirely.
 * <p>
 * Each client is recognised by its Fabric mod IDs or by a class only it ships (each
 * {@link ClientIcon} says which, using the helpers here). The result is
 * kept from the first client tick on, by which point Essential's loader stub has loaded the
 * real mod if it's going to; anything asking earlier gets a fresh, uncached look.
 */
public final class NativeClients {

    private static volatile Set<ClientIcon> running;

    private NativeClients() {}

    /** Whether the client behind {@code icon} is running in this game. */
    public static boolean isRunning(ClientIcon icon) {
        final Set<ClientIcon> result = running;
        return (result != null ? result : detect(false)).contains(icon);
    }

    /** Settles the result; called on the first client tick. */
    static void settle() {
        if (running == null) {
            running = detect(true);
        }
    }

    private static Set<ClientIcon> detect(boolean log) {
        final Set<ClientIcon> found = EnumSet.noneOf(ClientIcon.class);
        for (ClientIcon icon : ClientIcon.values()) {
            if (icon.detectRunning()) {
                found.add(icon);
            }
        }
        if (log && !found.isEmpty()) {
            LogManager.getLogger("ClientTag").info("Running alongside {} - leaving their indicators to them", found);
        }
        return found;
    }

    /** Dawn's mod ID is just "dawn", so its metadata has to say it's Dawn Client too. */
    public static boolean isDawnClient() {
        return FabricLoader.getInstance().getModContainer("dawn")
                .map(mod -> mod.getMetadata().getName().toLowerCase(Locale.ROOT).startsWith("dawn client")
                        || mod.getMetadata().getContact().get("homepage").map(url -> url.contains("dawn.gg")).orElse(false))
                .orElse(false);
    }

    /** Whether any of these Fabric mods is loaded. */
    public static boolean anyMod(String... ids) {
        for (String id : ids) {
            if (FabricLoader.getInstance().isModLoaded(id)) {
                return true;
            }
        }
        return false;
    }

    /** Whether any of these class files is on our or the system classpath. */
    public static boolean anyClass(String... resources) {
        final ClassLoader ours = NativeClients.class.getClassLoader();
        final ClassLoader system = ClassLoader.getSystemClassLoader();
        for (String resource : resources) {
            if (ours.getResource(resource) != null || system.getResource(resource) != null) {
                return true;
            }
        }
        return false;
    }

}
