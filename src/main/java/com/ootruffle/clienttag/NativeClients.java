package com.ootruffle.clienttag;

import com.ootruffle.clienttag.render.ClientIcon;
import java.util.EnumSet;
import java.util.Set;
import net.fabricmc.loader.api.FabricLoader;
import org.apache.logging.log4j.LogManager;

/**
 * Knows which of the supported clients this game is itself running on (or has installed).
 * That client then draws its own indicators, and a second connection with the same account
 * could knock its own one off - so ClientTag leaves that client alone entirely.
 * <p>
 * Each client is recognised by its Fabric mod IDs or by a class only it ships. The result is
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
        if (OneConfigCompat.isInstalled()) {
            found.add(ClientIcon.POLYPLUS);
        }
        // Lunar loads mods through its own Ichor loader without registering a mod ID, but its
        // launcher (genesis) is on the classpath of every version it runs. These names survive its obfuscation.
        if (anyClass("com/moonsworth/lunar/genesis/Genesis.class", "com/moonsworth/lunar/ichor/api/IchorAPI.class")) {
            found.add(ClientIcon.LUNAR);
        }
        if (anyMod("dawnclient", "dawn-client")) {
            found.add(ClientIcon.DAWN);
        }
        // Essential's loader stub registers as a mod, while the real mod is downloaded and loaded later.
        if (anyMod("essential", "essential-container") || anyClass("gg/essential/Essential.class")) {
            found.add(ClientIcon.ESSENTIAL);
        }
        if (anyMod("nrcclient") || anyClass("gg/norisk/client/bootstrap/ClientBootstrap.class")) {
            found.add(ClientIcon.NORISK);
        }
        if (anyMod("labymod") || anyClass("net/labymod/api/Laby.class")) {
            found.add(ClientIcon.LABYMOD);
        }
        if (anyMod("cosmetica")) {
            found.add(ClientIcon.COSMETICA);
        }
        if (log && !found.isEmpty()) {
            LogManager.getLogger("ClientTag").info("Running alongside {} - leaving their indicators to them", found);
        }
        return found;
    }

    private static boolean anyMod(String... ids) {
        for (String id : ids) {
            if (FabricLoader.getInstance().isModLoaded(id)) {
                return true;
            }
        }
        return false;
    }

    private static boolean anyClass(String... resources) {
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
