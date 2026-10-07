package com.ootruffle.clienttag.modern;

import com.ootruffle.clienttag.render.ClientIcon;
import java.util.List;
import java.util.UUID;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
//? if >= 1.21.10 {
import net.minecraft.network.chat.FontDescription;
//?}
//? if >= 1.21.11 {
import net.minecraft.resources.Identifier;
//?} else
/*import net.minecraft.resources.ResourceLocation;*/

/**
 * Puts the client icons in front of a player's name as glyphs of the {@code clienttag:icons}
 * bitmap font (assets/clienttag/font/icons.json), each colored with its client's color for them.
 * Being text, the icons get vanilla's see-through pass, background and tab list layout for free.
 */
public final class IconText {

    //? if >= 1.21.11 {
    private static final Style FONT = Style.EMPTY.withFont(new FontDescription.Resource(Identifier.fromNamespaceAndPath("clienttag", "icons")));
    //?} elif >= 1.21.10 {
    /*private static final Style FONT = Style.EMPTY.withFont(new FontDescription.Resource(ResourceLocation.fromNamespaceAndPath("clienttag", "icons")));
    *///?} else
    /*private static final Style FONT = Style.EMPTY.withFont(ResourceLocation.fromNamespaceAndPath("clienttag", "icons"));*/
    /** A 2px space glyph in the icon font, between the icons and the name. */
    private static final String GAP = "";

    private IconText() {}

    /** {@code name} with the player's icons in front, or {@code name} itself if they have none. */
    public static Component withIcons(UUID uuid, Component name) {
        final List<ClientIcon.Tinted> icons = ClientIcon.iconsFor(uuid);
        if (icons.isEmpty() || name == null) {
            return name;
        }
        final MutableComponent text = Component.empty();
        for (ClientIcon.Tinted icon : icons) {
            text.append(Component.literal(String.valueOf(icon.icon().glyph())).setStyle(FONT.withColor(icon.color())));
        }
        text.append(Component.literal(GAP).setStyle(FONT));
        return text.append(name);
    }

}
