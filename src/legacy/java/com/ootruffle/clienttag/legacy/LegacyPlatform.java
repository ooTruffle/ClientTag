package com.ootruffle.clienttag.legacy;

import com.mojang.authlib.GameProfile;
import com.mojang.authlib.properties.Property;
import com.ootruffle.clienttag.legacy.gui.QuestionScreen;
import com.ootruffle.clienttag.platform.Platform;
import java.util.UUID;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.network.NetworkPlayerInfo;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.server.integrated.IntegratedServer;

/** {@link Platform} for 1.8.9, in MCP names. */
final class LegacyPlatform implements Platform {

    @Override
    public String currentServerAddress() {
        final Minecraft mc = Minecraft.getMinecraft();
        if (mc.theWorld == null) {
            return null;
        }
        if (mc.isSingleplayer()) {
            // Other players can only see us in our own world once it's opened to LAN.
            final IntegratedServer server = mc.getIntegratedServer();
            return server != null && server.getPublic() ? LAN_HOST : null;
        }
        final ServerData data = mc.getCurrentServerData();
        return data == null ? null : data.serverIP;
    }

    @Override
    public UUID localPlayerId() {
        final Minecraft mc = Minecraft.getMinecraft();
        return mc.thePlayer == null ? null : mc.thePlayer.getUniqueID();
    }

    @Override
    public void forEachWorldPlayer(PlayerVisitor visitor) {
        for (EntityPlayer player : Minecraft.getMinecraft().theWorld.playerEntities) {
            visit(visitor, player.getGameProfile());
        }
    }

    @Override
    public void forEachTabListPlayer(PlayerVisitor visitor) {
        final Minecraft mc = Minecraft.getMinecraft();
        if (mc.getNetHandler() != null) {
            for (NetworkPlayerInfo info : mc.getNetHandler().getPlayerInfoMap()) {
                visit(visitor, info.getGameProfile());
            }
        }
    }

    private static void visit(PlayerVisitor visitor, GameProfile profile) {
        if (profile == null || profile.getId() == null) {
            return;
        }
        String textures = null;
        for (Property property : profile.getProperties().get("textures")) {
            textures = property.getValue();
            break;
        }
        visitor.visit(profile.getId(), textures);
    }

    @Override
    public UUID sessionId() {
        return Minecraft.getMinecraft().getSession().getProfile().getId();
    }

    @Override
    public String sessionName() {
        return Minecraft.getMinecraft().getSession().getProfile().getName();
    }

    @Override
    public void joinServer(String serverId) throws Exception {
        final Minecraft mc = Minecraft.getMinecraft();
        mc.getSessionService().joinServer(mc.getSession().getProfile(), mc.getSession().getToken(), serverId);
    }

    @Override
    public boolean canAsk() {
        // Ticks only start once 1.8.9 has finished loading.
        return true;
    }

    @Override
    public boolean isAsking() {
        return Minecraft.getMinecraft().currentScreen instanceof QuestionScreen;
    }

    @Override
    public void ask(String title, String message, String yes, String no, int delaySeconds, Consumer<Boolean> onAnswer) {
        final Minecraft mc = Minecraft.getMinecraft();
        final GuiScreen previous = mc.currentScreen;
        mc.displayGuiScreen(new QuestionScreen(title, message, yes, no, delaySeconds, answer -> {
            onAnswer.accept(answer);
            mc.displayGuiScreen(previous);
        }));
    }

}
