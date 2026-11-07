package com.ootruffle.clienttag;

import com.ootruffle.clienttag.platform.Platform;
import com.ootruffle.clienttag.platform.Platform.ChatPart;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * {@code /clienttag debug <player>}: draws every client's icon by a player, as if they were on
 * all of them at once, to check how the icons look and line up. Purely local and forgotten when
 * the game closes - nothing is sent anywhere, and {@link WhoCommand} still answers truthfully.
 * Bare {@code /clienttag debug} turns it off for everyone. Client thread only, except
 * {@link #isShown}, which the renderers call.
 */
public final class DebugTags {

    // As in WhoCommand.
    private static final int TEXT = 0xAAAAAA;
    private static final int NAME = 0xFFFFFF;
    private static final int PREFIX = 0x55FFFF;
    private static final int ERROR = 0xFF5555;

    private static final Set<UUID> shown = ConcurrentHashMap.newKeySet();
    /** Names as they were when shown, for the messages. */
    private static final List<String> shownNames = new ArrayList<>();

    private DebugTags() {}

    /** Whether to draw every client's icon for this player. */
    public static boolean isShown(UUID uuid) {
        return uuid != null && !shown.isEmpty() && shown.contains(uuid);
    }

    /** Starts or stops showing every icon by the player with this name. */
    public static void toggle(String name) {
        final Platform platform = Platform.get();
        final UUID[] found = {null, null};
        final String[] foundName = {null, null};
        final String lower = name.toLowerCase(Locale.ROOT);
        platform.forEachPlayerName((uuid, playerName) -> {
            if (found[0] == null && playerName.equals(name)) {
                found[0] = uuid;
                foundName[0] = playerName;
            } else if (found[1] == null && playerName.toLowerCase(Locale.ROOT).equals(lower)) {
                found[1] = uuid;
                foundName[1] = playerName;
            }
        });
        final int match = found[0] != null ? 0 : 1;
        if (found[match] == null) {
            platform.showMessage(line(new ChatPart("No player called " + name + " is in the tab list or nearby.", ERROR)));
            return;
        }
        final List<ChatPart> message;
        if (shown.remove(found[match])) {
            shownNames.remove(foundName[match]);
            message = line(new ChatPart("Stopped showing every icon by ", TEXT));
        } else {
            shown.add(found[match]);
            shownNames.add(foundName[match]);
            message = line(new ChatPart("Showing every icon by ", TEXT));
        }
        message.add(new ChatPart(foundName[match], NAME));
        message.add(new ChatPart(" (only for you)", TEXT));
        platform.showMessage(message);
    }

    /** Stops showing every icon by anyone. */
    public static void clear() {
        final Platform platform = Platform.get();
        if (shown.isEmpty()) {
            platform.showMessage(line(new ChatPart("Nobody has every icon shown. Use /clienttag debug <player>", TEXT)));
            return;
        }
        platform.showMessage(line(new ChatPart("Stopped showing every icon by " + String.join(", ", shownNames), TEXT)));
        shown.clear();
        shownNames.clear();
    }

    private static List<ChatPart> line(ChatPart first) {
        final List<ChatPart> parts = new ArrayList<>();
        parts.add(new ChatPart("ClientTag » ", PREFIX));
        parts.add(first);
        return parts;
    }

}
