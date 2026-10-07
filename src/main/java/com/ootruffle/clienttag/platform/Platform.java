package com.ootruffle.clienttag.platform;

import java.util.UUID;
import java.util.function.Consumer;

/**
 * Everything the version-independent code needs from Minecraft. 1.8.9 (Ornithe) and the
 * modern Fabric versions each provide one, installed by their entrypoint before
 * {@link com.ootruffle.clienttag.ClientTag#init} runs.
 */
public interface Platform {

    /** Stands in for the address of a world we're hosting over LAN; we never connect to it ourselves. */
    String LAN_HOST = "localhost";

    /** Receives a player's UUID and base64 skin textures property (null if there isn't one). */
    @FunctionalInterface
    interface PlayerVisitor {
        void visit(UUID uuid, String textures);
    }

    static Platform get() {
        return Holder.instance;
    }

    static void install(Platform platform) {
        Holder.instance = platform;
    }

    /**
     * The address of the server we're on: the address typed in (or picked from the LAN list)
     * for a remote server, {@link #LAN_HOST} while hosting an Open to LAN world, and null
     * in plain singleplayer or out of a world. Works for any server, not just Hypixel.
     */
    String currentServerAddress();

    /** Our own player entity's UUID, or null without one. */
    UUID localPlayerId();

    /** Every player entity in the loaded world; only called while in a world. */
    void forEachWorldPlayer(PlayerVisitor visitor);

    /** Every entry in the tab list, if there is one. */
    void forEachTabListPlayer(PlayerVisitor visitor);

    /** The logged-in account's UUID. */
    UUID sessionId();

    /** The logged-in account's name. */
    String sessionName();

    /** Tells Mojang's session server we're joining {@code serverId}, as a server join would. */
    void joinServer(String serverId) throws Exception;

    /** Whether the game has finished loading, so a question can be shown. */
    boolean canAsk();

    /** Whether the question from {@link #ask} is still on screen. */
    boolean isAsking();

    /**
     * Asks the player a yes/no question over whatever is open (a menu, or the world), going
     * back to it afterwards. The buttons only work after {@code delaySeconds}, so it gets read.
     * {@code onAnswer} isn't called if something else replaces the question.
     */
    void ask(String title, String message, String yes, String no, int delaySeconds, Consumer<Boolean> onAnswer);

    final class Holder {
        private static Platform instance;

        private Holder() {}
    }

}
