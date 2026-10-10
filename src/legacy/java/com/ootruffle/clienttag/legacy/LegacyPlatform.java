package com.ootruffle.clienttag.legacy;

import com.mojang.authlib.GameProfile;
import com.mojang.authlib.properties.Property;
import com.ootruffle.clienttag.legacy.gui.QuestionScreen;
import com.ootruffle.clienttag.platform.Platform;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.network.NetworkPlayerInfo;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.server.integrated.IntegratedServer;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.ChatStyle;
import net.minecraft.util.EnumChatFormatting;

/** {@link Platform} for 1.8.9, in MCP names. */
final class LegacyPlatform implements Platform {

    /** 1.8.9's chat colors, which have no RGB of their own, with the RGB they're drawn in. */
    private static final EnumChatFormatting[] CHAT_COLORS = {
            EnumChatFormatting.BLACK, EnumChatFormatting.DARK_BLUE, EnumChatFormatting.DARK_GREEN, EnumChatFormatting.DARK_AQUA,
            EnumChatFormatting.DARK_RED, EnumChatFormatting.DARK_PURPLE, EnumChatFormatting.GOLD, EnumChatFormatting.GRAY,
            EnumChatFormatting.DARK_GRAY, EnumChatFormatting.BLUE, EnumChatFormatting.GREEN, EnumChatFormatting.AQUA,
            EnumChatFormatting.RED, EnumChatFormatting.LIGHT_PURPLE, EnumChatFormatting.YELLOW, EnumChatFormatting.WHITE,
    };
    private static final int[] CHAT_RGB = {
            0x000000, 0x0000AA, 0x00AA00, 0x00AAAA, 0xAA0000, 0xAA00AA, 0xFFAA00, 0xAAAAAA,
            0x555555, 0x5555FF, 0x55FF55, 0x55FFFF, 0xFF5555, 0xFF55FF, 0xFFFF55, 0xFFFFFF,
    };

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
    public void forEachPlayerName(NamedPlayerVisitor visitor) {
        final Minecraft mc = Minecraft.getMinecraft();
        if (mc.getNetHandler() != null) {
            for (NetworkPlayerInfo info : mc.getNetHandler().getPlayerInfoMap()) {
                visitName(visitor, info.getGameProfile());
            }
        }
        if (mc.theWorld != null) {
            for (EntityPlayer player : mc.theWorld.playerEntities) {
                visitName(visitor, player.getGameProfile());
            }
        }
    }

    private static void visitName(NamedPlayerVisitor visitor, GameProfile profile) {
        if (profile != null && profile.getId() != null && profile.getName() != null) {
            visitor.visit(profile.getId(), profile.getName());
        }
    }

    @Override
    public void showMessage(List<ChatPart> parts) {
        final ChatComponentText line = new ChatComponentText("");
        for (ChatPart part : parts) {
            final ChatComponentText text = new ChatComponentText(part.text());
            if (part.color() != ChatPart.DEFAULT) {
                text.setChatStyle(new ChatStyle().setColor(nearestChatColor(part.color())));
            }
            line.appendSibling(text);
        }
        Minecraft.getMinecraft().ingameGUI.getChatGUI().printChatMessage(line);
    }

    private static EnumChatFormatting nearestChatColor(int rgb) {
        int best = 0;
        long bestDistance = Long.MAX_VALUE;
        for (int i = 0; i < CHAT_RGB.length; i++) {
            final long dr = (rgb >> 16 & 0xFF) - (CHAT_RGB[i] >> 16 & 0xFF);
            final long dg = (rgb >> 8 & 0xFF) - (CHAT_RGB[i] >> 8 & 0xFF);
            final long db = (rgb & 0xFF) - (CHAT_RGB[i] & 0xFF);
            final long distance = dr * dr + dg * dg + db * db;
            if (distance < bestDistance) {
                bestDistance = distance;
                best = i;
            }
        }
        return CHAT_COLORS[best];
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
