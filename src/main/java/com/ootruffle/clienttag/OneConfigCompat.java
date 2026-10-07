package com.ootruffle.clienttag;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import com.ootruffle.clienttag.render.ClientIcon;
import java.util.UUID;
import net.fabricmc.loader.api.FabricLoader;
import org.apache.logging.log4j.LogManager;

/**
 * Knows whether a player is online on PolyPlus (OneClient's companion mod). With PolyPlus
 * installed it's asked directly; without it, {@link OneConfigTagManager} asks PolyPlus's
 * servers instead. For players the ClientTag server doesn't know, being on PolyPlus is taken
 * as a sign of running ClientTag (see {@link ClientTagUsers#hides}).
 * <p>
 * PolyPlus is reached reflectively ({@code CosmeticCatalog.INSTANCE.isPolyPlusUser}), so
 * one build works against every Minecraft version's PolyPlus without compiling against any.
 */
public final class OneConfigCompat {

    private static final boolean LOADED = FabricLoader.getInstance().isModLoaded("polyplus");

    private OneConfigCompat() {}

    /** Whether PolyPlus itself is installed - it then draws its own badge. */
    public static boolean isInstalled() {
        return LOADED;
    }

    public static boolean isPolyPlusUser(UUID uuid) {
        return LOADED ? Present.isPolyPlusUser(uuid) : OneConfigTagManager.isOnline(uuid);
    }

    /** The badge color for our own PolyPlus badge (untinted), or null if PolyPlus draws it or they're not on PolyPlus. */
    public static Integer getBadgeColor(UUID uuid) {
        // ClientTag logs its users into PolyPlus too, so theirs is only shown if they said they're really on it.
        return !LOADED && OneConfigTagManager.isOnline(uuid) && !ClientTagUsers.hides(uuid, ClientIcon.POLYPLUS) ? 0xFFFFFF : null;
    }

    private static final class Present {
        /** Bound to CosmeticCatalog.INSTANCE; null if PolyPlus's API isn't what we expect. */
        private static final MethodHandle IS_POLYPLUS_USER = lookup();

        static boolean isPolyPlusUser(UUID uuid) {
            if (IS_POLYPLUS_USER == null) {
                return false;
            }
            try {
                return (boolean) IS_POLYPLUS_USER.invokeExact(uuid);
            } catch (Throwable t) {
                return false;
            }
        }

        private static MethodHandle lookup() {
            try {
                final Class<?> catalog = Class.forName("org.polyfrost.polyplus.client.cosmetics.CosmeticCatalog");
                final Object instance = catalog.getField("INSTANCE").get(null);
                return MethodHandles.publicLookup()
                        .findVirtual(catalog, "isPolyPlusUser", MethodType.methodType(boolean.class, UUID.class))
                        .bindTo(instance);
            } catch (ReflectiveOperationException | RuntimeException e) {
                LogManager.getLogger("ClientTag").warn("Couldn't hook into PolyPlus: {}", e.toString());
                return null;
            }
        }
    }

}
