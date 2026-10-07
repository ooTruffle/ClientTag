package com.ootruffle.clienttag.legacy.render;

import com.ootruffle.clienttag.config.ClientTagSettings;
import com.ootruffle.clienttag.render.ClientIcon;
import java.util.Collections;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.network.NetworkPlayerInfo;

/**
 * Adds the client icons in front of players' names in the tab list.
 * <p>
 * Names are padded with leading spaces wherever the tab list asks for them, so vanilla's
 * own column-width calculation makes room for the icons. The row's draw call comes right
 * after its getPlayerName call, so the icons are handed from one to the other through
 * {@link #pendingIcons}.
 */
public final class TabIconRenderer {

    private static final int ICON_SIZE = 7;
    private static final int GAP = 1;

    private static List<ClientIcon.Tinted> pendingIcons;

    private TabIconRenderer() {}

    public static String padName(NetworkPlayerInfo info, String name) {
        final List<ClientIcon.Tinted> icons = ClientTagSettings.showInTab()
                ? ClientIcon.iconsFor(info.getGameProfile().getId())
                : Collections.<ClientIcon.Tinted>emptyList();
        pendingIcons = icons.isEmpty() ? null : icons;
        return pendingIcons == null ? name : padding(icons.size()) + name;
    }

    /** Called right before a row's name is drawn at (x, y) with the given ARGB text color. */
    public static void drawPendingIcons(float x, float y, int textColor) {
        final List<ClientIcon.Tinted> icons = pendingIcons;
        pendingIcons = null;
        if (icons == null) {
            return;
        }
        final int alpha = textColor >>> 24;
        for (int i = 0; i < icons.size(); i++) {
            IconTextures.draw(icons.get(i), (int) x + i * (ICON_SIZE + GAP), (int) y, ICON_SIZE, alpha == 0 ? 1.0F : alpha / 255.0F);
        }
    }

    /** Enough spaces to fit the icons plus a 1px gap after each, whatever the font's space width. */
    private static String padding(int iconCount) {
        final int spaceWidth = Math.max(1, Minecraft.getMinecraft().fontRendererObj.getCharWidth(' '));
        final int width = iconCount * (ICON_SIZE + GAP);
        final int count = (width + spaceWidth - 1) / spaceWidth;
        final StringBuilder sb = new StringBuilder(count);
        for (int i = 0; i < count; i++) {
            sb.append(' ');
        }
        return sb.toString();
    }

}
