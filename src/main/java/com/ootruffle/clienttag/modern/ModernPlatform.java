package com.ootruffle.clienttag.modern;

import com.mojang.authlib.GameProfile;
import com.mojang.authlib.properties.Property;
import com.ootruffle.clienttag.platform.Platform;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

/** {@link Platform} for the Fabric versions, in Mojang names. */
final class ModernPlatform implements Platform {

    /** The question on screen, if any. Client thread only. */
    private Screen question;

    @Override
    public String currentServerAddress() {
        final Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) {
            return null;
        }
        if (mc.hasSingleplayerServer()) {
            // Other players can only see us in our own world once it's opened to LAN.
            final IntegratedServer server = mc.getSingleplayerServer();
            return server != null && server.isPublished() ? LAN_HOST : null;
        }
        final ServerData data = mc.getCurrentServer();
        return data == null ? null : data.ip;
    }

    @Override
    public UUID localPlayerId() {
        final Minecraft mc = Minecraft.getInstance();
        return mc.player == null ? null : mc.player.getUUID();
    }

    @Override
    public void forEachWorldPlayer(PlayerVisitor visitor) {
        for (AbstractClientPlayer player : Minecraft.getInstance().level.players()) {
            visit(visitor, player.getGameProfile());
        }
    }

    @Override
    public void forEachTabListPlayer(PlayerVisitor visitor) {
        final ClientPacketListener connection = Minecraft.getInstance().getConnection();
        if (connection != null) {
            for (PlayerInfo info : connection.getOnlinePlayers()) {
                visit(visitor, info.getProfile());
            }
        }
    }

    private static void visit(PlayerVisitor visitor, GameProfile profile) {
        if (profile == null) {
            return;
        }
        // GameProfile became a record in authlib 7.
        //? if >= 1.21.10 {
        final UUID uuid = profile.id();
        final Iterable<Property> textures = profile.properties().get("textures");
        //?} else {
        /*final UUID uuid = profile.getId();
        final Iterable<Property> textures = profile.getProperties().get("textures");
        *///?}
        if (uuid == null) {
            return;
        }
        String value = null;
        for (Property property : textures) {
            value = property.value();
            break;
        }
        visitor.visit(uuid, value);
    }

    @Override
    public void forEachPlayerName(NamedPlayerVisitor visitor) {
        final Minecraft mc = Minecraft.getInstance();
        final ClientPacketListener connection = mc.getConnection();
        if (connection != null) {
            for (PlayerInfo info : connection.getOnlinePlayers()) {
                visitName(visitor, info.getProfile());
            }
        }
        if (mc.level != null) {
            for (AbstractClientPlayer player : mc.level.players()) {
                visitName(visitor, player.getGameProfile());
            }
        }
    }

    private static void visitName(NamedPlayerVisitor visitor, GameProfile profile) {
        if (profile == null) {
            return;
        }
        //? if >= 1.21.10 {
        final UUID uuid = profile.id();
        final String name = profile.name();
        //?} else {
        /*final UUID uuid = profile.getId();
        final String name = profile.getName();
        *///?}
        if (uuid != null && name != null) {
            visitor.visit(uuid, name);
        }
    }

    @Override
    public void showMessage(List<ChatPart> parts) {
        final MutableComponent line = Component.empty();
        for (ChatPart part : parts) {
            final MutableComponent text = Component.literal(part.text());
            line.append(part.color() == ChatPart.DEFAULT ? text : text.withColor(part.color()));
        }
        // The chat moved into Gui's Hud in 26.2, and 26.1 split client and server system messages.
        //? if >= 26.2 {
        Minecraft.getInstance().gui.hud.getChat().addClientSystemMessage(line);
        //?} elif >= 26.1 {
        /*Minecraft.getInstance().gui.getChat().addClientSystemMessage(line);
        *///?} else
        /*Minecraft.getInstance().gui.getChat().addMessage(line);*/
    }

    @Override
    public UUID sessionId() {
        return Minecraft.getInstance().getUser().getProfileId();
    }

    @Override
    public String sessionName() {
        return Minecraft.getInstance().getUser().getName();
    }

    @Override
    public void joinServer(String serverId) throws Exception {
        final Minecraft mc = Minecraft.getInstance();
        //? if >= 1.21.10 {
        mc.services().sessionService().joinServer(mc.getUser().getProfileId(), mc.getUser().getAccessToken(), serverId);
        //?} else
        /*mc.getMinecraftSessionService().joinServer(mc.getUser().getProfileId(), mc.getUser().getAccessToken(), serverId);*/
    }

    @Override
    public boolean canAsk() {
        final Minecraft mc = Minecraft.getInstance();
        //? if >= 26.2 {
        return mc.gui.overlay() == null;
        //?} else
        /*return mc.getOverlay() == null;*/
    }

    @Override
    public boolean isAsking() {
        return question != null && screen(Minecraft.getInstance()) == question;
    }

    @Override
    public void ask(String title, String message, String yes, String no, int delaySeconds, Consumer<Boolean> onAnswer) {
        final Minecraft mc = Minecraft.getInstance();
        final Screen previous = screen(mc);
        final long readyAt = System.currentTimeMillis() + delaySeconds * 1000L;
        question = new ConfirmScreen(answer -> {
            question = null;
            onAnswer.accept(answer);
            setScreen(mc, previous);
        }, Component.literal(title), Component.literal(message), Component.literal(yes), Component.literal(no)) {
            @Override
            protected void init() {
                super.init();
                // setDelay only covers buttons that exist, and init (e.g. on resize) makes new ones.
                final long left = readyAt - System.currentTimeMillis();
                if (left > 0) {
                    setDelay((int) Math.max(1, left / 50));
                }
            }
        };
        setScreen(mc, question);
    }

    // The current screen moved to Gui in 26.2.
    private static Screen screen(Minecraft mc) {
        //? if >= 26.2 {
        return mc.gui.screen();
        //?} else
        /*return mc.screen;*/
    }

    private static void setScreen(Minecraft mc, Screen screen) {
        //? if >= 26.2 {
        mc.gui.setScreen(screen);
        //?} else
        /*mc.setScreen(screen);*/
    }

}
