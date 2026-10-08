package com.ootruffle.clienttag.config;

import com.ootruffle.clienttag.NativeClients;
import com.ootruffle.clienttag.render.ClientIcon;
import org.polyfrost.compose.render.PolyColor;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.Map;
import org.polyfrost.oneconfig.api.config.v1.Config;
import org.polyfrost.oneconfig.api.config.v1.ConfigManager;
import org.polyfrost.oneconfig.api.config.v1.Property;
import org.polyfrost.oneconfig.api.config.v1.annotations.Color;
import org.polyfrost.oneconfig.api.config.v1.annotations.Switch;

/**
 * The OneConfig settings page. Only ever loaded through {@link ClientTagSettings} when
 * OneConfig is installed - everything else reads settings through that class.
 * <p>
 * Display options are on the General tab. Each client has its own tab, where it can be
 * hidden, or have its icon drawn in one fixed color instead of the color its client
 * reports for each player.
 * <p>
 * Every {@link ClientIcon} needs {@code <key>Enabled}, {@code <key>CustomColor} and
 * {@code <key>Color} fields here, named after its {@link ClientIcon#configKey()}.
 */
public final class ClientTagConfig extends Config {

    public static final ClientTagConfig INSTANCE = new ClientTagConfig();

    @Switch(title = "Show on Nametags", subcategory = "Display")
    public boolean showOnNametags = true;

    @Switch(title = "Show in Tab List", subcategory = "Display")
    public boolean showInTab = true;

    @Switch(title = "Use ClientTag Server", description = "Share which client you're really on with other ClientTag users, and learn theirs, so only real tags are shown. Anyone can look players up on this server.", subcategory = "ClientTag Server")
    public boolean useServer = true;

    @Switch(title = "Show My ClientTag Icon", description = "Show other ClientTag users a ClientTag icon by your name, next to the icon of the client you're on. It's always shown when you're on none.", subcategory = "ClientTag Server")
    public boolean showOwnTag = true;

    @Switch(title = "Show Lunar Client", category = "Lunar Client")
    public boolean lunarEnabled = true;
    @Switch(title = "Custom Color", description = "Use one color for everyone instead of the color Lunar reports.", category = "Lunar Client")
    public boolean lunarCustomColor = false;
    @Color(title = "Color", category = "Lunar Client", alpha = false)
    public PolyColor lunarColor = new PolyColor(0xFF8FB8FF);

    @Switch(title = "Show Dawn Client", category = "Dawn Client")
    public boolean dawnEnabled = true;
    @Switch(title = "Custom Color", description = "Use one color for everyone instead of their Dawn rank color.", category = "Dawn Client")
    public boolean dawnCustomColor = false;
    @Color(title = "Color", category = "Dawn Client", alpha = false)
    public PolyColor dawnColor = new PolyColor(0xFFFFA94D);

    @Switch(title = "Show Essential", category = "Essential")
    public boolean essentialEnabled = true;
    @Switch(title = "Custom Color", description = "Use one color for everyone instead of the color Essential reports.", category = "Essential")
    public boolean essentialCustomColor = false;
    @Color(title = "Color", category = "Essential", alpha = false)
    public PolyColor essentialColor = new PolyColor(0xFF4AE08A);

    @Switch(title = "Show NoRiskClient", category = "NoRiskClient")
    public boolean noRiskEnabled = true;
    @Switch(title = "Custom Color", description = "Use one color for everyone instead of the color NoRisk reports.", category = "NoRiskClient")
    public boolean noRiskCustomColor = false;
    @Color(title = "Color", category = "NoRiskClient", alpha = false)
    public PolyColor noRiskColor = new PolyColor(0xFFFF5555);

    @Switch(title = "Show LabyMod", category = "LabyMod")
    public boolean labyModEnabled = true;
    @Switch(title = "Custom Color", description = "Use one color for everyone instead of their LabyMod role color.", category = "LabyMod")
    public boolean labyModCustomColor = false;
    @Color(title = "Color", category = "LabyMod", alpha = false)
    public PolyColor labyModColor = new PolyColor(0xFFD8D8D8);

    @Switch(title = "Show Cosmetica", category = "Cosmetica")
    public boolean cosmeticaEnabled = true;
    @Switch(title = "Custom Color", description = "Tint the Cosmetica halo.", category = "Cosmetica")
    public boolean cosmeticaCustomColor = false;
    @Color(title = "Color", category = "Cosmetica", alpha = false)
    public PolyColor cosmeticaColor = new PolyColor(0xFFFFFFFF);

