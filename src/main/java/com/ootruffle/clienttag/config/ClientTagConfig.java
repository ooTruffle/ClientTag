package com.ootruffle.clienttag.config;

import com.ootruffle.clienttag.OneConfigCompat;
import org.polyfrost.compose.render.PolyColor;
import java.nio.file.Files;
import java.nio.file.Path;
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
 */
public final class ClientTagConfig extends Config {

    public static final ClientTagConfig INSTANCE = new ClientTagConfig();

    @Switch(title = "Show on Nametags", subcategory = "Display")
    public boolean showOnNametags = true;

    @Switch(title = "Show in Tab List", subcategory = "Display")
    public boolean showInTab = true;

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
        hideIf("lunarColor", () -> !lunarCustomColor);
        hideIf("dawnColor", () -> !dawnCustomColor);
        hideIf("essentialColor", () -> !essentialCustomColor);
        hideIf("noRiskColor", () -> !noRiskCustomColor);
        hideIf("labyModColor", () -> !labyModCustomColor);
        hideIf("polyPlusColor", () -> !polyPlusCustomColor || OneConfigCompat.isInstalled());
        // The PolyPlus mod draws its own badge when installed, so ClientTag's OneConfig options don't apply.
        addDependency("polyPlusEnabled", "PolyPlus mod not installed", ClientTagConfig::polyPlusDisplay);
        addDependency("polyPlusCustomColor", "PolyPlus mod not installed", ClientTagConfig::polyPlusDisplay);
    }

    private static Property.Display polyPlusDisplay() {
        return OneConfigCompat.isInstalled() ? Property.Display.DISABLED : Property.Display.SHOWN;
    }

}
