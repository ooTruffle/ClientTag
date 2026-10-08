package com.ootruffle.clienttag.render;

import com.ootruffle.clienttag.ClientTagUsers;
import com.ootruffle.clienttag.CosmeticaTagManager;
import com.ootruffle.clienttag.DawnTagManager;
import com.ootruffle.clienttag.EssentialTagManager;
import com.ootruffle.clienttag.LabyModTagManager;
import com.ootruffle.clienttag.LunarTagManager;
import com.ootruffle.clienttag.NativeClients;
import com.ootruffle.clienttag.NoRiskTagManager;
import com.ootruffle.clienttag.OneConfigCompat;
import com.ootruffle.clienttag.OneConfigTagManager;
import com.ootruffle.clienttag.config.ClientTagSettings;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Every client ClientTag knows about, in their default left-to-right drawing order (users can
 * rearrange them, see {@link ClientTagSettings#iconOrder()}) - the one place a client
 * is wired up. Adding one takes its tag manager, an entry here, its three config fields in
 * {@link com.ootruffle.clienttag.config.ClientTagConfig} (named after {@link Spec#config}), and its
 * glyph in assets/clienttag/font/icons.json (plus {@link IconArt#main} if it's drawn in code).
 * <p>
 * Lunar, Dawn, Essential, NoRisk and LabyMod icons are generated in code rather than shipped as
 * assets, so none of those clients' artwork is used: Lunar gets a crescent moon, Dawn a rising sun,
 * Essential a sparkle, NoRisk a shield, LabyMod a wolf head, and ClientTag itself a name tag.
 * PolyPlus gets its own badge (used with Polyfrost's permission), and Cosmetica its halo (at its
 * lead dev's request), but only when PolyPlus isn't installed to draw it.
 * <p>
 * 1.8.9 paints each icon into a dynamic texture at runtime; modern versions draw them as
 * glyphs of the {@code clienttag:icons} bitmap font, whose textures {@link IconArt} paints at build time.
 */
public enum ClientIcon {

    POLYPLUS(new Spec("polyplus", "OneClient", '\uE000')
            .color(OneConfigCompat::getBadgeColor)
            .art(IconArt.image("/assets/clienttag/textures/polyplus_badge.png"))
            .tick(OneConfigTagManager::onClientTick)
            .config("polyPlus", "PolyPlus mod not installed")
            .runningWhen(OneConfigCompat::isInstalled)),
    LUNAR(new Spec("lunar", "Lunar Client", '\uE001')
            .color(LunarTagManager::getLunarColor)
            .art(IconArt::crescent)
            .tick(LunarTagManager::onClientTick)
            .config("lunar", "Not running on Lunar Client")
            // Lunar loads mods through its own Ichor loader without registering a mod ID, but its
            // launcher (genesis) is on the classpath of every version it runs. These names survive its obfuscation.
            .runningWhen(() -> NativeClients.anyClass("com/moonsworth/lunar/genesis/Genesis.class",
                    "com/moonsworth/lunar/ichor/api/IchorAPI.class"))),
    DAWN(new Spec("dawn", "Dawn Client", '\uE002')
            .color(DawnTagManager::getDawnColor)
            .art(IconArt::sunrise)
            .tick(DawnTagManager::onClientTick)
            .config("dawn", "Not running on Dawn Client")
            // Dawn is the former Feather Client and still ships Feather's entrypoint, which is the fallback.
            .runningWhen(() -> NativeClients.isDawnClient()
                    || NativeClients.anyClass("net/digitalingot/feather/FeatherMod.class"))),
    ESSENTIAL(new Spec("essential", "Essential", '\uE003')
            .color(EssentialTagManager::getEssentialColor)
            .art(IconArt::sparkle)
            .tick(EssentialTagManager::onClientTick)
            .config("essential", "Essential not installed")
            // Essential's loader stub registers as a mod, while the real mod is downloaded and loaded later.
            .runningWhen(() -> NativeClients.anyMod("essential", "essential-container")
                    || NativeClients.anyClass("gg/essential/Essential.class"))),
    NORISK(new Spec("norisk", "NoRiskClient", '\uE004')
            .color(NoRiskTagManager::getNoRiskColor)
            .art(IconArt::shield)
            .tick(NoRiskTagManager::onClientTick)
            .config("noRisk", "Not running on NoRiskClient")
            .runningWhen(() -> NativeClients.anyMod("nrcclient")
                    || NativeClients.anyClass("gg/norisk/client/bootstrap/ClientBootstrap.class"))),
    LABYMOD(new Spec("labymod", "LabyMod", '\uE005')
            .color(LabyModTagManager::getLabyModColor)
            .art(IconArt::wolf)
            .tick(LabyModTagManager::onClientTick)
            .config("labyMod", "Not running on LabyMod")
            .runningWhen(() -> NativeClients.anyMod("labymod") || NativeClients.anyClass("net/labymod/api/Laby.class"))),
    COSMETICA(new Spec("cosmetica", "Cosmetica", '\uE007')
            .color(CosmeticaTagManager::getCosmeticaColor)
            .art(IconArt.image("/assets/clienttag/textures/cosmetica_halo.png"))
            .tick(CosmeticaTagManager::onClientTick)
            .config("cosmetica", "Cosmetica not installed")
            .runningWhen(() -> NativeClients.anyMod("cosmetica"))),
    // ClientTag is never "running natively" from its own point of view, and ClientTagUsers is
    // ticked by ClientTag itself, since it also tells the other managers who to hide.
    CLIENTTAG(new Spec("clienttag", "ClientTag", '\uE006')
            .color(ClientTagUsers::getClientTagColor)
            .art(IconArt::nametag)
            .config("clientTag", null));

    public static final int TEXTURE_SIZE = 32;

    /** An icon to draw for one player, tinted with its client's color for them. */
    public static final class Tinted {
        private final ClientIcon icon;
        private final int color;

        Tinted(ClientIcon icon, int color) {
            this.icon = icon;
            this.color = color;
        }

        public ClientIcon icon() {
            return icon;
        }

        /** RGB. */
        public int color() {
            return color;
        }
    }

    /** How a client is wired up; see the entries above. */
    private static final class Spec {
        private final String id;
        private final String displayName;
        private final char glyph;
        private Function<UUID, Integer> colorLookup;
        private Consumer<int[]> painter;
        private Runnable ticker;
        private BooleanSupplier running;
        private String configKey;
        private String notRunningLabel;

        Spec(String id, String displayName, char glyph) {
            this.id = id;
            this.displayName = displayName;
            this.glyph = glyph;
        }

        /** The player's indicator color (RGB), or null if they're not on the client. */
        Spec color(Function<UUID, Integer> colorLookup) {
            this.colorLookup = colorLookup;
            return this;
        }

        /** Paints the white (or full-color) icon; see {@link ClientIcon#paint}. */
        Spec art(Consumer<int[]> painter) {
            this.painter = painter;
            return this;
        }

        /** Run every client tick, unless the game is running on this client. */
        Spec tick(Runnable ticker) {
            this.ticker = ticker;
            return this;
        }

        /**
         * The prefix of its {@code <key>Enabled}, {@code <key>CustomColor} and {@code <key>Color}
         * config fields, and what the config says while the game runs on it (null if it never can).
         */
        Spec config(String configKey, String notRunningLabel) {
            this.configKey = configKey;
            this.notRunningLabel = notRunningLabel;
            return this;
        }

        /** Whether this game is itself running on (or has installed) the client; see {@link NativeClients}. */
        Spec runningWhen(BooleanSupplier running) {
            this.running = running;
            return this;
        }
    }

    static {
        final Set<String> ids = new HashSet<>();
        final Set<String> names = new HashSet<>();
        final Set<Character> glyphs = new HashSet<>();
        for (ClientIcon icon : values()) {
            if (!ids.add(icon.id) || !names.add(icon.displayName) || !glyphs.add(icon.glyph)) {
                throw new IllegalStateException("Duplicate client id, name or glyph: " + icon);
            }
        }
    }

    private final String id;
    private final String displayName;
    private final char glyph;
    private final Function<UUID, Integer> colorLookup;
    private final Consumer<int[]> painter;
    private final Runnable ticker;
    private final BooleanSupplier running;
    private final String configKey;
    private final String notRunningLabel;

    ClientIcon(Spec spec) {
        this.id = spec.id;
        this.displayName = spec.displayName;
        this.glyph = spec.glyph;
        this.colorLookup = spec.colorLookup;
        this.painter = spec.painter;
        this.ticker = spec.ticker;
        this.running = spec.running;
        this.configKey = spec.configKey;
        this.notRunningLabel = spec.notRunningLabel;
    }

    /** Every enabled icon the player should get, in the user's drawing order; empty if they're on none of the clients. */
    public static List<Tinted> iconsFor(UUID uuid) {
        final List<Tinted> icons = new ArrayList<>(values().length);
        if (uuid == null) {
            return icons;
        }
        for (ClientIcon icon : ClientTagSettings.iconOrder()) {
            if (!ClientTagSettings.isEnabled(icon)) {
                continue;
            }
            final Integer color = icon.colorLookup.apply(uuid);
            if (color != null) {
                icons.add(new Tinted(icon, ClientTagSettings.color(icon, color)));
            }
        }
        return icons;
    }

    /** The icon with this {@link #displayName()}, or null. */
    public static ClientIcon byDisplayName(String displayName) {
        for (ClientIcon icon : values()) {
            if (icon.displayName.equals(displayName)) {
                return icon;
            }
        }
        return null;
    }

    /** The icon with this {@link #id()}, or null. */
    public static ClientIcon byId(String id) {
        for (ClientIcon icon : values()) {
            if (icon.id.equals(id)) {
                return icon;
            }
        }
        return null;
    }

    /** Short lowercase name, e.g. "lunar". Sent to the ClientTag server, so it must never change. */
    public String id() {
        return id;
    }

    /** The client's name as players know it, e.g. "Lunar Client". Shown in, and saved by, the icon order setting. */
    public String displayName() {
        return displayName;
    }

    /**
     * The icon's character in the {@code clienttag:icons} font (see assets/clienttag/font/icons.json).
     * Fixed per client, independent of drawing order.
     */
    public char glyph() {
        return glyph;
    }

    /** Paints the white (or, for PolyPlus and Cosmetica, full-color) icon into TEXTURE_SIZE x TEXTURE_SIZE ARGB pixels. */
    public void paint(int[] pixels) {
        painter.accept(pixels);
    }

    /** Runs the client's tag manager for one client tick, if it has one. */
    public void tick() {
        if (ticker != null) {
            ticker.run();
        }
    }

    /** Looks for the client in this game, uncached; use {@link NativeClients#isRunning} instead. */
    public boolean detectRunning() {
        return running != null && running.getAsBoolean();
    }

    /** The prefix of the client's config fields, e.g. "noRisk". */
    public String configKey() {
        return configKey;
    }

    /** What the config shows while the game runs on this client, e.g. "Not running on LabyMod"; null if it never can. */
    public String notRunningLabel() {
        return notRunningLabel;
    }

}
