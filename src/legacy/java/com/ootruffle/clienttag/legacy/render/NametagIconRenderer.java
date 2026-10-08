package com.ootruffle.clienttag.legacy.render;

import com.ootruffle.clienttag.config.ClientTagSettings;
import com.ootruffle.clienttag.render.ClientIcon;
import java.util.List;
import java.util.regex.Pattern;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;

/**
 * Puts the client indicators (Lunar crescent, Dawn sunrise, Essential sparkle, NoRisk shield, LabyMod wolf, Cosmetica halo)
 * in front of a player's nametag as {@link IconGlyphs} characters, each tinted with the color
 * its client reports for them.
 * <p>
 * Called at the start of {@code Render#renderLivingLabel}; being text, the icons get vanilla's
 * background, see-through pass and layout - or any nametag mod's - for free.
 */
public final class NametagIconRenderer {

    // Formatting codes, plus private-use glyphs other mods prepend as badges (PolyPlus adds U+E000).
    private static final Pattern FORMATTING_CODE = Pattern.compile("(?i)§[0-9a-fk-or]|[\\uE000-\\uF8FF]");

    private NametagIconRenderer() {}

    /** {@code label} with the player's icons in front, or {@code label} itself. */
    public static String withIcons(Entity entity, String label) {
        if (!(entity instanceof EntityPlayer) || label == null || !ClientTagSettings.showOnNametags()) {
            return label;
        }
        // renderLivingLabel also draws the below-name scoreboard line - only tag the name itself.
        // Compared without formatting codes or badge glyphs: the drawn label can lack the trailing
        // "§r" that getFormattedText() appends, and PolyPlus prefixes its badge glyph.
        if (!stripFormatting(label).equals(stripFormatting(entity.getDisplayName().getFormattedText()))) {
            return label;
        }
        final List<ClientIcon.Tinted> icons = ClientIcon.iconsFor(entity.getUniqueID());
        return icons.isEmpty() ? label : IconGlyphs.of(icons) + label;
    }

    private static String stripFormatting(String text) {
        return FORMATTING_CODE.matcher(text).replaceAll("").trim();
    }

}
