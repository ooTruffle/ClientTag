package com.ootruffle.clienttag;

import com.ootruffle.clienttag.config.ClientTagSettings;
import com.ootruffle.clienttag.platform.Platform;
import com.ootruffle.clienttag.platform.Platform.ChatPart;
import com.ootruffle.clienttag.render.ClientIcon;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.TreeSet;
import java.util.UUID;

/**
 * {@code /clienttag who <player>}: says in chat which clients a player in the tab list or the
 * world is known to be on, each in its icon's color and in the user's icon order, and which
 * clients weren't checked for them. Only answers from what the tag managers already know,
 * so it never waits on the network. Client thread only.
 */
public final class WhoCommand {

    private static final int TEXT = 0xAAAAAA;
    private static final int NAME = 0xFFFFFF;
    private static final int PREFIX = 0x55FFFF;
    private static final int ERROR = 0xFF5555;

    private WhoCommand() {}

    /** Every player name that {@link #run} would find, for tab completion, sorted. */
    public static Collection<String> playerNames() {
        final TreeSet<String> names = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        Platform.get().forEachPlayerName((uuid, name) -> names.add(name));
        return names;
    }

    /** Answers {@code /clienttag who <name>} in chat. */
    public static void run(String name) {
        final Platform platform = Platform.get();
        final Player player = find(platform, name);
        if (player == null) {
            platform.showMessage(line(new ChatPart("No player called " + name + " is in the tab list or nearby.", ERROR)));
            return;
        }
        if (player.uuid.equals(platform.localPlayerId()) || player.uuid.equals(platform.sessionId())) {
            platform.showMessage(self());
            return;
        }
        if (!Consent.accepted()) {
            platform.showMessage(line(new ChatPart("ClientTag isn't checking anyone yet - accept its first-launch warning first.", ERROR)));
            return;
        }

        final List<ChatPart> on = new ArrayList<>();
        final List<String> notChecked = new ArrayList<>();
        for (ClientIcon icon : ClientTagSettings.iconOrder()) {
            // Checked first: isEnabled is also false for a client the game runs.
            if (NativeClients.isRunning(icon)) {
                notChecked.add(icon.displayName() + " (your game runs it)");
                continue;
            }
            if (!ClientTagSettings.isEnabled(icon)) {
                notChecked.add(icon.displayName() + " (off in settings)");
                continue;
            }
            final Integer color = icon.reportedColor(player.uuid);
            if (color != null) {
                if (!on.isEmpty()) {
                    on.add(new ChatPart(", ", TEXT));
                }
                on.add(new ChatPart(icon.displayName(), ClientTagSettings.color(icon, color)));
            }
        }

        final List<ChatPart> answer = line(new ChatPart(player.name, NAME));
        if (on.isEmpty()) {
            answer.add(new ChatPart(" isn't on any client ClientTag can see.", TEXT));
        } else {
            answer.add(new ChatPart(" is on ", TEXT));
            answer.addAll(on);
        }
        platform.showMessage(answer);
        if (!notChecked.isEmpty()) {
            platform.showMessage(line(new ChatPart("Not checked: " + String.join(", ", notChecked), TEXT)));
        }
    }

    /** For yourself: ClientTag never looks you up, but it knows which of the clients your game runs. */
    private static List<ChatPart> self() {
        final List<String> running = new ArrayList<>();
        for (ClientIcon icon : ClientTagSettings.iconOrder()) {
            if (NativeClients.isRunning(icon)) {
                running.add(icon.displayName());
            }
        }
        return line(new ChatPart(running.isEmpty()
                ? "That's you - your game isn't running any of the clients ClientTag knows."
                : "That's you - your game is running " + String.join(", ", running) + ".", TEXT));
    }

    /** The player with this name, preferring an exact match over one that only differs in case. */
    private static Player find(Platform platform, String name) {
        final Player[] found = new Player[2];
        final String lower = name.toLowerCase(Locale.ROOT);
        platform.forEachPlayerName((uuid, playerName) -> {
            if (found[0] == null && playerName.equals(name)) {
                found[0] = new Player(uuid, playerName);
            } else if (found[1] == null && playerName.toLowerCase(Locale.ROOT).equals(lower)) {
                found[1] = new Player(uuid, playerName);
            }
        });
        return found[0] != null ? found[0] : found[1];
    }

    private static List<ChatPart> line(ChatPart first) {
        final List<ChatPart> parts = new ArrayList<>();
        parts.add(new ChatPart("ClientTag » ", PREFIX));
        parts.add(first);
        return parts;
    }

    private static final class Player {
        final UUID uuid;
        final String name;

        Player(UUID uuid, String name) {
            this.uuid = uuid;
            this.name = name;
        }
    }

}
