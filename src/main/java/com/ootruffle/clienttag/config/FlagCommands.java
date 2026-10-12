package com.ootruffle.clienttag.config;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import com.ootruffle.clienttag.LocalFlags;
import java.util.Collection;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;

/**
 * {@code /clienttag flag <player> [reason]}, {@code /clienttag unflag <player>} and
 * {@code /clienttag flags}, for {@link LocalFlags}. Generic over the command source, since
 * OneConfig's differs between 1.8.9 and the modern versions. Only loaded with OneConfig.
 */
final class FlagCommands {

    private FlagCommands() {}

    static <S> LiteralArgumentBuilder<S> addTo(LiteralArgumentBuilder<S> root) {
        return root
                .then(LiteralArgumentBuilder.<S>literal("flag")
                        .then(RequiredArgumentBuilder.<S, String>argument("player", StringArgumentType.word())
                                .suggests((ctx, builder) -> suggest(builder, LocalFlags.onlineNames()))
                                .executes(ctx -> {
                                    LocalFlags.flag(StringArgumentType.getString(ctx, "player"), "");
                                    return 1;
                                })
                                .then(RequiredArgumentBuilder.<S, String>argument("reason", StringArgumentType.greedyString())
                                        .executes(ctx -> {
                                            LocalFlags.flag(StringArgumentType.getString(ctx, "player"),
                                                    StringArgumentType.getString(ctx, "reason"));
                                            return 1;
                                        }))))
                .then(LiteralArgumentBuilder.<S>literal("unflag")
                        .then(RequiredArgumentBuilder.<S, String>argument("player", StringArgumentType.word())
                                .suggests((ctx, builder) -> suggest(builder, LocalFlags.flaggedNames()))
                                .executes(ctx -> {
                                    LocalFlags.unflag(StringArgumentType.getString(ctx, "player"));
                                    return 1;
                                })))
                .then(LiteralArgumentBuilder.<S>literal("flags")
                        .executes(ctx -> {
                            LocalFlags.list();
                            return 1;
                        }));
    }

    private static CompletableFuture<Suggestions> suggest(SuggestionsBuilder builder, Collection<String> names) {
        final String typed = builder.getRemaining().toLowerCase(Locale.ROOT);
        for (String name : names) {
            if (name.toLowerCase(Locale.ROOT).startsWith(typed)) {
                builder.suggest(name);
            }
        }
        return builder.buildFuture();
    }

}
