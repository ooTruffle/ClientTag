package com.ootruffle.clienttag.modern;

import com.mojang.authlib.GameProfile;
import com.mojang.authlib.properties.Property;
import com.ootruffle.clienttag.platform.Platform;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.server.IntegratedServer;

/** {@link Platform} for the Fabric versions, in Mojang names. */
final class ModernPlatform implements Platform {

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

}
