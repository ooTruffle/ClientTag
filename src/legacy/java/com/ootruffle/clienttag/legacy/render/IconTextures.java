package com.ootruffle.clienttag.legacy.render;

import com.ootruffle.clienttag.render.ClientIcon;
import java.util.EnumMap;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.util.ResourceLocation;

/** Paints each {@link ClientIcon} into a dynamic texture on first use and draws it tinted. */
final class IconTextures {

    private static final int TEXTURE_SIZE = ClientIcon.TEXTURE_SIZE;
    private static final Map<ClientIcon, ResourceLocation> TEXTURES = new EnumMap<>(ClientIcon.class);

    private IconTextures() {}

    /** Draws the icon at (x, y), size x size, tinted with its RGB color. */
    static void draw(ClientIcon.Tinted tinted, int x, int y, int size, float alpha) {
        final Minecraft mc = Minecraft.getMinecraft();
        final int color = tinted.color();
        final float r = (color >> 16 & 0xFF) / 255.0F;
        final float g = (color >> 8 & 0xFF) / 255.0F;
        final float b = (color & 0xFF) / 255.0F;
        GlStateManager.enableBlend();
        GlStateManager.tryBlendFuncSeparate(770, 771, 1, 0);
        GlStateManager.color(r, g, b, alpha);
        mc.getTextureManager().bindTexture(texture(mc, tinted.icon()));
        Gui.drawScaledCustomSizeModalRect(x, y, 0, 0, TEXTURE_SIZE, TEXTURE_SIZE, size, size, TEXTURE_SIZE, TEXTURE_SIZE);
        GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
    }

    private static ResourceLocation texture(Minecraft mc, ClientIcon icon) {
        ResourceLocation texture = TEXTURES.get(icon);
        if (texture == null) {
            final DynamicTexture dynamicTexture = new DynamicTexture(TEXTURE_SIZE, TEXTURE_SIZE);
            icon.paint(dynamicTexture.getTextureData());
            dynamicTexture.updateDynamicTexture();
            texture = mc.getTextureManager().getDynamicTextureLocation("clienttag_" + icon.id(), dynamicTexture);
            TEXTURES.put(icon, texture);
        }
        return texture;
    }

}
