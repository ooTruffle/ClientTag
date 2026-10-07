package com.ootruffle.clienttag.config;

import com.ootruffle.clienttag.OneConfigCompat;
import com.ootruffle.clienttag.render.ClientIcon;
import net.fabricmc.loader.api.FabricLoader;
import org.polyfrost.compose.render.PolyColor;
import org.polyfrost.oneconfig.api.commands.v1.CommandManager;
import org.polyfrost.oneconfig.utils.v1.dsl.ScreensKt;

/**
 * The mod's settings, editable through OneConfig when it's installed (also via /clienttag).
 * Without OneConfig every client is shown everywhere in its reported color.
 * <p>
 * ClientTag's own PolyPlus tracking is always off while the PolyPlus mod is installed - it
 * draws its own badge then.
 * <p>
 * {@link Present} is the only class touching OneConfig, and is never loaded without it.
 */
public final class ClientTagSettings {

    private static final boolean LOADED = FabricLoader.getInstance().isModLoaded("oneconfigv1");

    private ClientTagSettings() {}

    /** Loads the config and registers /clienttag; call once from the mod initializer. */
    public static void init() {
        if (LOADED) {
            Present.init();
        }
    }

    public static boolean showOnNametags() {
        return !LOADED || Present.showOnNametags();
    }

    public static boolean showInTab() {
        return !LOADED || Present.showInTab();
    }

    public static boolean isEnabled(ClientIcon icon) {
        if (icon == ClientIcon.POLYPLUS && OneConfigCompat.isInstalled()) {
            return false;
        }
        return !LOADED || Present.isEnabled(icon);
    }

    /** The RGB color to draw the icon in, given the color its client reported for the player. */
    public static int color(ClientIcon icon, int reportedColor) {
        return LOADED ? Present.color(icon, reportedColor) : reportedColor;
    }

    private static final class Present {
        private static ClientTagConfig config() {
            return ClientTagConfig.INSTANCE;
        }

        static void init() {
            config().preload();
            CommandManager.INSTANCE.register(ScreensKt.addDefaultCommand(config(), "clienttag"));
        }

        static boolean showOnNametags() {
            return config().showOnNametags;
        }

        static boolean showInTab() {
            return config().showInTab;
        }

        static boolean isEnabled(ClientIcon icon) {
            final ClientTagConfig c = config();
            switch (icon) {
                case POLYPLUS: return c.polyPlusEnabled;
                case LUNAR: return c.lunarEnabled;
                case DAWN: return c.dawnEnabled;
                case ESSENTIAL: return c.essentialEnabled;
                case NORISK: return c.noRiskEnabled;
                case LABYMOD: return c.labyModEnabled;
                default: return true;
            }
        }

        static int color(ClientIcon icon, int reportedColor) {
            final ClientTagConfig c = config();
            switch (icon) {
                case POLYPLUS: return custom(c.polyPlusCustomColor, c.polyPlusColor, reportedColor);
                case LUNAR: return custom(c.lunarCustomColor, c.lunarColor, reportedColor);
                case DAWN: return custom(c.dawnCustomColor, c.dawnColor, reportedColor);
                case ESSENTIAL: return custom(c.essentialCustomColor, c.essentialColor, reportedColor);
                case NORISK: return custom(c.noRiskCustomColor, c.noRiskColor, reportedColor);
                case LABYMOD: return custom(c.labyModCustomColor, c.labyModColor, reportedColor);
                default: return reportedColor;
            }
        }

        private static int custom(boolean enabled, PolyColor color, int reportedColor) {
            // getArgb() follows chroma, so a chroma color animates.
            return enabled && color != null ? color.getArgb() & 0xFFFFFF : reportedColor;
        }
    }

}
