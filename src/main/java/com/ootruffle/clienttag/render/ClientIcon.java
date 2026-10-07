package com.ootruffle.clienttag.render;

import com.ootruffle.clienttag.DawnTagManager;
import com.ootruffle.clienttag.EssentialTagManager;
import com.ootruffle.clienttag.LabyModTagManager;
import com.ootruffle.clienttag.LunarTagManager;
import com.ootruffle.clienttag.NoRiskTagManager;
import com.ootruffle.clienttag.OneConfigCompat;
import com.ootruffle.clienttag.config.ClientTagSettings;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * The client indicators a player can have, in left-to-right drawing order. Lunar, Dawn,
 * Essential, NoRisk and LabyMod icons are generated in code rather than shipped as assets, so none of those
 * clients' artwork is used: Lunar gets a crescent moon, Dawn a rising sun, Essential a sparkle,
 * NoRisk a shield, LabyMod a wolf head. PolyPlus gets its own
 * badge (used with Polyfrost's permission), but only when PolyPlus isn't installed to draw it.
 * <p>
 * 1.8.9 paints each icon into a dynamic texture at runtime; modern versions draw them as
 * glyphs of the {@code clienttag:icons} bitmap font, whose textures {@link IconArt} paints at build time.
 */
public enum ClientIcon {

    POLYPLUS("polyplus", OneConfigCompat::getBadgeColor, IconArt.image("/assets/clienttag/textures/polyplus_badge.png")),
    LUNAR("lunar", LunarTagManager::getLunarColor, IconArt::crescent),
    DAWN("dawn", DawnTagManager::getDawnColor, IconArt::sunrise),
    ESSENTIAL("essential", EssentialTagManager::getEssentialColor, IconArt::sparkle),
    NORISK("norisk", NoRiskTagManager::getNoRiskColor, IconArt::shield),
    LABYMOD("labymod", LabyModTagManager::getLabyModColor, IconArt::wolf);

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

    private final String id;
    private final Function<UUID, Integer> colorLookup;
    private final Consumer<int[]> painter;

    ClientIcon(String id, Function<UUID, Integer> colorLookup, Consumer<int[]> painter) {
        this.id = id;
        this.colorLookup = colorLookup;
        this.painter = painter;
    }

    /** Every enabled icon the player should get, in drawing order; empty if they're on none of the clients. */
    public static List<Tinted> iconsFor(UUID uuid) {
        final List<Tinted> icons = new ArrayList<>(values().length);
        if (uuid == null) {
            return icons;
        }
        for (ClientIcon icon : values()) {
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

    /** Short lowercase name, e.g. "lunar". */
    public String id() {
        return id;
    }

    /** The icon's character in the {@code clienttag:icons} font (see assets/clienttag/font/icons.json). */
    public char glyph() {
        return (char) (0xE000 + ordinal());
    }

    /** Paints the white (or, for PolyPlus, full-color) icon into TEXTURE_SIZE x TEXTURE_SIZE ARGB pixels. */
    public void paint(int[] pixels) {
        painter.accept(pixels);
    }

}
