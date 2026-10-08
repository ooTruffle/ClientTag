package com.ootruffle.clienttag.legacy.render;

import com.ootruffle.clienttag.render.ClientIcon;
import java.util.EnumMap;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.WorldRenderer;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.util.ResourceLocation;
import org.lwjgl.opengl.GL11;

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

    /**
     * Draws the icon at (x, y) in the middle of a FontRenderer draw. Uses raw GL inside a pushed
     * attribute state rather than GlStateManager, so the font's color and blend state - and
     * GlStateManager's cache of them - are exactly as they were for the next character.
     */
    static void drawInline(ClientIcon.Tinted tinted, float x, float y, int size, float alpha) {
        final Minecraft mc = Minecraft.getMinecraft();
        final int color = tinted.color();
        mc.getTextureManager().bindTexture(texture(mc, tinted.icon()));
        GL11.glPushAttrib(GL11.GL_CURRENT_BIT | GL11.GL_ENABLE_BIT | GL11.GL_COLOR_BUFFER_BIT);
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        GL11.glColor4f((color >> 16 & 0xFF) / 255.0F, (color >> 8 & 0xFF) / 255.0F, (color & 0xFF) / 255.0F, alpha);
        final Tessellator tessellator = Tessellator.getInstance();
        final WorldRenderer worldRenderer = tessellator.getWorldRenderer();
        worldRenderer.begin(7, DefaultVertexFormats.POSITION_TEX);
        worldRenderer.pos(x, y + size, 0.0D).tex(0.0D, 1.0D).endVertex();
        worldRenderer.pos(x + size, y + size, 0.0D).tex(1.0D, 1.0D).endVertex();
        worldRenderer.pos(x + size, y, 0.0D).tex(1.0D, 0.0D).endVertex();
        worldRenderer.pos(x, y, 0.0D).tex(0.0D, 0.0D).endVertex();
        tessellator.draw();
        GL11.glPopAttrib();
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
