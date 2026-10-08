package com.ootruffle.clienttag.legacy.render;

import com.ootruffle.clienttag.render.ClientIcon;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Private-use characters that stand for a tinted client icon inside 1.8.9 text, the way
 * PolyPlus puts its badge into nametags. Being part of the string, the icons follow whatever
 * draws it - vanilla, PolyNametag, Argentum's batched nametags - and get counted in its width.
 * {@code FontRendererMixin} draws them.
 * <p>
 * Each icon and color pair gets its own character on first use. Starts at U+E800, clear of
 * PolyPlus's badge (U+E000) and emoji (U+F100-U+F3FF). Render thread only.
 */
public final class IconGlyphs {

    private static final char FIRST = '';
    private static final int CAPACITY = 0x800;
    /** Each glyph is an 8px icon followed by a 2px gap. */
    public static final int ADVANCE = 10;
    private static final int ICON_SIZE = 8;

    private static final Map<Integer, Character> CHARS = new HashMap<>();
    private static final ClientIcon.Tinted[] ICONS = new ClientIcon.Tinted[CAPACITY];
    private static int next;

    private IconGlyphs() {}

    /** The characters for these icons, in order; icons past the character budget are left out. */
    public static String of(List<ClientIcon.Tinted> icons) {
        final StringBuilder sb = new StringBuilder(icons.size());
        for (ClientIcon.Tinted icon : icons) {
            final Character c = charFor(icon);
            if (c != null) {
                sb.append(c.charValue());
            }
        }
        return sb.toString();
    }

    public static boolean isIcon(char c) {
        final int index = c - FIRST;
        return index >= 0 && index < CAPACITY && ICONS[index] != null;
    }

    public static boolean containsIcon(String text) {
        for (int i = 0; i < text.length(); i++) {
            if (isIcon(text.charAt(i))) {
                return true;
            }
        }
        return false;
    }

    /** Draws the icon for {@code c} with its top-left at (x, y). */
    public static void draw(char c, float x, float y, float alpha) {
        IconTextures.drawInline(ICONS[c - FIRST], x, y, ICON_SIZE, alpha);
    }

    private static Character charFor(ClientIcon.Tinted icon) {
        final Integer key = icon.icon().ordinal() << 24 | icon.color() & 0xFFFFFF;
        Character c = CHARS.get(key);
        if (c == null && next < CAPACITY) {
            ICONS[next] = icon;
            c = (char) (FIRST + next++);
            CHARS.put(key, c);
        }
        return c;
    }

}
