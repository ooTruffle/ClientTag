package com.ootruffle.clienttag.config;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.ootruffle.clienttag.NativeClients;
import com.ootruffle.clienttag.WhoCommand;
import com.ootruffle.clienttag.render.ClientIcon;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import net.fabricmc.loader.api.FabricLoader;
import org.polyfrost.oneconfig.api.commands.v1.CommandManager;
import org.polyfrost.oneconfig.utils.v1.dsl.ScreensKt;

/**
 * The mod's settings, editable through OneConfig when it's installed (also via /clienttag,
 * which also has {@code /clienttag who <player>}).
 * Without OneConfig every client is shown everywhere in its reported color.
 * <p>
 * A client is always off while the game is running on it (see {@link NativeClients}) - it
 * draws its own indicators then, e.g. the PolyPlus mod draws its own badge.
 * <p>
 * {@link Present} is the only class touching OneConfig, and is never loaded without it.
 */
public final class ClientTagSettings {

    private static final boolean LOADED = FabricLoader.getInstance().isModLoaded("oneconfigv1");
    private static final List<ClientIcon> DEFAULT_ORDER = Collections.unmodifiableList(Arrays.asList(ClientIcon.values()));

    private ClientTagSettings() {}

    /** Loads the config and registers /clienttag; call once from the mod initializer. */
    public static void init() {
        if (LOADED) {
            Present.init();
        }
    }

    public static boolean showOnNametags() {
        return !LOADED || Present.showOnNametags();
    }

    public static boolean showInTab() {
        return !LOADED || Present.showInTab();
    }

    /** Whether to share our real client with, and look players up on, the ClientTag server. */
    public static boolean useServer() {
        return !LOADED || Present.useServer();
    }

    /** Whether to ask the ClientTag server to show other ClientTag users a ClientTag icon by our name. */
    public static boolean showOwnTag() {
        return !LOADED || Present.showOwnTag();
    }

    /** Every client, in the order their icons are drawn, left to right. */
    public static List<ClientIcon> iconOrder() {
        return LOADED ? Present.iconOrder() : DEFAULT_ORDER;
    }

    public static boolean isEnabled(ClientIcon icon) {
        // A client we're running on draws its own indicators.
        if (NativeClients.isRunning(icon)) {
            return false;
        }
        return !LOADED || Present.isEnabled(icon);
    }

    /** The RGB color to draw the icon in, given the color its client reported for the player. */
    public static int color(ClientIcon icon, int reportedColor) {
        return LOADED ? Present.color(icon, reportedColor) : reportedColor;
    }

    private static final class Present {
        private static ClientTagConfig config() {
            return ClientTagConfig.INSTANCE;
        }

        static void init() {
            config().preload();
            config().normalizeIconOrder();
            CommandManager.INSTANCE.register(withWho(ScreensKt.addDefaultCommand(config(), "clienttag")));
        }

        /**
         * Adds {@code who <player>} (see {@link WhoCommand}) to /clienttag. Generic because
         * the command source type differs between Minecraft versions.
         */
        private static <S> LiteralArgumentBuilder<S> withWho(LiteralArgumentBuilder<S> root) {
            return root.then(LiteralArgumentBuilder.<S>literal("who")
                    .then(RequiredArgumentBuilder.<S, String>argument("player", StringArgumentType.word())
                            .suggests((context, builder) -> {
                                final String typed = builder.getRemaining().toLowerCase(Locale.ROOT);
                                for (String name : WhoCommand.playerNames()) {
                                    if (name.toLowerCase(Locale.ROOT).startsWith(typed)) {
                                        builder.suggest(name);
                                    }
                                }
                                return builder.buildFuture();
                            })
                            .executes(context -> {
                                WhoCommand.run(StringArgumentType.getString(context, "player"));
                                return 1;
                            })));
        }

        static boolean showOnNametags() {
            return config().showOnNametags;
        }

        static boolean showInTab() {
            return config().showInTab;
        }

        static boolean useServer() {
            return config().useServer;
        }

        static boolean showOwnTag() {
            return config().showOwnTag;
        }

        static List<ClientIcon> iconOrder() {
            return config().iconOrder();
        }

        static boolean isEnabled(ClientIcon icon) {
            return config().isEnabled(icon);
        }

        static int color(ClientIcon icon, int reportedColor) {
            return config().color(icon, reportedColor);
        }
    }

}
