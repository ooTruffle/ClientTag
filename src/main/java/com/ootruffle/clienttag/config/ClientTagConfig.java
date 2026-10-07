package com.ootruffle.clienttag.config;

import com.ootruffle.clienttag.NativeClients;
import com.ootruffle.clienttag.render.ClientIcon;
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
        hideIf("lunarColor", () -> !lunarCustomColor);
        hideIf("dawnColor", () -> !dawnCustomColor);
        hideIf("essentialColor", () -> !essentialCustomColor);
        hideIf("noRiskColor", () -> !noRiskCustomColor);
        hideIf("labyModColor", () -> !labyModCustomColor);
        hideIf("clientTagColor", () -> !clientTagCustomColor);
        hideIf("showOwnTag", () -> !useServer);
        hideIf("polyPlusColor", () -> !polyPlusCustomColor || NativeClients.isRunning(ClientIcon.POLYPLUS));
        // A client we're running on draws its own indicators, so its options here don't apply.
        notOn(ClientIcon.LUNAR, "Not running on Lunar Client", "lunarEnabled", "lunarCustomColor", "lunarColor");
        notOn(ClientIcon.DAWN, "Not running on Dawn Client", "dawnEnabled", "dawnCustomColor", "dawnColor");
        notOn(ClientIcon.ESSENTIAL, "Essential not installed", "essentialEnabled", "essentialCustomColor", "essentialColor");
        notOn(ClientIcon.NORISK, "Not running on NoRiskClient", "noRiskEnabled", "noRiskCustomColor", "noRiskColor");
        notOn(ClientIcon.LABYMOD, "Not running on LabyMod", "labyModEnabled", "labyModCustomColor", "labyModColor");
        notOn(ClientIcon.POLYPLUS, "PolyPlus mod not installed", "polyPlusEnabled", "polyPlusCustomColor");
    }

    private void notOn(ClientIcon icon, String condition, String... options) {
        for (String option : options) {
            addDependency(option, condition,
                    () -> NativeClients.isRunning(icon) ? Property.Display.DISABLED : Property.Display.SHOWN);
        }
    }

}