    @Switch(title = "Show ClientTag", description = "For ClientTag users who chose to show it, or aren't on any other client. Needs the ClientTag server.", category = "ClientTag")
    public boolean clientTagEnabled = true;
    @Switch(title = "Custom Color", description = "Use one color for everyone instead of the color the ClientTag server gives each player.", category = "ClientTag")
    public boolean clientTagCustomColor = false;
    @Color(title = "Color", category = "ClientTag", alpha = false)
    public PolyColor clientTagColor = new PolyColor(0xFFFFFFFF);

    @Switch(title = "Show OneClient", description = "Unavailable while the PolyPlus mod is installed - it draws the OneClient badge itself.", category = "OneClient")
    public boolean polyPlusEnabled = true;
    @Switch(title = "Custom Color", description = "Tint the OneClient badge.", category = "OneClient")
    public boolean polyPlusCustomColor = false;
    @Color(title = "Color", category = "OneClient", alpha = false)
    public PolyColor polyPlusColor = new PolyColor(0xFFFFFFFF);

    private ClientTagConfig() {
        super("clienttag.json", "ClientTag", Category.VISUALS);
        // The mod used to be called LunerTag: carry its settings over until ours are first saved.
        final Path folder = ConfigManager.active().getFolder();
        if (!Files.exists(folder.resolve("clienttag.json")) && Files.exists(folder.resolve("lunertag.json"))) {
            loadFrom(folder.resolve("lunertag.json"));
        }
        hideIf("showOwnTag", () -> !useServer);
        for (ClientIcon icon : ClientIcon.values()) {
            final String key = icon.configKey();
            hideIf(key + "Color", () -> !Options.of(icon).customColor(this));
            // A client we're running on draws its own indicators, so its options here don't apply.
            if (icon.notRunningLabel() != null) {
                for (String option : new String[] {key + "Enabled", key + "CustomColor", key + "Color"}) {
                    addDependency(option, icon.notRunningLabel(),
                            () -> NativeClients.isRunning(icon) ? Property.Display.DISABLED : Property.Display.SHOWN);
                }
            }
        }
    }

    /** Whether the user wants this client's icon shown. */
    public boolean isEnabled(ClientIcon icon) {
        return Options.of(icon).enabled(this);
    }

    /** The RGB color to draw the icon in, given the color its client reported for the player. */
    public int color(ClientIcon icon, int reportedColor) {
        final Options options = Options.of(icon);
        final PolyColor color = options.customColor(this) ? options.color(this) : null;
        // getArgb() follows chroma, so a chroma color animates.
        return color != null ? color.getArgb() & 0xFFFFFF : reportedColor;
    }

    /**
     * A client's three option fields, found by {@link ClientIcon#configKey()}. Kept out of the
     * config class itself so OneConfig never sees it.
     */
    private static final class Options {
        private static final Map<ClientIcon, Options> ALL = new EnumMap<>(ClientIcon.class);

        static {
            for (ClientIcon icon : ClientIcon.values()) {
                ALL.put(icon, new Options(icon.configKey()));
            }
        }

        private final Field enabled;
        private final Field customColor;
        private final Field color;

        private Options(String key) {
            try {
                enabled = ClientTagConfig.class.getField(key + "Enabled");
                customColor = ClientTagConfig.class.getField(key + "CustomColor");
                color = ClientTagConfig.class.getField(key + "Color");
            } catch (NoSuchFieldException e) {
                throw new IllegalStateException("ClientTagConfig is missing an option for \"" + key + "\"", e);
            }
        }

        static Options of(ClientIcon icon) {
            return ALL.get(icon);
        }

        boolean enabled(ClientTagConfig config) {
            return get(enabled, config, Boolean.class);
        }

        boolean customColor(ClientTagConfig config) {
            return get(customColor, config, Boolean.class);
        }

        PolyColor color(ClientTagConfig config) {
            return get(color, config, PolyColor.class);
        }

        private static <T> T get(Field field, ClientTagConfig config, Class<T> type) {
            try {
                return type.cast(field.get(config));
            } catch (IllegalAccessException e) {
                throw new IllegalStateException(e);
            }
        }
    }

}
